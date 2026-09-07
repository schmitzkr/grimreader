package com.schmitzkr.grimreader.data

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.audiobookProgressBody
import com.schmitzkr.grimreader.core.api.epubProgressBody
import com.schmitzkr.grimreader.core.api.parseEpubProgress
import com.schmitzkr.grimreader.core.model.EpubProgress
import com.schmitzkr.grimreader.core.api.bookmarkBody
import com.schmitzkr.grimreader.core.api.inProgressOrder
import com.schmitzkr.grimreader.core.api.pageProgressBody
import com.schmitzkr.grimreader.core.api.parseAudiobookProgress
import com.schmitzkr.grimreader.core.api.parsePageProgress
import com.schmitzkr.grimreader.core.model.PageFormat
import com.schmitzkr.grimreader.core.model.PageProgress
import java.io.File
import com.schmitzkr.grimreader.core.api.StatusRequest
import com.schmitzkr.grimreader.core.api.RatingRequest
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.model.AudiobookProgress
import com.schmitzkr.grimreader.core.model.Author
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.Bookmark
import com.schmitzkr.grimreader.core.model.DashboardConfig
import com.schmitzkr.grimreader.core.model.FilterOptions
import com.schmitzkr.grimreader.core.model.Library
import com.schmitzkr.grimreader.core.model.MagicShelf
import com.schmitzkr.grimreader.core.model.Series
import com.schmitzkr.grimreader.core.model.Shelf
import com.schmitzkr.grimreader.core.api.resolveProgress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** The file types this app can read on-screen, for the Continue Reading row. */
val READABLE_FILE_TYPES = listOf("EPUB", "PDF", "CBX", "FB2", "MOBI", "AZW3")

@Singleton
class BooksRepository @Inject constructor(
    private val clients: ClientHolder,
    private val progressStore: ProgressStore,
) {
    private fun client(): GrimmoryClient = clients.current()
    private val api get() = client().api

    /**
     * Emits a book id whenever its progress changed, so every screen showing
     * it can refetch. The audio service and the readers post here.
     */
    private val _progressChanged = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val progressChanged: SharedFlow<Long> = _progressChanged.asSharedFlow()

    fun notifyProgressChanged(bookId: Long) {
        _progressChanged.tryEmit(bookId)
    }

    suspend fun libraries(): List<Library> = api.libraries()

    suspend fun libraryBooks(
        libraryId: Long,
        sort: String? = null,
        dir: String? = null,
        fileTypes: List<String>? = null,
        statuses: List<String>? = null,
        author: String? = null,
    ): List<Book> = api.books(
        libraryId = libraryId,
        sort = sort,
        dir = dir,
        authors = author?.let { listOf(it) },
        fileType = fileTypes?.joinToString(","),
        status = statuses?.joinToString(","),
    ).content

    suspend fun filterOptions(libraryId: Long): FilterOptions = api.filterOptions(libraryId)

    suspend fun book(id: Long): Book = api.book(id)

    suspend fun search(query: String): List<Book> = api.search(query).content

    /**
     * The `continue-*` endpoints return nothing for admin accounts (their
     * null "all libraries" set goes straight into a JPQL IN clause), so the
     * rows are built from the filtered list, the way the web dashboard
     * builds its own.
     */
    suspend fun continueListening(limit: Int = 20): List<Book> =
        inProgressOrder(inProgress(listOf("AUDIOBOOK")), limit)

    suspend fun continueReading(limit: Int = 20): List<Book> =
        inProgressOrder(inProgress(READABLE_FILE_TYPES), limit)

    private suspend fun inProgress(fileTypes: List<String>): List<Book> = api.books(
        status = "READING,RE_READING",
        fileType = fileTypes.joinToString(","),
        page = 0,
        size = 50,
    ).content

    suspend fun recentlyAdded(limit: Int = 20): List<Book> = api.recentlyAdded(limit)

    suspend fun randomBooks(size: Int = 20, libraryId: Long? = null): List<Book> = api.randomBooks(size, libraryId)

    suspend fun dashboardConfig(): DashboardConfig? =
        runCatching { api.currentUser().userSettings?.dashboardConfig }.getOrNull()

    // ── Browse ────────────────────────────────────────────────────────────

    suspend fun series(): List<Series> = api.series().content
    suspend fun seriesBooks(name: String): List<Book> = api.seriesBooks(name).content
    suspend fun authors(): List<Author> = api.authors().content
    suspend fun author(id: Long): Author = api.author(id)
    suspend fun booksByAuthor(name: String): List<Book> = api.books(authors = listOf(name), sort = "title", dir = "asc").content
    suspend fun shelves(): List<Shelf> = api.shelves()
    suspend fun shelfBooks(shelfId: Long): List<Book> = api.books(shelfId = shelfId, sort = "title", dir = "asc").content
    suspend fun magicShelves(): List<MagicShelf> = api.magicShelves()
    suspend fun magicShelfBooks(id: Long, size: Int = 100): List<Book> = api.magicShelfBooks(id, size = size).content

    fun authorPhotoUrl(authorId: Long): String = client().authorPhotoUrl(authorId)

    // ── Bookmarks ─────────────────────────────────────────────────────────

    suspend fun bookmarks(bookId: Long): List<Bookmark> = api.bookmarks(bookId)

    suspend fun addBookmark(bookId: Long, title: String?, positionMs: Long, trackIndex: Int?): Bookmark =
        api.createBookmark(bookmarkBody(bookId, title, positionMs = positionMs, trackIndex = trackIndex))

    suspend fun renameBookmark(bookmark: Bookmark, title: String) {
        api.updateBookmark(bookmark.id, buildJsonObject {
            put("title", title)
            bookmark.cfi?.let { put("cfi", it) }
        })
    }

    suspend fun deleteBookmark(id: Long) {
        api.deleteBookmark(id)
    }

    suspend fun updateReadStatus(bookId: Long, status: String) {
        api.updateReadStatus(bookId, StatusRequest(status))
        notifyProgressChanged(bookId)
    }

    suspend fun updatePersonalRating(bookId: Long, rating: Int) {
        api.updatePersonalRating(bookId, RatingRequest(rating))
    }

    // ── Audiobooks ────────────────────────────────────────────────────────

    suspend fun audiobookInfo(bookId: Long): AudiobookInfo = api.audiobookInfo(bookId)

    // ── EPUB ──────────────────────────────────────────────────────────────

    suspend fun epubProgress(bookId: Long): EpubProgress? =
        loadProgress(KIND_EPUB, bookId)?.let { parseEpubProgress(it, client().json) }

    suspend fun saveEpubProgress(bookId: Long, progress: EpubProgress, bookFileId: Long?) {
        progressStore.save(KIND_EPUB, bookId, epubProgressBody(progress, bookFileId, client().json))
    }

    suspend fun addEpubBookmark(bookId: Long, title: String?, cfi: String): Bookmark =
        api.createBookmark(bookmarkBody(bookId, title, cfi = cfi))

    // ── Page readers (comics and PDFs) ────────────────────────────────────

    suspend fun pageProgress(bookId: Long, format: PageFormat): PageProgress? =
        loadProgress(format.name.lowercase(), bookId)?.let { parsePageProgress(it, format, client().json) }

    /** Lands locally first; a server failure leaves it pending for retry rather than failing the caller. */
    suspend fun savePageProgress(bookId: Long, progress: PageProgress, format: PageFormat, bookFileId: Long?) {
        progressStore.save(format.name.lowercase(), bookId, pageProgressBody(progress, format, bookFileId, client().json))
    }

    /**
     * The position to open at: a pending local save first, else the server's
     * copy (remembered for next time), else the local copy when offline.
     * Every load also nudges the pending queue.
     */
    private suspend fun loadProgress(kind: String, bookId: Long): JsonObject? {
        val local = progressStore.get(kind, bookId)
        progressStore.retryPendingLater()
        if (local?.pending == true) return local.body
        val server: JsonObject? = try {
            val response = api.progress(bookId)
            when {
                response.code() == 404 -> null
                response.isSuccessful -> response.body()?.also { progressStore.remember(kind, bookId, it) }
                else -> throw HttpException(response)
            }
        } catch (e: HttpException) {
            if (local == null) throw e else null
        } catch (e: IOException) {
            if (local == null) throw e else null
        }
        return resolveProgress(local, server)
    }

    /** The page numbers the server can render for a comic, in reading order. */
    suspend fun comicPages(bookId: Long): List<Int> = api.comicPages(bookId)

    fun comicPageUrl(bookId: Long, page: Int): String = client().comicPageUrl(bookId, page)

    /**
     * Streams a book file to [target] (through a temp file, so a half
     * download never looks complete). [fileId] null means the primary file.
     */
    suspend fun downloadToFile(book: Book, fileId: Long?, target: File) {
        val additional = book.downloadFileId(fileId)
        val response = if (additional == null) api.downloadBook(book.id) else api.downloadBookFile(book.id, additional)
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: error("Empty download")
        val temp = File(target.parentFile, "${target.name}.part")
        body.byteStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
        if (!temp.renameTo(target)) error("Could not move the downloaded file into place")
    }

    suspend fun audiobookProgress(bookId: Long): AudiobookProgress? =
        loadProgress(KIND_AUDIOBOOK, bookId)?.let { parseAudiobookProgress(it, client().json) }

    /** Lands locally first; a server failure leaves it pending for retry rather than failing the caller. */
    suspend fun saveAudiobookProgress(bookId: Long, progress: AudiobookProgress, bookFileId: Long?) {
        progressStore.save(KIND_AUDIOBOOK, bookId, audiobookProgressBody(progress, bookFileId, client().json))
    }

    fun coverUrl(book: Book): String = client().coverUrl(book.id, book.isAudiobook, book.coverVersion)
    fun coverUrl(bookId: Long, audiobook: Boolean): String = client().coverUrl(bookId, audiobook, null)
    fun fallbackCoverUrl(book: Book): String = client().fallbackCoverUrl(book.id, book.coverVersion)
    fun streamUrl(bookId: Long): String = client().streamUrl(bookId)
    fun trackStreamUrl(bookId: Long, index: Int): String = client().trackStreamUrl(bookId, index)

    companion object {
        const val KIND_AUDIOBOOK = "audiobook"
        const val KIND_EPUB = "epub"
    }
}
