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
    fun `hostile extensions and file names stay inside the book directory`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "dl-names-test/42")
        val hostile = listOf("a.../../../evil", "x.p/../../q", "x.e p u b", "x.\u00e9\u00e8\u4e2d", "x.${"a".repeat(40)}")
        for (name in hostile) {
            val file = java.io.File(dir, "track_1${DownloadManager.extensionOf(name)}")
            assertEquals(name, dir.canonicalFile, file.canonicalFile.parentFile)
        }
        assertEquals(".epub", DownloadManager.extensionOf("a.EPUB"))
        assertEquals("", DownloadManager.extensionOf("a.\u00e9"))
        assertEquals(10, DownloadManager.extensionOf(hostile.last()).length - 1)
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
