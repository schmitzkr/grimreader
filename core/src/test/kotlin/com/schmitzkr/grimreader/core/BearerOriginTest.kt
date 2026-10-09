package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.Session
import com.schmitzkr.grimreader.core.api.SessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The bearer goes to the configured server's origin and nowhere else. */
class BearerOriginTest {
    private val store = object : SessionStore {
        private var session: Session? = Session("secret-token", "refresh", null)
        override fun current(): Session? = session
        override fun replace(session: Session?) {
            this.session = session
        }
    }
    private val home = MockWebServer()
    private val foreign = MockWebServer()
    private lateinit var client: GrimmoryClient

    @Before
    fun setUp() {
        home.start()
        foreign.start()
        client = GrimmoryClient(home.url("/").toString(), store)
    }

    @After
    fun tearDown() {
        home.shutdown()
        foreign.shutdown()
    }

    @Test
    fun `a request to the configured server carries the bearer`() {
        home.enqueue(MockResponse().setBody("ok"))
        client.okHttp.newCall(Request.Builder().url(home.url("/api/v1/books")).build()).execute().close()
        assertEquals("Bearer secret-token", home.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a request to another origin carries no bearer`() {
        foreign.enqueue(MockResponse().setBody("ok"))
        client.okHttp.newCall(Request.Builder().url(foreign.url("/api/v1/books")).build()).execute().close()
        assertNull(foreign.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `fetchBytes and downloadToFile refuse a foreign origin`() {
        val dir = Files.createTempDirectory("origin").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.fetchBytes(foreign.url("/art").toString()) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.downloadToFile(foreign.url("/file").toString(), File(dir, "x")) }
            }
            assertEquals(0, foreign.requestCount)
        } finally {
            dir.deleteRecursively()
        }
    }
}
