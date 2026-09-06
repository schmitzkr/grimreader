package com.schmitzkr.grimreader.core.stats

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.WeekFields

// ── Server shapes ────────────────────────────────────────────────────────

@Serializable
data class ReadingStreak(
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalReadingDays: Int = 0,
    val last52Weeks: List<StreakDay> = emptyList(),
)

@Serializable
data class StreakDay(
    /** `yyyy-MM-dd`. */
    val date: String,
    val active: Boolean = false,
)

/** One book's sessions in a week, from `user-stats/reading/timeline`. */
@Serializable
data class WeekTimelineEntry(
    val bookId: Long,
    val bookTitle: String? = null,
    val bookType: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val totalSessions: Long = 0,
    val totalDurationSeconds: Long = 0,
) {
    val isListening: Boolean get() = bookType == "AUDIOBOOK"
}

@Serializable
data class ListeningCompletion(
    val totalAudiobooks: Int = 0,
    val completed: Int = 0,
    val inProgressCount: Int = 0,
    val inProgress: List<AudiobookCompletionEntry> = emptyList(),
)

@Serializable
data class AudiobookCompletionEntry(
    val bookId: Long,
    val title: String? = null,
    val progressPercent: Double? = null,
    val totalDurationSeconds: Long? = null,
    val listenedDurationSeconds: Long? = null,
)

/** One day with listening, from `user-stats/listening/heatmap/monthly`. */
@Serializable
data class ListeningDay(
    val date: String,
    val sessions: Long = 0,
    val durationMinutes: Long = 0,
)

// ── Maths the screen needs ───────────────────────────────────────────────

/**
 * The `year` and `week` the timeline endpoint wants for the week holding
 * [date]. The server builds the week from `Jan 1 with weekOfYear = week`,
 * so this is the ISO week-of-year (0 for the first days of a year whose
 * first week is short), not the week-based year's number.
 */
fun timelineWeekOf(date: LocalDate): Pair<Int, Int> =
    date.year to date.get(WeekFields.ISO.weekOfYear())

data class WeekTotals(val listeningSeconds: Long, val readingSeconds: Long)

fun weekTotals(entries: List<WeekTimelineEntry>): WeekTotals = WeekTotals(
    listeningSeconds = entries.filter { it.isListening }.sumOf { it.totalDurationSeconds },
    readingSeconds = entries.filterNot { it.isListening }.sumOf { it.totalDurationSeconds },
)

/** The seven days ending [today], each with its listening minutes (0 when none). */
fun lastSevenDays(days: List<ListeningDay>, today: LocalDate): List<Pair<LocalDate, Long>> {
    val byDate = days.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.durationMinutes } }
    return (6 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        d to (byDate[d.toString()] ?: 0L)
    }
}

/** Listening minutes in [today]'s month. */
fun monthMinutes(days: List<ListeningDay>, today: LocalDate): Long {
    val prefix = "%04d-%02d-".format(today.year, today.monthValue)
    return days.filter { it.date.startsWith(prefix) }.sumOf { it.durationMinutes }
}

/**
 * The past-year grid as columns of seven, Monday first, oldest column on
 * the left. Days before the first Monday are padded with null so every
 * column lines up.
 */
fun streakColumns(days: List<StreakDay>): List<List<StreakDay?>> {
    if (days.isEmpty()) return emptyList()
    val sorted = days.sortedBy { it.date }
    val firstDow = LocalDate.parse(sorted.first().date).dayOfWeek.value // Monday = 1
    val padded: List<StreakDay?> = List(firstDow - 1) { null } + sorted
    return padded.chunked(7).map { col -> if (col.size < 7) col + List(7 - col.size) { null } else col }
}
