package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.dashboard.ScrollerKind
import com.schmitzkr.grimreader.core.dashboard.discoverPick
import com.schmitzkr.grimreader.core.dashboard.naturalCompare
import com.schmitzkr.grimreader.core.dashboard.normalizeDashboard
import com.schmitzkr.grimreader.core.dashboard.scrollerTitle
import com.schmitzkr.grimreader.core.dashboard.sortBooks
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.DashboardConfig
import com.schmitzkr.grimreader.core.model.DashboardScroller
import com.schmitzkr.grimreader.core.update.isNewerVersion
import com.schmitzkr.grimreader.core.update.parseVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DashboardTest {
    @Test
    fun `defaults apply when nothing is saved and rows follow the user's order`() {
        assertEquals(
            listOf(ScrollerKind.LAST_LISTENED, ScrollerKind.LAST_READ, ScrollerKind.LATEST_ADDED, ScrollerKind.RANDOM),
            normalizeDashboard(null).map { ScrollerKind.fromWire(it.type) },
        )
        val custom = DashboardConfig(
            scrollers = listOf(
                DashboardScroller(type = "random", order = 3),
                DashboardScroller(type = "magicShelf", order = 1),              // no shelf id: dropped
                DashboardScroller(type = "lastRead", order = 2, enabled = false),
                DashboardScroller(type = "unknownKind", order = 0),
                DashboardScroller(type = "magicShelf", order = 1, magicShelfId = 7, title = "Sci-fi"),
            ),
        )
        val rows = normalizeDashboard(custom)
        assertEquals(listOf("magicShelf", "random"), rows.map { it.type })
        assertEquals("Sci-fi", scrollerTitle(rows[0]))
        assertEquals("Discover Something New", scrollerTitle(rows[1]))
        assertEquals("Continue Listening", scrollerTitle(DashboardScroller(type = "lastListened", title = "dashboard.scroller.continueListening")))
    }

    @Test
    fun `discover leaves out anything started or dismissed`() {
        val books = listOf(
            Book(1, "new"),
            Book(2, "reading", readStatus = "READING"),
            Book(3, "unread", readStatus = "UNREAD"),
            Book(4, "abandoned", readStatus = "ABANDONED"),
        )
        val pick = discoverPick(books, max = 10, random = Random(1))
        assertEquals(setOf(1L, 3L), pick.map { it.id }.toSet())
        assertEquals(1, discoverPick(books, max = 1, random = Random(1)).size)
    }

    @Test
    fun `magic shelf sorting is natural and puts missing keys last`() {
        val books = listOf(Book(1, "Book 10"), Book(2, "Book 2"), Book(3, "Album"))
        assertEquals(listOf("Album", "Book 2", "Book 10"), sortBooks(books, "title", "asc").map { it.title })
        assertEquals(listOf("Book 10", "Book 2", "Album"), sortBooks(books, "title", "desc").map { it.title })
        val bySeries = listOf(Book(1, "a", seriesNumber = 2.0), Book(2, "b"), Book(3, "c", seriesNumber = 1.0))
        assertEquals(listOf(3L, 1L, 2L), sortBooks(bySeries, "seriesNumber", "asc").map { it.id })
        assertEquals(books, sortBooks(books, null, "asc"))
        assertEquals(books, sortBooks(books, "narrator", "asc"))
        assertTrue(naturalCompare("chapter 9", "Chapter 10") < 0)
    }

    @Test
    fun `versions parse and compare numerically`() {
        assertEquals(listOf(0, 13, 0), parseVersion("v0.13.0"))
        assertEquals(listOf(0, 13, 1), parseVersion("0.13.1-debug"))
        assertNull(parseVersion("0.0.x"))
        assertTrue(isNewerVersion(listOf(0, 13, 1), listOf(0, 13, 0)))
        assertTrue(isNewerVersion(listOf(1, 0), listOf(0, 99, 99)))
        assertFalse(isNewerVersion(listOf(0, 13), listOf(0, 13, 0)))
        assertFalse(isNewerVersion(listOf(0, 12, 9), listOf(0, 13, 0)))
    }
}
