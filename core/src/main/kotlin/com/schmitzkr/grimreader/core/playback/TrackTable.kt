package com.schmitzkr.grimreader.core.playback

import com.schmitzkr.grimreader.core.model.AudiobookTrack

/**
 * The playlist index playing at a book-wide [absoluteMs]: the last track
 * that starts at or before it, the first when the position precedes every
 * start, and 0 for an empty table. [tracks] are in playlist order, so a
 * single stream is a one-entry table starting at zero and always maps to 0.
 */
fun trackIndexAt(absoluteMs: Long, tracks: List<AudiobookTrack>): Int {
    if (tracks.isEmpty()) return 0
    val i = tracks.indexOfLast { it.cumulativeStartMs <= absoluteMs }
    return if (i < 0) 0 else i
}
