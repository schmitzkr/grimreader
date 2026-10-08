package com.schmitzkr.grimreader.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.schmitzkr.grimreader.core.model.AudiobookChapter
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.playback.chapterEndMs
import com.schmitzkr.grimreader.core.playback.skipTarget
import com.schmitzkr.grimreader.core.playback.trackIndexAt
import com.schmitzkr.grimreader.core.playback.trackRelativeMs
import com.schmitzkr.grimreader.core.playback.wallClockUntil
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton

/** Everything a "now playing" surface needs, derived once from the controller. */
data class PlaybackState(
    val bookId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    /** Book-wide position and length. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val trackIndex: Int? = null,
    val trackCount: Int = 0,
    val speed: Float = 1f,
    val chapters: List<AudiobookChapter> = emptyList(),
    val sleepRemainingMs: Long? = null,
    /** The running sleep timer ends at the current chapter's end rather than after a fixed length. */
    val sleepAtChapterEnd: Boolean = false,
    /** Why the last load or connection failed, until the next [PlayerController.play]. */
    val error: String? = null,
) {
    val hasBook: Boolean get() = bookId != null
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val currentChapterIndex: Int?
        get() = chapters.indexOfLast { it.startTimeMs <= positionMs }.takeIf { it >= 0 }
}

/**
 * The app's handle on [PlaybackService]: connects a [MediaController] and
 * keeps a [PlaybackState] flow current. Also owns the sleep timer, which
 * is app-side state the service does not need to know about.
 *
 * Everything here runs on the main thread. Per-book state is keyed by the
 * book it belongs to and never applied to another:
 *  - the track table (seeking, chapter ends) comes from the extras the
 *    service stamps on every item, so it is always the loaded book's and
 *    needs no fetch;
 *  - [info] is only the chapter list; it is fetched once per book
 *    ([infoBookId]), the download record first, and a stale fetch is
 *    dropped when it lands;
 *  - [loadingBookId] is the book a `play` asked for and not yet current;
 *    it clears when that book's item appears or when the service reports
 *    the load failed ([LoadFailed]).
 * A command issued before the controller is connected (cold start, or
 * after the session went away) is queued and runs on connection.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class PlayerController @Inject constructor(
    private val context: Context,
    private val books: BooksRepository,
    private val downloads: DownloadManager,
    private val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var positionJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepLengthMs = 0L
    private var sleepChapterMode = false
    private var shake: ShakeDetector? = null

    /** The book a `play` asked for whose items are not current yet. */
    private var loadingBookId: Long? = null
    /** The book [info] was requested for, whether or not the request succeeded. */
    private var infoBookId: Long? = null
    private var info: AudiobookInfo? = null
    private var infoJob: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        connect()
    }

    // ── Connection ────────────────────────────────────────────────────────

    private fun connect() {
        if (controller != null || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(Connection()).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            controllerFuture = null
            val c = try {
                future.get()
            } catch (e: ExecutionException) {
                onConnectFailed(e.cause ?: e); return@addListener
            } catch (e: CancellationException) {
                onConnectFailed(e); return@addListener
            }
            controller = c
            c.addListener(Events())
            while (pending.isNotEmpty()) pending.removeFirst()(c)
            refresh()
            startPositionJob()
        }, MoreExecutors.directExecutor())
    }

    private fun onConnectFailed(cause: Throwable) {
        Log.w(TAG, "Connecting to the playback service failed", cause)
        pending.clear()
        loadingBookId = null
        _state.update { it.copy(loading = false, playing = false, error = "Could not reach the player") }
    }

    /**
     * Runs [block] on the connected controller, or queues it until the
     * connection (started here if none is in flight) comes up. Nothing a
     * user asked for is dropped on the floor.
     */
    private fun command(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
            return
        }
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(block)
        connect()
    }

    private inner class Connection : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerController.controller !== controller) return
            this@PlayerController.controller = null
            positionJob?.cancel()
            positionJob = null
            controller.release()
            // Whatever was playing went away with the session; a queued
            // command (a fresh play, say) reissues against the new one.
            cancelSleepTimer()
            loadingBookId = null
            infoBookId = null
            info = null
            _state.value = PlaybackState()
            connect()
        }

        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == LoadFailed.ACTION) {
                val bookId = args.getLong(LoadFailed.BOOK_ID, -1L)
                if (bookId == loadingBookId) {
                    loadingBookId = null
                    _state.update { it.copy(error = args.getString(LoadFailed.MESSAGE) ?: "Could not load this book") }
                    refresh()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** Loads [bookId] (resuming its saved position) and starts playing. */
    fun play(bookId: Long) {
        _state.update { it.copy(error = null) }
        val c = controller
        if (c != null && c.currentMediaItem?.bookId == bookId && c.mediaItemCount > 0) {
            // An earlier chapter fetch may have failed (offline); a deliberate
            // tap on the same book is the moment to try again.
            ensureInfo(bookId, retry = true)
            c.play()
            return
        }
        // The spinner shows at once, even while the connection is still coming up.
        loadingBookId = bookId
        _state.update { it.copy(loading = true, bookId = bookId) }
        command {
            it.setMediaItem(MediaItem.Builder().setMediaId(bookMediaId(bookId)).build())
            it.prepare()
            it.play()
        }
    }

    fun togglePlayPause() = command { c -> if (c.isPlaying) c.pause() else c.play() }

    fun pause() = command { it.pause() }

    fun stop() {
        cancelSleepTimer()
        command {
            it.stop()
            it.clearMediaItems()
        }
        loadingBookId = null
        infoBookId = null
        info = null
        _state.value = PlaybackState()
    }

    /** Seek to a book-wide absolute position, mapped through the loaded book's own track table. */
    fun seekToAbsolute(absoluteMs: Long) = command { c ->
        if (c.mediaItemCount == 0) return@command
        val tracks = c.items().trackTable()
        val index = trackIndexAt(absoluteMs, tracks)
        val position = trackRelativeMs(absoluteMs, index, tracks, folderBased = true).coerceAtLeast(0)
        if (index == c.currentMediaItemIndex) c.seekTo(position) else c.seekTo(index, position)
    }

    fun skipBy(deltaMs: Long) = command { c ->
        if (c.mediaItemCount == 0) return@command
        val durations = c.items().trackTable().map { it.durationMs }
        val target = skipTarget(c.currentMediaItemIndex, c.currentPosition, deltaMs, durations)
        if (target.trackIndex == c.currentMediaItemIndex) c.seekTo(target.positionMs)
        else c.seekTo(target.trackIndex, target.positionMs)
    }

    fun rewind() = skipBy(-PlaybackService.SKIP_MS)
    fun fastForward() = skipBy(PlaybackService.SKIP_MS)
    fun previousTrack() = command { it.seekToPreviousMediaItem() }
    fun nextTrack() = command { it.seekToNextMediaItem() }
    fun seekToTrack(index: Int) = command { it.seekTo(index, 0L) }

    fun seekToChapter(chapter: AudiobookChapter) = seekToAbsolute(chapter.startTimeMs)

    fun setSpeed(speed: Float) = command { it.setPlaybackSpeed(speed.coerceIn(0.5f, 3f)) }

    // ── Sleep timer ───────────────────────────────────────────────────────

    /** Counts down, fades the last five seconds, then pauses. A shake restarts it at the same length. */
    fun startSleepTimer(durationMs: Long) {
        sleepChapterMode = false
        runSleep(durationMs)
    }

    /**
     * Sleeps at the end of the chapter now playing (the track for a
     * folder-based book without chapters, else the book's end), in content
     * time at the current speed. A shake re-targets the chapter playing then.
     */
    fun startSleepAtChapterEnd(): Boolean {
        val s = _state.value
        val tracks = controller?.items()?.trackTable() ?: emptyList()
        val end = chapterEndMs(s.positionMs, s.chapters, tracks, s.durationMs) ?: return false
        val wall = wallClockUntil(s.positionMs, end, s.speed)
        if (wall <= 0) return false
        sleepChapterMode = true
        runSleep(wall)
        return true
    }

    private fun runSleep(durationMs: Long) {
        sleepJob?.cancel()
        sleepLengthMs = durationMs
        controller?.volume = 1f
        val forBook = _state.value.bookId
        val chapterMode = sleepChapterMode
        sleepJob = scope.launch {
            listenForShake()
            var remaining = durationMs
            _state.update { it.copy(sleepRemainingMs = remaining, sleepAtChapterEnd = chapterMode) }
            while (remaining > 0 && isActive) {
                delay(1_000)
                remaining -= 1_000
                if (_state.value.bookId != forBook) {
                    cancelSleepTimer(); return@launch
                }
                _state.update { it.copy(sleepRemainingMs = remaining.coerceAtLeast(0)) }
                if (remaining in 1..FADE_MS) controller?.volume = sleepVolumeFor(remaining)
            }
            if (isActive) {
                controller?.pause()
                controller?.volume = 1f
                stopListeningForShake()
                sleepChapterMode = false
                _state.update { it.copy(sleepRemainingMs = null, sleepAtChapterEnd = false) }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        stopListeningForShake()
        controller?.volume = 1f
        sleepChapterMode = false
        _state.update { it.copy(sleepRemainingMs = null, sleepAtChapterEnd = false) }
    }

    /** The accelerometer only runs while a timer does, and only if the setting is on. */
    private suspend fun listenForShake() {
        if (shake != null) return
        if (!settings.shakeToReset.first()) return
        val detector = ShakeDetector(context) {
            if (_state.value.sleepRemainingMs == null) return@ShakeDetector
            if (sleepChapterMode) startSleepAtChapterEnd() else if (sleepLengthMs > 0) startSleepTimer(sleepLengthMs)
        }
        if (detector.start()) shake = detector
    }

    private fun stopListeningForShake() {
        shake?.stop()
        shake = null
    }

    // ── State ─────────────────────────────────────────────────────────────

    private inner class Events : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    /**
     * Fetches the chapter list for [bookId] once: the download record when
     * the book is on the device, else the server. A failure stays failed
     * (no refetch from the ticker) until [retry]: a deliberate play of the
     * same book, or a reconnect, which starts over with a null [infoBookId].
     */
    private fun ensureInfo(bookId: Long, retry: Boolean = false) {
        if (infoBookId == bookId && (info != null || !retry)) return
        infoJob?.cancel()
        infoBookId = bookId
        info = null
        _state.update { it.copy(chapters = emptyList()) }
        infoJob = scope.launch {
            val fetched = withContext(Dispatchers.IO) {
                runCatching { downloads.record(bookId)?.info ?: books.audiobookInfo(bookId) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "Chapter list for book $bookId unavailable", it)
                    }
                    .getOrNull()
            }
            // The player may have moved on to another book meanwhile.
            if (infoBookId != bookId) return@launch
            info = fetched
            _state.update { it.copy(chapters = fetched?.chapters ?: emptyList()) }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val loadingFor = loadingBookId
        if (item == null || c.mediaItemCount == 0) {
            // Nothing loaded: either a book is on its way (keep the spinner for
            // it) or the player is empty.
            if (loadingFor == null) _state.update {
                PlaybackState(sleepRemainingMs = it.sleepRemainingMs, sleepAtChapterEnd = it.sleepAtChapterEnd, error = it.error)
            }
            return
        }
        val bookId = item.bookId
        if (loadingFor != null) {
            // The previous book's items stay current while the requested one
            // resolves; the state keeps showing the requested one as loading.
            if (bookId != loadingFor) return
            loadingBookId = null
        }
        if (bookId != null) ensureInfo(bookId)
        val position = c.currentPosition.coerceAtLeast(0)
        _state.update { s ->
            s.copy(
                bookId = bookId ?: s.bookId,
                title = item.mediaMetadata.albumTitle?.toString() ?: item.mediaMetadata.title?.toString() ?: s.title,
                artist = item.mediaMetadata.artist?.toString() ?: "",
                artworkUrl = item.mediaMetadata.artworkUri?.toString(),
                playing = c.isPlaying,
                loading = c.playbackState == Player.STATE_BUFFERING && !c.isPlaying,
                positionMs = item.absolutePositionMs(position),
                durationMs = item.totalDurationMs.takeIf { it > 0 } ?: c.duration.coerceAtLeast(0),
                trackIndex = item.trackIndex,
                trackCount = c.mediaItemCount,
                speed = c.playbackParameters.speed,
                chapters = if (bookId != null && bookId == infoBookId) info?.chapters ?: emptyList() else emptyList(),
            )
        }
    }

    private fun startPositionJob() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                delay(500)
                if (controller?.isPlaying == true) refresh()
            }
        }
    }

    /** The playlist as the controller sees it; main thread only. */
    private fun MediaController.items(): List<MediaItem> = (0 until mediaItemCount).map { getMediaItemAt(it) }

    companion object {
        private const val TAG = "PlayerController"
        const val FADE_MS = 5_000L
        /** Taps that pile up before the connection comes up; older ones are superseded. */
        private const val MAX_PENDING = 8

        /** Full until the fade, then down to a whisper, never silent before the pause. */
        fun sleepVolumeFor(remainingMs: Long): Float {
            if (remainingMs >= FADE_MS) return 1f
            val fraction = remainingMs.toFloat() / FADE_MS
            return (0.1f + 0.9f * fraction).coerceIn(0.1f, 1f)
        }
    }
}
