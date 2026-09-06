package com.schmitzkr.grimreader.core.playback

import com.schmitzkr.grimreader.core.model.AudiobookTrack
import kotlin.math.roundToInt

/*
 * Grimmory audiobooks come in two shapes: one continuous stream with
 * chapter markers, or a folder-based book split into tracks where the
 * server's progress API still wants a book-wide absolute position. These
 * convert between the two and implement a skip that crosses track edges.
 */

/** Book-wide position → position inside the track the player is on. */
fun trackRelativeMs(
    positionMs: Long,
    trackIndex: Int?,
    tracks: List<AudiobookTrack>,
    folderBased: Boolean,
): Long {
    if (!folderBased || trackIndex == null) return positionMs
    if (trackIndex < 0 || trackIndex >= tracks.size) return positionMs
    val relative = positionMs - tracks[trackIndex].cumulativeStartMs
    return if (relative < 0) 0 else relative
}

/** Position inside a track → the book-wide absolute position. */
fun absoluteMs(
    trackPositionMs: Long,
    trackIndex: Int?,
    tracks: List<AudiobookTrack>,
    folderBased: Boolean,
): Long {
    if (!folderBased) return trackPositionMs
    if (trackIndex == null || trackIndex < 0 || trackIndex >= tracks.size) return trackPositionMs
    return tracks[trackIndex].cumulativeStartMs + trackPositionMs
}

data class SkipTarget(val trackIndex: Int, val positionMs: Long)

/**
 * Where a relative seek of [deltaMs] lands, carrying over into the previous
 * or next track instead of stopping at the edge of the current one.
 */
fun skipTarget(
    trackIndex: Int,
    positionMs: Long,
    deltaMs: Long,
    trackDurationsMs: List<Long>,
): SkipTarget {
    if (trackDurationsMs.isEmpty()) {
        val p = positionMs + deltaMs
        return SkipTarget(trackIndex, if (p < 0) 0 else p)
    }
    var index = trackIndex.coerceIn(0, trackDurationsMs.size - 1)
    var pos = positionMs + deltaMs
    while (pos < 0 && index > 0) {
        index--
        pos += trackDurationsMs[index]
    }
    while (pos >= trackDurationsMs[index] && index < trackDurationsMs.size - 1) {
        pos -= trackDurationsMs[index]
        index++
    }
    val end = trackDurationsMs[index]
    if (pos < 0) pos = 0
    if (pos > end) pos = end
    return SkipTarget(index, pos)
}

/** 0–100 to one decimal, the way the web client saves it. */
fun audiobookPercentage(positionMs: Long, totalDurationMs: Long): Double {
    if (totalDurationMs <= 0) return 0.0
    val raw = (positionMs.toDouble() / totalDurationMs * 100).coerceIn(0.0, 100.0)
    return (raw * 10).roundToInt() / 10.0
}

/** Page-based progress, `round(page / pageCount * 1000) / 10`, [pageIndex] 0-based. */
fun pagePercentage(pageIndex: Int, pageCount: Int): Double {
    if (pageCount <= 0) return 0.0
    val clamped = pageIndex.coerceIn(0, pageCount - 1)
    return ((clamped + 1).toDouble() / pageCount * 1000).roundToInt() / 10.0
}
