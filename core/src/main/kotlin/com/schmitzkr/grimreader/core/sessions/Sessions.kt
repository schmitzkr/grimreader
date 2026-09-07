package com.schmitzkr.grimreader.core.sessions

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One stretch of reading or listening, as `POST /reading-sessions` takes
 * it. The stats pages are built from these.
 */
@Serializable
data class ReadingSession(
    val bookId: Long,
    /** `AUDIOBOOK`, `EPUB`, `PDF` or `CBX`. */
    val bookType: String,
    /** ISO-8601 instants. */
    val startTime: String,
    val endTime: String,
    val durationSeconds: Long,
    val durationFormatted: String,
    /** 0–100. */
    val startProgress: Double,
    val endProgress: Double,
    val progressDelta: Double,
    val startLocation: String? = null,
    val endLocation: String? = null,
)

/** A session that has started and not yet ended. Times are wall clock; [startElapsedMs] is a monotonic clock. */
data class ActiveSession(
    val bookId: Long,
    val bookType: String,
    val startedAt: Instant,
    val startElapsedMs: Long,
    val startProgress: Double,
    val startLocation: String?,
)

/** Sessions shorter than this are noise (a mis-tap, a preview) and are dropped. */
const val MIN_SESSION_SECONDS = 30L

/**
 * Closes [this] at [endedAt] / [endElapsedMs], or returns null when it was
 * too short to count. Duration comes from the monotonic clock so a wall
 * clock jump cannot produce a negative or day-long session.
 */
fun ActiveSession.finish(endedAt: Instant, endElapsedMs: Long, endProgress: Double, endLocation: String?): ReadingSession? {
    val seconds = ((endElapsedMs - startElapsedMs) / 1000).coerceAtLeast(0)
    if (seconds < MIN_SESSION_SECONDS) return null
    val start = startProgress.coerceIn(0.0, 100.0)
    val end = endProgress.coerceIn(0.0, 100.0)
    return ReadingSession(
        bookId = bookId,
        bookType = bookType,
        startTime = startedAt.toString(),
        endTime = endedAt.toString(),
        durationSeconds = seconds,
        durationFormatted = formatSessionDuration(seconds),
        startProgress = start,
        endProgress = end,
        progressDelta = ((end - start) * 100).let { Math.round(it) / 100.0 },
        startLocation = startLocation,
        endLocation = endLocation,
    )
}

/** `1h 5m`, `12m 30s`, `45s`. */
fun formatSessionDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
