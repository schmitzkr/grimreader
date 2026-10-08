package com.schmitzkr.grimreader.data

import android.content.Context
import android.util.Log
import com.schmitzkr.grimreader.core.api.LocalProgress
import com.schmitzkr.grimreader.core.api.SaveOutcome
import com.schmitzkr.grimreader.core.api.progressKey
import com.schmitzkr.grimreader.core.api.saveOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every position the app has saved or loaded, one per book and kind, in
 * a small JSON file. A save lands here first and stays [LocalProgress.pending]
 * until the server answers for it; pending saves are pushed again at launch
 * and sign-in, after each successful save, and on every load. A save the
 * server refuses outright (see [saveOutcome]) is settled too, so it is never
 * sent again and no longer beats the server's copy; it only stands in for
 * an offline open until the next load or save replaces it.
 */
@Singleton
class ProgressStore @Inject constructor(
    private val context: Context,
    private val clients: ClientHolder,
    private val auth: AuthRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), LocalProgress.serializer())
    private val lock = Mutex()
    private val syncLock = Mutex()
    private var cache: MutableMap<String, LocalProgress>? = null
    private val file get() = File(context.filesDir, "progress-store.json")

    /** Called once from the Application. */
    fun start() {
        scope.launch { auth.currentUser.filterNotNull().collect { retryPending() } }
    }

    suspend fun get(kind: String, bookId: Long): LocalProgress? = lock.withLock { entries()[progressKey(kind, bookId)] }

    suspend fun put(kind: String, bookId: Long, body: JsonObject, pending: Boolean) = lock.withLock {
        entries()[progressKey(kind, bookId)] = LocalProgress(bookId, kind, body, System.currentTimeMillis(), pending)
        persist()
    }

    /** Ends the pending state of [body] once the server has answered for it, accepted or refused. */
    private suspend fun settle(kind: String, bookId: Long, body: JsonObject) = lock.withLock {
        val key = progressKey(kind, bookId)
        val current = entries()[key] ?: return@withLock
        // A newer pending save may have landed meanwhile; leave that one pending.
        if (current.body == body) {
            entries()[key] = current.copy(pending = false)
            persist()
        }
    }

    /** Sends one body; a transport failure counts as a retry, never as an answer. */
    private suspend fun push(bookId: Long, body: JsonObject): SaveOutcome = try {
        saveOutcome(clients.current().api.updateProgress(bookId, body).code()).also {
            if (it == SaveOutcome.REJECTED) Log.w(TAG, "Progress save for book $bookId refused by the server, not retried")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Progress save failed, kept pending", e)
        SaveOutcome.RETRY
    }

    /** Pushes every pending save; the ones the server could not answer stay pending. */
    suspend fun retryPending() {
        if (!clients.isConfigured) return
        if (!syncLock.tryLock()) return
        try {
            val pending = lock.withLock { entries().values.filter { it.pending } }
            for (p in pending) {
                if (push(p.bookId, p.body) != SaveOutcome.RETRY) settle(p.kind, p.bookId, p.body)
            }
        } finally {
            syncLock.unlock()
        }
    }

    fun retryPendingLater() {
        scope.launch { retryPending() }
    }

    /**
     * Local first: the body is stored pending, sent, and settled once the
     * server answers. Returns whether the server took it.
     */
    suspend fun save(kind: String, bookId: Long, body: JsonObject): Boolean {
        put(kind, bookId, body, pending = true)
        val outcome = push(bookId, body)
        if (outcome != SaveOutcome.RETRY) settle(kind, bookId, body)
        if (outcome == SaveOutcome.ACCEPTED) retryPendingLater()
        return outcome == SaveOutcome.ACCEPTED
    }

    /**
     * Remembers what the server said so a later offline load has something
     * to open at; null means the server has no position, so the remembered
     * one goes too. A pending save is left alone either way.
     */
    suspend fun remember(kind: String, bookId: Long, serverBody: JsonObject?) {
        val current = get(kind, bookId)
        if (current?.pending == true) return
        if (serverBody != null) put(kind, bookId, serverBody, pending = false) else forget(kind, bookId)
    }

    private suspend fun forget(kind: String, bookId: Long) = lock.withLock {
        if (entries().remove(progressKey(kind, bookId)) != null) persist()
    }

    private suspend fun entries(): MutableMap<String, LocalProgress> {
        cache?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyMap() }
                .getOrDefault(emptyMap())
        }
        return loaded.toMutableMap().also { cache = it }
    }

    private suspend fun persist() {
        val snapshot = cache?.toMap() ?: return
        withContext(Dispatchers.IO) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(serializer, snapshot))
            if (!temp.renameTo(file)) Log.w(TAG, "Could not replace the progress store")
        }
    }

    companion object {
        private const val TAG = "ProgressStore"
    }
}
