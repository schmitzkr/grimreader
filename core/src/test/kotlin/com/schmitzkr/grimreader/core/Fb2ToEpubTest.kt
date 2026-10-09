package com.schmitzkr.grimreader.core

import com.schmitzkr.grimreader.core.fb2.Fb2ToEpub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test
    fun `binaries whose ids fold to the same file name do not abort the conversion`() {
        val xml = fb2.replace("<binary id=\"pic.png\"", "<binary id=\"pic 1.png\" content-type=\"image/png\">iVBORw0KGgo=</binary><binary id=\"pic_1.png\"")
        val e = convert(xml.toByteArray())
        assertEquals(1, e.keys.count { it == "OEBPS/images/pic_1.png" })
    }

    @Test
    fun `body title and epigraph survive and bare title text renders`() {
        val xml = fb2.replace("<body><title><p>Sample</p></title>", "<body><title><p>Part One</p></title><epigraph><p>An epigraph.</p></epigraph>")
            .replace("<section><title><p>Two</p></title>", "<section><title>Chapter 2</title>")
        val e = convert(xml.toByteArray())
        val one = e["OEBPS/text/chap001.xhtml"]!!.decodeToString()
        assertTrue(one.contains("<h1>Part One</h1>"))
        assertTrue(one.contains("An epigraph."))
        assertTrue(e["OEBPS/text/chap002.xhtml"]!!.decodeToString().contains("<h2>Chapter 2</h2>"))
    }

    @Test
    fun `a DOCTYPE is rejected`() {
        val xml = """<?xml version="1.0"?>
<!DOCTYPE FictionBook [<!ENTITY x "boom">]>
<FictionBook><description/><body><section><p>&x;</p></section></body></FictionBook>"""
        val e = assertThrows(IllegalArgumentException::class.java) { convert(xml.toByteArray()) }
        assertTrue(e.message!!.contains("DOCTYPE"))
    }

    @Test
    fun `a zip that inflates past the cap is rejected`() {
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { z ->
            z.putNextEntry(ZipEntry("big.fb2"))
            val chunk = ByteArray(1024 * 1024)
            repeat(65) { z.write(chunk) }
            z.closeEntry()
        }
        assertTrue(zipped.size() < Fb2ToEpub.MAX_FB2_BYTES / 100)
        val e = assertThrows(IllegalArgumentException::class.java) { convert(zipped.toByteArray()) }
        assertTrue(e.message!!.contains("larger than"))
    }

    @Test
    fun `an oversized binary is skipped and the rest converts`() {
        val big = "AAAA".repeat(2048) // 6 KiB decoded, over the 1 KiB limit used here
        val xml = fb2.replace("</FictionBook>", """<binary id="huge.png" content-type="image/png">$big</binary></FictionBook>""")
        val out = ByteArrayOutputStream()
        Fb2ToEpub.convert(xml.toByteArray(), out, Fb2ToEpub.Limits(maxBinaryBytes = 1024))
        val e = entries(out.toByteArray())
        assertFalse("OEBPS/images/huge.png" in e)
        assertTrue("OEBPS/images/pic.png" in e)
        assertTrue("OEBPS/text/chap001.xhtml" in e)
    }

    @Test
    fun `nesting beyond the depth bound is rejected`() {
        val depth = Fb2ToEpub.MAX_DEPTH + 40
        val xml = "<FictionBook><description/><body>" + "<section>".repeat(depth) + "<p>x</p>" + "</section>".repeat(depth) + "</body></FictionBook>"
        val e = assertThrows(IllegalArgumentException::class.java) { convert(xml.toByteArray()) }
        assertTrue(e.message!!.contains("deep"))
    }
}
