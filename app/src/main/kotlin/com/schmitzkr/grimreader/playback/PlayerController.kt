package com.schmitzkr.grimreader.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.schmitzkr.grimreader.core.model.AudiobookChapter
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.playback.skipTarget
import com.schmitzkr.grimreader.data.BooksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
) {
    val hasBook: Boolean get() = bookId != null
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val currentChapterIndex: Int?
        get() = chapters.indexOfLast { it.startTimeMs <= positionMs }.takeIf { it >= 0 }
}

/**
 * The app's handle on [PlaybackService]: connects a [MediaController] once
 * and keeps a [PlaybackState] flow current. Also owns the sleep timer,
 * which is app-side state the service does not need to know about.
 */
@Singleton
class PlayerController @Inject constructor(
    private val context: Context,
    private val books: BooksRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var positionJob: Job? = null
    private var sleepJob: Job? = null
    private var info: AudiobookInfo? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(Events())
            refresh()
            startPositionJob()
        }, MoreExecutors.directExecutor())
    }

    // ── Commands ──────────────────────────────────────────────────────────

    /** Loads [bookId] (resuming its saved position) and starts playing. */
    fun play(bookId: Long) {
        val c = controller ?: return
        if (_state.value.bookId == bookId && c.mediaItemCount > 0) {
            c.play()
            return
        }
        _state.update { it.copy(loading = true, bookId = bookId) }
        c.setMediaItem(MediaItem.Builder().setMediaId(bookMediaId(bookId)).build())
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() = controller?.pause()

    fun stop() {
        cancelSleepTimer()
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        info = null
        _state.value = PlaybackState()
    }

    /** Seek to a book-wide absolute position. */
    fun seekToAbsolute(absoluteMs: Long) {
        val c = controller ?: return
        val i = info
        if (i != null && i.folderBased && i.tracks.isNotEmpty()) {
            val track = i.tracks.lastOrNull { it.cumulativeStartMs <= absoluteMs } ?: i.tracks.first()
            c.seekTo(track.index, (absoluteMs - track.cumulativeStartMs).coerceAtLeast(0))
        } else {
            c.seekTo(absoluteMs.coerceAtLeast(0))
        }
    }

    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        val durations = (0 until c.mediaItemCount).map {
            c.getMediaItemAt(it).mediaMetadata.extras?.getLong(Extras.TRACK_DURATION_MS, 0L) ?: 0L
        }
        val target = skipTarget(c.currentMediaItemIndex, c.currentPosition, deltaMs, durations)
        if (target.trackIndex == c.currentMediaItemIndex) c.seekTo(target.positionMs)
        else c.seekTo(target.trackIndex, target.positionMs)
    }

    fun rewind() = skipBy(-PlaybackService.SKIP_MS)
    fun fastForward() = skipBy(PlaybackService.SKIP_MS)
    fun previousTrack() = controller?.seekToPreviousMediaItem()
    fun nextTrack() = controller?.seekToNextMediaItem()
    fun seekToTrack(index: Int) = controller?.seekTo(index, 0L)

    fun seekToChapter(chapter: AudiobookChapter) = seekToAbsolute(chapter.startTimeMs)

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
    }

    // ── Sleep timer ───────────────────────────────────────────────────────

    /** Counts down, fades the last five seconds, then pauses. */
    fun startSleepTimer(durationMs: Long) {
        sleepJob?.cancel()
        val forBook = _state.value.bookId
        sleepJob = scope.launch {
            var remaining = durationMs
            _state.update { it.copy(sleepRemainingMs = remaining) }
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
                _state.update { it.copy(sleepRemainingMs = null) }
            }
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        controller?.volume = 1f
        _state.update { it.copy(sleepRemainingMs = null) }
    }

    // ── State ─────────────────────────────────────────────────────────────

    private inner class Events : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val bookId = mediaItem?.bookId
            if (bookId != null && info?.bookId != bookId) loadInfo(bookId)
            refresh()
        }
    }

    private fun loadInfo(bookId: Long) {
        scope.launch {
            info = withContext(Dispatchers.IO) { runCatching { books.audiobookInfo(bookId) }.getOrNull() }
            _state.update { it.copy(chapters = info?.chapters ?: emptyList()) }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val item = c.currentMediaItem
        if (item == null || c.mediaItemCount == 0) {
            if (!_state.value.loading) _state.value = _state.value.copy(
                bookId = null, title = "", playing = false, positionMs = 0, durationMs = 0,
            )
            return
        }
        val bookId = item.bookId
        if (bookId != null && info?.bookId != bookId && !_state.value.loading) loadInfo(bookId)
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

    companion object {
        const val FADE_MS = 5_000L

        /** Full until the fade, then down to a whisper, never silent before the pause. */
        fun sleepVolumeFor(remainingMs: Long): Float {
            if (remainingMs >= FADE_MS) return 1f
            val fraction = remainingMs.toFloat() / FADE_MS
            return (0.1f + 0.9f * fraction).coerceIn(0.1f, 1f)
        }
    }
}
