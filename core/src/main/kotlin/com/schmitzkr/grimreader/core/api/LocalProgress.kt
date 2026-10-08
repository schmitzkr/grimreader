package com.schmitzkr.grimreader.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One saved position on the device: the exact body the server takes,
 * which also parses like the server's own progress response, since both
 * carry the typed field (`audiobookProgress`, `cbxProgress`, …).
 * [pending] means the server has not answered for this body yet; once it
 * has, accepted or refused, the body is only a stand-in for offline opens.
 */
@Serializable
data class LocalProgress(
    val bookId: Long,
    /** `audiobook`, `epub`, `cbx` or `pdf`. */
    val kind: String,
    val body: JsonObject,
    val updatedAt: Long,
    val pending: Boolean,
)

fun progressKey(kind: String, bookId: Long) = "$kind:$bookId"

/** What `GET /progress` answered, the three cases the resolution rule tells apart. */
sealed interface ServerProgress {
    /** The server's stored position. */
    data class Found(val body: JsonObject) : ServerProgress

    /** The server has no position for this book (404): a deliberate empty state, such as a reset on the web. */
    data object None : ServerProgress

    /** No usable answer: transport failure, 5xx, or still unauthorised. */
    data object Unreachable : ServerProgress
}

/**
 * Which position to open at. A pending local save is the newest truth;
 * otherwise the server's answer is, its body or nothing when it says it
 * has none, and the remembered local copy stands in only while the server
 * cannot be reached.
 */
fun resolveProgress(local: LocalProgress?, server: ServerProgress): JsonObject? = when {
    local?.pending == true -> local.body
    server is ServerProgress.Found -> server.body
    server is ServerProgress.None -> null
    else -> local?.body
}

/** The server's verdict on a queued write (a progress save, a reading session). */
enum class SaveOutcome { ACCEPTED, REJECTED, RETRY }

/**
 * A 2xx is accepted; any other 4xx except 401, 408 and 429 is a rejection
 * the server would only repeat, so the body is never sent again; everything
 * else (those three, 5xx) is tried again later.
 */
fun saveOutcome(status: Int): SaveOutcome = when {
    status in 200..299 -> SaveOutcome.ACCEPTED
    status in 400..499 && status !in RETRYABLE_CLIENT_ERRORS -> SaveOutcome.REJECTED
    else -> SaveOutcome.RETRY
}

private val RETRYABLE_CLIENT_ERRORS = setOf(401, 408, 429)
