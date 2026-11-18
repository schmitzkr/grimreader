package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.Session
import com.schmitzkr.grimreader.core.api.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ExistsTest {
    private val store = object : SessionStore {
        override fun current(): Session? = null
        override fun replace(session: Session?) {}
    }
    private val server = MockWebServer()
    private lateinit var client: GrimmoryClient

    @Before
    fun setUp() {
        server.start()
        client = GrimmoryClient(server.url("/").toString(), store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = client.coverUrl(7, audiobook = true, version = null)

    @Test
    fun `art the server has is found with a HEAD`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.exists(url()))
        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertTrue(request.path!!.endsWith("/api/v1/media/book/7/audiobook-cover"))
    }

    @Test
    fun `missing art or a server that refuses HEAD is not found`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(client.exists(url()))
        server.enqueue(MockResponse().setResponseCode(405))
        assertFalse(client.exists(url()))
    }

    @Test(expected = IOException::class)
    fun `an unreachable server throws rather than answering no`(): Unit = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        client.exists(url())
    }
}
