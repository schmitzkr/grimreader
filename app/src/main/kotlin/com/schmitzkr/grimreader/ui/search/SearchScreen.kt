package com.schmitzkr.grimreader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.Settings
import com.schmitzkr.grimreader.ui.components.BookGrid
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.components.SectionLabel
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Error(val message: String) : SearchUiState
    data class Results(val query: String, val books: List<Book>) : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val books: BooksRepository,
    private val settings: Settings,
) : ViewModel() {
    val query = MutableStateFlow("")
    val state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val recent = settings.recentSearches.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            query.debounce(350).distinctUntilChanged().collect { q ->
                if (q.trim().length < 2) state.value = SearchUiState.Idle else search(q.trim())
            }
        }
    }

    fun coverUrl(book: Book) = books.coverUrl(book)

    fun submit(q: String) {
        query.value = q
        if (q.trim().length >= 2) viewModelScope.launch { settings.rememberSearch(q) }
    }

    fun clearRecent() = viewModelScope.launch { settings.clearSearches() }

    private suspend fun search(q: String) {
        state.value = SearchUiState.Loading
        runCatching { books.search(q) }
            .onSuccess { state.value = SearchUiState.Results(q, it) }
            .onFailure { state.value = SearchUiState.Error(friendlyError(it)) }
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit, vm: SearchViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Title, author, series…") },
                singleLine = true,
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit(query) }),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus),
            )
        }
        when (val s = state) {
            SearchUiState.Idle -> if (recent.isNotEmpty()) {
                LazyColumn(contentPadding = PaddingValues(bottom = 160.dp)) {
                    item {
                        SectionLabel("Recent", trailing = { TextButton(onClick = vm::clearRecent) { Text("Clear") } })
                    }
                    items(recent) { q ->
                        ListItem(
                            headlineContent = { Text(q) },
                            leadingContent = { Icon(Icons.Rounded.History, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.submit(q) },
                        )
                    }
                }
            } else EmptyState("Search your libraries by title, author or series.")
            SearchUiState.Loading -> LoadingState()
            is SearchUiState.Error -> ErrorState(s.message, onRetry = { vm.submit(query) })
            is SearchUiState.Results -> if (s.books.isEmpty()) EmptyState("Nothing found for \"${s.query}\".")
            else BookGrid(
                books = s.books,
                coverUrl = vm::coverUrl,
                onOpen = { vm.submit(query); onOpenBook(it) },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
            )
        }
    }
}
