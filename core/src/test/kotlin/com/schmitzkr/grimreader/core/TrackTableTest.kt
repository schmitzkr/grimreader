package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.model.AudiobookTrack
import com.schmitzkr.grimreader.core.playback.trackIndexAt
import com.schmitzkr.grimreader.core.playback.trackRelativeMs
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTableTest {
    private val tracks = listOf(
        AudiobookTrack(0, "01.mp3", "One", 60_000, 0),
        AudiobookTrack(1, "02.mp3", "Two", 90_000, 60_000),
        AudiobookTrack(2, "03.mp3", "Three", 30_000, 150_000),
    )

    @Test
    fun `an absolute position maps to the track that starts at or before it`() {
        assertEquals(0, trackIndexAt(0, tracks))
        assertEquals(0, trackIndexAt(59_999, tracks))
        assertEquals(1, trackIndexAt(60_000, tracks))
        assertEquals(1, trackIndexAt(149_999, tracks))
        assertEquals(2, trackIndexAt(150_000, tracks))
        // Past the end still lands on the last track; the player clamps the offset.
        assertEquals(2, trackIndexAt(999_999, tracks))
        // Before every start (a negative position) lands on the first.
        assertEquals(0, trackIndexAt(-5, tracks))
    }

    @Test
    fun `index and relative position together round-trip a book-wide seek`() {
        val absolute = 75_000L
        val index = trackIndexAt(absolute, tracks)
        assertEquals(1, index)
        assertEquals(15_000L, trackRelativeMs(absolute, index, tracks, folderBased = true))
    }

    @Test
    fun `a single stream is a one-entry table and an empty table is index zero`() {
        val single = listOf(AudiobookTrack(0, "book.m4b", "Book", 3_600_000, 0))
        assertEquals(0, trackIndexAt(0, single))
        assertEquals(0, trackIndexAt(3_000_000, single))
        assertEquals(0, trackIndexAt(3_000_000, emptyList()))
    }
}
