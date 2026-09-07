package com.schmitzkr.grimreader.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/*
 * Grimmory's response shapes, confirmed against its Java source (package
 * org.booklore, v3.3.3). Dates stay as the strings the server sends and are
 * parsed on demand by [parseServerInstant], because the server mixes
 * Instant (`...Z`) and LocalDateTime (no zone) depending on the DTO.
 */

/** `AccessTokenDto`: [expires] is the access token's lifetime in seconds. */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expires: Long? = null,
)

@Serializable
data class Library(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val bookCount: Int = 0,
)

/** `AppBookSummary` and `AppBookDetail` share this; detail-only fields are nullable. */
@Serializable
data class Book(
    val id: Long,
    val title: String,
    val thumbnailUrl: String? = null,
    val coverUpdatedOn: String? = null,
    val audiobookCoverUpdatedOn: String? = null,
    val primaryFileId: Long? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesNumber: Double? = null,
    val libraryId: Long? = null,
    val narrator: String? = null,
    val description: String? = null,
    val subtitle: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val categories: List<String> = emptyList(),
    val personalRating: Int? = null,
    val libraryName: String? = null,
    /** AUDIOBOOK, EPUB, PDF, CBX, FB2, MOBI or AZW3. */
    val primaryFileType: String? = null,
    /** 0–100; null for audiobooks (the server never fills it for them). */
    val readProgress: Double? = null,
    /** One of Grimmory's `ReadStatus` names. */
    val readStatus: String? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
    /** Detail only. */
    val files: List<BookFile> = emptyList(),
) {
    val isAudiobook: Boolean get() = primaryFileType == "AUDIOBOOK"

    /** 2:3 for books, square for audiobook art. */
    val coverAspectRatio: Float get() = if (isAudiobook) 1f else 2f / 3f

    /** Cache-busting stamp for the cover URL; null when neither date is known. */
    val coverVersion: String?
        get() = listOfNotNull(coverUpdatedOn, audiobookCoverUpdatedOn)
            .mapNotNull { parseServerInstant(it) }
            .maxOrNull()
            ?.toEpochMilli()
            ?.toString()

    val normalizedReadProgress: Double? get() = readProgress?.let { it / 100 }
    val isFinished: Boolean get() = readStatus == "READ"

    val lastReadInstant: Instant? get() = lastReadTime?.let { parseServerInstant(it) }
    val addedInstant: Instant? get() = addedOn?.let { parseServerInstant(it) }

    val primaryFileIsEbook: Boolean get() = primaryFileType in EBOOK_FILE_TYPES

    /** The ebook file to read: the primary one if it is an ebook, else the first ebook. */
    val ebookFileId: Long?
        get() {
            val ebooks = files.filter { it.bookType in EBOOK_FILE_TYPES }
            return (ebooks.firstOrNull { it.isPrimary } ?: ebooks.firstOrNull())?.id
        }

    fun fileIdFor(format: PageFormat): Long? {
        val matches = files.filter { it.bookType == format.bookType }
        return (matches.firstOrNull { it.isPrimary } ?: matches.firstOrNull())?.id
    }

    /**
     * The file id to pass to the per-file download endpoint, or null to use
     * the plain book download: the server refuses its own primary file
     * through the additional-file route.
     */
    fun downloadFileId(fileId: Long?): Long? {
        if (fileId == null || fileId == primaryFileId) return null
        return if (files.any { it.id == fileId && it.isPrimary }) null else fileId
    }

    companion object {
        val EBOOK_FILE_TYPES = setOf("EPUB", "FB2", "MOBI", "AZW3")
    }
}

@Serializable
data class BookFile(
    val id: Long,
    val bookType: String? = null,
    @SerialName("primary") val isPrimary: Boolean = false,
    val folderBased: Boolean = false,
    val fileName: String? = null,
    val extension: String? = null,
    val fileSizeKb: Long? = null,
    /** False for a supplementary file (a cover scan, a sample chapter) rather than the book itself. */
    @SerialName("book") val isBook: Boolean = true,
) {
    /** How the app opens this file, or null when it can only hand it to another app. */
    val reader: FileReader?
        get() = when (bookType) {
            "AUDIOBOOK" -> FileReader.AUDIO
            "EPUB", "FB2" -> FileReader.EPUB
            "PDF" -> FileReader.PDF
            "CBX" -> FileReader.CBX
            else -> null
        }

    val displayName: String get() = fileName ?: (bookType ?: extension ?: "File")
}

enum class FileReader { AUDIO, EPUB, PDF, CBX }

/** Comics and PDFs share one page-based progress shape. */
enum class PageFormat(val bookType: String, val progressKey: String) {
    CBX("CBX", "cbxProgress"),
    PDF("PDF", "pdfProgress"),
}

@Serializable
data class AudiobookInfo(
    val bookId: Long,
    val bookFileId: Long? = null,
    val narrator: String? = null,
    val durationMs: Long,
    val folderBased: Boolean = false,
    val chapters: List<AudiobookChapter> = emptyList(),
    val tracks: List<AudiobookTrack> = emptyList(),
)

@Serializable
data class AudiobookChapter(
    val index: Int,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
)

@Serializable
data class AudiobookTrack(
    val index: Int,
    val fileName: String,
    val title: String,
    val durationMs: Long,
    val cumulativeStartMs: Long,
)

@Serializable
data class AudiobookProgress(
    val positionMs: Long,
    val trackIndex: Int? = null,
    val trackPositionMs: Long? = null,
    /** 0–100. */
    val percentage: Double,
)

@Serializable
data class EpubProgress(
    val cfi: String,
    /** 0–100. */
    val percentage: Double,
)

@Serializable
data class PageProgress(
    /** 1-based page number as the server stores it. */
    val page: Int,
    /** 0–100. */
    val percentage: Double,
)

@Serializable
data class Series(
    val seriesName: String,
    val bookCount: Int,
    val authors: List<String> = emptyList(),
    val booksRead: Int = 0,
    val coverBooks: List<SeriesCoverBook> = emptyList(),
)

@Serializable
data class SeriesCoverBook(
    val bookId: Long,
    val coverUpdatedOn: String? = null,
    val seriesNumber: Double? = null,
    val primaryFileType: String? = null,
) {
    val coverVersion: String?
        get() = coverUpdatedOn?.let { parseServerInstant(it) }?.toEpochMilli()?.toString()
}

@Serializable
data class Bookmark(
    val id: Long,
    val bookId: Long,
    val cfi: String? = null,
    val positionMs: Long? = null,
    val trackIndex: Int? = null,
    val title: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class Author(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val description: String? = null,
    val hasPhoto: Boolean = false,
)

@Serializable
data class Shelf(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val icon: String? = null,
    val publicShelf: Boolean = false,
)

@Serializable
data class MagicShelf(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val iconType: String? = null,
    val publicShelf: Boolean = false,
)

@Serializable
data class CurrentUser(
    val id: Long,
    val username: String,
    val name: String? = null,
    val email: String? = null,
    val provisioningMethod: String? = null,
    val permissions: UserPermissions? = null,
    val userSettings: UserSettings? = null,
) {
    val displayName: String get() = name?.trim().takeUnless { it.isNullOrEmpty() } ?: username
    val signedInWithSso: Boolean get() = provisioningMethod == "OIDC"
}

@Serializable
data class UserPermissions(
    @SerialName("admin") val isAdmin: Boolean = false,
    val canDownload: Boolean = false,
    val canUpload: Boolean = false,
    val canAccessUserStats: Boolean = false,
)

@Serializable
data class UserSettings(
    val dashboardConfig: DashboardConfig? = null,
)

@Serializable
data class DashboardConfig(
    val scrollers: List<DashboardScroller> = emptyList(),
)

@Serializable
data class DashboardScroller(
    val id: String? = null,
    val type: String,
    val title: String? = null,
    val enabled: Boolean = true,
    val order: Int = 0,
    val maxItems: Int? = null,
    val magicShelfId: Long? = null,
    val sortField: String? = null,
    val sortDirection: String? = null,
)

@Serializable
data class PublicSettings(
    val oidcEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
    val remoteAuthEnabled: Boolean = false,
    val oidcProviderDetails: OidcProviderDetails? = null,
)

@Serializable
data class OidcProviderDetails(
    val providerName: String? = null,
    val clientId: String? = null,
    val issuerUri: String? = null,
)

@Serializable
data class CountedOption(
    val name: String,
    val count: Int = 0,
)

@Serializable
data class FilterOptions(
    val authors: List<CountedOption> = emptyList(),
    val fileTypes: List<CountedOption> = emptyList(),
    val readStatuses: List<CountedOption> = emptyList(),
    val series: List<CountedOption> = emptyList(),
    val narrators: List<CountedOption> = emptyList(),
    val categories: List<CountedOption> = emptyList(),
)

/** The `/app` list endpoints wrap their items in a page. */
@Serializable
data class PageResponse<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
)

/**
 * Grimmory sends `Instant`s as `2026-09-01T10:00:00Z` and `LocalDateTime`s
 * as `2026-09-01T10:00:00`; both mean the server's clock, so a missing
 * zone is read as UTC. A date-only string (`2021-03-09`) is a midnight.
 */
fun parseServerInstant(text: String): Instant? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return runCatching { Instant.parse(t) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
}
