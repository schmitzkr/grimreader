package com.schmitzkr.grimreader.core

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.schmitzkr.grimreader.core.api.isAuthPath
import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.Session
import com.schmitzkr.grimreader.core.api.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientUrlsTest {
    private val store = object : SessionStore {
        override fun current(): Session? = null
        override fun replace(session: Session?) {}
    }
    private val client = GrimmoryClient("https://books.test/", store)

    @Test
    fun `media urls match the server's BookMediaController paths`() {
        assertEquals("https://books.test/api/v1/media/book/5/cover", client.coverUrl(5, audiobook = false, version = null))
        assertEquals("https://books.test/api/v1/media/book/5/audiobook-cover?v=123", client.coverUrl(5, audiobook = true, version = "123"))
        assertEquals("https://books.test/api/v1/media/book/5/cover?v=123", client.fallbackCoverUrl(5, "123"))
        assertEquals("https://books.test/api/v1/media/author/9/photo", client.authorPhotoUrl(9))
        assertEquals("https://books.test/api/v1/media/book/5/cbx/pages/3", client.comicPageUrl(5, 3))
        assertEquals("https://books.test/api/v1/audiobooks/5/track/2/stream", client.trackStreamUrl(5, 2))
    }

    @Test
    fun `only the real auth endpoints skip the bearer`() {
        val p = "/api/v1/"
        assertTrue(isAuthPath("/api/v1/auth/login", p))
        assertTrue(isAuthPath("/api/v1/auth/oidc/state", p))
        assertTrue(isAuthPath("/api/v1/public-settings", p))
        assertFalse(isAuthPath("/api/v1/app/series/auth/books", p))
        assertFalse(isAuthPath("/api/v1/app/series/public-settings", p))
    }
}
