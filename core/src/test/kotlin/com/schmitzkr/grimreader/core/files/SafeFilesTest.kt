package com.schmitzkr.grimreader.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeFilesTest {
    @Test
    fun `package formats are refused`() {
        for (e in listOf("apk", "apks", "xapk", "aab", "APK", ".apk", " apk ")) {
            assertNull(e, SafeFiles.openableMime(e))
            assertNull(e, SafeFiles.openableExtension(e))
        }
    }

    @Test
    fun `unknown and missing extensions are refused, never a wildcard`() {
        assertNull(SafeFiles.openableMime("bin"))
        assertNull(SafeFiles.openableMime(""))
        assertNull(SafeFiles.openableMime(null))
        assertNull(SafeFiles.openableMime("epub/../apk"))
    }

    @Test
    fun `allow-listed types resolve ignoring case and a leading dot`() {
        assertEquals("application/x-mobipocket-ebook", SafeFiles.openableMime("mobi"))
        assertEquals("application/epub+zip", SafeFiles.openableMime("EPUB"))
        assertEquals("application/pdf", SafeFiles.openableMime(".Pdf"))
        assertEquals("text/plain", SafeFiles.openableMime("txt"))
        assertEquals("azw3", SafeFiles.openableExtension(".AZW3"))
    }

    @Test
    fun `extensions are cut down to lower-case alphanumerics`() {
        assertEquals("epub", SafeFiles.sanitizeExtension(".EPUB"))
        assertEquals("", SafeFiles.sanitizeExtension("../.."))
        assertEquals("xy", SafeFiles.sanitizeExtension("../x y"))
        assertEquals("", SafeFiles.sanitizeExtension("éè"))
        assertEquals("abcdefghij", SafeFiles.sanitizeExtension("abcdefghijklmnop"))
        assertEquals("epub", SafeFiles.sanitizeExtension("é/", "EPUB"))
    }
}
