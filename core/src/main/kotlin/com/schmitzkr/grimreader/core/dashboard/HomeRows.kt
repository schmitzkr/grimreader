package com.schmitzkr.grimreader.core.dashboard

import com.schmitzkr.grimreader.core.model.Book
import java.time.Instant

/*
 * Two rows the server's dashboard layout has no kind for, built from the
 * filtered list endpoint like the Continue rows. The server records no
 * finish time, so the last read time of a finished book stands in for it.
 */

/** Finished books, newest finish first, cut to [max]. */
fun recentlyFinished(books: Iterable<Book>, max: Int): List<Book> =
    books.filter { it.isFinished }
        .sortedByDescending { it.lastReadInstant ?: Instant.EPOCH }
        .take(max)

/** The series worth an "up next", newest finish first, one entry per series, cut to [max]. */
fun seriesToContinue(finished: Iterable<Book>, max: Int): List<String> =
    recentlyFinished(finished, Int.MAX_VALUE)
        .mapNotNull { it.seriesName?.takeIf { n -> n.isNotBlank() } }
        .distinct()
        .take(max)

/**
 * The next unfinished number after the highest finished one in a series,
 * or null when the series is finished, has nothing numbered, or nothing
 * finished yet.
 */
fun nextInSeries(seriesBooks: Iterable<Book>): Book? {
    val numbered = seriesBooks.filter { it.seriesNumber != null }
    val highestFinished = numbered.filter { it.isFinished }.maxOfOrNull { it.seriesNumber!! } ?: return null
    return numbered
        .filter { !it.isFinished && it.seriesNumber!! > highestFinished }
        .minByOrNull { it.seriesNumber!! }
}
