package com.schmitzkr.grimreader.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.schmitzkr.grimreader.MainActivity
import com.schmitzkr.grimreader.core.model.AudiobookProgress
import com.schmitzkr.grimreader.core.playback.audiobookPercentage
import com.schmitzkr.grimreader.core.playback.trackRelativeMs
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.ClientHolder
import com.schmitzkr.grimreader.data.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The one player, as a media session the system, Android Auto and the app
 * all drive. A book is loaded by setting `book:<id>` as the media item; the
 * service resolves it to its stream or tracks and the saved position.
 *
 * Progress goes to the server every five seconds while playing and on every
 * pause, stop, finish or switch, with the book-wide absolute position the
 * server expects even on a folder-based book.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var books: BooksRepository
    @Inject lateinit var clients: ClientHolder
    @Inject lateinit var settings: Settings

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var pausedAt: Instant? = null
    private var lastSavedBookId: Long? = null

    override fun onCreate() {
        super.onCreate()
        val callFactory = okhttp3.Call.Factory { request -> clients.current().okHttp.newCall(request) }
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(callFactory))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
        player.addListener(PlayerEvents())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .setBitmapLoader(AuthBitmapLoader(this, callFactory))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing when the app is swiped away; stop only if paused.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        // Read the player here on Main; the post outlives this scope.
        snapshotProgress()?.let { postProgress(it, sessionEnded = true, ioScope = detachedIo) }
        session?.run {
            player.release()
            release()
            session = null
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Resolving books ───────────────────────────────────────────────────

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folderItem(ID_ROOT, "GrimReader"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
            runCatching { ImmutableList.copyOf(children(parentId)) }
                .map { LibraryResult.ofItemList(it, params) }
                .getOrElse { LibraryResult.ofError(SessionError.ERROR_UNKNOWN) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future(Dispatchers.IO) {
            val id = bookIdOf(mediaId)
            if (id == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else runCatching { books.book(id) }
                .map { LibraryResult.ofItem(bookEntryItem(it, books.coverUrl(it)), null) }
                .getOrElse { LibraryResult.ofError(SessionError.ERROR_UNKNOWN) }
        }

        /** `book:<id>` becomes the stream or tracks plus the saved position. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
            val bookId = mediaItems.firstOrNull()?.let { it.bookId ?: bookIdOf(it.mediaId) }
            if (bookId == null || mediaItems.size != 1 || mediaItems.first().localConfiguration != null) {
                return@future MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
            // The previous book's last position goes out before the switch.
            saveProgress(sessionEnded = true)
            val book = books.book(bookId)
            val info = books.audiobookInfo(bookId)
            val progress = runCatching { books.audiobookProgress(bookId) }.getOrNull()
            val items = playableItems(
                book, info,
                coverUrl = books.coverUrl(book),
                streamUrl = books.streamUrl(bookId),
                trackUrl = { books.trackStreamUrl(bookId, it) },
            )
            val index: Int
            val position: Long
            if (progress == null) {
                index = 0; position = 0L
            } else if (info.folderBased && info.tracks.isNotEmpty()) {
                val trackIndex = progress.trackIndex
                    ?: info.tracks.lastOrNull { it.cumulativeStartMs <= progress.positionMs }?.index
                    ?: 0
                index = trackIndex.coerceIn(0, items.size - 1)
                position = progress.trackPositionMs
                    ?: trackRelativeMs(progress.positionMs, index, info.tracks, folderBased = true)
            } else {
                index = 0; position = progress.positionMs
            }
            val speed = settings.speedFor(bookId)
            scope.launch { player.setPlaybackSpeed(speed) }
            pausedAt = null
            MediaItemsWithStartPosition(items, index, position)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)
    }

    /** The Android Auto browse tree. */
    private suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ID_ROOT -> listOf(
            folderItem(ID_CONTINUE, "Continue listening"),
            folderItem(ID_LIBRARIES, "Libraries"),
        )
        parentId == ID_CONTINUE -> books.continueListening().map { bookEntryItem(it, books.coverUrl(it)) }
        parentId == ID_LIBRARIES -> books.libraries().map { folderItem(libraryMediaId(it.id), it.name) }
        parentId.startsWith("library:") -> {
            val id = libraryIdOf(parentId) ?: return emptyList()
            books.libraryBooks(id, fileTypes = listOf("AUDIOBOOK"), sort = "title", dir = "asc")
                .map { bookEntryItem(it, books.coverUrl(it)) }
        }
        else -> emptyList()
    }

    // ── Progress ──────────────────────────────────────────────────────────

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTicker()
            } else {
                stopTicker()
                pausedAt = Instant.now()
                scope.launch { saveProgress(sessionEnded = true) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) scope.launch { autoRewind() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                stopTicker()
                scope.launch { saveProgress(sessionEnded = true) }
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val bookId = player.currentMediaItem?.bookId ?: return
            scope.launch(Dispatchers.IO) { settings.rememberSpeed(bookId, playbackParameters.speed) }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "Playback error", error)
        }
    }

    /** Backs up a little on resume, more the longer the pause lasted. */
    private suspend fun autoRewind() {
        val since = pausedAt ?: return
        pausedAt = null
        if (!settings.autoRewind.first()) return
        val back = autoRewindFor(Duration.between(since, Instant.now()))
        if (back.isZero) return
        val target = player.currentPosition - back.toMillis()
        if (target > 0 || player.currentMediaItemIndex == 0) {
            player.seekTo(maxOf(0L, target))
        } else {
            // Cross into the previous track.
            val prev = player.currentMediaItemIndex - 1
            val prevDuration = player.getMediaItemAt(prev).mediaMetadata.extras
                ?.getLong(Extras.TRACK_DURATION_MS, 0L) ?: 0L
            player.seekTo(prev, maxOf(0L, prevDuration + target))
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                saveProgress(sessionEnded = false)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private data class ProgressSnapshot(val bookId: Long, val progress: AudiobookProgress, val bookFileId: Long?)

    /** Main thread only: the player may only be read there. */
    private fun snapshotProgress(): ProgressSnapshot? {
        val item = player.currentMediaItem ?: return null
        val bookId = item.bookId ?: return null
        val position = player.currentPosition.coerceAtLeast(0L)
        val absolute = item.absolutePositionMs(position)
        val total = item.totalDurationMs.takeIf { it > 0 } ?: player.duration.coerceAtLeast(0L)
        return ProgressSnapshot(
            bookId = bookId,
            progress = AudiobookProgress(
                positionMs = absolute,
                trackIndex = if (item.isFolderBased) item.trackIndex else null,
                trackPositionMs = if (item.isFolderBased) position else null,
                percentage = audiobookPercentage(absolute, total),
            ),
            bookFileId = item.bookFileId,
        )
    }

    private fun postProgress(s: ProgressSnapshot, sessionEnded: Boolean, ioScope: CoroutineScope = scope) {
        ioScope.launch(Dispatchers.IO) {
            runCatching { books.saveAudiobookProgress(s.bookId, s.progress, s.bookFileId) }
                .onSuccess {
                    // The first save after a load flips the server's status to
                    // READING, which is what Continue Listening is built on.
                    if (sessionEnded || lastSavedBookId != s.bookId) {
                        lastSavedBookId = s.bookId
                        books.notifyProgressChanged(s.bookId)
                    }
                }
                .onFailure { Log.w(TAG, "Progress save failed", it) }
        }
    }

    /** Safe from any thread: hops to Main for the read, posts on IO. */
    private suspend fun saveProgress(sessionEnded: Boolean) {
        val snapshot = withContext(Dispatchers.Main.immediate) { snapshotProgress() } ?: return
        postProgress(snapshot, sessionEnded)
    }

    companion object {
        private const val TAG = "PlaybackService"
        const val SKIP_MS = 30_000L
        private const val SAVE_INTERVAL_MS = 5_000L

        /** For the one save that must outlive the service. */
        private val detachedIo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** How far to back up on resume after [paused]: nothing for a blink, more after a long break. */
fun autoRewindFor(paused: Duration): Duration = when {
    paused < Duration.ofSeconds(10) -> Duration.ZERO
    paused < Duration.ofMinutes(1) -> Duration.ofSeconds(5)
    paused < Duration.ofMinutes(10) -> Duration.ofSeconds(15)
    else -> Duration.ofSeconds(30)
}
