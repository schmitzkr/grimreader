package com.schmitzkr.grimreader.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.schmitzkr.grimreader.playback.PlayerController
import com.schmitzkr.grimreader.ui.formatClock
import com.schmitzkr.grimreader.ui.formatShort
import com.schmitzkr.grimreader.ui.formatSpeed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(val player: PlayerController) : ViewModel()

private val speedSteps = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
private val sleepPresets = listOf(15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, vm: PlayerViewModel = hiltViewModel()) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val player = vm.player
    var sheet by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Close") }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .aspectRatio(1f)
                .shadow(24.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            state.artworkUrl?.let {
                AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(28.dp))
        state.currentChapterIndex?.let { i ->
            Text(
                "CHAPTER ${i + 1} · ${state.chapters[i].title}".uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            state.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            state.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        val fraction = dragging ?: state.progress
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekToAbsolute((it * state.durationMs).toLong()) }
                dragging = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock((fraction * state.durationMs).toLong()), style = MaterialTheme.typography.bodySmall)
            Text("-${formatClock(((1 - fraction) * state.durationMs).toLong())}", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = player::previousTrack, enabled = state.trackCount > 1) {
                Icon(Icons.Rounded.SkipPrevious, "Previous track", Modifier.size(28.dp))
            }
            IconButton(onClick = player::rewind) { Icon(Icons.Rounded.Replay30, "Back 30 seconds", Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = player::togglePlayPause,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.playing) "Pause" else "Play",
                    Modifier.size(40.dp),
                )
            }
            IconButton(onClick = player::fastForward) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds", Modifier.size(36.dp)) }
            IconButton(onClick = player::nextTrack, enabled = state.trackCount > 1) {
                Icon(Icons.Rounded.SkipNext, "Next track", Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BottomAction(Icons.Outlined.Speed, formatSpeed(state.speed)) { sheet = "speed" }
            BottomAction(
                Icons.Outlined.Bedtime,
                state.sleepRemainingMs?.let { if (state.sleepAtChapterEnd) "Chapter end" else formatShort(it) } ?: "Sleep",
            ) { sheet = "sleep" }
            BottomAction(Icons.Outlined.FormatListBulleted, "Chapters", enabled = state.chapters.isNotEmpty()) { sheet = "chapters" }
            BottomAction(Icons.Outlined.BookmarkBorder, "Bookmarks") { sheet = "bookmarks" }
        }
    }

    when (sheet) {
        "speed" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Playback speed", style = MaterialTheme.typography.titleMedium)
                Text(formatSpeed(state.speed), style = MaterialTheme.typography.headlineMedium)
                Slider(
                    value = state.speed.coerceIn(0.5f, 3f),
                    onValueChange = { player.setSpeed((it * 20).toInt() / 20f) },
                    valueRange = 0.5f..3f,
                    steps = 24,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                    speedSteps.forEach { preset ->
                        FilterChip(
                            selected = kotlin.math.abs(preset - state.speed) < 0.01f,
                            onClick = { player.setSpeed(preset) },
                            label = { Text(formatSpeed(preset)) },
                        )
                    }
                }
            }
        }
        "sleep" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text("Sleep timer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp, 8.dp))
                ListItem(
                    headlineContent = { Text("End of chapter") },
                    supportingContent = {
                        Text(
                            state.currentChapterIndex?.let { "Stops where \"${state.chapters[it].title}\" ends" }
                                ?: if (state.trackCount > 1) "Stops at the end of this track" else "Stops at the end of the book",
                        )
                    },
                    modifier = Modifier.clickableRow { if (player.startSleepAtChapterEnd()) sheet = null },
                )
                sleepPresets.forEach { minutes ->
                    ListItem(
                        headlineContent = { Text("$minutes minutes") },
                        modifier = Modifier.clickableRow { player.startSleepTimer(minutes * 60_000L); sheet = null },
                    )
                }
                if (state.sleepRemainingMs != null) {
                    TextButton(onClick = { player.cancelSleepTimer(); sheet = null }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("Cancel timer")
                    }
                }
            }
        }
        "bookmarks" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            BookmarksSheetContent(state = state, onSeek = { player.seekToAbsolute(it); sheet = null })
        }
        "chapters" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            val current = state.currentChapterIndex
            LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(state.chapters) { index, chapter ->
                    ListItem(
                        headlineContent = {
                            Text(
                                chapter.title,
                                fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        trailingContent = { Text(formatShort(chapter.durationMs)) },
                        modifier = Modifier.clickableRow { player.seekToChapter(chapter); sheet = null },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        )
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
