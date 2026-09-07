package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.stats.ListeningDay
import com.schmitzkr.grimreader.core.stats.StreakDay
import com.schmitzkr.grimreader.core.stats.WeekTimelineEntry
import com.schmitzkr.grimreader.core.stats.lastSevenDays
import com.schmitzkr.grimreader.core.stats.monthMinutes
import com.schmitzkr.grimreader.core.stats.streakColumns
import com.schmitzkr.grimreader.core.stats.timelineWeekOf
import com.schmitzkr.grimreader.core.stats.weekTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    @Test
    fun `timeline week matches the server's Jan-1 anchored week of year`() {
        // 2026 starts on a Thursday, so its first four days already count as week 1;
        // 2027 starts on a Friday, so Jan 1-3 are week 0.
        assertEquals(2026 to 1, timelineWeekOf(LocalDate.of(2026, 1, 2)))
        assertEquals(2026 to 2, timelineWeekOf(LocalDate.of(2026, 1, 5)))
        assertEquals(2026 to 36, timelineWeekOf(LocalDate.of(2026, 9, 6)))
        assertEquals(2027 to 0, timelineWeekOf(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `week totals split audio from reading`() {
        val totals = weekTotals(
            listOf(
                WeekTimelineEntry(1, bookType = "AUDIOBOOK", totalDurationSeconds = 600),
                WeekTimelineEntry(2, bookType = "EPUB", totalDurationSeconds = 300),
                WeekTimelineEntry(3, bookType = "CBX", totalDurationSeconds = 100),
            ),
        )
        assertEquals(600, totals.listeningSeconds)
        assertEquals(400, totals.readingSeconds)
    }

    @Test
    fun `last seven days fills gaps with zero and ends today`() {
        val today = LocalDate.of(2026, 9, 6)
        val days = lastSevenDays(listOf(ListeningDay("2026-09-06", 2, 45), ListeningDay("2026-09-01", 1, 10)), today)
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 8, 31), days.first().first)
        assertEquals(0L, days.first().second)
        assertEquals(10L, days[1].second)
        assertEquals(45L, days.last().second)
    }

    @Test
    fun `month minutes only count this month`() {
        val today = LocalDate.of(2026, 9, 6)
        assertEquals(50L, monthMinutes(listOf(ListeningDay("2026-09-01", 1, 20), ListeningDay("2026-09-03", 1, 30), ListeningDay("2026-08-31", 1, 99)), today))
    }

    @Test
    fun `streak columns start on a Monday and pad the first week`() {
        // 2026-09-02 is a Wednesday.
        val days = (0 until 10).map { StreakDay(LocalDate.of(2026, 9, 2).plusDays(it.toLong()).toString(), it % 2 == 0) }
        val columns = streakColumns(days)
        assertEquals(2, columns.size)
        assertNull(columns[0][0])
        assertNull(columns[0][1])
        assertEquals("2026-09-02", columns[0][2]!!.date)
        assertEquals(7, columns[1].size)
        assertEquals("2026-09-07", columns[1][0]!!.date)
    }
}
