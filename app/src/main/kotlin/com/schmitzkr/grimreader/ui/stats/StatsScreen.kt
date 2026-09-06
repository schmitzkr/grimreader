package com.schmitzkr.grimreader.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.stats.ListeningCompletion
import com.schmitzkr.grimreader.core.stats.ReadingStreak
import com.schmitzkr.grimreader.core.stats.StreakDay
import com.schmitzkr.grimreader.core.stats.WeekTimelineEntry
import com.schmitzkr.grimreader.core.stats.WeekTotals
import com.schmitzkr.grimreader.core.stats.lastSevenDays
import com.schmitzkr.grimreader.core.stats.monthMinutes
import com.schmitzkr.grimreader.core.stats.streakColumns
import com.schmitzkr.grimreader.core.stats.timelineWeekOf
import com.schmitzkr.grimreader.core.stats.weekTotals
import com.schmitzkr.grimreader.data.StatsRepository
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.GrimCard
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.components.SectionLabel
import com.schmitzkr.grimreader.ui.formatShort
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

data class StatsData(
    val week: WeekTotals,
    val weekBooks: List<WeekTimelineEntry>,
    val streak: ReadingStreak,
    val completion: ListeningCompletion,
    val lastSeven: List<Pair<LocalDate, Long>>,
    val monthMinutes: Long,
)

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data object Forbidden : StatsUiState
    data class Error(val message: String) : StatsUiState
    data class Ready(val data: StatsData) : StatsUiState
}

/**
 * Everything at once, in parallel. The server keys its weeks and months
 * to its own clock; the app asks in UTC, which is where its timestamps go.
 */
@HiltViewModel
class StatsViewModel @Inject constructor(private val stats: StatsRepository) : ViewModel() {
    val state = MutableStateFlow<StatsUiState>(StatsUiState.Loading)

    init { load() }

    fun load() {
        viewModelScope.launch {
            state.value = StatsUiState.Loading
            runCatching {
                val today = LocalDate.now(ZoneOffset.UTC)
                val (year, week) = timelineWeekOf(today)
                coroutineScope {
                    val timeline = async { stats.weekTimeline(year, week) }
                    val streak = async { stats.readingStreak() }
                    val completion = async { stats.listeningCompletion() }
                    val thisMonth = async { stats.listeningDays(today.year, today.monthValue) }
                    val previous = today.minusMonths(1)
                    val lastMonth = async {
                        if (today.dayOfMonth <= 7) stats.listeningDays(previous.year, previous.monthValue) else emptyList()
                    }
                    val entries = timeline.await()
                    val days = thisMonth.await() + lastMonth.await()
                    StatsData(
                        week = weekTotals(entries),
                        weekBooks = entries.sortedByDescending { it.totalDurationSeconds },
                        streak = streak.await(),
                        completion = completion.await(),
                        lastSeven = lastSevenDays(days, today),
                        monthMinutes = monthMinutes(days, today),
                    )
                }
            }.onSuccess { state.value = StatsUiState.Ready(it) }
                .onFailure { e ->
                    state.value = if (e is HttpException && e.code() == 403) StatsUiState.Forbidden
                    else StatsUiState.Error(friendlyError(e))
                }
        }
    }
}

@Composable
fun StatsScreen(onBack: () -> Unit, vm: StatsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Your stats", style = MaterialTheme.typography.headlineMedium)
        }
        when (val s = state) {
            StatsUiState.Loading -> LoadingState()
            StatsUiState.Forbidden -> EmptyState(
                "Your account does not have the stats permission. An admin can grant it in Grimmory's user settings.",
                icon = Icons.Outlined.Lock,
            )
            is StatsUiState.Error -> ErrorState(s.message, onRetry = vm::load)
            is StatsUiState.Ready -> StatsBody(s.data)
        }
    }
}

@Composable
private fun StatsBody(d: StatsData) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionLabel("This week", Modifier.padding(0.dp)) }
        item {
            GrimCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp)) {
                    BigNumber("Listening", formatShort(d.week.listeningSeconds * 1000), Modifier.weight(1f))
                    BigNumber("Reading", formatShort(d.week.readingSeconds * 1000), Modifier.weight(1f))
                }
            }
        }

        item { SectionLabel("Streak", Modifier.padding(0.dp)) }
        item {
            GrimCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        BigNumber("Current", days(d.streak.currentStreak), Modifier.weight(1f))
                        BigNumber("Longest", days(d.streak.longestStreak), Modifier.weight(1f))
                        BigNumber("Total", days(d.streak.totalReadingDays), Modifier.weight(1f))
                    }
                    if (d.streak.last52Weeks.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Past year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        YearGrid(d.streak.last52Weeks)
                    }
                }
            }
        }

        item { SectionLabel("Listening", Modifier.padding(0.dp)) }
        item {
            GrimCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Last 7 days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("This month: ${formatShort(d.monthMinutes * 60_000)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    WeekBars(d.lastSeven)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Row {
                        BigNumber("Audiobooks", d.completion.totalAudiobooks.toString(), Modifier.weight(1f))
                        BigNumber("Finished", d.completion.completed.toString(), Modifier.weight(1f))
                        BigNumber("In progress", d.completion.inProgressCount.toString(), Modifier.weight(1f))
                    }
                    d.completion.inProgress.take(5).forEach { entry ->
                        Spacer(Modifier.height(12.dp))
                        Text(entry.title ?: "Untitled", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { ((entry.progressPercent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }

        if (d.weekBooks.isNotEmpty()) {
            item { SectionLabel("This week's books", Modifier.padding(0.dp)) }
            item {
                GrimCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        d.weekBooks.forEachIndexed { i, b ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.bookTitle ?: "Book ${b.bookId}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${if (b.isListening) "Listened" else "Read"} · ${b.totalSessions} session${if (b.totalSessions == 1L) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(formatShort(b.totalDurationSeconds * 1000), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

@Composable
private fun BigNumber(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Seven bars, today on the right, scaled to the busiest day. */
@Composable
private fun WeekBars(days: List<Pair<LocalDate, Long>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / days.size
            val barWidth = slot * 0.55f
            days.forEachIndexed { i, (_, minutes) ->
                val h = (minutes.toFloat() / max) * size.height
                val left = i * slot + (slot - barWidth) / 2
                drawRoundRect(track, Offset(left, 0f), Size(barWidth, size.height), CornerRadius(8f, 8f))
                if (h > 0) drawRoundRect(bar, Offset(left, size.height - h), Size(barWidth, h), CornerRadius(8f, 8f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { (date, minutes) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                    Text(if (minutes > 0) "${minutes}m" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A column per week, Monday at the top, active days in the accent. */
@Composable
private fun YearGrid(days: List<StreakDay>) {
    val columns = streakColumns(days)
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val cell = 10.dp
    val gap = 3.dp
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
        Canvas(Modifier.width((cell + gap) * columns.size).height((cell + gap) * 7)) {
            val c = cell.toPx()
            val g = gap.toPx()
            columns.forEachIndexed { x, col ->
                col.forEachIndexed { y, day ->
                    if (day == null) return@forEachIndexed
                    drawRoundRect(
                        if (day.active) active else idle,
                        Offset(x * (c + g), y * (c + g)),
                        Size(c, c),
                        CornerRadius(3f, 3f),
                    )
                }
            }
        }
    }
}
