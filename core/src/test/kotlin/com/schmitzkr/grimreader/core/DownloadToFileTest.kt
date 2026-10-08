package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.Session
import com.schmitzkr.grimreader.core.api.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DownloadToFileTest {
    private val store = object : SessionStore {
        override fun current(): Session? = null
        override fun replace(session: Session?) {}
    }
    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var client: GrimmoryClient

    @Before
    fun setUp() {
        server.start()
        dir = Files.createTempDirectory("download").toFile()
        client = GrimmoryClient(server.url("/").toString(), store)
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun url() = server.url("/api/v1/books/1/download").toString()

    @Test
    fun `a complete transfer lands at the target with no part file left`() = runBlocking {
        val payload = ByteArray(300_000) { it.toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val target = File(dir, "book.epub")
        val fractions = mutableListOf<Float>()

        val written = client.downloadToFile(url(), target) { fractions += it }

        assertEquals(payload.size.toLong(), written)
        assertArrayEquals(payload, target.readBytes())
        assertFalse(GrimmoryClient.partFile(target).exists())
        assertEquals(1f, fractions.last())
    }

    @Test
    fun `cancelling the caller stops the transfer at once and leaves no target`() = runBlocking {
        // 64 KiB every 100 ms: left to run, this transfer takes over ten
        // seconds, and the client's read timeout is twenty. A cancel that only
        // took effect once the socket gave up would blow the bound below.
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(8 * 1024 * 1024)))
                .throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS),
        )
        val target = File(dir, "book.epub")
        val firstBytes = CompletableDeferred<Unit>()
        val job = launch { client.downloadToFile(url(), target) { firstBytes.complete(Unit) } }
        firstBytes.await()

        val started = System.nanoTime()
        job.cancelAndJoin()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("cancel took $tookMs ms", tookMs < 3_000)
        assertTrue(job.isCancelled)
        assertFalse(target.exists())
    }

    @Test
    fun `a transfer the server cuts short fails and drops its part file`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(ByteArray(200_000)))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val target = File(dir, "book.epub")

        val error = runCatching { client.downloadToFile(url(), target) }.exceptionOrNull()

        assertTrue("got $error", error is IOException)
        assertFalse(target.exists())
        assertFalse(GrimmoryClient.partFile(target).exists())
    }

    @Test
    fun `a non-2xx answer is an HttpException carrying the code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))
        val target = File(dir, "book.epub")

        val error = runCatching { client.downloadToFile(url(), target) }.exceptionOrNull()

        assertTrue("got $error", error is HttpException)
        assertEquals(404, (error as HttpException).code())
        assertFalse(target.exists())
        assertFalse(GrimmoryClient.partFile(target).exists())
    }
}
