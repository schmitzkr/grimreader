package com.schmitzkr.grimreader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.ui.components.BookTile
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.components.SectionLabel
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

data class HomeRow(val title: String, val books: List<Book>)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Ready(val rows: List<HomeRow>, val refreshing: Boolean = false) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val books: BooksRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    val user = auth.currentUser

    init {
        load()
        viewModelScope.launch { books.progressChanged.collect { load(quiet = true) } }
    }

    fun coverUrl(book: Book) = books.coverUrl(book)
    fun fallbackCoverUrl(book: Book) = if (book.isAudiobook) books.fallbackCoverUrl(book) else null

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            val current = _state.value
            if (current is HomeUiState.Ready) _state.value = current.copy(refreshing = !quiet)
            else if (!quiet) _state.value = HomeUiState.Loading
            runCatching {
                coroutineScope {
                    val listening = async { books.continueListening() }
                    val reading = async { books.continueReading() }
                    val recent = async { books.recentlyAdded(20) }
                    listOf(
                        HomeRow("Continue listening", listening.await()),
                        HomeRow("Continue reading", reading.await()),
                        HomeRow("Recently added", recent.await()),
                    ).filter { it.books.isNotEmpty() }
                }
            }.onSuccess { _state.value = HomeUiState.Ready(it) }
                .onFailure { if (current !is HomeUiState.Ready) _state.value = HomeUiState.Error(friendlyError(it)) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenBook: (Long) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()

    when (val s = state) {
        HomeUiState.Loading -> LoadingState()
        is HomeUiState.Error -> ErrorState(s.message, onRetry = { vm.load() })
        is HomeUiState.Ready -> PullToRefreshBox(isRefreshing = s.refreshing, onRefresh = { vm.load() }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
            ) {
                item {
                    Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
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
                }
                if (s.rows.isEmpty()) {
                    item {
                        Text(
                            "Nothing in progress yet. Open a library and start something.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(s.rows, key = { it.title }) { row ->
                    SectionLabel(row.title)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(row.books, key = { it.id }) { book ->
                            BookTile(
                                book,
                                vm.coverUrl(book),
                                onClick = { onOpenBook(book.id) },
                                modifier = Modifier.width(if (book.isAudiobook) 132.dp else 112.dp),
                                fallbackUrl = vm.fallbackCoverUrl(book),
                            )
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
