package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.sessions.ActiveSession
import com.schmitzkr.grimreader.core.sessions.finish
import com.schmitzkr.grimreader.core.sessions.formatSessionDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SessionsTest {
    private val start = Instant.parse("2026-09-06T20:00:00Z")
    private fun active(progress: Double = 10.0) =
        ActiveSession(bookId = 7, bookType = "AUDIOBOOK", startedAt = start, startElapsedMs = 1_000, startProgress = progress, startLocation = "00:10:00")

    @Test
    fun `a session under thirty seconds is dropped`() {
        assertNull(active().finish(start.plusSeconds(29), endElapsedMs = 30_000, endProgress = 10.5, endLocation = null))
    }

    @Test
    fun `duration comes from the monotonic clock, not the wall clock`() {
        // Wall clock jumped back an hour mid-session; the monotonic clock says 5 minutes.
        val s = active().finish(start.minusSeconds(3600), endElapsedMs = 301_000, endProgress = 12.0, endLocation = "00:15:00")!!
        assertEquals(300, s.durationSeconds)
        assertEquals("5m 0s", s.durationFormatted)
        assertEquals(2.0, s.progressDelta, 0.0001)
        assertEquals("2026-09-06T20:00:00Z", s.startTime)
    }

    @Test
    fun `progress is clamped and the delta rounded to two places`() {
        val s = active(progress = -1.0).finish(start.plusSeconds(60), endElapsedMs = 61_000, endProgress = 100.4, endLocation = null)!!
        assertEquals(0.0, s.startProgress, 0.0)
        assertEquals(100.0, s.endProgress, 0.0)
        assertEquals(100.0, s.progressDelta, 0.0)
    }

    @Test
    fun `durations format like the web`() {
        assertEquals("45s", formatSessionDuration(45))
        assertEquals("12m 30s", formatSessionDuration(750))
        assertEquals("1h 5m", formatSessionDuration(3900))
    }
}
