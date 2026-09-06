package com.schmitzkr.grimreader.core.api

import com.schmitzkr.grimreader.core.model.AuthTokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Instant
import java.util.concurrent.TimeUnit

/** What a session looks like at rest: the two tokens and when the access one dies. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant?,
) {
    /** True inside the last minute of the access token's life. */
    fun expiresSoon(now: Instant = Instant.now()): Boolean =
        expiresAt != null && expiresAt.minusSeconds(60).isBefore(now)
}

/**
 * Where the session lives while the app runs. The app persists it; the
 * client only reads and replaces it. Kept synchronous because OkHttp's
 * interceptors and authenticator run on its own threads.
 */
interface SessionStore {
    fun current(): Session?
    fun replace(session: Session?)
}

/** Fires once when the server rejects the session for good. */
class SessionEvents {
    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()
    internal fun signalExpired() {
        _expired.tryEmit(Unit)
    }
}

/**
 * Builds the OkHttp client and Retrofit API for one server. Auth behaves
 * as the Flutter app's did after its refresh-storm fix:
 *
 * - every request outside the auth endpoints and `public-settings` gets the bearer;
 * - a token inside a minute of expiry is refreshed before the request;
 * - a 401 refreshes once (single-flight) and retries;
 * - a refresh the server rejects (401/403) clears the session locally and
 *   signals [SessionEvents.expired]. A rate limit, 5xx or dead network does
 *   not: the old token is kept and the next request tries again.
 *
 * [AccessTokenDto.expires] is the token's *lifetime in seconds* (7200),
 * not a timestamp.
 */
class GrimmoryClient(
    serverUrl: String,
    private val store: SessionStore,
    val events: SessionEvents = SessionEvents(),
    userAgent: String = "GrimReader",
) {
    val baseUrl: String = serverUrl.trimEnd('/')
    val apiBase: String = "$baseUrl/api/v1/"

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val refreshLock = Any()

    /** Talks to the refresh endpoint without interceptors, so a refresh cannot recurse. */
    private val bareClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder().header("User-Agent", userAgent)
            if (!isAuthPath(request.url.encodedPath)) {
                var session = store.current()
                if (session != null && session.expiresSoon()) {
                    // Best effort: on failure the old token goes out and the
                    // 401 path below takes over.
                    session = refreshBlocking(session) ?: session
                }
                session?.let { builder.header("Authorization", "Bearer ${it.accessToken}") }
            }
            chain.proceed(builder.build())
        })
        .authenticator(Authenticator { _: Route?, response: Response ->
            if (isAuthPath(response.request.url.encodedPath)) return@Authenticator null
            // One retry per request: a second 401 with a fresh token means
            // the account really is not allowed.
            if (response.priorResponse != null) return@Authenticator null
            val session = store.current() ?: run {
                events.signalExpired()
                return@Authenticator null
            }
            val sent = response.request.header("Authorization")
            val latest = store.current()
            // Another thread already refreshed while this request was out.
            val refreshed = if (latest != null && sent != "Bearer ${latest.accessToken}") latest
            else refreshBlocking(session)
            refreshed?.let {
                response.request.newBuilder()
                    .header("Authorization", "Bearer ${it.accessToken}")
                    .build()
            }
        })
        .build()

    val api: GrimmoryApi = Retrofit.Builder()
        .baseUrl(apiBase)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GrimmoryApi::class.java)

    /** Headers for loaders outside Retrofit (cover images, the audio player). */
    fun authHeaders(): Map<String, String> =
        store.current()?.let { mapOf("Authorization" to "Bearer ${it.accessToken}") } ?: emptyMap()

    fun coverUrl(bookId: Long, audiobook: Boolean, version: String?): String {
        val path = if (audiobook) "audiobooks/$bookId/cover" else "media/book/$bookId/thumbnail"
        return if (version == null) "$apiBase$path" else "$apiBase$path?v=$version"
    }

    fun authorPhotoUrl(authorId: Long): String = "${apiBase}media/author/$authorId/photo"

    fun streamUrl(bookId: Long): String = "${apiBase}audiobooks/$bookId/stream"

    fun trackStreamUrl(bookId: Long, trackIndex: Int): String =
        "${apiBase}audiobooks/$bookId/track/$trackIndex/stream"

    fun comicPageUrl(bookId: Long, page: Int): String =
        "${apiBase}media/book/$bookId/cbx/pages/$page"

    /** Stores a fresh login or refresh result. */
    fun storeTokens(tokens: AuthTokens) {
        store.replace(tokens.toSession())
    }

    /** Forgets the session on this device only. */
    fun clearSession() = store.replace(null)

    // ── Refresh ───────────────────────────────────────────────────────────

    /**
     * Refreshes [session] and returns the new one, or null if it could not.
     * Serialised: concurrent callers share one refresh and get its result.
     */
    private fun refreshBlocking(session: Session): Session? = synchronized(refreshLock) {
        val latest = store.current()
        // Someone finished a refresh while we waited for the lock.
        if (latest != null && latest.accessToken != session.accessToken) return latest
        val call = RefreshCall(bareClient, apiBase, json, session.refreshToken)
        when (val result = call.execute()) {
            is RefreshResult.Success -> result.tokens.toSession().also { store.replace(it) }
            RefreshResult.Rejected -> {
                store.replace(null)
                events.signalExpired()
                null
            }
            RefreshResult.Unavailable -> null
        }
    }

    private fun isAuthPath(path: String): Boolean =
        path.contains("/auth/") || path.endsWith("/public-settings")

    private fun AuthTokens.toSession() = Session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expires?.let { Instant.now().plusSeconds(it) },
    )
}

internal sealed interface RefreshResult {
    data class Success(val tokens: AuthTokens) : RefreshResult
    /** 401/403: the refresh token is dead. */
    data object Rejected : RefreshResult
    /** Anything else: try again later with the old token. */
    data object Unavailable : RefreshResult
}

/** A synchronous refresh, since it runs inside OkHttp's own threads. */
internal class RefreshCall(
    private val client: OkHttpClient,
    private val apiBase: String,
    private val json: Json,
    private val refreshToken: String,
) {
    fun execute(): RefreshResult {
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
        val request = Request.Builder()
            .url("${apiBase}auth/refresh")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        RefreshResult.Success(json.decodeFromString(AuthTokens.serializer(), text))
                    }
                    response.code == 401 || response.code == 403 -> RefreshResult.Rejected
                    else -> RefreshResult.Unavailable
                }
            }
        } catch (e: Exception) {
            RefreshResult.Unavailable
        }
    }
}
