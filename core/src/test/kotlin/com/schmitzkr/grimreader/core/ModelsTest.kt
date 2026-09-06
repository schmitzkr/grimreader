package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.inProgressOrder
import com.schmitzkr.grimreader.core.api.parseAudiobookProgress
import com.schmitzkr.grimreader.core.model.Book
import com.schmitzkr.grimreader.core.model.BookFile
import com.schmitzkr.grimreader.core.model.CurrentUser
import com.schmitzkr.grimreader.core.model.PageFormat
import com.schmitzkr.grimreader.core.model.PageResponse
import com.schmitzkr.grimreader.core.model.parseServerInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun `server dates parse with or without a zone`() {
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), parseServerInstant("2026-09-01T10:00:00Z"))
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), parseServerInstant("2026-09-01T10:00:00"))
        assertEquals(Instant.parse("2021-03-09T00:00:00Z"), parseServerInstant("2021-03-09"))
        assertNull(parseServerInstant("not a date"))
    }

    @Test
    fun `a paginated list is unwrapped and unknown fields are ignored`() {
        val page = json.decodeFromString(
            PageResponse.serializer(Book.serializer()),
            """{"content":[{"id":1,"title":"A","surprise":true,"readStatus":null}],"page":0,"size":100,"totalElements":1}""",
        )
        assertEquals(1, page.content.size)
        assertEquals("A", page.content.single().title)
        assertNull(page.content.single().readStatus)
    }

    @Test
    fun `admin permission uses the Lombok key`() {
        val user = json.decodeFromString(
            CurrentUser.serializer(),
            """{"id":1,"username":"kyle","name":" ","permissions":{"admin":true}}""",
        )
        assertTrue(user.permissions!!.isAdmin)
        assertEquals("kyle", user.displayName)
        assertFalse(user.signedInWithSso)
    }

    @Test
    fun `file ids prefer the primary file and never send it as an additional file`() {
        val book = Book(
            id = 1, title = "Dual", primaryFileId = 10, primaryFileType = "AUDIOBOOK",
            files = listOf(
                BookFile(10, "AUDIOBOOK", isPrimary = true),
                BookFile(11, "EPUB"),
                BookFile(12, "PDF"),
            ),
        )
        assertEquals(11L, book.ebookFileId)
        assertEquals(12L, book.fileIdFor(PageFormat.PDF))
        assertNull(book.fileIdFor(PageFormat.CBX))
        assertNull(book.downloadFileId(10))
        assertEquals(11L, book.downloadFileId(11))
        // A single-format EPUB flagged primary only through files[].primary.
        val single = Book(id = 2, title = "One", files = listOf(BookFile(20, "EPUB", isPrimary = true)))
        assertNull(single.downloadFileId(20))
    }

    @Test
    fun `cover version is the newest of the two stamps`() {
        val book = Book(
            id = 1, title = "A",
            coverUpdatedOn = "2026-01-01T00:00:00Z",
            audiobookCoverUpdatedOn = "2026-02-01T00:00:00",
        )
        assertEquals(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli().toString(), book.coverVersion)
        assertNull(Book(id = 2, title = "B").coverVersion)
    }

    @Test
    fun `continue rows order by last read, drop undated, apply the limit`() {
        val books = listOf(
            Book(1, "old", lastReadTime = "2026-09-01T10:00:00Z"),
            Book(2, "never"),
            Book(3, "new", lastReadTime = "2026-09-05T10:00:00Z"),
            Book(4, "mid", lastReadTime = "2026-09-03T10:00:00Z"),
        )
        assertEquals(listOf(3L, 4L), inProgressOrder(books, limit = 2).map { it.id })
    }

    @Test
    fun `a hollow audiobook progress row is no position`() {
        val hollow = json.decodeFromString(JsonObject.serializer(), """{"audiobookProgress":{"positionMs":null,"percentage":0}}""")
        assertNull(parseAudiobookProgress(hollow, json))
        val real = json.decodeFromString(JsonObject.serializer(), """{"audiobookProgress":{"positionMs":3500,"trackIndex":2,"percentage":12.3}}""")
        assertEquals(3500L, parseAudiobookProgress(real, json)!!.positionMs)
    }
}
