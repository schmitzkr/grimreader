package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.data.PendingExpiry
import com.schmitzkr.grimreader.data.WrappedFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCipherTest {
    private val iv = ByteArray(12) { it.toByte() }
    private val ct = ByteArray(40) { (it * 3).toByte() }

    @Test fun `wrapped value round trips`() {
        val parts = WrappedFormat.parse(WrappedFormat.serialise(iv, ct))
        assertNotNull(parts)
        assertArrayEquals(iv, parts!!.iv)
        assertArrayEquals(ct, parts.ciphertext)
    }

    @Test fun `legacy plaintext tokens are not wrapped`() {
        assertNull(WrappedFormat.parse("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln"))
        assertNull(WrappedFormat.parse("opaque-token"))
        assertFalse(WrappedFormat.isWrapped(""))
    }

    @Test fun `malformed wrapped values are rejected`() {
        assertNull(WrappedFormat.parse("gr1:onlyone"))
        assertNull(WrappedFormat.parse("gr1:a:b:c"))
        assertNull(WrappedFormat.parse("gr1:!!!:???"))
        // Wrong IV length.
        assertNull(WrappedFormat.parse(WrappedFormat.serialise(ByteArray(8), ct)))
        // Empty ciphertext.
        assertNull(WrappedFormat.parse(WrappedFormat.serialise(iv, ByteArray(0))))
    }

    @Test fun `pending entries expire after ten minutes`() {
        val now = 1_000_000_000L
        assertFalse(PendingExpiry.isStale(now - 9 * 60_000L, now))
        assertFalse(PendingExpiry.isStale(now - PendingExpiry.MAX_AGE_MS, now))
        assertTrue(PendingExpiry.isStale(now - PendingExpiry.MAX_AGE_MS - 1, now))
    }

    @Test fun `pending entries stamped in the far future are stale`() {
        val now = 1_000_000_000L
        assertTrue(PendingExpiry.isStale(now + PendingExpiry.MAX_AGE_MS + 1, now))
        assertEquals(false, PendingExpiry.isStale(now + 1000, now))
    }
}
