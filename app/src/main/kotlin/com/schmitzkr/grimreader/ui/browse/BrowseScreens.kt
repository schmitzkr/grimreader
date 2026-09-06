package com.schmitzkr.grimreader.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.schmitzkr.grimreader.core.model.Author
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.MagicShelf
import com.schmitzkr.grimreader.core.model.Series
import com.schmitzkr.grimreader.core.model.Shelf
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.ui.components.BookGrid
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.components.SectionLabel
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Simple loaded-or-not state for the browse lists. */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Error(val message: String) : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

data class BrowseData(
    val series: List<Series>,
    val authors: List<Author>,
    val shelves: List<Shelf>,
    val magicShelves: List<MagicShelf>,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(private val books: BooksRepository) : ViewModel() {
    val state = MutableStateFlow<Loaded<BrowseData>>(Loaded.Loading)

    init { load() }

    fun coverUrl(bookId: Long, audiobook: Boolean) = books.coverUrl(bookId, audiobook)
    fun photoUrl(author: Author) = books.authorPhotoUrl(author.id)

    fun load() {
        viewModelScope.launch {
            state.value = Loaded.Loading
            runCatching {
                coroutineScope {
                    val series = async { books.series() }
                    val authors = async { books.authors() }
                    val shelves = async { runCatching { books.shelves() }.getOrDefault(emptyList()) }
                    val magic = async { runCatching { books.magicShelves() }.getOrDefault(emptyList()) }
                    BrowseData(series.await(), authors.await(), shelves.await(), magic.await())
                }
            }.onSuccess { state.value = Loaded.Ready(it) }
                .onFailure { state.value = Loaded.Error(friendlyError(it)) }
        }
    }
}

private val sections = listOf("Series", "Authors", "Shelves")

/** One tab for the three browse lists, switched with a segmented control. */
@Composable
fun BrowseScreen(
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (Long) -> Unit,
    onOpenShelf: (Long) -> Unit,
    onOpenMagicShelf: (Long) -> Unit,
    vm: BrowseViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("THE STACKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Browse", style = MaterialTheme.typography.headlineMedium)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            sections.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = section == i,
                    onClick = { section = i },
                    shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                    icon = {},
                ) { Text(label) }
            }
        }
        when (val s = state) {
            Loaded.Loading -> LoadingState()
            is Loaded.Error -> ErrorState(s.message, onRetry = vm::load)
            is Loaded.Ready -> when (section) {
                0 -> SeriesList(s.value.series, vm::coverUrl, onOpenSeries)
                1 -> AuthorsList(s.value.authors, vm::photoUrl, onOpenAuthor)
                else -> ShelvesList(s.value.shelves, s.value.magicShelves, onOpenShelf, onOpenMagicShelf)
            }
        }
    }
}

@Composable
private fun SeriesList(series: List<Series>, coverUrl: (Long, Boolean) -> String, onOpen: (String) -> Unit) {
    if (series.isEmpty()) return EmptyState("No series yet.")
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp)) {
        items(series, key = { it.seriesName }) { s ->
            val cover = s.coverBooks.firstOrNull()
            ListItem(
                headlineContent = { Text(s.seriesName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            "${s.bookCount} ${if (s.bookCount == 1) "book" else "books"}",
                            s.booksRead.takeIf { it > 0 }?.let { "$it read" },
                            s.authors.firstOrNull(),
                        ).joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = {
                    Box(
                        Modifier.size(width = 44.dp, height = 62.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        cover?.let {
                            AsyncImage(coverUrl(it.bookId, it.primaryFileType == "AUDIOBOOK"), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(s.seriesName) },
            )
        }
    }
}

@Composable
private fun AuthorsList(authors: List<Author>, photoUrl: (Author) -> String, onOpen: (Long) -> Unit) {
    if (authors.isEmpty()) return EmptyState("No authors yet.")
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp)) {
        items(authors, key = { it.id }) { a ->
            ListItem(
                headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${a.bookCount} ${if (a.bookCount == 1) "book" else "books"}") },
                leadingContent = {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (a.hasPhoto) AsyncImage(photoUrl(a), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(a.id) },
            )
        }
    }
}

@Composable
private fun ShelvesList(shelves: List<Shelf>, magic: List<MagicShelf>, onOpenShelf: (Long) -> Unit, onOpenMagic: (Long) -> Unit) {
    if (shelves.isEmpty() && magic.isEmpty()) return EmptyState("No shelves yet.")
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp)) {
        if (shelves.isNotEmpty()) item { SectionLabel("Shelves") }
        items(shelves, key = { "s${it.id}" }) { s ->
            ListItem(
                headlineContent = { Text(s.name) },
                supportingContent = s.bookCount.takeIf { it > 0 }?.let { { Text("$it books") } },
                leadingContent = { Icon(Icons.Outlined.Bookmarks, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpenShelf(s.id) },
            )
        }
        if (magic.isNotEmpty()) item { SectionLabel("Magic shelves") }
        items(magic, key = { "m${it.id}" }) { m ->
            ListItem(
                headlineContent = { Text(m.name) },
                leadingContent = { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpenMagic(m.id) },
            )
        }
    }
}

// ── Detail screens: a titled grid loaded from one call ─────────────────────

@HiltViewModel
class BookListViewModel @Inject constructor(private val books: BooksRepository) : ViewModel() {
    val state = MutableStateFlow<Loaded<List<Book>>>(Loaded.Loading)
    val heading = MutableStateFlow<String?>(null)
    val authorName = MutableStateFlow<String?>(null)
    private var key: String? = null

    fun coverUrl(book: Book) = books.coverUrl(book)

    fun start(key: String, load: suspend () -> List<Book>) {
        if (this.key == key && state.value !is Loaded.Error) return
        this.key = key
        viewModelScope.launch {
            state.value = Loaded.Loading
            runCatching { load() }
                .onSuccess { state.value = Loaded.Ready(it) }
                .onFailure { state.value = Loaded.Error(friendlyError(it)) }
        }
    }

    fun seriesBooks(name: String) = start("series:$name", { books.seriesBooks(name) })
    fun authorBooks(id: Long) = start("author:$id", {
        val author = books.author(id)
        authorName.value = author.name
        heading.value = author.description?.takeIf { it.isNotBlank() }
        books.booksByAuthor(author.name)
    })
    fun shelfBooks(id: Long) = start("shelf:$id", { books.shelfBooks(id) })
    fun magicShelfBooks(id: Long) = start("magic:$id", { books.magicShelfBooks(id) })
}

/** An author's books, with the author's name looked up for the heading. */
@Composable
fun AuthorTitledList(id: Long, vm: BookListViewModel, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val name by vm.authorName.collectAsStateWithLifecycle()
    TitledBookList(
        title = name ?: "Author",
        onBack = onBack,
        onOpenBook = onOpenBook,
        vm = vm,
        start = { it.authorBooks(id) },
    )
}

@Composable
fun TitledBookList(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    vm: BookListViewModel,
    start: (BookListViewModel) -> Unit,
) {
    LaunchedEffect(title) { start(vm) }
    val state by vm.state.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                (subtitle ?: heading)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        when (val s = state) {
            Loaded.Loading -> LoadingState()
            is Loaded.Error -> ErrorState(s.message, onRetry = { start(vm) })
            is Loaded.Ready -> if (s.value.isEmpty()) EmptyState("Nothing here.") else BookGrid(
                books = s.value,
                coverUrl = vm::coverUrl,
                onOpen = onOpenBook,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 160.dp),
            )
        }
    }
}
