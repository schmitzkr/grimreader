package com.schmitzkr.grimreader.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One saved position on the device: the exact body the server takes,
 * which also parses like the server's own progress response, since both
 * carry the typed field (`audiobookProgress`, `cbxProgress`, …).
 * [pending] means the server has not accepted this body yet.
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

/**
 * Which position to open at. A save the server has not taken yet is the
 * newest truth; otherwise the server's copy, falling back to the local
 * copy when the server could not be reached ([server] null).
 */
fun resolveProgress(local: LocalProgress?, server: JsonObject?): JsonObject? = when {
    local?.pending == true -> local.body
    server != null -> server
    else -> local?.body
}
