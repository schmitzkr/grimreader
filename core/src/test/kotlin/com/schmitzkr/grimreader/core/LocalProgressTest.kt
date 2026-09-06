package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.api.LocalProgress
import com.schmitzkr.grimreader.core.api.audiobookProgressBody
import com.schmitzkr.grimreader.core.api.parseAudiobookProgress
import com.schmitzkr.grimreader.core.api.resolveProgress
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

    @Test
    fun `a pending local save beats the server`() {
        val local = LocalProgress(1, "audiobook", localBody, updatedAt = 1, pending = true)
        assertSame(localBody, resolveProgress(local, serverBody))
    }

    @Test
    fun `a synced local copy yields to the server when it answers`() {
        val local = LocalProgress(1, "audiobook", localBody, updatedAt = 1, pending = false)
        assertSame(serverBody, resolveProgress(local, serverBody))
    }

    @Test
    fun `the local copy stands in when the server is unreachable`() {
        val local = LocalProgress(1, "audiobook", localBody, updatedAt = 1, pending = false)
        assertSame(localBody, resolveProgress(local, null))
        assertNull(resolveProgress(null, null))
    }

    @Test
    fun `a saved body parses back like a server response`() {
        assertEquals(5_000L, parseAudiobookProgress(localBody, json)!!.positionMs)
    }
}
