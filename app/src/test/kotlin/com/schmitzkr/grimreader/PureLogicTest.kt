package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.core.model.CountedOption
import com.schmitzkr.grimreader.playback.PlayerController
import com.schmitzkr.grimreader.playback.autoRewindFor
import com.schmitzkr.grimreader.playback.bookIdOf
import com.schmitzkr.grimreader.playback.bookMediaId
import com.schmitzkr.grimreader.playback.libraryIdOf
import com.schmitzkr.grimreader.ui.formatClock
import com.schmitzkr.grimreader.ui.formatShort
import com.schmitzkr.grimreader.ui.formatSpeed
import com.schmitzkr.grimreader.ui.library.StatusFilter
import com.schmitzkr.grimreader.ui.library.statusCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class PureLogicTest {
    @Test
    fun `auto-rewind scales with how long the pause lasted`() {
        assertEquals(Duration.ZERO, autoRewindFor(Duration.ofSeconds(9)))
        assertEquals(Duration.ofSeconds(5), autoRewindFor(Duration.ofSeconds(10)))
        assertEquals(Duration.ofSeconds(15), autoRewindFor(Duration.ofMinutes(1)))
        assertEquals(Duration.ofSeconds(30), autoRewindFor(Duration.ofHours(8)))
    }

    @Test
    fun `sleep volume is full until the fade then eases to a whisper`() {
        assertEquals(1f, PlayerController.sleepVolumeFor(60_000), 0.0001f)
        assertEquals(1f, PlayerController.sleepVolumeFor(5_000), 0.0001f)
        assertEquals(0.82f, PlayerController.sleepVolumeFor(4_000), 0.001f)
        assertEquals(0.1f, PlayerController.sleepVolumeFor(0), 0.0001f)
    }

    @Test
    fun `media ids round-trip`() {
        assertEquals(42L, bookIdOf(bookMediaId(42)))
        assertEquals(42L, bookIdOf("book:42:track:3"))
        assertEquals(7L, libraryIdOf("library:7"))
        assertNull(bookIdOf("library:7"))
        assertNull(bookIdOf(null))
    }

    @Test
    fun `unread counts include books the status facet never saw`() {
        val counts = statusCounts(
            fileTypes = listOf(CountedOption("AUDIOBOOK", 10), CountedOption("EPUB", 10)),
            readStatuses = listOf(
                CountedOption("READING", 3),
                CountedOption("READ", 2),
                CountedOption("UNREAD", 1),
                CountedOption("PAUSED", 1),
            ),
        )
        assertEquals(20, counts[StatusFilter.ALL])
        assertEquals(3, counts[StatusFilter.IN_PROGRESS])
        assertEquals(2, counts[StatusFilter.FINISHED])
        assertEquals(14, counts[StatusFilter.UNREAD])
        assertFalse(counts.values.any { it < 0 })
    }

    @Test
    fun `durations and speeds format the way the player shows them`() {
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("2:03", formatClock(123_000))
        assertEquals("1h 2m", formatShort(3_720_000))
        assertEquals("2m", formatShort(120_000))
        assertEquals("1.5×", formatSpeed(1.5f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("1.0×", formatSpeed(1f))
    }
}
