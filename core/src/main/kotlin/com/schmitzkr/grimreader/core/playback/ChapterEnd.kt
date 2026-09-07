package com.schmitzkr.grimreader.core.playback

import com.schmitzkr.grimreader.core.model.AudiobookChapter
import com.schmitzkr.grimreader.core.model.AudiobookTrack

/**
 * Where the chapter playing at [positionMs] ends, book-wide: the next
 * chapter's start, else the end of the current track for a folder-based
 * book without chapters, else the end of the book. Null when the book has
 * no known length.
 */
fun chapterEndMs(
    positionMs: Long,
    chapters: List<AudiobookChapter>,
    tracks: List<AudiobookTrack>,
    totalDurationMs: Long,
): Long? {
    if (totalDurationMs <= 0) return null
    if (chapters.isNotEmpty()) {
        // The current chapter's own end when the server gave one, else the next chapter's start.
        val current = chapters.lastOrNull { it.startTimeMs <= positionMs }
        if (current != null && current.endTimeMs > positionMs) return current.endTimeMs.coerceAtMost(totalDurationMs)
        val nextChapter = chapters.map { it.startTimeMs }.filter { it > positionMs }.minOrNull()
        return (nextChapter ?: totalDurationMs).coerceAtMost(totalDurationMs)
    }
    val track = tracks.lastOrNull { it.cumulativeStartMs <= positionMs }
    if (track != null) return (track.cumulativeStartMs + track.durationMs).coerceAtMost(totalDurationMs)
    return totalDurationMs
}

/** Wall-clock milliseconds until [endMs] plays out from [positionMs] at [speed]. */
fun wallClockUntil(positionMs: Long, endMs: Long, speed: Float): Long {
    val content = (endMs - positionMs).coerceAtLeast(0)
    val s = if (speed > 0f) speed else 1f
    return (content / s).toLong()
}
