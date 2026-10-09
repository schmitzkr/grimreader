package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.data.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoomedDownloadDirTest {
    @Test
    fun `renamed-for-deletion directories are recognised`() {
        assertTrue(DownloadManager.isDoomedDirName("42.deleting-123456789"))
        assertFalse(DownloadManager.isDoomedDirName("42"))
        assertFalse(DownloadManager.isDoomedDirName("notes"))
    }

    @Test
    fun `a doomed directory is never a numeric book id so scan cannot list it`() {
        assertNull("42.deleting-123456789".toLongOrNull())
        assertEquals(42L, "42".toLongOrNull())
    }
}
