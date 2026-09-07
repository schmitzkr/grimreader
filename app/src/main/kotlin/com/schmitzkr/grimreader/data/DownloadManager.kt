package com.schmitzkr.grimreader.data

import android.content.Context
import android.util.Log
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What is on disk for one book: `downloads/<bookId>/record.json`. */
@Serializable
data class DownloadRecord(
    val book: Book,
    /** Track layout for an audiobook; null for the other kinds. */
    val info: AudiobookInfo? = null,
    /** File names inside the book's directory: tracks in order, comic pages in order, or the one book file. */
    val files: List<String>,
    val bytes: Long,
    val downloadedAt: Long,
    /** AUDIOBOOK, EPUB, FB2, PDF or CBX. */
    val kind: String = "AUDIOBOOK",
    /** The server's file id for an ebook, when it was not the primary file. */
    val fileId: Long? = null,
    /** The server's page numbers for a comic, matching [files]. */
    val pages: List<Int> = emptyList(),
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

data class DownloadState(
    val bookId: Long,
    val title: String,
    val author: String,
    val status: DownloadStatus,
    val fraction: Float = 0f,
    val bytes: Long = 0,
    val error: String? = null,
) {
    val isDone: Boolean get() = status == DownloadStatus.DONE
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING
}

/**
 * Books kept on the device: one directory per book with the audio files,
 * the ebook file or every comic page, the cover, and a JSON record of the
 * book (and an audiobook's track layout), so playing or reading needs no
 * network at all. Transfers run here; [DownloadService] holds the process
 * in the foreground with a progress notification while any are active. A
 * download interrupted by the process dying shows as failed on the next
 * launch, and a retry resumes from the files already complete.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val context: Context,
    private val clients: ClientHolder,
    private val books: BooksRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jobs = mutableMapOf<Long, Job>()
    private val root: File get() = File(context.filesDir, "downloads")

    private val _state = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val state: StateFlow<Map<Long, DownloadState>> = _state.asStateFlow()

    init {
        scope.launch { scan() }
    }

    fun isDownloaded(bookId: Long): Boolean = _state.value[bookId]?.isDone == true

    fun downloadedIds(): Set<Long> = _state.value.filterValues { it.isDone }.keys

    fun totalBytes(): Long = _state.value.values.filter { it.isDone }.sumOf { it.bytes }

    fun record(bookId: Long): DownloadRecord? {
        if (!isDownloaded(bookId)) return null
        return readRecord(dir(bookId))
    }

    fun localFile(bookId: Long, name: String): File? = File(dir(bookId), name).takeIf { it.isFile }

    /** The audio file for [trackIndex] of a folder-based book, or the single stream for `null`. */
    fun localAudio(bookId: Long, trackIndex: Int?): File? {
        val record = record(bookId) ?: return null
        val name = if (trackIndex == null) record.files.firstOrNull() else record.files.getOrNull(trackIndex)
        return name?.let { localFile(bookId, it) }
    }

    fun localCover(bookId: Long): File? = localFile(bookId, COVER)

    /** The downloaded ebook file (EPUB, FB2 or PDF), or null for other kinds. */
    fun localBookFile(bookId: Long): File? {
        val record = record(bookId) ?: return null
        if (record.kind == "AUDIOBOOK" || record.kind == "CBX") return null
        return record.files.firstOrNull()?.let { localFile(bookId, it) }
    }

    /** Every downloaded comic page in reading order, or null unless all are present. */
    fun localPages(bookId: Long): List<File>? {
        val record = record(bookId) ?: return null
        if (record.kind != "CBX") return null
        val files = record.files.mapNotNull { localFile(bookId, it) }
        return files.takeIf { it.size == record.files.size && it.isNotEmpty() }
    }

    /** Where every downloaded book lives, for serving files to the reader page. */
    val downloadsRoot: File get() = root

    fun download(bookId: Long) {
        val current = _state.value[bookId]
        if (current?.isActive == true || current?.isDone == true) return
        _state.update { it + (bookId to DownloadState(bookId, current?.title ?: "Book $bookId", current?.author ?: "", DownloadStatus.QUEUED)) }
        jobs[bookId] = scope.launch { run(bookId) }
        DownloadService.start(context)
    }

    fun cancel(bookId: Long) {
        jobs.remove(bookId)?.cancel()
        dir(bookId).deleteRecursively()
        _state.update { it - bookId }
    }

    fun remove(bookId: Long) = cancel(bookId)

    private suspend fun run(bookId: Long) {
        val dir = dir(bookId).apply { mkdirs() }
        try {
            val book = books.book(bookId)
            val kind = kindOf(book) ?: error("This book has nothing the app can keep offline")
            _state.update { it + (bookId to DownloadState(bookId, book.title, book.authors.joinToString(", "), DownloadStatus.DOWNLOADING)) }
            runCatching { fetch(books.coverUrl(book), File(dir, COVER)) {} }
            var info: AudiobookInfo? = null
            var fileId: Long? = null
            var pages: List<Int> = emptyList()
            val targets: List<Pair<String, String>> = when (kind) {
                "AUDIOBOOK" -> {
                    val i = books.audiobookInfo(bookId).also { info = it }
                    if (i.folderBased && i.tracks.isNotEmpty()) i.tracks.map { books.trackStreamUrl(bookId, it.index) to "track_${it.index}${extensionOf(it.fileName)}" }
                    else listOf(books.streamUrl(bookId) to "stream")
                }
                "CBX" -> {
                    pages = books.comicPages(bookId)
                    pages.map { books.comicPageUrl(bookId, it) to "page_%05d".format(it) }
                }
                else -> {
                    val file = book.files.firstOrNull { it.bookType == kind }
                    fileId = file?.id
                    listOf(books.downloadUrl(book, fileId) to "book.${(file?.extension ?: kind).lowercase().removePrefix(".")}")
                }
            }
            var bytes = 0L
            targets.forEachIndexed { i, (url, name) ->
                val target = File(dir, name)
                // A file is only ever renamed into place once complete, so an
                // existing one is a finished piece of an interrupted download.
                bytes += if (target.isFile && target.length() > 0) target.length()
                else fetch(url, target) { partial ->
                    _state.update { s -> s[bookId]?.let { d -> s + (bookId to d.copy(fraction = (i + partial) / targets.size)) } ?: s }
                }
                _state.update { s -> s[bookId]?.let { d -> s + (bookId to d.copy(fraction = (i + 1f) / targets.size)) } ?: s }
            }
            val record = DownloadRecord(book, info, targets.map { it.second }, bytes, System.currentTimeMillis(), kind, fileId, pages)
            File(dir, RECORD).writeText(json.encodeToString(DownloadRecord.serializer(), record))
            _state.update { it + (bookId to DownloadState(bookId, book.title, book.authors.joinToString(", "), DownloadStatus.DONE, 1f, bytes)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            _state.update { s -> s[bookId]?.let { d -> s + (bookId to d.copy(status = DownloadStatus.FAILED, error = friendlyError(e))) } ?: s }
        } finally {
            jobs.remove(bookId)
        }
    }

    /** Streams [url] to [target] through a temp file; returns the bytes written. */
    private fun fetch(url: String, target: File, onProgress: (Float) -> Unit): Long {
        val call = clients.current().okHttp.newCall(Request.Builder().url(url).build())
        call.execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Server said ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty response")
            val total = body.contentLength()
            val temp = File(target.parentFile, "${target.name}.part")
            var written = 0L
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!temp.renameTo(target)) throw java.io.IOException("Could not move the file into place")
            return written
        }
    }

    /** Finished downloads reappear from disk; a directory without a record is a torn download. */
    private fun scan() {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        val found = dirs.mapNotNull { d ->
            val id = d.name.toLongOrNull() ?: return@mapNotNull null
            val record = readRecord(d)
            if (record == null) {
                DownloadState(id, "Book $id", "", DownloadStatus.FAILED, error = "Interrupted before it finished; retry picks up where it stopped")
            } else {
                DownloadState(id, record.book.title, record.book.authors.joinToString(", "), DownloadStatus.DONE, 1f, record.bytes)
            }
        }
        _state.update { current -> found.associateBy { it.bookId } + current }
    }

    private fun readRecord(dir: File): DownloadRecord? {
        val file = File(dir, RECORD)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(DownloadRecord.serializer(), file.readText()) }.getOrNull()
    }

    private fun dir(bookId: Long) = File(root, bookId.toString())

    companion object {
        private const val TAG = "Downloads"
        private val READABLE = listOf("EPUB", "FB2", "PDF", "CBX")

        /** What a download of [book] keeps: its audio, or its primary readable file, or the first readable one. */
        fun kindOf(book: Book): String? = when {
            book.isAudiobook -> "AUDIOBOOK"
            else -> book.primaryFileType?.takeIf { it in READABLE }
                ?: READABLE.firstOrNull { k -> book.files.any { it.bookType == k } }
        }
        const val RECORD = "record.json"
        const val COVER = "cover.jpg"

        fun extensionOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot) else ""
        }
    }
}
