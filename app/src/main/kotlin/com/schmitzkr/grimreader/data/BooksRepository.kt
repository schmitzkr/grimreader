package com.schmitzkr.grimreader.data

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.audiobookProgressBody
import com.schmitzkr.grimreader.core.api.inProgressOrder
import com.schmitzkr.grimreader.core.api.parseAudiobookProgress
import com.schmitzkr.grimreader.core.api.StatusRequest
import com.schmitzkr.grimreader.core.api.RatingRequest
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.model.AudiobookProgress
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.DashboardConfig
import com.schmitzkr.grimreader.core.model.FilterOptions
import com.schmitzkr.grimreader.core.model.Library
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** The file types this app can read on-screen, for the Continue Reading row. */
val READABLE_FILE_TYPES = listOf("EPUB", "PDF", "CBX", "FB2", "MOBI", "AZW3")

@Singleton
class BooksRepository @Inject constructor(private val clients: ClientHolder) {
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

    suspend fun randomBooks(size: Int = 20): List<Book> = api.randomBooks(size)

    suspend fun dashboardConfig(): DashboardConfig? =
        runCatching { api.currentUser().userSettings?.dashboardConfig }.getOrNull()

    suspend fun updateReadStatus(bookId: Long, status: String) {
        api.updateReadStatus(bookId, StatusRequest(status))
        notifyProgressChanged(bookId)
    }

    suspend fun updatePersonalRating(bookId: Long, rating: Int) {
        api.updatePersonalRating(bookId, RatingRequest(rating))
    }

    // ── Audiobooks ────────────────────────────────────────────────────────

    suspend fun audiobookInfo(bookId: Long): AudiobookInfo = api.audiobookInfo(bookId)

    suspend fun audiobookProgress(bookId: Long): AudiobookProgress? {
        val response = api.progress(bookId)
        if (response.code() == 404) return null
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.let { parseAudiobookProgress(it, client().json) }
    }

    suspend fun saveAudiobookProgress(bookId: Long, progress: AudiobookProgress, bookFileId: Long?) {
        api.updateProgress(bookId, audiobookProgressBody(progress, bookFileId, client().json))
    }

    fun coverUrl(book: Book): String = client().coverUrl(book.id, book.isAudiobook, book.coverVersion)
    fun coverUrl(bookId: Long, audiobook: Boolean): String = client().coverUrl(bookId, audiobook, null)
    fun streamUrl(bookId: Long): String = client().streamUrl(bookId)
    fun trackStreamUrl(bookId: Long, index: Int): String = client().trackStreamUrl(bookId, index)
}
