package com.schmitzkr.grimreader.playback

import androidx.media3.common.PlaybackException
import com.schmitzkr.grimreader.ui.friendlyError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * Why resolving a book for playback failed, in one line. The server
 * explains its refusals in a `{"status", "message"}` body ("No audiobook
 * file found for book 12"), which says far more than the status code, so
 * that message wins when there is one.
 */
fun loadFailureMessage(error: Throwable): String {
    if (error is HttpException) {
        val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        serverMessage(body)?.let { return "The server could not load this book: $it" }
    }
    return friendlyError(error)
}

/** The `message` of a server error body, or null when there is none. */
fun serverMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    return runCatching { obj["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The player's own failure (the stream, not the metadata), by error code:
 * that is all that reliably crosses the session to a controller.
 */
fun playbackErrorMessage(errorCode: Int): String = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "The server refused to stream this book. Its audio file may be missing or unreadable."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This book's audio file is missing."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "This book's audio file could not be opened."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> "Could not reach the server to stream this book."
    in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    -> "This book's audio could not be played on this device."
    else -> "Playback failed (${PlaybackException.getErrorCodeName(errorCode)})."
}
