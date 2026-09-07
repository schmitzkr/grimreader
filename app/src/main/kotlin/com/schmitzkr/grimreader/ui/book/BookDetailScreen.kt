package com.schmitzkr.grimreader.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.PageFormat
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.data.DownloadStatus
import com.schmitzkr.grimreader.ui.formatBytes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import com.schmitzkr.grimreader.playback.PlayerController
import com.schmitzkr.grimreader.ui.components.BookCover
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.components.SectionLabel
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BookUiState {
    data object Loading : BookUiState
    data class Error(val message: String) : BookUiState
    data class Ready(val book: Book) : BookUiState
}

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val books: BooksRepository,
    val player: PlayerController,
    val downloads: DownloadManager,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val bookId: Long = savedState.get<Long>("id") ?: -1L
    val state = MutableStateFlow<BookUiState>(BookUiState.Loading)

    init {
        load()
        viewModelScope.launch { books.progressChanged.collect { if (it == bookId) load(quiet = true) } }
    }

    fun coverUrl(book: Book) = books.coverUrl(book)
    fun fallbackCoverUrl(book: Book) = if (book.isAudiobook) books.fallbackCoverUrl(book) else null

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) state.value = BookUiState.Loading
            runCatching { books.book(bookId) }
                // Offline, a downloaded book still opens from its record.
                .recoverCatching { e -> downloads.record(bookId)?.book ?: throw e }
                .onSuccess { state.value = BookUiState.Ready(it) }
                .onFailure { if (!quiet) state.value = BookUiState.Error(friendlyError(it)) }
        }
    }

    fun toggleFinished(book: Book) {
        viewModelScope.launch {
            runCatching { books.updateReadStatus(book.id, if (book.isFinished) "UNREAD" else "READ") }
        }
    }

    fun play(bookId: Long) = player.play(bookId)
}

@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenReader: (PageFormat) -> Unit,
    onOpenEpub: () -> Unit,
    vm: BookDetailViewModel = hiltViewModel(key = "book-$bookId"),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val downloadStates by vm.downloads.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        }
        when (val s = state) {
            BookUiState.Loading -> LoadingState()
            is BookUiState.Error -> ErrorState(s.message, onRetry = { vm.load() })
            is BookUiState.Ready -> {
                val book = s.book
                val isCurrent = playback.bookId == book.id
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, bottom = 160.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BookCover(
                        book, vm.coverUrl(book),
                        modifier = Modifier.width(if (book.isAudiobook) 220.dp else 180.dp),
                        cornerRadius = 16,
                        showProgress = false,
                        fallbackUrl = vm.fallbackCoverUrl(book),
                        downloaded = downloadStates[book.id]?.isDone == true,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(book.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    if (book.authors.isNotEmpty()) {
                        Text(
                            book.authors.joinToString(", "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    book.narrator?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            "Read by $it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    book.seriesName?.let { series ->
                        Text(
                            buildString {
                                append(series)
                                book.seriesNumber?.let { n ->
                                    append(" #")
                                    append(if (n % 1.0 == 0.0) n.toInt().toString() else n.toString())
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    val progress = if (isCurrent && playback.durationMs > 0) playback.progress.toDouble()
                    else book.normalizedReadProgress
                    if (book.isFinished) {
                        Text("Finished", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    } else if (progress != null && progress > 0) {
                        LinearProgressIndicator(
                            progress = { progress.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.extraSmall),
                            drawStopIndicator = {},
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${(progress * 100).toInt()}%${if (isCurrent && playback.playing) " · Now playing" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    if (book.isAudiobook) {
                        Button(
                            onClick = {
                                if (isCurrent) onOpenPlayer() else { vm.play(book.id); onOpenPlayer() }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isCurrent) "Now playing" else if ((progress ?: 0.0) > 0) "Continue" else "Listen")
                        }
                        Spacer(Modifier.height(10.dp))
                        val dl = downloadStates[book.id]
                        when {
                            dl == null || dl.status == DownloadStatus.FAILED -> OutlinedButton(
                                onClick = { if (dl != null) vm.downloads.remove(book.id); vm.downloads.download(book.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Download, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (dl == null) "Download for offline" else "Download failed · Retry")
                            }
                            dl.isActive -> OutlinedButton(onClick = { vm.downloads.cancel(book.id) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Close, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (dl.status == DownloadStatus.QUEUED) "Waiting… · Cancel" else "Downloading ${(dl.fraction * 100).toInt()}% · Cancel")
                            }
                            else -> OutlinedButton(onClick = { vm.downloads.remove(book.id) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.DownloadDone, null)
                                Spacer(Modifier.width(8.dp))
                                Text("On this device · ${formatBytes(dl.bytes)} · Remove")
                            }
                        }
                    } else {
                        val pageFormat = PageFormat.entries.firstOrNull { book.primaryFileType == it.bookType || book.fileIdFor(it) != null }
                        val hasEpub = book.primaryFileType == "EPUB" || book.files.any { it.bookType == "EPUB" }
                        if (hasEpub) {
                            Button(onClick = onOpenEpub, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.MenuBook, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if ((progress ?: 0.0) > 0) "Continue reading" else "Read")
                            }
                        } else if (pageFormat != null) {
                            Button(onClick = { onOpenReader(pageFormat) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.MenuBook, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if ((progress ?: 0.0) > 0) "Continue reading" else "Read")
                            }
                        } else {
                            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("No reader for ${book.primaryFileType ?: "this format"} yet")
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { vm.toggleFinished(book) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (book.isFinished) "Mark unread" else "Mark finished")
                    }
                    book.description?.takeIf { it.isNotBlank() }?.let { description ->
                        Spacer(Modifier.height(8.dp))
                        SectionLabel("About", Modifier.fillMaxWidth().padding(0.dp))
                        Text(
                            plainText(description),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val facts = listOfNotNull(
                        book.pageCount?.let { "$it pages" },
                        book.publishedDate?.take(4),
                        book.publisher?.takeIf { it.isNotBlank() },
                        book.language?.takeIf { it.isNotBlank() }?.uppercase(),
                        book.libraryName?.takeIf { it.isNotBlank() },
                    )
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            facts.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (book.categories.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                book.categories.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Descriptions arrive as HTML from most metadata sources. */
private fun plainText(html: String): String =
    android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
