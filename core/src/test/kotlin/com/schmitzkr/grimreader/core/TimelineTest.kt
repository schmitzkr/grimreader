package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.model.AudiobookTrack
import com.schmitzkr.grimreader.core.playback.SkipTarget
import com.schmitzkr.grimreader.core.playback.absoluteMs
import com.schmitzkr.grimreader.core.playback.audiobookPercentage
import com.schmitzkr.grimreader.core.playback.pagePercentage
import com.schmitzkr.grimreader.core.playback.skipTarget
import com.schmitzkr.grimreader.core.playback.trackRelativeMs
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    private val tracks = listOf(
        AudiobookTrack(0, "01.mp3", "One", 60_000, 0),
        AudiobookTrack(1, "02.mp3", "Two", 90_000, 60_000),
        AudiobookTrack(2, "03.mp3", "Three", 30_000, 150_000),
    )

    @Test
    fun `absolute and relative positions round-trip on a folder-based book`() {
        assertEquals(75_000, absoluteMs(15_000, 1, tracks, folderBased = true))
        assertEquals(15_000, trackRelativeMs(75_000, 1, tracks, folderBased = true))
        // A single stream is its own timeline.
        assertEquals(75_000, absoluteMs(75_000, 1, tracks, folderBased = false))
        assertEquals(75_000, trackRelativeMs(75_000, null, tracks, folderBased = true))
    }

    @Test
    fun `skipping crosses track boundaries in both directions`() {
        val durations = tracks.map { it.durationMs }
        assertEquals(SkipTarget(1, 10_000), skipTarget(0, 40_000, 30_000, durations))
        assertEquals(SkipTarget(0, 50_000), skipTarget(1, 20_000, -30_000, durations))
        // Cannot go before the start or past the end.
        assertEquals(SkipTarget(0, 0), skipTarget(0, 5_000, -30_000, durations))
        assertEquals(SkipTarget(2, 30_000), skipTarget(2, 25_000, 30_000, durations))
        // No tracks known: plain arithmetic, floored at zero.
        assertEquals(SkipTarget(0, 0), skipTarget(0, 5_000, -30_000, emptyList()))
    }

    @Test
    fun `percentages are one decimal on a 0-100 scale`() {
        assertEquals(12.3, audiobookPercentage(12_345, 100_000), 0.0001)
        assertEquals(0.0, audiobookPercentage(10, 0), 0.0)
        assertEquals(100.0, audiobookPercentage(200, 100), 0.0)
        assertEquals(40.0, pagePercentage(11, 30), 0.0001)
        assertEquals(100.0, pagePercentage(99, 30), 0.0)
        assertEquals(0.0, pagePercentage(0, 0), 0.0)
    }
}
