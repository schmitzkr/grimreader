package com.schmitzkr.grimreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.schmitzkr.grimreader.ui.adaptive.WindowWidth
import com.schmitzkr.grimreader.ui.adaptive.rememberWindowWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.model.Library
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.GrimCard
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface LibrariesUiState {
    data object Loading : LibrariesUiState
    data class Error(val message: String) : LibrariesUiState
    data class Ready(val libraries: List<Library>) : LibrariesUiState
}

@HiltViewModel
class LibrariesViewModel @Inject constructor(private val books: BooksRepository) : ViewModel() {
    val state = MutableStateFlow<LibrariesUiState>(LibrariesUiState.Loading)

    init { load() }

    fun load() {
        viewModelScope.launch {
            state.value = LibrariesUiState.Loading
            runCatching { books.librariesWithCounts() }
                .onSuccess { state.value = LibrariesUiState.Ready(it) }
                .onFailure { state.value = LibrariesUiState.Error(friendlyError(it)) }
        }
    }
}

/**
 * The library list; an account with exactly one library goes straight into
 * it, since a list of one is a detour.
 */
@Composable
fun LibrariesScreen(
    onOpenLibrary: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    vm: LibrariesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val twoPane = rememberWindowWidth() != WindowWidth.COMPACT
    when (val s = state) {
        LibrariesUiState.Loading -> LoadingState()
        is LibrariesUiState.Error -> ErrorState(s.message, onRetry = vm::load)
        is LibrariesUiState.Ready -> {
            val single = s.libraries.singleOrNull()
            if (single == null && twoPane && s.libraries.isNotEmpty()) {
                // Wide screens: the list on the left, the chosen library's grid beside it.
                var selected by rememberSaveable { mutableStateOf(s.libraries.first().id) }
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.width(300.dp).fillMaxHeight().statusBarsPadding(),
                        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 160.dp),
                    ) {
                        item {
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Text("THE STACKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Libraries", style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        items(s.libraries, key = { it.id }) { library ->
                            val isSelected = library.id == selected
                            GrimCard(Modifier.padding(vertical = 6.dp)) {
                                ListItem(
                                    headlineContent = { Text(library.name) },
                                    supportingContent = library.bookCount.takeIf { it > 0 }?.let { { Text("$it books") } },
                                    leadingContent = { Icon(Icons.Outlined.LibraryBooks, null) },
                                    colors = ListItemDefaults.colors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    ),
                                    modifier = Modifier.clickable { selected = library.id }.padding(4.dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val chosen = s.libraries.firstOrNull { it.id == selected } ?: s.libraries.first()
                        LibraryScreen(
                            libraryId = chosen.id,
                            onBack = null,
                            onOpenBook = onOpenBook,
                            titleOverride = chosen.name,
                        )
                    }
                }
            } else if (single != null) {
                LibraryScreen(
                    libraryId = single.id,
                    onBack = null,
                    onOpenBook = onOpenBook,
                    titleOverride = single.name,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 160.dp),
                ) {
                    item {
                        Column(Modifier.padding(bottom = 12.dp)) {
                            Text(
                                "THE STACKS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Libraries", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    items(s.libraries, key = { it.id }) { library ->
                        GrimCard(Modifier.padding(vertical = 6.dp)) {
                            ListItem(
                                headlineContent = { Text(library.name) },
                                supportingContent = library.bookCount.takeIf { it > 0 }?.let { { Text("$it books") } },
                                leadingContent = { Icon(Icons.Outlined.LibraryBooks, null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier
                                    .clickable { onOpenLibrary(library.id) }
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
