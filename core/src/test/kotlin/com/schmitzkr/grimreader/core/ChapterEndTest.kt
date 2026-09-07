package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.model.AudiobookChapter
import com.schmitzkr.grimreader.core.model.AudiobookTrack
import com.schmitzkr.grimreader.core.playback.chapterEndMs
import com.schmitzkr.grimreader.core.playback.wallClockUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterEndTest {
    private val chapters = listOf(
        AudiobookChapter(0, "One", 0),
        AudiobookChapter(1, "Two", 60_000),
        AudiobookChapter(2, "Three", 120_000),
    )
    private val tracks = listOf(
        AudiobookTrack(0, "a.mp3", "A", 50_000, 0),
        AudiobookTrack(1, "b.mp3", "B", 50_000, 50_000),
    )

    @Test
    fun `chapters end at the next chapter's start, the last at the book's end`() {
        assertEquals(60_000L, chapterEndMs(10_000, chapters, tracks, 180_000))
        assertEquals(120_000L, chapterEndMs(60_000, chapters, tracks, 180_000))
        assertEquals(180_000L, chapterEndMs(150_000, chapters, tracks, 180_000))
    }

    @Test
    fun `without chapters a folder-based book ends at the current track`() {
        assertEquals(50_000L, chapterEndMs(10_000, emptyList(), tracks, 100_000))
        assertEquals(100_000L, chapterEndMs(70_000, emptyList(), tracks, 100_000))
    }

    @Test
    fun `a single stream without chapters ends at the book's end, and no length means no end`() {
        assertEquals(100_000L, chapterEndMs(10_000, emptyList(), emptyList(), 100_000))
        assertNull(chapterEndMs(10_000, chapters, tracks, 0))
    }

    @Test
    fun `the countdown runs in content time at the playback speed`() {
        assertEquals(40_000L, wallClockUntil(20_000, 80_000, 1.5f))
        assertEquals(60_000L, wallClockUntil(20_000, 80_000, 0f))
        assertEquals(0L, wallClockUntil(90_000, 80_000, 1f))
    }
}
