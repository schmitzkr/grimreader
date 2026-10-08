package com.schmitzkr.grimreader.data

import android.content.Context
import android.util.Log
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * The one live transfer per book, touched from Main ([download], [cancel])
     * and IO (a job's own `finally`), hence concurrent. Each attempt is its own
     * [Job], and the entry here is that job's claim on the book: a job only
     * ever removes *itself* (`remove(key, value)`) and only writes the book's
     * [state] while it is still the registered job, so a retry started after
     * a cancel can never have its handle or its progress clobbered by the
     * attempt it replaced, however long that one takes to wind down.
     */
    private val jobs = ConcurrentHashMap<Long, Job>()
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
        // Registered before it starts, so the job's own `finally` always finds
        // its entry; and before the state entry, so a job's owner check (see
        // [jobs]) is never satisfied by a stale registration.
        val job = scope.launch(start = CoroutineStart.LAZY) { run(bookId) }
        jobs[bookId] = job
        _state.update { it + (bookId to DownloadState(bookId, current?.title ?: "Book $bookId", current?.author ?: "", DownloadStatus.QUEUED)) }
        job.start()
        DownloadService.start(context)
    }

    /**
     * Stops a transfer and drops the book. The job's claim is released first,
     * then its coroutine cancelled -- which cancels the OkHttp call outright
     * (see `GrimmoryClient.downloadToFile`), so it stops writing within one
     * buffer rather than running to the end of the current file.
     */
    fun cancel(bookId: Long) {
        val job = jobs.remove(bookId)
        job?.cancel()
        _state.update { it - bookId }
        // Rename (cheap) so a re-download can start clean at once, then delete the
        // tree off the caller's thread once the cancelled transfer has stopped
        // writing. That sweep is what removes a cancelled job's `.part`: the
        // transfer deliberately leaves it, since by then its path may already
        // name a file of the retry's in the recreated directory.
        val d = dir(bookId)
        val doomed = File(root, "$bookId.deleting-${System.nanoTime()}")
        val target = if (d.exists() && d.renameTo(doomed)) doomed else d
        scope.launch {
            job?.join()
            target.deleteRecursively()
        }
    }

    fun remove(bookId: Long) = cancel(bookId)

    /** Changes [bookId]'s entry only while [job] still owns it; a replaced job's late writes are dropped. */
    private fun updateOwned(bookId: Long, job: Job, transform: (DownloadState) -> DownloadState) {
        _state.update { s ->
            val d = s[bookId]
            if (d == null || jobs[bookId] !== job) s else s + (bookId to transform(d))
        }
    }

    private suspend fun run(bookId: Long) {
        val job = currentCoroutineContext().job
        val dir = dir(bookId).apply { mkdirs() }
        try {
            val book = books.book(bookId)
            val kind = kindOf(book) ?: error("This book has nothing the app can keep offline")
            val author = book.authors.joinToString(", ")
            updateOwned(bookId, job) { it.copy(title = book.title, author = author, status = DownloadStatus.DOWNLOADING) }
            try {
                fetch(books.coverUrl(book), File(dir, COVER)) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "No cover for $bookId", e)
            }
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
                    // Same pick as Book.fileIdFor: the primary file of this kind first.
                    val matches = book.files.filter { it.bookType == kind }
                    val file = matches.firstOrNull { it.isPrimary } ?: matches.firstOrNull()
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
                    updateOwned(bookId, job) { it.copy(fraction = (i + partial) / targets.size) }
                }
                updateOwned(bookId, job) { it.copy(fraction = (i + 1f) / targets.size) }
            }
            // A cancel landing after the last file must not leave a record that
            // claims the (now doomed, or already replaced) directory is complete.
            currentCoroutineContext().ensureActive()
            val record = DownloadRecord(book, info, targets.map { it.second }, bytes, System.currentTimeMillis(), kind, fileId, pages)
            File(dir, RECORD).writeText(json.encodeToString(DownloadRecord.serializer(), record))
            updateOwned(bookId, job) { it.copy(title = book.title, author = author, status = DownloadStatus.DONE, fraction = 1f, bytes = bytes) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download failed", e)
            updateOwned(bookId, job) { it.copy(status = DownloadStatus.FAILED, error = friendlyError(e)) }
        } finally {
            // Only this attempt's own claim: a retry registered since stays put.
            jobs.remove(bookId, job)
        }
    }

    /**
     * Streams [url] to [target] through a temp file; returns the bytes
     * written. Cancellable: the client cancels the call and stops the copy
     * within one buffer when this coroutine is cancelled.
     */
    private suspend fun fetch(url: String, target: File, onProgress: (Float) -> Unit): Long =
        clients.current().downloadToFile(url, target, onProgress)

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

    /**
     * Forgets every download, files and all. For a sign-out or server
     * change: book ids belong to one server and library access to one
     * account, so nothing here means anything to the next one. The files
     * go before the state does, so a transfer still writing either
     * finished before its directory vanished (and is cleared with the
     * rest) or fails afterwards and finds no state entry left to mark.
     */
    suspend fun removeAll() {
        jobs.values.toList().forEach { it.cancel() }
        withContext(Dispatchers.IO) { root.deleteRecursively() }
        _state.value = emptyMap()
        jobs.clear()
    }

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
