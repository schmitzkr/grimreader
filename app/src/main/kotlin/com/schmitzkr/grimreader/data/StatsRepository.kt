package com.schmitzkr.grimreader.data

import com.schmitzkr.grimreader.core.stats.ListeningCompletion
import com.schmitzkr.grimreader.core.stats.ListeningDay
import com.schmitzkr.grimreader.core.stats.ReadingStreak
import com.schmitzkr.grimreader.core.stats.WeekTimelineEntry
import javax.inject.Inject
import javax.inject.Singleton

/** The user-stats endpoints the stats screen reads. */
@Singleton
class StatsRepository @Inject constructor(private val clients: ClientHolder) {
    private val api get() = clients.current().api

    suspend fun weekTimeline(year: Int, week: Int): List<WeekTimelineEntry> = api.weekTimeline(year, week)
    suspend fun readingStreak(): ReadingStreak = api.readingStreak()
    suspend fun listeningCompletion(): ListeningCompletion = api.listeningCompletion()
    suspend fun listeningDays(year: Int, month: Int): List<ListeningDay> = api.listeningDays(year, month)
}
