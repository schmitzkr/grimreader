package com.schmitzkr.grimreader.core.api

import com.schmitzkr.grimreader.core.model.AudiobookProgress
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.EpubProgress
import com.schmitzkr.grimreader.core.model.PageFormat
import com.schmitzkr.grimreader.core.model.PageProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Grimmory stores progress per file. The deprecated per-type fields alone
 * persist nothing for audiobooks and are dropped for an EPUB whose library
 * format priority makes an audiobook file primary, so every save sends the
 * `fileProgress` block the web client sends, with the same string-encoded
 * positions, alongside the typed field.
 */

fun audiobookProgressBody(progress: AudiobookProgress, bookFileId: Long?, json: Json): JsonObject =
    buildJsonObject {
        put("audiobookProgress", json.encodeToJsonElement(AudiobookProgress.serializer(), progress))
        if (bookFileId != null) {
            put("fileProgress", buildJsonObject {
                put("bookFileId", bookFileId)
                put("positionData", progress.positionMs.toString())
                progress.trackIndex?.let { put("positionHref", it.toString()) } ?: put("positionHref", JsonNull)
                put("progressPercent", progress.percentage)
            })
        }
    }

fun epubProgressBody(progress: EpubProgress, bookFileId: Long?, json: Json): JsonObject =
    buildJsonObject {
        put("epubProgress", json.encodeToJsonElement(EpubProgress.serializer(), progress))
        if (bookFileId != null) {
            put("fileProgress", buildJsonObject {
                put("bookFileId", bookFileId)
                put("positionData", progress.cfi)
                put("positionHref", JsonNull)
                put("progressPercent", progress.percentage)
            })
        }
    }

/** Comics and PDFs store the 1-based page as the digits alone; the server parses it with `Integer.parseInt`. */
fun pageProgressBody(progress: PageProgress, format: PageFormat, bookFileId: Long?, json: Json): JsonObject =
    buildJsonObject {
        put(format.progressKey, json.encodeToJsonElement(PageProgress.serializer(), progress))
        if (bookFileId != null) {
            put("fileProgress", buildJsonObject {
                put("bookFileId", bookFileId)
                put("positionData", progress.page.toString())
                put("positionHref", JsonNull)
                put("progressPercent", progress.percentage)
            })
        }
    }

/**
 * Reads the audiobook position out of a `/progress` response. A hollow row
 * left by an older client (lastReadTime only) maps to `positionMs: null`
 * and counts as no position.
 */
fun parseAudiobookProgress(body: JsonObject, json: Json): AudiobookProgress? {
    val node = body["audiobookProgress"] as? JsonObject ?: return null
    if (node["positionMs"] == null || node["positionMs"] is JsonNull) return null
    return runCatching { json.decodeFromJsonElement(AudiobookProgress.serializer(), node) }.getOrNull()
}

fun parseEpubProgress(body: JsonObject, json: Json): EpubProgress? {
    val node = body["epubProgress"] as? JsonObject ?: return null
    if (node["cfi"] == null || node["cfi"] is JsonNull) return null
    return runCatching { json.decodeFromJsonElement(EpubProgress.serializer(), node) }.getOrNull()
}

fun parsePageProgress(body: JsonObject, format: PageFormat, json: Json): PageProgress? {
    val node = body[format.progressKey] as? JsonObject ?: return null
    if (node["page"] == null || node["page"] is JsonNull) return null
    return runCatching { json.decodeFromJsonElement(PageProgress.serializer(), node) }.getOrNull()
}

/**
 * The Continue Reading/Listening order: books with a recorded last-read
 * time, newest first, cut to [limit]. A book with no timestamp has never
 * been opened by this user and is left out, as the web dashboard leaves it
 * out.
 */
fun inProgressOrder(books: Iterable<Book>, limit: Int): List<Book> =
    books.filter { it.lastReadInstant != null }
        .sortedByDescending { it.lastReadInstant }
        .take(limit)

/** A bookmark's fields as `POST /bookmarks` takes them. */
fun bookmarkBody(
    bookId: Long,
    title: String?,
    cfi: String? = null,
    positionMs: Long? = null,
    trackIndex: Int? = null,
    pageNumber: Int? = null,
): JsonObject = buildJsonObject {
    put("bookId", bookId)
    title?.let { put("title", it) }
    cfi?.let { put("cfi", it) }
    positionMs?.let { put("positionMs", it) }
    trackIndex?.let { put("trackIndex", it) }
    pageNumber?.let { put("pageNumber", it) }
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
