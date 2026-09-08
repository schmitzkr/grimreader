package com.schmitzkr.grimreader.ui.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewAssetLoader
import com.schmitzkr.grimreader.BuildConfig
import com.schmitzkr.grimreader.core.fb2.Fb2ToEpub
import com.schmitzkr.grimreader.core.model.Bookmark
import com.schmitzkr.grimreader.core.model.EpubProgress
import com.schmitzkr.grimreader.data.BooksRepository
import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.data.SessionKind
import com.schmitzkr.grimreader.data.SessionRepository
import com.schmitzkr.grimreader.data.Settings
import com.schmitzkr.grimreader.ui.components.EmptyState
import com.schmitzkr.grimreader.ui.components.ErrorState
import com.schmitzkr.grimreader.ui.components.LoadingState
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

@Serializable
data class TocEntry(val label: String = "", val href: String = "", val depth: Int = 0)

data class EpubUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    /** The book's URL on the asset loader's origin, once downloaded. */
    val fileUrl: String? = null,
    val initialCfi: String? = null,
    val cfi: String? = null,
    val percentage: Double = 0.0,
    val chapter: String = "",
    val toc: List<TocEntry> = emptyList(),
    val theme: String = "light",
    val fontPct: Int = 100,
    /** `book`, `serif` or `sans`. */
    val font: String = "book",
    /** Line height as a percentage of the font size; 100 is the book's own. */
    val linePct: Int = 100,
    val bookmarks: List<Bookmark> = emptyList(),
    val exiting: Boolean = false,
    val syncError: String? = null,
)

/**
 * The EPUB is pulled down once into the cache and rendered by epub.js in a
 * WebView; the page reports every relocation as a CFI and a percentage,
 * which are saved through the file-level progress path the web reader
 * shares, so the two open at the same place.
 */
@HiltViewModel
class EpubReaderViewModel @Inject constructor(
    private val books: BooksRepository,
    private val settings: Settings,
    private val sessions: SessionRepository,
    private val downloads: DownloadManager,
    private val context: Context,
) : ViewModel() {
    val state = MutableStateFlow(EpubUiState())
    /** JavaScript for the page, in order. */
    val commands = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private var bookId = -1L
    private var bookFileId: Long? = null
    private var saver: DebouncedSaver<EpubProgress>? = null
    private val json = Json { ignoreUnknownKeys = true }

    private var requestedFileId: Long? = null

    /** [fileId] picks one of the book's files; null means its EPUB (or first ebook file). */
    fun start(bookId: Long, fileId: Long?, defaultTheme: String) {
        if (this.bookId == bookId) return
        this.bookId = bookId
        this.requestedFileId = fileId
        viewModelScope.launch { load(defaultTheme) }
    }

    fun retry() = viewModelScope.launch { load(state.value.theme) }

    private suspend fun load(defaultTheme: String) {
        state.update { it.copy(loading = true, error = null) }
        runCatching {
            // A downloaded copy is used whenever it is the file asked for; the book itself opens from its record offline.
            val local = downloads.record(bookId)?.takeIf { (it.kind == "EPUB" || it.kind == "FB2") && (requestedFileId == null || requestedFileId == it.fileId) }
            val book = runCatching { books.book(bookId) }.getOrElse { downloads.record(bookId)?.book ?: throw it }
            val chosen = requestedFileId?.let { id -> book.files.firstOrNull { it.id == id } }
            bookFileId = chosen?.id ?: local?.fileId ?: book.ebookFileId
            val isFb2 = local?.kind == "FB2" ||
                (local == null && (chosen?.bookType ?: book.files.firstOrNull { it.id == bookFileId }?.bookType ?: book.primaryFileType) == "FB2")
            val localFile = local?.let { downloads.localBookFile(bookId) }
            val fileUrl = withContext(Dispatchers.IO) {
                if (localFile != null && !isFb2) {
                    "$ORIGIN/downloads/${book.id}/${localFile.name}"
                } else {
                    val dir = File(context.cacheDir, "books").apply { mkdirs() }
                    val suffix = bookFileId?.let { "_$it" } ?: ""
                    val epub = File(dir, "epub_${book.id}$suffix.epub")
                    if (!epub.exists() || epub.length() == 0L) {
                        if (isFb2) {
                            // No server route renders FB2; convert it here into the EPUB the reader shows.
                            val fb2 = localFile ?: File(dir, "fb2_${book.id}$suffix.fb2").also { f ->
                                if (!f.exists() || f.length() == 0L) books.downloadToFile(book, bookFileId, f)
                            }
                            Fb2ToEpub.convert(fb2, epub)
                        } else {
                            books.downloadToFile(book, bookFileId, epub)
                        }
                    }
                    "$ORIGIN/books/${epub.name}"
                }
            }
            val progress = runCatching { books.epubProgress(bookId) }.getOrNull()
            val theme = settings.epubTheme() ?: defaultTheme
            val font = settings.epubFontPct()
            val family = settings.epubFont()
            val line = settings.epubLinePct()
            saver = DebouncedSaver(
                scope = viewModelScope,
                persist = { books.saveEpubProgress(bookId, it, bookFileId) },
                onError = { e -> state.update { it.copy(syncError = friendlyError(e)) } },
            )
            state.update {
                it.copy(
                    loading = false, title = book.title,
                    fileUrl = fileUrl,
                    initialCfi = progress?.cfi, cfi = progress?.cfi, percentage = progress?.percentage ?: 0.0,
                    theme = theme, fontPct = font, font = family, linePct = line,
                )
            }
            loadBookmarks()
        }.onFailure { e -> state.update { it.copy(loading = false, error = friendlyError(e)) } }
    }

    /** From the page: a new location on screen. */
    fun relocated(cfi: String, percentage: Double, chapter: String) {
        state.update { it.copy(cfi = cfi, percentage = percentage, chapter = chapter) }
        saver?.changed(EpubProgress(cfi, percentage))
        foreground()
    }

    fun ready(tocJson: String) {
        val toc = runCatching { json.decodeFromString(ListSerializer(TocEntry.serializer()), tocJson) }.getOrDefault(emptyList())
        state.update { it.copy(toc = toc) }
    }

    fun pageError(message: String) {
        // The Snackbar this drives is easy to miss or dismiss before it's read;
        // this is the only path (unlike onConsoleMessage) that makes a page
        // error findable afterward in a logcat capture.
        Log.e("EpubReader", "page error: $message")
        state.update { it.copy(syncError = message) }
    }

    fun clearSyncError() = state.update { it.copy(syncError = null) }

    // ── Commands to the page ──────────────────────────────────────────────

    private fun js(script: String) { commands.tryEmit(script) }

    fun next() = js("reader.next()")
    fun prev() = js("reader.prev()")
    fun goTo(target: String) = js("reader.display(${JSONObject.quote(target)})")
    fun goToPercentage(p: Double) = js("reader.goToPercentage($p)")

    fun setTheme(name: String) {
        state.update { it.copy(theme = name) }
        js("reader.setTheme(${JSONObject.quote(name)})")
        viewModelScope.launch { settings.setEpubTheme(name) }
    }

    fun setFont(name: String) {
        state.update { it.copy(font = name) }
        js("reader.setFont(${JSONObject.quote(name)})")
        viewModelScope.launch { settings.setEpubFont(name) }
    }

    fun adjustLine(delta: Int) {
        val pct = (state.value.linePct + delta).coerceIn(100, 220)
        state.update { it.copy(linePct = pct) }
        js("reader.setLineHeight($pct)")
        viewModelScope.launch { settings.setEpubLinePct(pct) }
    }

    fun adjustFont(delta: Int) {
        val pct = (state.value.fontPct + delta).coerceIn(70, 200)
        state.update { it.copy(fontPct = pct) }
        js("reader.setFontSize($pct)")
        viewModelScope.launch { settings.setEpubFontPct(pct) }
    }

    // ── Bookmarks ─────────────────────────────────────────────────────────

    private fun loadBookmarks() = viewModelScope.launch {
        runCatching { books.bookmarks(bookId) }
            .onSuccess { list -> state.update { it.copy(bookmarks = list.filter { b -> b.cfi != null }) } }
    }

    fun addBookmark() = viewModelScope.launch {
        val s = state.value
        val cfi = s.cfi ?: return@launch
        val title = s.chapter.ifBlank { "${s.percentage.toInt()}%" }
        runCatching { books.addEpubBookmark(bookId, title, cfi) }
            .onSuccess { loadBookmarks() }
            .onFailure { e -> state.update { it.copy(syncError = friendlyError(e)) } }
    }

    fun deleteBookmark(bookmark: Bookmark) = viewModelScope.launch {
        runCatching { books.deleteBookmark(bookmark.id) }
            .onSuccess { loadBookmarks() }
            .onFailure { e -> state.update { it.copy(syncError = friendlyError(e)) } }
    }

    // ── Sessions ──────────────────────────────────────────────────────────

    fun foreground() {
        val s = state.value
        if (s.cfi == null || s.loading) return
        if (sessions.current(SessionKind.READER)?.bookId == bookId) return
        sessions.begin(SessionKind.READER, bookId, "EPUB", s.percentage, s.cfi)
    }

    fun background() {
        val s = state.value
        sessions.end(SessionKind.READER, s.percentage, s.cfi)
    }

    /** Final save, close the session, tell the other screens, then leave. */
    fun exit(then: () -> Unit) {
        if (state.value.exiting) return
        state.update { it.copy(exiting = true) }
        viewModelScope.launch {
            val s = state.value
            val cfi = s.cfi
            if (cfi != null) saver?.saveNow(EpubProgress(cfi, s.percentage))
            background()
            books.notifyProgressChanged(bookId)
            then()
        }
    }

    companion object {
        const val ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE = "$ORIGIN/assets/reader/index.html"
    }
}

/**
 * Wraps a JS statement injected via `evaluateJavascript` so a call-site
 * error (e.g. `reader` not being defined yet, if a reader asset failed to
 * load) reports through the same `Android.onError` channel a failure
 * inside a `reader.*` function body uses -- otherwise it just vanishes
 * into the discarded result callback, since `evaluateJavascript` doesn't
 * surface exceptions any other way.
 */
private fun guardedEval(js: String): String =
    "try { $js } catch (e) { window.Android && window.Android.onError('eval: ' + ((e && e.message) || e)); }"

/** The page's window.Android. Calls arrive on the WebView's JS thread. */
private class ReaderBridge(private val vm: EpubReaderViewModel, private val tapped: () -> Unit) {
    @JavascriptInterface fun onRelocated(cfi: String, percentage: Double, chapter: String, atEnd: Boolean) = vm.relocated(cfi, percentage, chapter)
    @JavascriptInterface fun onReady(tocJson: String) = vm.ready(tocJson)
    @JavascriptInterface fun onError(message: String) = vm.pageError(message)
    @JavascriptInterface fun onTap() = tapped()
}

/**
 * [WebViewAssetLoader.InternalStoragePathHandler] resolves content type via
 * [android.webkit.MimeTypeMap], which has no built-in mapping for `epub`/
 * `fb2`; this wraps it to fix those up while keeping its (already
 * path-traversal-safe) file resolution.
 */
private class MimeCorrectingPathHandler(
    private val delegate: WebViewAssetLoader.PathHandler,
    private val overrides: Map<String, String>,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val response = delegate.handle(path) ?: return null
        overrides[path.substringAfterLast('.', "").lowercase()]?.let { response.mimeType = it }
        return response
    }
}

private val readerMimeOverrides = mapOf(
    "epub" to "application/epub+zip",
    "fb2" to "application/x-fictionbook+xml",
)

/** The reading-area colors a book can be shown in; matches `reader.js`'s `themes` map. */
private data class ReaderThemeOption(val key: String, val label: String, val background: Color, val onBackground: Color)

private val readerThemeOptions = listOf(
    ReaderThemeOption("light", "Light", Color(0xFFFFFFFF), Color(0xFF1B1B1F)),
    ReaderThemeOption("sepia", "Sepia", Color(0xFFF4ECD8), Color(0xFF3B2F22)),
    ReaderThemeOption("dark", "Dark", Color(0xFF121212), Color(0xFFD6D6D6)),
    ReaderThemeOption("black", "Black", Color(0xFF000000), Color(0xFFCFCFCF)),
    ReaderThemeOption("forest", "Forest", Color(0xFF1B2A1E), Color(0xFFDBE8DB)),
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubReaderScreen(
    bookId: Long,
    fileId: Long?,
    onBack: () -> Unit,
    vm: EpubReaderViewModel = hiltViewModel(key = "epub-$bookId-${fileId ?: 0}"),
) {
    val dark = isSystemInDarkTheme()
    LaunchedEffect(bookId, fileId) { vm.start(bookId, fileId, if (dark) "dark" else "light") }
    val state by vm.state.collectAsStateWithLifecycle()
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Measured from Compose's own layout, not read from the page's
    // window.innerWidth/innerHeight: on-device testing found the WebView
    // reporting a real width but an exact zero height on first layout, and
    // the DOM 'resize' event never firing even on a genuine on-screen size
    // change (rotation) -- so the page's self-reported size can't be trusted
    // for either the initial open or a later resize.
    var webViewSizePx by remember { mutableStateOf<IntSize?>(null) }
    var opened by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val view = LocalView.current
    val context = LocalContext.current
    val exit = { vm.exit(onBack) }
    BackHandler { exit() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.foreground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.background() }

    LaunchedEffect(chrome) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(Unit) {
        onDispose {
            (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    // Open the book once the page has loaded, the file is ready, and Compose
    // has reported a real (nonzero) size for the WebView -- opened is a
    // one-shot latch so a later size change (rotation) resizes the already-
    // open rendition below instead of re-running open() from scratch.
    LaunchedEffect(pageLoaded, state.fileUrl, webViewSizePx) {
        if (opened) return@LaunchedEffect
        val url = state.fileUrl ?: return@LaunchedEffect
        if (!pageLoaded) return@LaunchedEffect
        val size = webViewSizePx?.takeIf { it.width > 0 && it.height > 0 } ?: return@LaunchedEffect
        opened = true
        val cfi = state.initialCfi?.let { JSONObject.quote(it) } ?: "null"
        val widthCss = (size.width / density).toInt()
        val heightCss = (size.height / density).toInt()
        webView?.evaluateJavascript(
            guardedEval(
                "reader.open(${JSONObject.quote(url)}, $cfi, ${JSONObject.quote(state.theme)}, " +
                    "${state.fontPct}, ${JSONObject.quote(state.font)}, ${state.linePct}, $widthCss, $heightCss)",
            ),
            null,
        )
    }
    // A later size change (rotation, split-screen, a fold/unfold): resize the
    // already-open rendition directly rather than relying on the page's own
    // (confirmed unreliable in this WebView) resize handling.
    LaunchedEffect(webViewSizePx) {
        if (!opened) return@LaunchedEffect
        val size = webViewSizePx?.takeIf { it.width > 0 && it.height > 0 } ?: return@LaunchedEffect
        val widthCss = (size.width / density).toInt()
        val heightCss = (size.height / density).toInt()
        webView?.evaluateJavascript(guardedEval("reader.resize($widthCss, $heightCss)"), null)
    }
    LaunchedEffect(webView) {
        val w = webView ?: return@LaunchedEffect
        vm.commands.collect { w.evaluateJavascript(guardedEval(it), null) }
    }

    val pageBackground = readerThemeOptions.firstOrNull { it.key == state.theme }?.background ?: Color.White
    // ReaderBar and the Slider otherwise take the app's own light/dark
    // setting, entirely independent of which reading theme is picked here --
    // a light bar sitting on a black page reads as broken. Keep the app's
    // accent, swap only light/dark to follow the reading theme instead.
    val chromeDark = state.theme != "light" && state.theme != "sepia"
    val appPrimary = MaterialTheme.colorScheme.primary
    val appOnPrimary = MaterialTheme.colorScheme.onPrimary
    val chromeScheme = remember(chromeDark, appPrimary, appOnPrimary) {
        if (chromeDark) {
            darkColorScheme(primary = appPrimary, onPrimary = appOnPrimary, secondary = appPrimary, onSecondary = appOnPrimary)
        } else {
            lightColorScheme(primary = appPrimary, onPrimary = appOnPrimary, secondary = appPrimary, onSecondary = appOnPrimary)
        }
    }
    Box(Modifier.fillMaxSize().background(pageBackground)) {
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error!!, onRetry = { vm.retry() })
            else -> AndroidView(
                modifier = Modifier.fillMaxSize().systemBarsPadding().onSizeChanged { webViewSizePx = it },
                factory = { ctx ->
                    val loader = WebViewAssetLoader.Builder()
                        .setDomain("appassets.androidplatform.net")
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
                        .addPathHandler(
                            "/books/",
                            MimeCorrectingPathHandler(WebViewAssetLoader.InternalStoragePathHandler(ctx, File(ctx.cacheDir, "books")), readerMimeOverrides),
                        )
                        .addPathHandler(
                            "/downloads/",
                            MimeCorrectingPathHandler(WebViewAssetLoader.InternalStoragePathHandler(ctx, File(ctx.filesDir, "downloads")), readerMimeOverrides),
                        )
                        .build()
                    // Debug builds only: lets `chrome://inspect` on a connected computer attach
                    // to this WebView for live DOM/CSS inspection -- the actual iframe epub.js
                    // renders into, its size, and whether its body ever gets real content, none
                    // of which onConsoleMessage/onError can show since nothing here is throwing.
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        addJavascriptInterface(ReaderBridge(vm) { post { chrome = !chrome } }, "Android")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)
                            override fun onPageFinished(view: WebView, url: String?) { pageLoaded = true }
                        }
                        // epub.js/JSZip errors and any other page console output are otherwise
                        // invisible: a book that fails to render leaves no trace anywhere.
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.d("EpubReader", "console: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                                return true
                            }
                        }
                        loadUrl(EpubReaderViewModel.PAGE)
                        webView = this
                    }
                },
            )
        }

        MaterialTheme(colorScheme = chromeScheme) {
            AnimatedVisibility(visible = chrome && !state.loading, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                ReaderBar(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
                    IconButton(onClick = exit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (state.chapter.isNotBlank()) {
                            Text(state.chapter, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val here = state.bookmarks.any { it.cfi == state.cfi }
                    IconButton(onClick = { if (!here) vm.addBookmark(); sheet = "bookmarks" }) {
                        Icon(if (here) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmarks", tint = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { sheet = "chapters" }) { Icon(Icons.AutoMirrored.Rounded.List, "Chapters") }
                    IconButton(onClick = { sheet = "display" }) { Icon(Icons.Rounded.FormatSize, "Display") }
                }
            }
            AnimatedVisibility(visible = chrome && !state.loading, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderBar(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
                    IconButton(onClick = vm::prev) { Icon(Icons.Rounded.ChevronLeft, "Previous page") }
                    var drag by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = drag ?: (state.percentage / 100).toFloat().coerceIn(0f, 1f),
                        onValueChange = { drag = it },
                        onValueChangeFinished = { drag?.let { vm.goToPercentage(it * 100.0) }; drag = null },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${((drag?.times(100)) ?: state.percentage).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = vm::next) { Icon(Icons.Rounded.ChevronRight, "Next page") }
                }
            }
        }
        if (state.exiting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.syncError?.let {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = vm::clearSyncError) { Text("OK") } },
            ) { Text(it) }
        }
    }

    when (sheet) {
        "chapters" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Text("Chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(24.dp, 8.dp))
            if (state.toc.isEmpty()) {
                EmptyState("This book has no table of contents.", Modifier.height(200.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    items(state.toc) { entry ->
                        Text(
                            entry.label.ifBlank { entry.href },
                            style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (entry.label == state.chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.goTo(entry.href); sheet = null }
                                .padding(start = (24 + entry.depth * 16).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }
        "bookmarks" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addBookmark() }, enabled = state.cfi != null && state.bookmarks.none { it.cfi == state.cfi }) { Text("Add here") }
            }
            if (state.bookmarks.isEmpty()) {
                EmptyState("No bookmarks in this book yet.", Modifier.height(200.dp))
            } else {
                LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    items(state.bookmarks, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { b.cfi?.let { vm.goTo(it) }; sheet = null }.padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(b.title ?: "Bookmark", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.deleteBookmark(b) }) { Icon(Icons.Rounded.Delete, "Delete") }
                        }
                    }
                }
            }
        }
        "display" -> ModalBottomSheet(onDismissRequest = { sheet = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("Display", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    readerThemeOptions.forEach { option ->
                        val selected = state.theme == option.key
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { vm.setTheme(option.key) }.padding(4.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(option.background)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Rounded.Check, null, tint = option.onBackground, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(option.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Text size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.adjustFont(-10) }, enabled = state.fontPct > 70) { Text("A−") }
                    Text("${state.fontPct}%", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { vm.adjustFont(10) }, enabled = state.fontPct < 200) { Text("A+") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Font", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                val fonts = listOf("book" to "Book's own", "serif" to "Serif", "sans" to "Sans")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    fonts.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = state.font == key,
                            onClick = { vm.setFont(key) },
                            shape = SegmentedButtonDefaults.itemShape(i, fonts.size),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Line spacing", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.adjustLine(-20) }, enabled = state.linePct > 100) { Text("−") }
                    Text(if (state.linePct == 100) "Book's own" else "${state.linePct}%", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { vm.adjustLine(20) }, enabled = state.linePct < 220) { Text("+") }
                }
            }
        }
    }
}
