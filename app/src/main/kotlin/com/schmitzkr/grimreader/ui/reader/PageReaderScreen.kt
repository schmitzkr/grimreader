package com.schmitzkr.grimreader.ui.reader

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.rounded.FormatTextdirectionLToR
import androidx.compose.material.icons.rounded.FormatTextdirectionRToL
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.PageFormat
import android.net.Uri
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.data.SessionKind
import com.schmitzkr.grimreader.data.SessionRepository
import com.schmitzkr.grimreader.data.Settings
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/** What a page shows: an authenticated URL for a comic page, or a rendered PDF page. */
sealed interface PageSource {
    val count: Int
    data class Comic(val urls: List<String>) : PageSource { override val count get() = urls.size }
    data class Pdf(val pages: PdfPages) : PageSource { override val count get() = pages.pageCount }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val source: PageSource? = null,
    val initialIndex: Int = 0,
    val index: Int = 0,
    val rtl: Boolean = false,
    val night: Boolean = false,
    val exiting: Boolean = false,
    val syncError: String? = null,
)

/**
 * Comics come page by page from the server; PDFs are pulled down once and
 * rendered on the device. Both save the 1-based page with the web's
 * percentage formula through the file-level progress path, so a page
 * turned here is where the web reader opens.
 */
@HiltViewModel
class PageReaderViewModel @Inject constructor(
    private val books: BooksRepository,
    private val settings: Settings,
    private val sessions: SessionRepository,
    private val downloads: DownloadManager,
    private val context: android.content.Context,
) : ViewModel() {
    val state = MutableStateFlow(ReaderUiState())
    private var bookId = -1L
    private lateinit var format: PageFormat
    private var bookFileId: Long? = null
    private var pageNumbers: List<Int> = emptyList()
    private var saver: PageProgressSaver? = null
    private var pdf: PdfPages? = null

    fun start(bookId: Long, format: PageFormat) {
        if (this.bookId == bookId) return
        this.bookId = bookId
        this.format = format
        viewModelScope.launch { load() }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    /** A slider jump: the pager follows through [jumpTo]. */
    val jumpTo = MutableStateFlow<Int?>(null)
    fun jump(index: Int) { jumpTo.value = index }
    fun jumped() { jumpTo.value = null }

    private suspend fun load() {
        state.update { it.copy(loading = true, error = null) }
        runCatching {
            // A downloaded copy is used when there is one; the book itself opens from its record offline.
            val local = downloads.record(bookId)?.takeIf { it.kind == format.bookType }
            val book = runCatching { books.book(bookId) }.getOrElse { downloads.record(bookId)?.book ?: throw it }
            bookFileId = book.fileIdFor(format)
            val rtl = if (format == PageFormat.CBX) settings.comicRtl(bookId) else false
            val night = settings.readerNight()
            val source = when (format) {
                PageFormat.CBX -> {
                    val localPages = local?.let { downloads.localPages(bookId) }
                    if (localPages != null) {
                        pageNumbers = local.pages.takeIf { it.size == localPages.size } ?: (1..localPages.size).toList()
                        PageSource.Comic(localPages.map { Uri.fromFile(it).toString() })
                    } else {
                        PageSource.Comic(books.comicPages(bookId).also { pageNumbers = it }.map { books.comicPageUrl(bookId, it) })
                    }
                }
                PageFormat.PDF -> PageSource.Pdf(openPdf(book, local?.let { downloads.localBookFile(bookId) }))
            }
            if (format == PageFormat.PDF) pageNumbers = (1..source.count).toList()
            val progress = runCatching { books.pageProgress(bookId, format) }.getOrNull()
            val initial = when (format) {
                PageFormat.CBX -> progress?.let { pageNumbers.indexOf(it.page).takeIf { i -> i >= 0 } } ?: 0
                PageFormat.PDF -> ((progress?.page ?: 1) - 1).coerceIn(0, (source.count - 1).coerceAtLeast(0))
            }
            saver = PageProgressSaver(
                scope = viewModelScope,
                pageCount = source.count,
                pageNumberAt = { pageNumbers[it] },
                persist = { books.savePageProgress(bookId, it, format, bookFileId) },
                onError = { e -> state.update { it.copy(syncError = friendlyError(e)) } },
            )
            state.update {
                it.copy(loading = false, title = book.title, source = source, initialIndex = initial, index = initial, rtl = rtl, night = night)
            }
            foreground()
        }.onFailure { e -> state.update { it.copy(loading = false, error = friendlyError(e)) } }
    }

    private suspend fun openPdf(book: Book, localFile: File?): PdfPages = withContext(Dispatchers.IO) {
        val file = localFile ?: run {
            val dir = File(context.cacheDir, "books").apply { mkdirs() }
            File(dir, "pdf_${book.id}.pdf").also { f ->
                if (!f.exists() || f.length() == 0L) books.downloadToFile(book, bookFileId, f)
            }
        }
        PdfPages(file).also { pdf = it }
    }

    fun pageChanged(index: Int) {
        state.update { it.copy(index = index) }
        saver?.pageChanged(index)
    }

    // ── Reading sessions: the screen is visible, or it is not ─────────────

    /** Starts a session for the page on screen; a no-op before the book is loaded or while one runs. */
    fun foreground() {
        val s = saver ?: return
        if (sessions.current(SessionKind.READER)?.bookId == bookId) return
        val p = s.progressAt(state.value.index)
        sessions.begin(SessionKind.READER, bookId, format.bookType, p.percentage, p.page.toString())
    }

    fun background() {
        val s = saver ?: return
        val p = s.progressAt(state.value.index)
        sessions.end(SessionKind.READER, p.percentage, p.page.toString())
    }

    fun toggleRtl() = viewModelScope.launch {
        val rtl = !state.value.rtl
        state.update { it.copy(rtl = rtl) }
        settings.setComicRtl(bookId, rtl)
    }

    fun toggleNight() = viewModelScope.launch {
        val night = !state.value.night
        state.update { it.copy(night = night) }
        settings.setReaderNight(night)
    }

    fun clearSyncError() = state.update { it.copy(syncError = null) }

    /** The one exit path: final save, tell the other screens, then leave. */
    fun exit(then: () -> Unit) {
        if (state.value.exiting) return
        state.update { it.copy(exiting = true) }
        viewModelScope.launch {
            val s = saver
            if (s != null && (state.value.source?.count ?: 0) > 0) s.saveNow(state.value.index)
            background()
            books.notifyProgressChanged(bookId)
            then()
        }
    }

    override fun onCleared() {
        pdf?.close()
    }
}

@Composable
fun PageReaderScreen(
    bookId: Long,
    format: PageFormat,
    onBack: () -> Unit,
    vm: PageReaderViewModel = hiltViewModel(key = "reader-${format.name}-$bookId"),
) {
    LaunchedEffect(bookId, format) { vm.start(bookId, format) }
    val state by vm.state.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf(true) }
    // Zoom is owned per page, not by the screen: this is the index of the one
    // page currently zoomed in (ZoomableBox reports its own state, keyed by
    // its index below), and `zoomed` is derived from it for the *current*
    // page only. A neighbouring page composing (the pager keeps both
    // neighbours alive) reports false for itself, which can only clear its
    // own claim -- it can no longer clobber the zoomed page's.
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
    // Trickplay page scrubbing: dragging the slider tracks a live target page and
    // shows a floating preview of it above the thumb, rather than jumping blind
    // until the finger lifts.
    var drag by remember { mutableStateOf<Float?>(null) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var sliderCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    val exit = { vm.exit(onBack) }
    BackHandler { exit() }
    // Reader sessions only count while the screen is in front.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.background() }

    // Immersive while the chrome is hidden; restored on leave.
    LaunchedEffect(chrome) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    // The status bar and gesture nav bar sit directly over the page (always
    // black for comics, night-mode-dependent for PDF), not over ReaderBar's
    // own themed pill, so their icon color needs to track the page, not the
    // app's own light/dark setting -- a light appearance (dark icons) stayed
    // on top of an always-black comic page otherwise.
    LaunchedEffect(state.night, format) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val pageIsLight = !state.night && format != PageFormat.CBX
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = pageIsLight
            isAppearanceLightNavigationBars = pageIsLight
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            (view.context as? Activity)?.window?.let { window ->
                val controller = WindowCompat.getInsetsController(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(if (state.night || format == PageFormat.CBX) Color.Black else Color.White)
            .onGloballyPositioned { rootCoords = it },
    ) {
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error!!, onRetry = vm::retry)
            state.source != null -> {
                val source = state.source!!
                val pager = rememberPagerState(initialPage = state.initialIndex) { source.count }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.currentPage }.collect { vm.pageChanged(it) }
                }
                val jump by vm.jumpTo.collectAsStateWithLifecycle()
                LaunchedEffect(jump) {
                    val target = jump ?: return@LaunchedEffect
                    pager.scrollToPage(target.coerceIn(0, source.count - 1))
                    vm.jumped()
                }
                // Gesture rules follow the current page alone: the pager stops
                // swiping and edge taps stop turning pages only while *it* is zoomed.
                val zoomed = zoomedPage == pager.currentPage
                HorizontalPager(
                    state = pager,
                    reverseLayout = state.rtl,
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                    ZoomableBox(
                        // Edges turn the page (left back, right forward); the
                        // middle third toggles the chrome, same split the
                        // EPUB reader uses. Disabled while zoomed in, same as
                        // the pager's own swipe-to-turn.
                        onTap = { x ->
                            when {
                                zoomed -> chrome = !chrome
                                x < 0.3f -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                                x > 0.7f -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(source.count - 1)) }
                                else -> chrome = !chrome
                            }
                        },
                        onZoomChanged = { z -> zoomedPage = if (z) index else zoomedPage.takeIf { it != index } },
                        // A page that stops being current is held back at 1× by the box
                        // itself, so a zoomed page jumped away from (slider) reopens at fit.
                        current = pager.currentPage == index,
                    ) {
                        when (source) {
                            is PageSource.Comic -> AsyncImage(
                                model = source.urls[index],
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            is PageSource.Pdf -> PdfPage(source.pages, index, night = state.night)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderBar(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
                IconButton(onClick = exit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (format == PageFormat.CBX) IconButton(onClick = vm::toggleRtl) {
                    Icon(if (state.rtl) Icons.Rounded.FormatTextdirectionRToL else Icons.Rounded.FormatTextdirectionLToR, "Reading direction")
                }
                if (format == PageFormat.PDF) IconButton(onClick = vm::toggleNight) {
                    Icon(if (state.night) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, "Night mode")
                }
            }
        }
        val source = state.source
        AnimatedVisibility(visible = chrome && source != null && source.count > 1, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            val count = source?.count ?: 1
            val previewIndex = (drag ?: state.index.toFloat()).roundToInt().coerceIn(0, count - 1)
            ReaderBar(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
                Text("${previewIndex + 1} / $count", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = drag ?: state.index.toFloat(),
                    onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { vm.jump(it.roundToInt()) }; drag = null },
                    valueRange = 0f..(count - 1).toFloat(),
                    modifier = Modifier.weight(1f).padding(end = 8.dp).onGloballyPositioned { sliderCoords = it }
                        .semantics {
                            contentDescription = "Page"
                            stateDescription = "Page ${previewIndex + 1} of $count"
                        },
                )
            }
        }
        // The trickplay-style preview bubble: while the slider is being dragged, a small
        // thumbnail of the page under the thumb floats above it, so the target page can be
        // seen before the finger lifts rather than only after the jump lands.
        val previewDrag = drag
        val previewRoot = rootCoords
        val previewSlider = sliderCoords
        if (previewDrag != null && source != null && source.count > 1 && previewRoot != null && previewSlider != null) {
            val count = source.count
            val previewIndex = previewDrag.roundToInt().coerceIn(0, count - 1)
            val sliderTopLeft = previewRoot.localPositionOf(previewSlider, Offset.Zero)
            val fraction = (previewDrag / (count - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val centerX = sliderTopLeft.x + fraction * previewSlider.size.width
            val density = LocalDensity.current
            val widthPx = with(density) { PREVIEW_WIDTH.roundToPx() }
            val heightPx = with(density) { PREVIEW_HEIGHT.roundToPx() }
            val gapPx = with(density) { PREVIEW_GAP.roundToPx() }
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .offset { IntOffset((centerX - widthPx / 2f).roundToInt(), (sliderTopLeft.y - heightPx - gapPx).roundToInt()) }
                    .width(PREVIEW_WIDTH)
                    .height(PREVIEW_HEIGHT),
            ) {
                PageThumbnail(source, previewIndex)
            }
        }
        if (state.exiting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.syncError?.let {
            androidx.compose.material3.Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { androidx.compose.material3.TextButton(onClick = vm::clearSyncError) { Text("OK") } },
            ) { Text("Could not sync reading progress: $it") }
        }
    }
}

private val invert = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private val PREVIEW_WIDTH = 84.dp
private val PREVIEW_HEIGHT = 120.dp
private val PREVIEW_GAP = 12.dp

/** A small, cheap render of one page for the drag-to-scrub preview bubble. */
@Composable
private fun PageThumbnail(source: PageSource, index: Int) {
    when (source) {
        is PageSource.Comic -> AsyncImage(
            model = source.urls[index],
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        is PageSource.Pdf -> {
            val widthPx = with(LocalDensity.current) { PREVIEW_WIDTH.roundToPx() }
            val bitmap by produceState<Bitmap?>(initialValue = null, source, index, widthPx) {
                value = runCatching { source.pages.render(index, widthPx) }.getOrNull()
            }
            val b = bitmap
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun PdfPage(pages: PdfPages, index: Int, night: Boolean) {
    val widthPx = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
    val bitmap by produceState<Bitmap?>(initialValue = null, pages, index, widthPx) {
        value = runCatching { pages.render(index, widthPx) }.getOrNull()
    }
    val b = bitmap
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        Image(
            bitmap = b.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Fit,
            colorFilter = if (night) invert else null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
