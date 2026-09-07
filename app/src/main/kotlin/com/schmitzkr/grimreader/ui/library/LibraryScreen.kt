package com.schmitzkr.grimreader.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.CountedOption
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.data.Settings
import androidx.compose.material.icons.rounded.DownloadDone
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.schmitzkr.grimreader.ui.components.BookGrid
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.FilterPill
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibrarySort(val label: String, val sort: String, val dir: String) {
    DATE_ADDED_NEWEST("Date added (newest)", "addedon", "desc"),
    DATE_ADDED_OLDEST("Date added (oldest)", "addedon", "asc"),
    TITLE_AZ("Title (A–Z)", "title", "asc"),
    TITLE_ZA("Title (Z–A)", "title", "desc"),
    SERIES("Series", "series", "asc"),
}

enum class TypeFilter(val label: String, val fileTypes: List<String>?) {
    ALL("All", null),
    AUDIOBOOKS("Audiobooks", listOf("AUDIOBOOK")),
    EBOOKS("Ebooks", listOf("EPUB", "FB2", "MOBI", "AZW3")),
    COMICS("Comics", listOf("CBX")),
    PDFS("PDFs", listOf("PDF")),
}

/** `UNSET` is the server's name for "no progress row at all", which a reader means by unread. */
enum class StatusFilter(val label: String, val statuses: List<String>?) {
    ALL("All", null),
    IN_PROGRESS("In progress", listOf("READING", "RE_READING", "PARTIALLY_READ")),
    UNREAD("Unread", listOf("UNREAD", "UNSET")),
    FINISHED("Finished", listOf("READ")),
}

/**
 * Counts for the status pills. The server's facet only counts books with a
 * progress row, so Unread is the explicit UNREAD entries plus every book
 * the facet never saw.
 */
fun statusCounts(fileTypes: List<CountedOption>, readStatuses: List<CountedOption>): Map<StatusFilter, Int> {
    val total = fileTypes.sumOf { it.count }
    val counted = readStatuses.sumOf { it.count }
    fun of(names: List<String>) = readStatuses.filter { it.name in names }.sumOf { it.count }
    val untracked = (total - counted).coerceIn(0, total)
    return mapOf(
        StatusFilter.ALL to total,
        StatusFilter.IN_PROGRESS to of(StatusFilter.IN_PROGRESS.statuses!!),
        StatusFilter.UNREAD to of(listOf("UNREAD")) + untracked,
        StatusFilter.FINISHED to of(StatusFilter.FINISHED.statuses!!),
    )
}

data class LibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val books: List<Book> = emptyList(),
    val sort: LibrarySort = LibrarySort.DATE_ADDED_NEWEST,
    val type: TypeFilter = TypeFilter.ALL,
    val status: StatusFilter = StatusFilter.ALL,
    val statusCounts: Map<StatusFilter, Int> = emptyMap(),
    val typeCounts: Map<TypeFilter, Int> = emptyMap(),
    /** Client-side: only books on this device. */
    val downloadedOnly: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val books: BooksRepository,
    private val settings: Settings,
    downloads: DownloadManager,
) : ViewModel() {
    val downloadedIds = downloads.state
        .map { m -> m.filterValues { it.isDone }.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), downloads.downloadedIds())

    fun setDownloadedOnly(on: Boolean) = state.update { it.copy(downloadedOnly = on) }

    private var libraryId: Long = -1L
    val state = MutableStateFlow(LibraryUiState())

    /** The id comes from the screen, not the route: the Libraries tab embeds this view for a lone library. */
    fun start(id: Long) {
        if (libraryId == id) return
        libraryId = id
        viewModelScope.launch {
            restoreFilters()
            load()
        }
        viewModelScope.launch {
            runCatching { books.filterOptions(libraryId) }.onSuccess { options ->
                state.update {
                    it.copy(
                        statusCounts = statusCounts(options.fileTypes, options.readStatuses),
                        typeCounts = TypeFilter.entries.associateWith { t ->
                            if (t.fileTypes == null) options.fileTypes.sumOf { o -> o.count }
                            else options.fileTypes.filter { o -> o.name in t.fileTypes }.sumOf { o -> o.count }
                        },
                    )
                }
            }
        }
        viewModelScope.launch { books.progressChanged.collect { load(quiet = true) } }
    }

    fun coverUrl(book: Book) = books.coverUrl(book)

    fun setSort(sort: LibrarySort) { state.update { it.copy(sort = sort) }; load(); rememberFilters() }
    fun setType(type: TypeFilter) { state.update { it.copy(type = type) }; load(); rememberFilters() }
    fun setStatus(status: StatusFilter) { state.update { it.copy(status = status) }; load(); rememberFilters() }

    /** Sort and pills come back the way they were left, per library. */
    private suspend fun restoreFilters() {
        val saved = settings.libraryFilters(libraryId) ?: return
        if (saved.size < 3) return
        state.update {
            it.copy(
                sort = LibrarySort.entries.firstOrNull { e -> e.name == saved[0] } ?: it.sort,
                type = TypeFilter.entries.firstOrNull { e -> e.name == saved[1] } ?: it.type,
                status = StatusFilter.entries.firstOrNull { e -> e.name == saved[2] } ?: it.status,
            )
        }
    }

    private fun rememberFilters() {
        val s = state.value
        viewModelScope.launch { settings.rememberLibraryFilters(libraryId, s.sort.name, s.type.name, s.status.name) }
    }

    fun load(quiet: Boolean = false) {
        val s = state.value
        viewModelScope.launch {
            if (!quiet) state.update { it.copy(loading = true, error = null) }
            runCatching {
                books.libraryBooks(
                    libraryId,
                    sort = s.sort.sort,
                    dir = s.sort.dir,
                    fileTypes = s.type.fileTypes,
                    statuses = s.status.statuses,
                )
            }.onSuccess { list -> state.update { it.copy(loading = false, books = list) } }
                .onFailure { e -> state.update { it.copy(loading = false, error = friendlyError(e)) } }
        }
    }
}

@Composable
fun LibraryScreen(
    libraryId: Long,
    onBack: (() -> Unit)?,
    onOpenBook: (Long) -> Unit,
    titleOverride: String? = null,
    vm: LibraryViewModel = hiltViewModel(key = "library-$libraryId"),
) {
    LaunchedEffect(libraryId) { vm.start(libraryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloaded by vm.downloadedIds.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    val shown = if (state.downloadedOnly) state.books.filter { it.id in downloaded } else state.books

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }
            Text(
                titleOverride ?: "Library",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp),
            )
            IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                LibrarySort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        trailingIcon = if (option == state.sort) ({ Icon(Icons.Rounded.Check, null) }) else null,
                        onClick = { sortMenu = false; vm.setSort(option) },
                    )
                }
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusFilter.entries.forEach { option ->
                FilterPill(
                    label = option.label,
                    selected = state.status == option,
                    onClick = { vm.setStatus(option) },
                    icon = when (option) {
                        StatusFilter.IN_PROGRESS -> Icons.Rounded.PlayArrow
                        StatusFilter.FINISHED -> Icons.Rounded.Check
                        else -> null
                    },
                    count = state.statusCounts[option],
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TypeFilter.entries.forEach { option ->
                val count = state.typeCounts[option]
                if (option != TypeFilter.ALL && count == 0) return@forEach
                FilterPill(
                    label = option.label,
                    selected = state.type == option,
                    onClick = { vm.setType(option) },
                    count = count,
                )
            }
            val onDevice = state.books.count { it.id in downloaded }
            if (onDevice > 0 || state.downloadedOnly) FilterPill(
                label = "Downloaded",
                selected = state.downloadedOnly,
                onClick = { vm.setDownloadedOnly(!state.downloadedOnly) },
                icon = Icons.Rounded.DownloadDone,
                count = onDevice,
            )
        }
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error!!, onRetry = { vm.load() })
            shown.isEmpty() -> EmptyState(
                if (state.downloadedOnly) "Nothing downloaded here yet." else when (state.status) {
                    StatusFilter.ALL -> "No books here."
                    StatusFilter.IN_PROGRESS -> "Nothing in progress here yet."
                    StatusFilter.UNREAD -> "Everything here has been started."
                    StatusFilter.FINISHED -> "Nothing finished here yet."
                },
            )
            else -> BookGrid(
                books = shown,
                coverUrl = vm::coverUrl,
                onOpen = onOpenBook,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
                downloadedIds = downloaded,
            )
        }
    }
}
