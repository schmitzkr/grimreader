package com.schmitzkr.grimreader.core.dashboard

import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.DashboardConfig
import com.schmitzkr.grimreader.core.model.DashboardScroller
import kotlin.random.Random

/*
 * The Home tab mirrors the web dashboard's saved layout: the user's
 * `dashboardConfig.scrollers`, each a row of one kind, in the user's order.
 * A user who never saved a layout gets the web's defaults.
 */

enum class ScrollerKind(val wire: String, val defaultTitle: String) {
    LAST_READ("lastRead", "Continue Reading"),
    LAST_LISTENED("lastListened", "Continue Listening"),
    LATEST_ADDED("latestAdded", "Recently Added"),
    RANDOM("random", "Discover Something New"),
    MAGIC_SHELF("magicShelf", "Magic Shelf");

    /** A row of things in progress vanishes when there is nothing in progress. */
    val hidesWhenEmpty: Boolean get() = this == LAST_READ || this == LAST_LISTENED

    companion object {
        fun fromWire(value: String): ScrollerKind? = entries.firstOrNull { it.wire == value }
    }
}

val DashboardScroller.kind: ScrollerKind? get() = ScrollerKind.fromWire(type)

const val DASHBOARD_MAX_ITEMS = 20

val dashboardDefaultScrollers: List<DashboardScroller> = listOf(
    DashboardScroller(id = "1", type = "lastListened", title = "dashboard.scroller.continueListening", order = 1, maxItems = DASHBOARD_MAX_ITEMS),
    DashboardScroller(id = "2", type = "lastRead", title = "dashboard.scroller.continueReading", order = 2, maxItems = DASHBOARD_MAX_ITEMS),
    DashboardScroller(id = "3", type = "latestAdded", title = "dashboard.scroller.recentlyAdded", order = 3, maxItems = DASHBOARD_MAX_ITEMS),
    DashboardScroller(id = "4", type = "random", title = "dashboard.scroller.discoverNew", order = 4, maxItems = DASHBOARD_MAX_ITEMS),
)

/** Enabled rows of a known kind, in the user's order; defaults when nothing is saved. */
fun normalizeDashboard(config: DashboardConfig?): List<DashboardScroller> {
    val source = config?.scrollers?.takeIf { it.isNotEmpty() } ?: dashboardDefaultScrollers
    return source
        .filter { it.enabled }
        .filter { it.kind != null }
        .filter { it.kind != ScrollerKind.MAGIC_SHELF || it.magicShelfId != null }
        .sortedBy { it.order }
}

private val titleKeys = mapOf(
    "dashboard.scroller.continueListening" to "Continue Listening",
    "dashboard.scroller.continueReading" to "Continue Reading",
    "dashboard.scroller.recentlyAdded" to "Recently Added",
    "dashboard.scroller.discoverNew" to "Discover Something New",
    "dashboard.scroller.magicShelf" to "Magic Shelf",
)

/** The web stores i18n keys as titles for its defaults; anything else is the user's own text. */
fun scrollerTitle(scroller: DashboardScroller): String {
    val raw = scroller.title?.trim().orEmpty()
    if (raw.isEmpty()) return scroller.kind?.defaultTitle ?: "Magic Shelf"
    return titleKeys[raw] ?: raw
}

private val discoverExcluded = setOf("READ", "PARTIALLY_READ", "READING", "PAUSED", "WONT_READ", "ABANDONED")

/** Random books the user has not started, shuffled, cut to [max]. */
fun discoverPick(books: Iterable<Book>, max: Int, random: Random = Random.Default): List<Book> =
    books.filter { it.readStatus !in discoverExcluded }.shuffled(random).take(max)

/** Applies a magic shelf's `sortField`/`sortDirection` client-side, with natural string order. */
fun sortBooks(books: List<Book>, field: String?, direction: String?): List<Book> {
    if (field.isNullOrEmpty()) return books
    val keys = books.associate { it.id to sortKey(it, field) }
    if (keys.values.all { it == null }) return books
    val sign = if (direction == "desc") -1 else 1
    return books.sortedWith { a, b -> sign * compareKeys(keys[a.id], keys[b.id]) }
}

private fun sortKey(book: Book, field: String): Comparable<*>? = when (field) {
    "title" -> book.title
    "addedOn" -> book.addedInstant
    "author" -> book.authors.firstOrNull()
    "seriesName" -> book.seriesName
    "seriesNumber" -> book.seriesNumber
    "lastReadTime" -> book.lastReadInstant
    "readStatus" -> book.readStatus
    "readingProgress" -> book.readProgress
    "bookType" -> book.primaryFileType
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun compareKeys(a: Comparable<*>?, b: Comparable<*>?): Int {
    if (a == null && b == null) return 0
    if (a == null) return 1
    if (b == null) return -1
    if (a is String && b is String) return naturalCompare(a, b)
    return (a as Comparable<Any>).compareTo(b)
}

private val chunk = Regex("\\d+|\\D+")

/** "Book 2" before "Book 10", case-insensitive. */
fun naturalCompare(a: String, b: String): Int {
    val ac = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val bc = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ac.size, bc.size)) {
        val x = ac[i]
        val y = bc[i]
        val xn = x.toIntOrNull()
        val yn = y.toIntOrNull()
        val c = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
        if (c != 0) return c
    }
    return ac.size.compareTo(bc.size)
}
