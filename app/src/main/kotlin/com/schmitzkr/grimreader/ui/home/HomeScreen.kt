package com.schmitzkr.grimreader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.dashboard.DASHBOARD_MAX_ITEMS
import com.schmitzkr.grimreader.core.dashboard.ScrollerKind
import com.schmitzkr.grimreader.core.dashboard.discoverPick
import com.schmitzkr.grimreader.core.dashboard.kind
import com.schmitzkr.grimreader.core.dashboard.normalizeDashboard
import com.schmitzkr.grimreader.core.dashboard.scrollerTitle
import com.schmitzkr.grimreader.core.dashboard.sortBooks
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.DashboardScroller
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.UpdateRepository
import com.schmitzkr.grimreader.ui.components.BookTile
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.components.SectionLabel
import com.schmitzkr.grimreader.ui.components.UpdateBanner
import com.schmitzkr.grimreader.ui.adaptive.WindowWidth
import com.schmitzkr.grimreader.ui.adaptive.rememberWindowWidth
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

data class HomeRow(
    val key: String,
    val title: String,
    val kind: ScrollerKind,
    val books: List<Book>,
    val magicShelfId: Long? = null,
)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Ready(val rows: List<HomeRow>, val refreshing: Boolean = false) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val books: BooksRepository,
    auth: AuthRepository,
    val updates: UpdateRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    val user = auth.currentUser

    init {
        load()
        viewModelScope.launch { books.progressChanged.collect { load(quiet = true) } }
        viewModelScope.launch { updates.checkIfDue() }
    }

    fun coverUrl(book: Book) = books.coverUrl(book)
    fun fallbackCoverUrl(book: Book) = if (book.isAudiobook) books.fallbackCoverUrl(book) else null

    /** One row per enabled scroller in the server's layout, loaded in parallel. */
    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            val current = _state.value
            if (current is HomeUiState.Ready) _state.value = current.copy(refreshing = !quiet)
            else if (!quiet) _state.value = HomeUiState.Loading
            runCatching {
                val scrollers = normalizeDashboard(books.dashboardConfig())
                coroutineScope {
                    scrollers.map { s -> async { loadRow(s) } }.map { it.await() }
                }.filterNotNull().filter { !(it.kind.hidesWhenEmpty && it.books.isEmpty()) }
            }.onSuccess { _state.value = HomeUiState.Ready(it) }
                .onFailure { if (current !is HomeUiState.Ready) _state.value = HomeUiState.Error(friendlyError(it)) }
        }
    }

    private suspend fun loadRow(s: DashboardScroller): HomeRow? {
        val kind = s.kind ?: return null
        val max = s.maxItems?.takeIf { it > 0 } ?: DASHBOARD_MAX_ITEMS
        val list = runCatching {
            when (kind) {
                ScrollerKind.LAST_LISTENED -> books.continueListening(max)
                ScrollerKind.LAST_READ -> books.continueReading(max)
                ScrollerKind.LATEST_ADDED -> books.recentlyAdded(max)
                ScrollerKind.RANDOM -> discoverPick(books.randomBooks(max * 2), max)
                ScrollerKind.MAGIC_SHELF -> sortBooks(
                    books.magicShelfBooks(s.magicShelfId!!, size = max * 5), s.sortField, s.sortDirection,
                ).take(max)
            }
        }.getOrElse { emptyList() }
        return HomeRow(
            key = s.id ?: "${s.type}-${s.order}",
            title = scrollerTitle(s),
            kind = kind,
            books = list,
            magicShelfId = s.magicShelfId,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenBook: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMagicShelf: (Long) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val update by vm.updates.state.collectAsStateWithLifecycle()
    val wide = rememberWindowWidth() == WindowWidth.EXPANDED

    when (val s = state) {
        HomeUiState.Loading -> LoadingState()
        is HomeUiState.Error -> ErrorState(s.message, onRetry = { vm.load() })
        is HomeUiState.Ready -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.load() }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
            ) {
                item {
                    Row(
                        Modifier.statusBarsPadding().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                greeting().uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                user?.displayName?.let { "Hello, $it" } ?: "Home",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                        IconButton(onClick = onOpenSearch) { Icon(Icons.Rounded.Search, "Search") }
                    }
                }
                item {
                    UpdateBanner(
                        state = update,
                        onInstall = { r -> vm.viewModelScope.launch { vm.updates.install(r) } },
                        onDismiss = vm.updates::dismiss,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (s.rows.isEmpty()) {
                    item {
                        Text(
                            "Nothing to show yet. Open a library and start something.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(s.rows, key = { it.key }) { row ->
                    SectionLabel(
                        row.title,
                        trailing = if (row.kind == ScrollerKind.MAGIC_SHELF) {
                            {
                                Icon(
                                    Icons.Rounded.AutoAwesome, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(14.dp),
                                )
                            }
                        } else null,
                        modifier = if (row.magicShelfId != null) Modifier.padding(0.dp) else Modifier,
                    )
                    if (row.books.isEmpty()) {
                        Text(
                            "Nothing here yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(row.books, key = { it.id }) { book ->
                                BookTile(
                                    book,
                                    vm.coverUrl(book),
                                    onClick = { onOpenBook(book.id) },
                                    modifier = Modifier.width(
                                        if (book.isAudiobook) (if (wide) 160.dp else 132.dp) else (if (wide) 136.dp else 112.dp),
                                    ),
                                    fallbackUrl = vm.fallbackCoverUrl(book),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
