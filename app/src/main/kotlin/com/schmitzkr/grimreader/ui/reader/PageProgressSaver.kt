package com.schmitzkr.grimreader.ui.reader

import com.schmitzkr.grimreader.core.model.PageProgress
import com.schmitzkr.grimreader.core.playback.pagePercentage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounced, serialised page-progress saving shared by the comic and PDF
 * readers. Flipping through several pages collapses into one save of the
 * newest page; at most one save is on the wire at a time; the exit path's
 * [saveNow] waits for any in-flight save so it cannot be overtaken by a
 * stale one.
 *
 * Page indices are 0-based; [pageNumberAt] maps one to the number the
 * server stores (a comic's server-listed page ids, or `index + 1` for a PDF).
 */
class PageProgressSaver(
    private val scope: CoroutineScope,
    private val pageCount: Int,
    private val pageNumberAt: (Int) -> Int,
    private val persist: suspend (PageProgress) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val debounceMs: Long = 1_500,
) {
    private var pending: Int? = null
    private var timer: Job? = null
    private val lock = Mutex()

    fun progressAt(index: Int) = PageProgress(page = pageNumberAt(index), percentage = pagePercentage(index, pageCount))

    /** The reader moved to page [index]; a save follows after the debounce. */
    fun pageChanged(index: Int) {
        pending = index
        timer?.cancel()
        timer = scope.launch {
            delay(debounceMs)
            flush()
        }
    }

    private suspend fun flush() {
        val index = pending ?: return
        pending = null
        lock.withLock { save(index) }
        if (pending != null) flush()
    }

    /** The final save on exit: drops a pending passive save, waits for one in flight, saves [index]. */
    suspend fun saveNow(index: Int): Boolean {
        timer?.cancel()
        pending = null
        return lock.withLock { save(index) }
    }

    private suspend fun save(index: Int): Boolean = try {
        persist(progressAt(index))
        true
    } catch (e: CancellationException) {
        // Leaving the reader mid-save cancels this coroutine as a normal
        // part of the screen going away -- rethrow so structured concurrency
        // still sees it, instead of reporting "StandaloneCoroutine was
        // cancelled" to the user as if it were a real sync failure.
        throw e
    } catch (e: Exception) {
        onError(e)
        false
    }
}
