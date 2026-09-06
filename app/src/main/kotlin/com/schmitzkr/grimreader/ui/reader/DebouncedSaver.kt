package com.schmitzkr.grimreader.ui.reader

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
    } catch (e: Exception) {
        onError(e)
        false
    }
}
