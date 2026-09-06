package com.schmitzkr.grimreader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.schmitzkr.grimreader.data.Release
import com.schmitzkr.grimreader.data.UpdateState

/** A card at the top of Home when a newer release exists; nothing otherwise. */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: (Release) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        else -> null
    }
    if (release == null && state !is UpdateState.Failed) return
    GrimCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is UpdateState.Failed -> {
                    Text("Update failed", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
                is UpdateState.Downloading -> {
                    Text("Downloading ${state.release.version}…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        drawStopIndicator = {},
                    )
                }
                is UpdateState.Available -> {
                    Text("Version ${release!!.version} is available", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val firstLine = release.notes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    if (!firstLine.isNullOrBlank()) {
                        Text(firstLine.removePrefix("* ").removePrefix("- "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { onInstall(release) }) { Text("Update") }
                        TextButton(onClick = onDismiss) { Text("Later") }
                    }
                }
                else -> {}
            }
        }
    }
}
