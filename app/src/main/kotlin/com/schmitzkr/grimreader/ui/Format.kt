package com.schmitzkr.grimreader.ui

import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** `H:MM:SS`, or `MM:SS` under an hour. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** `1h 2m`, or `2m` under an hour. */
fun formatShort(ms: Long): String {
    val minutes = (ms / 60_000).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

fun formatSpeed(speed: Float): String {
    val s = "%.2f".format(speed)
    return (if (s.endsWith("0")) "%.1f".format(speed) else s) + "×"
}

/** One line a person can act on, instead of a stack trace. */
fun friendlyError(error: Throwable): String = when (error) {
    is HttpException -> when (error.code()) {
        401 -> "Your session has expired. Please sign in again."
        403 -> "You do not have permission to do that."
        404 -> "Not found on the server."
        429 -> "Too many requests. Try again in a moment."
        in 500..599 -> "The server had a problem (${error.code()})."
        else -> "Request failed (${error.code()})."
    }
    is UnknownHostException -> "Could not reach the server. Check the address and your connection."
    is SocketTimeoutException -> "The server took too long to answer."
    is IOException -> "Could not connect to the server."
    else -> error.message ?: "Something went wrong."
}
