package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.Session
import com.schmitzkr.grimreader.core.api.SessionEvents
import com.schmitzkr.grimreader.core.api.SessionStore
import com.schmitzkr.grimreader.core.model.AuthTokens
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** When a 401 is a real expiry and when it is not. */
class SessionExpiryTest {
    private val server = MockWebServer()
    private val events = SessionEvents()

    private class MemoryStore(private var session: Session?) : SessionStore {
        override fun current(): Session? = session
        override fun replace(session: Session?) {
            this.session = session
        }
    }

    private fun client(store: SessionStore) = GrimmoryClient(server.url("/").toString(), store, events)

    private fun GrimmoryClient.get(path: String) =
        okHttp.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `a 401 to a request sent with no session is not an expiry`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val response = client(MemoryStore(null)).get("/api/v1/books")
        assertEquals(401, response.code)
        assertFalse(events.expired.value)
        // Nothing to refresh with, so nothing was tried.
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a rejected refresh clears the session and marks it expired`() {
        server.enqueue(MockResponse().setResponseCode(401)) // the request
        server.enqueue(MockResponse().setResponseCode(401)) // the refresh
        val store = MemoryStore(Session("old", "refresh", expiresAt = null))
        val response = client(store).get("/api/v1/books")
        assertEquals(401, response.code)
        assertTrue(events.expired.value)
        assertNull(store.current())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a refresh the server could not serve keeps the session and is not an expiry`() {
        server.enqueue(MockResponse().setResponseCode(401)) // the request
        server.enqueue(MockResponse().setResponseCode(503)) // the refresh
        val store = MemoryStore(Session("old", "refresh", expiresAt = null))
        client(store).get("/api/v1/books")
        assertFalse(events.expired.value)
        assertEquals("old", store.current()?.accessToken)
    }

    @Test
    fun `a new session or a deliberate sign-out clears the expiry`() {
        val client = client(MemoryStore(null))
        events.signalExpired()
        assertTrue(events.expired.value)
        client.storeTokens(AuthTokens("new", "refresh"))
        assertFalse(events.expired.value)

        events.signalExpired()
        client.clearSession()
        assertFalse(events.expired.value)
    }

    @Test
    fun `fetchBytes carries the bearer and returns the body`() {
        server.enqueue(MockResponse().setBody("art"))
        val client = client(MemoryStore(Session("tok", "refresh", expiresAt = null)))
        val bytes = runBlocking { client.fetchBytes(server.url("/api/v1/media/book/1/cover").toString()) }
        assertArrayEquals("art".toByteArray(), bytes)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `fetchBytes fails on a refused request`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val client = client(MemoryStore(Session("tok", "refresh", expiresAt = null)))
        assertThrows(IOException::class.java) {
            runBlocking { client.fetchBytes(server.url("/api/v1/media/book/1/cover").toString()) }
        }
    }
}
