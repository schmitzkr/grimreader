package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.LocalProgress
import com.schmitzkr.grimreader.core.api.audiobookProgressBody
import com.schmitzkr.grimreader.core.api.parseAudiobookProgress
import com.schmitzkr.grimreader.core.api.resolveProgress
import com.schmitzkr.grimreader.core.api.SaveOutcome
import com.schmitzkr.grimreader.core.api.ServerProgress
import com.schmitzkr.grimreader.core.api.saveOutcome
import com.schmitzkr.grimreader.core.model.AudiobookProgress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LocalProgressTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val localBody = audiobookProgressBody(AudiobookProgress(positionMs = 5_000, percentage = 5.0), bookFileId = 9, json)
    private val serverBody = buildJsonObject {
        put("audiobookProgress", buildJsonObject { put("positionMs", 9_000); put("percentage", 9.0) })
    }

    private val pendingLocal = LocalProgress(1, "audiobook", localBody, updatedAt = 1, pending = true)
    private val settledLocal = pendingLocal.copy(pending = false)

    @Test
    fun `a pending local save beats the server`() {
        assertSame(localBody, resolveProgress(pendingLocal, ServerProgress.Found(serverBody)))
        assertSame(localBody, resolveProgress(pendingLocal, ServerProgress.None))
        assertSame(localBody, resolveProgress(pendingLocal, ServerProgress.Unreachable))
    }

    @Test
    fun `a settled local copy yields to the server when it answers`() {
        assertSame(serverBody, resolveProgress(settledLocal, ServerProgress.Found(serverBody)))
    }

    @Test
    fun `the server having no progress beats a settled local copy`() {
        // A reset on the web must not be undone by what the phone remembered.
        assertNull(resolveProgress(settledLocal, ServerProgress.None))
        assertNull(resolveProgress(null, ServerProgress.None))
    }

    @Test
    fun `the local copy stands in only when the server is unreachable`() {
        assertSame(localBody, resolveProgress(settledLocal, ServerProgress.Unreachable))
        assertNull(resolveProgress(null, ServerProgress.Unreachable))
    }

    @Test
    fun `a rejected save is never retried, auth and transport failures are`() {
        assertEquals(SaveOutcome.ACCEPTED, saveOutcome(200))
        assertEquals(SaveOutcome.ACCEPTED, saveOutcome(204))
        for (code in listOf(400, 403, 404, 409, 422)) assertEquals("status $code", SaveOutcome.REJECTED, saveOutcome(code))
        for (code in listOf(401, 408, 429, 500, 502, 503)) assertEquals("status $code", SaveOutcome.RETRY, saveOutcome(code))
    }

    @Test
    fun `a saved body parses back like a server response`() {
        assertEquals(5_000L, parseAudiobookProgress(localBody, json)!!.positionMs)
    }
}
