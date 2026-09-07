package com.schmitzkr.grimreader.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.schmitzkr.grimreader.playback.PlaybackState
import com.schmitzkr.grimreader.ui.formatShort

/**
 * The floating "now playing" pill: a stadium like the navigation bar, a
 * round cover ringed by the book's progress, two lines, a filled accent
 * play button and forward-30. Sits above the floating nav bar rather than
 * flush with the screen edge.
 */
@Composable
fun MiniPlayer(
    state: PlaybackState,
    onTap: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onTap,
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The cover, with the book's progress as a thin ring around it.
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.5.dp,
                    trackColor = scheme.outlineVariant,
                    gapSize = 0.dp,
                )
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest),
                ) {
                    state.artworkUrl?.let {
                        AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val remaining = (state.durationMs - state.positionMs).coerceAtLeast(0)
                Text(
                    state.sleepRemainingMs?.let { if (state.sleepAtChapterEnd) "Sleep at chapter end" else "Sleep in ${formatShort(it)}" }
                        ?: if (state.durationMs > 0) "${formatShort(remaining)} left" else state.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onForward) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds", tint = scheme.onSurfaceVariant) }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = scheme.onPrimary)
                else Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                )
            }
        }
    }
}
