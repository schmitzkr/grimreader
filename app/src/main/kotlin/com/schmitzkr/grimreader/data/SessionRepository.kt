package com.schmitzkr.grimreader.data

import android.os.SystemClock
import android.util.Log
import com.schmitzkr.grimreader.core.sessions.ActiveSession
import com.schmitzkr.grimreader.core.sessions.ReadingSession
import com.schmitzkr.grimreader.core.sessions.finish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Audio keeps counting in the background; a reader stops when it leaves the screen. */
enum class SessionKind { AUDIO, READER }

/**
 * Records stretches of reading and listening for the server's stats
 * pages. One session per kind runs at a time; a session that fails to
 * post is queued on the device and retried at launch, at sign-in and
 * after the next successful post.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val clients: ClientHolder,
    private val settings: Settings,
    private val auth: AuthRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<SessionKind, ActiveSession>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val retryLock = Mutex()

    /** Called once from the Application. */
    fun start() {
        scope.launch { auth.currentUser.filterNotNull().collect { retryPending() } }
    }

    fun begin(kind: SessionKind, bookId: Long, bookType: String, progress: Double, location: String?) {
        active[kind] = ActiveSession(
            bookId = bookId,
            bookType = bookType,
            startedAt = Instant.now(),
            startElapsedMs = SystemClock.elapsedRealtime(),
            startProgress = progress,
            startLocation = location,
        )
    }

    fun current(kind: SessionKind): ActiveSession? = active[kind]

    /** Ends the running session of [kind], if any; posts it when it was long enough to count. */
    fun end(kind: SessionKind, progress: Double, location: String?) {
        val a = active.remove(kind) ?: return
        val session = a.finish(Instant.now(), SystemClock.elapsedRealtime(), progress, location) ?: return
        scope.launch {
            if (post(session)) retryPending() else enqueue(session)
        }
    }

    private suspend fun post(session: ReadingSession): Boolean = runCatching {
        val body = json.encodeToJsonElement(ReadingSession.serializer(), session).jsonObject
        clients.current().api.createReadingSession(body).isSuccessful
    }.onFailure { Log.w(TAG, "Session post failed", it) }.getOrDefault(false)

    private suspend fun enqueue(session: ReadingSession) = retryLock.withLock {
        val pending = readPending() + session
        writePending(pending.takeLast(MAX_PENDING))
    }

    /** Posts every queued session in order; the ones that still fail stay queued. */
    suspend fun retryPending() = retryLock.withLock {
        val pending = readPending()
        if (pending.isEmpty()) return@withLock
        if (!clients.isConfigured) return@withLock
        val stillPending = pending.filterNot { post(it) }
        writePending(stillPending)
    }

    private suspend fun readPending(): List<ReadingSession> =
        settings.pendingSessions()?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(ReadingSession.serializer()), raw) }.getOrDefault(emptyList())
        } ?: emptyList()

    private suspend fun writePending(list: List<ReadingSession>) =
        settings.setPendingSessions(if (list.isEmpty()) null else json.encodeToString(ListSerializer(ReadingSession.serializer()), list))

    companion object {
        private const val TAG = "Sessions"
        private const val MAX_PENDING = 200
    }
}
