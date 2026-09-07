package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.data.DownloadManager
import com.schmitzkr.grimreader.ui.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadNamesTest {
    @Test
    fun `track files keep the server's extension`() {
        assertEquals(".m4b", DownloadManager.extensionOf("01 - Chapter One.m4b"))
        assertEquals("", DownloadManager.extensionOf("noext"))
        assertEquals("", DownloadManager.extensionOf(".hidden"))
        assertEquals("", DownloadManager.extensionOf("trailing."))
    }

    @Test
    fun `sizes read the way people say them`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("312 MB", formatBytes(312L * 1024 * 1024))
        assertEquals("2.3 GB", formatBytes((2.3 * 1024 * 1024 * 1024).toLong()))
    }
}
