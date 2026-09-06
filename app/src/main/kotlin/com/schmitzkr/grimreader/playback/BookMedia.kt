package com.schmitzkr.grimreader.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.schmitzkr.grimreader.core.model.AudiobookInfo
import com.schmitzkr.grimreader.core.model.Book

/*
 * Media ids the service and the app agree on:
 *   root, continue, libraries, library:<id>   browsable folders (Android Auto)
 *   book:<id>                                  a book to load (resolved by the service)
 *   book:<id>:track:<n>                        one playable track of a loaded book
 */

const val ID_ROOT = "root"
const val ID_CONTINUE = "continue"
const val ID_LIBRARIES = "libraries"

fun bookMediaId(bookId: Long) = "book:$bookId"
fun libraryMediaId(id: Long) = "library:$id"

/** The book id inside any `book:` media id, or null. */
fun bookIdOf(mediaId: String?): Long? =
    mediaId?.takeIf { it.startsWith("book:") }?.split(":")?.getOrNull(1)?.toLongOrNull()

fun libraryIdOf(mediaId: String?): Long? =
    mediaId?.takeIf { it.startsWith("library:") }?.substringAfter("library:")?.toLongOrNull()

object Extras {
    const val BOOK_ID = "bookId"
    const val TRACK_INDEX = "trackIndex"
    const val TRACK_DURATION_MS = "trackDurationMs"
    const val CUMULATIVE_START_MS = "cumulativeStartMs"
    const val TOTAL_DURATION_MS = "totalDurationMs"
    const val FOLDER_BASED = "folderBased"
    const val BOOK_FILE_ID = "bookFileId"
}

/** A folder for the browse tree. */
fun folderItem(id: String, title: String, artworkUri: Uri? = null): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .setArtworkUri(artworkUri)
            .build(),
    )
    .build()

/** A book as a playable entry in a browse list; the service resolves it on play. */
fun bookEntryItem(book: Book, coverUrl: String): MediaItem = MediaItem.Builder()
    .setMediaId(bookMediaId(book.id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(book.title)
            .setArtist(book.authors.joinToString(", "))
            .setArtworkUri(Uri.parse(coverUrl))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setExtras(bundleOf(Extras.BOOK_ID to book.id))
            .build(),
    )
    .build()

/**
 * The playable items for a loaded book: one per track for a folder-based
 * book, one for a single stream. Every item carries what the progress
 * saver needs in its extras so the service can work from the player alone.
 */
fun playableItems(
    book: Book,
    info: AudiobookInfo,
    coverUrl: String,
    streamUrl: String,
    trackUrl: (Int) -> String,
): List<MediaItem> {
    val artist = book.authors.joinToString(", ").ifEmpty { book.narrator ?: "" }
    fun metadata(title: String, extras: Bundle) = MediaMetadata.Builder()
        .setTitle(title)
        .setAlbumTitle(book.title)
        .setArtist(artist)
        .setArtworkUri(Uri.parse(coverUrl))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
        .setExtras(extras)
        .build()

    val common = arrayOf(
        Extras.BOOK_ID to book.id,
        Extras.TOTAL_DURATION_MS to info.durationMs,
        Extras.FOLDER_BASED to info.folderBased,
        Extras.BOOK_FILE_ID to (info.bookFileId ?: -1L),
    )
    return if (info.folderBased && info.tracks.isNotEmpty()) {
        info.tracks.map { track ->
            MediaItem.Builder()
                .setMediaId("${bookMediaId(book.id)}:track:${track.index}")
                .setUri(trackUrl(track.index))
                .setMediaMetadata(
                    metadata(
                        track.title.ifBlank { book.title },
                        bundleOf(
                            *common,
                            Extras.TRACK_INDEX to track.index,
                            Extras.TRACK_DURATION_MS to track.durationMs,
                            Extras.CUMULATIVE_START_MS to track.cumulativeStartMs,
                        ),
                    ),
                )
                .build()
        }
    } else {
        listOf(
            MediaItem.Builder()
                .setMediaId(bookMediaId(book.id))
                .setUri(streamUrl)
                .setMediaMetadata(
                    metadata(
                        book.title,
                        bundleOf(
                            *common,
                            Extras.TRACK_INDEX to -1,
                            Extras.TRACK_DURATION_MS to info.durationMs,
                            Extras.CUMULATIVE_START_MS to 0L,
                        ),
                    ),
                )
                .build(),
        )
    }
}

/** The book-wide absolute position for the item the player is on. */
fun MediaItem.absolutePositionMs(positionMs: Long): Long {
    val extras = mediaMetadata.extras ?: return positionMs
    return extras.getLong(Extras.CUMULATIVE_START_MS, 0L) + positionMs
}

val MediaItem.bookId: Long? get() = mediaMetadata.extras?.getLong(Extras.BOOK_ID, -1L)?.takeIf { it >= 0 }
val MediaItem.trackIndex: Int? get() = mediaMetadata.extras?.getInt(Extras.TRACK_INDEX, -1)?.takeIf { it >= 0 }
val MediaItem.totalDurationMs: Long get() = mediaMetadata.extras?.getLong(Extras.TOTAL_DURATION_MS, 0L) ?: 0L
val MediaItem.isFolderBased: Boolean get() = mediaMetadata.extras?.getBoolean(Extras.FOLDER_BASED, false) ?: false
val MediaItem.bookFileId: Long? get() = mediaMetadata.extras?.getLong(Extras.BOOK_FILE_ID, -1L)?.takeIf { it >= 0 }
