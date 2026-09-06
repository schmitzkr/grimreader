package com.schmitzkr.grimreader.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.model.Bookmark
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.playback.PlaybackState
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.formatClock
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(private val books: BooksRepository) : ViewModel() {
    val bookmarks = MutableStateFlow<List<Bookmark>?>(null)
    val error = MutableStateFlow<String?>(null)
    private var bookId: Long? = null

    fun load(bookId: Long) {
        this.bookId = bookId
        viewModelScope.launch {
            runCatching { books.bookmarks(bookId) }
                .onSuccess { bookmarks.value = it.sortedBy { b -> b.positionMs ?: 0 } }
                .onFailure { error.value = friendlyError(it) }
        }
    }

    fun add(state: PlaybackState) {
        val id = bookId ?: return
        val chapter = state.currentChapterIndex?.let { state.chapters[it].title }
        val title = chapter?.let { "$it · ${formatClock(state.positionMs)}" } ?: formatClock(state.positionMs)
        viewModelScope.launch {
            runCatching { books.addBookmark(id, title, state.positionMs, state.trackIndex) }
                .onSuccess { load(id) }
                .onFailure { error.value = friendlyError(it) }
        }
    }

    fun rename(bookmark: Bookmark, title: String) {
        val id = bookId ?: return
        viewModelScope.launch {
            runCatching { books.renameBookmark(bookmark, title) }
                .onSuccess { load(id) }
                .onFailure { error.value = friendlyError(it) }
        }
    }

    fun delete(bookmark: Bookmark) {
        val id = bookId ?: return
        viewModelScope.launch {
            runCatching { books.deleteBookmark(bookmark.id) }
                .onSuccess { load(id) }
                .onFailure { error.value = friendlyError(it) }
        }
    }
}

/** Contents of the player's bookmarks sheet: add at the current position, tap to seek, rename, delete. */
@Composable
fun BookmarksSheetContent(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    vm: BookmarksViewModel = hiltViewModel(),
) {
    val bookId = state.bookId ?: return
    LaunchedEffect(bookId) { vm.load(bookId) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Bookmark?>(null) }

    Column(Modifier.padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { vm.add(state) }) {
                Icon(Icons.Rounded.Add, null)
                Text("Add at ${formatClock(state.positionMs)}")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp)) }
        when {
            bookmarks == null -> {}
            bookmarks!!.isEmpty() -> EmptyState("No bookmarks in this book yet.", Modifier.padding(vertical = 24.dp).fillMaxWidth())
            else -> LazyColumn {
                items(bookmarks!!, key = { it.id }) { b ->
                    ListItem(
                        headlineContent = { Text(b.title ?: formatClock(b.positionMs ?: 0)) },
                        supportingContent = { Text(formatClock(b.positionMs ?: 0)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { renaming = b }) { Icon(Icons.Outlined.Edit, "Rename") }
                                IconButton(onClick = { vm.delete(b) }) { Icon(Icons.Outlined.Delete, "Delete") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { b.positionMs?.let(onSeek) },
                    )
                }
            }
        }
    }

    renaming?.let { b ->
        var title by remember(b.id) { mutableStateOf(b.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename bookmark") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(b, title.trim()); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
