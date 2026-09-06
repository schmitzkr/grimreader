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
    val info: AudiobookInfo,
    /** File names inside the book's directory, in track order (one entry for a single stream). */
    val files: List<String>,
    val bytes: Long,
    val downloadedAt: Long,
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
 * Audiobooks kept on the device: one directory per book with the audio
 * files, the cover, and a JSON record of the book and its track layout,
 * so playback needs no network at all. Downloads run in the app process;
 * a download interrupted by the process dying shows as failed on the next
 * launch and can be retried.
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

    fun download(bookId: Long) {
        val current = _state.value[bookId]
        if (current?.isActive == true || current?.isDone == true) return
        _state.update { it + (bookId to DownloadState(bookId, current?.title ?: "Book $bookId", current?.author ?: "", DownloadStatus.QUEUED)) }
        jobs[bookId] = scope.launch { run(bookId) }
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
            val info = books.audiobookInfo(bookId)
            _state.update { it + (bookId to DownloadState(bookId, book.title, book.authors.joinToString(", "), DownloadStatus.DOWNLOADING)) }
            val targets: List<Pair<String, String>> = if (info.folderBased && info.tracks.isNotEmpty()) {
                info.tracks.map { books.trackStreamUrl(bookId, it.index) to "track_${it.index}${extensionOf(it.fileName)}" }
            } else {
                listOf(books.streamUrl(bookId) to "stream")
            }
            runCatching { fetch(books.coverUrl(book), File(dir, COVER)) {} }
            var bytes = 0L
            targets.forEachIndexed { i, (url, name) ->
                bytes += fetch(url, File(dir, name)) { partial ->
                    _state.update { s -> s[bookId]?.let { d -> s + (bookId to d.copy(fraction = (i + partial) / targets.size)) } ?: s }
                }
            }
            val record = DownloadRecord(book, info, targets.map { it.second }, bytes, System.currentTimeMillis())
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
                DownloadState(id, "Book $id", "", DownloadStatus.FAILED, error = "Interrupted before it finished")
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
        const val RECORD = "record.json"
        const val COVER = "cover.jpg"

        fun extensionOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot) else ""
        }
    }
}
