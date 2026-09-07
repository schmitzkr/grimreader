package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.dashboard.nextInSeries
import com.schmitzkr.grimreader.core.dashboard.recentlyFinished
import com.schmitzkr.grimreader.core.dashboard.seriesToContinue
import com.schmitzkr.grimreader.core.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeRowsTest {
    private fun book(id: Long, series: String? = null, number: Double? = null, status: String? = null, lastRead: String? = null) =
        Book(id = id, title = "Book $id", seriesName = series, seriesNumber = number, readStatus = status, lastReadTime = lastRead)

    @Test
    fun `recently finished orders by last read time and drops the unfinished`() {
        val books = listOf(
            book(1, status = "READ", lastRead = "2026-09-01T10:00:00Z"),
            book(2, status = "READING", lastRead = "2026-09-05T10:00:00Z"),
            book(3, status = "READ", lastRead = "2026-09-03T10:00:00Z"),
            book(4, status = "READ"),
        )
        assertEquals(listOf(3L, 1L, 4L), recentlyFinished(books, 10).map { it.id })
        assertEquals(listOf(3L), recentlyFinished(books, 1).map { it.id })
    }

    @Test
    fun `series to continue come newest finish first, once each`() {
        val books = listOf(
            book(1, "Alpha", 1.0, "READ", "2026-09-01T10:00:00Z"),
            book(2, "Beta", 1.0, "READ", "2026-09-04T10:00:00Z"),
            book(3, "Alpha", 2.0, "READ", "2026-09-06T10:00:00Z"),
            book(4, null, 1.0, "READ", "2026-09-07T10:00:00Z"),
        )
        assertEquals(listOf("Alpha", "Beta"), seriesToContinue(books, 5))
    }

    @Test
    fun `next in series is the smallest unfinished number above the highest finished one`() {
        val series = listOf(
            book(1, "Alpha", 1.0, "READ"),
            book(2, "Alpha", 2.0, "READ"),
            book(3, "Alpha", 3.0, "UNREAD"),
            book(4, "Alpha", 2.5, "UNREAD"),
            book(5, "Alpha", null, "UNREAD"),
        )
        assertEquals(4L, nextInSeries(series)!!.id)
    }

    @Test
    fun `a finished or unstarted series has no next`() {
        assertNull(nextInSeries(listOf(book(1, "Alpha", 1.0, "READ"), book(2, "Alpha", 2.0, "READ"))))
        assertNull(nextInSeries(listOf(book(1, "Alpha", 1.0, "UNREAD"), book(2, "Alpha", 2.0, "UNREAD"))))
        assertNull(nextInSeries(emptyList()))
    }
}
