package com.schmitzkr.grimreader.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounced, serialised saving of any position value. Rapid changes
 * collapse into one save of the newest; at most one save is on the wire
 * at a time; [saveNow] waits for one in flight so an exit save cannot be
 * overtaken by a stale one.
 *
 * Single-thread invariant: [pending] and [timer] are plain fields with no
 * synchronisation, so [changed] and [saveNow] must be called from the main
 * thread, and [scope] must dispatch there too (`viewModelScope` does:
 * `Dispatchers.Main.immediate`), so [flush] runs on the same thread. The
 * only off-main caller is the EPUB reader's JS bridge, which hops to Main
 * before calling the ViewModel; keep it that way rather than adding locks here.
 */
class DebouncedSaver<T>(
    private val scope: CoroutineScope,
    private val persist: suspend (T) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val debounceMs: Long = 1_500,
) {
    private var pending: T? = null
    private var timer: Job? = null
    private val lock = Mutex()

    fun changed(value: T) {
        pending = value
        timer?.cancel()
        timer = scope.launch {
            delay(debounceMs)
            flush()
        }
    }

    private suspend fun flush() {
        val value = pending ?: return
        pending = null
        lock.withLock { save(value) }
        if (pending != null) flush()
    }

    suspend fun saveNow(value: T): Boolean {
        timer?.cancel()
        pending = null
        return lock.withLock { save(value) }
    }

    private suspend fun save(value: T): Boolean = try {
        persist(value)
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
