package com.schmitzkr.grimreader.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.data.DownloadState
import com.schmitzkr.grimreader.data.DownloadStatus
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.components.GrimCard
import com.schmitzkr.grimreader.ui.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(val downloads: DownloadManager) : ViewModel()

@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val state by vm.downloads.state.collectAsStateWithLifecycle()
    val entries = state.values.sortedWith(compareBy({ !it.isActive }, { it.title.lowercase() }))
    val total = entries.filter { it.isDone }.sumOf { it.bytes }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (entries.isEmpty()) "Nothing on this device" else "${entries.count { it.isDone }} on this device · ${formatBytes(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entries.isEmpty()) {
            EmptyState("Download a book from its page to listen or read without a connection.", icon = Icons.Outlined.CloudDownload)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
            ) {
                items(entries, key = { it.bookId }) { entry ->
                    DownloadRow(
                        entry,
                        onOpen = { onOpenBook(entry.bookId) },
                        onCancel = { vm.downloads.cancel(entry.bookId) },
                        onRemove = { vm.downloads.remove(entry.bookId) },
                        onRetry = { vm.downloads.remove(entry.bookId); vm.downloads.download(entry.bookId) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(entry: DownloadState, onOpen: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit) {
    GrimCard(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when (entry.status) {
                            DownloadStatus.QUEUED -> "Waiting…"
                            DownloadStatus.DOWNLOADING -> "Downloading ${(entry.fraction * 100).toInt()}%"
                            DownloadStatus.DONE -> listOf(entry.author, formatBytes(entry.bytes)).filter { it.isNotBlank() }.joinToString(" · ")
                            DownloadStatus.FAILED -> entry.error ?: "Failed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (entry.status) {
                    DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel") }
                    DownloadStatus.DONE -> IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove download") }
                    DownloadStatus.FAILED -> {
                        IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry") }
                        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove") }
                    }
                }
            }
            if (entry.status == DownloadStatus.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { entry.fraction },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).height(4.dp).clip(MaterialTheme.shapes.extraSmall),
                    drawStopIndicator = {},
                )
            }
        }
    }
}
