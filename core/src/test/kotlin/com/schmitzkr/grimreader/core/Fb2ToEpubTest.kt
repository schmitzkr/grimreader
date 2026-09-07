package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.fb2.Fb2ToEpub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Fb2ToEpubTest {
    private val fb2 = """<?xml version="1.0" encoding="utf-8"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info>
<author><first-name>Ann</first-name><last-name>Writer</last-name></author>
<book-title>Sample &amp; Co</book-title><lang>en</lang>
<coverpage><image l:href="#cover.jpg"/></coverpage>
</title-info></description>
<body><title><p>Sample</p></title>
<section id="s1"><title><p>One</p><p>The start</p></title><p>Hello <emphasis>there</emphasis><a l:href="#n1" type="note">1</a>.</p><empty-line/><p id="p2">Second &lt;line&gt;.</p></section>
<section><title><p>Two</p></title><p>More.</p><image l:href="#pic.png"/><poem><stanza><v>A verse</v></stanza></poem></section>
</body>
<body name="notes"><title><p>Notes</p></title><section id="n1"><title><p>1</p></title><p>A note.</p></section></body>
<binary id="cover.jpg" content-type="image/jpeg">/9j/4AAQ</binary>
<binary id="pic.png" content-type="image/png">iVBORw0KGgo=</binary>
</FictionBook>"""

    private fun entries(epub: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
            var e = zip.nextEntry
            while (e != null) { out[e.name] = zip.readBytes(); e = zip.nextEntry }
        }
        return out
    }

    private fun convert(bytes: ByteArray): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        Fb2ToEpub.convert(bytes, out)
        return entries(out.toByteArray())
    }

    @Test
    fun `produces a well-formed epub skeleton with mimetype first`() {
        val e = convert(fb2.toByteArray())
        assertEquals("mimetype", e.keys.first())
        assertEquals("application/epub+zip", e["mimetype"]!!.decodeToString())
        assertTrue("META-INF/container.xml" in e)
        val opf = e["OEBPS/content.opf"]!!.decodeToString()
        assertTrue(opf.contains("<dc:title>Sample &amp; Co</dc:title>"))
        assertTrue(opf.contains("<dc:creator>Ann Writer</dc:creator>"))
        assertTrue(opf.contains("""properties="cover-image""""))
        assertTrue(opf.contains("""<meta name="cover" content="img-cover.jpg"/>"""))
    }

    @Test
    fun `one chapter per section plus the notes, linked in the nav`() {
        val e = convert(fb2.toByteArray())
        assertEquals(listOf("OEBPS/text/chap001.xhtml", "OEBPS/text/chap002.xhtml", "OEBPS/text/chap003.xhtml"), e.keys.filter { it.startsWith("OEBPS/text/") })
        val nav = e["OEBPS/nav.xhtml"]!!.decodeToString()
        assertTrue(nav.contains(">One The start<"))
        assertTrue(nav.contains(">Two<"))
        assertTrue(nav.contains(">Notes<"))
    }

    @Test
    fun `inline markup, anchors, note links and images are rewritten`() {
        val e = convert(fb2.toByteArray())
        val one = e["OEBPS/text/chap001.xhtml"]!!.decodeToString()
        assertTrue(one.contains("<h2>One<br/>The start</h2>"))
        assertTrue(one.contains("Hello <em>there</em><a href=\"chap003.xhtml#n1\">1</a>."))
        assertTrue(one.contains("<p id=\"p2\">Second &lt;line&gt;.</p>"))
        assertTrue(one.contains("<section id=\"s1\">"))
        val two = e["OEBPS/text/chap002.xhtml"]!!.decodeToString()
        assertTrue(two.contains("""<img src="../images/pic.png""""))
        assertTrue(two.contains("""<p class="v">A verse</p>"""))
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), e["OEBPS/images/pic.png"])
    }

    @Test
    fun `a zipped fb2 is unwrapped first`() {
        val zipped = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                z.putNextEntry(ZipEntry("book.fb2")); z.write(fb2.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val e = convert(zipped)
        assertTrue("OEBPS/text/chap003.xhtml" in e)
    }
}
