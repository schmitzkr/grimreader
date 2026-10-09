package com.schmitzkr.grimreader.core.fb2

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns a FictionBook 2 file into a small EPUB 3 so the EPUB reader can
 * show it: one XHTML chapter per top-level section, notes as a final
 * chapter, embedded binaries as images, links between them rewritten.
 * The server has no reader route for FB2, so this runs on the device.
 */
object Fb2ToEpub {

    /** Largest FB2 accepted, as stored or inflated from a `.fb2.zip`. */
    const val MAX_FB2_BYTES: Long = 64L * 1024 * 1024

    /** Largest decoded size of a single `<binary>`; bigger ones are skipped. */
    const val MAX_BINARY_BYTES: Long = 32L * 1024 * 1024

    /** Budget for all decoded `<binary>` data together; images past it are skipped. */
    const val MAX_TOTAL_BINARY_BYTES: Long = 128L * 1024 * 1024

    /** Deepest element nesting walked while collecting ids and rendering. */
    const val MAX_DEPTH: Int = 64

    /** How much of the start of the XML is scanned for a DOCTYPE. */
    private const val DOCTYPE_SCAN_BYTES = 16 * 1024

    internal class Limits(
        val maxFb2Bytes: Long = MAX_FB2_BYTES,
        val maxBinaryBytes: Long = MAX_BINARY_BYTES,
        val maxTotalBinaryBytes: Long = MAX_TOTAL_BINARY_BYTES,
        val maxDepth: Int = MAX_DEPTH,
    )

    fun convert(input: File, output: File) {
        convert(input, output, Limits())
    }

    internal fun convert(input: File, output: File, limits: Limits) {
        val bytes = input.inputStream().use { readCapped(it, limits.maxFb2Bytes, "FB2 file") }
        output.outputStream().use { convert(bytes, it, limits) }
    }

    fun convert(fb2: ByteArray, output: OutputStream) {
        convert(fb2, output, Limits())
    }

    internal fun convert(fb2: ByteArray, output: OutputStream, limits: Limits) {
        if (fb2.size > limits.maxFb2Bytes) throw IllegalArgumentException("FB2 file is larger than ${limits.maxFb2Bytes} bytes")
        val xml = unwrap(fb2, limits)
        rejectDoctype(xml)
        val doc = parse(xml)
        val book = read(doc, limits)
        write(book, output)
    }

    /** Copies [input] into memory, failing as soon as more than [max] bytes have been read. */
    private fun readCapped(input: InputStream, max: Long, what: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalArgumentException("$what is larger than $max bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Android's DocumentBuilderFactory refuses the Xerces hardening features, so
     * they cannot be relied on; a DOCTYPE is never valid FB2, so refuse it here.
     * ASCII-compatible encodings are matched directly; UTF-16 is matched after
     * dropping NULs.
     */
    private fun rejectDoctype(xml: ByteArray) {
        val head = xml.copyOf(minOf(xml.size, DOCTYPE_SCAN_BYTES))
        val ascii = String(head, Charsets.ISO_8859_1)
        val flattened = ascii.replace("\u0000", "")
        if (ascii.contains("<!DOCTYPE", ignoreCase = true) || flattened.contains("<!DOCTYPE", ignoreCase = true)) {
            throw IllegalArgumentException("FB2 with a DOCTYPE declaration is not accepted")
        }
    }

    // ── Reading ───────────────────────────────────────────────────────────

    private class Chapter(val file: String, val title: String, val sources: List<Element>, val ids: MutableSet<String> = mutableSetOf())
    private class Image(val id: String, val mediaType: String, val bytes: ByteArray)
    private class Fb2Book(
        val title: String,
        val authors: List<String>,
        val language: String,
        val identifier: String,
        val coverId: String?,
        val chapters: List<Chapter>,
        val images: List<Image>,
        val maxDepth: Int,
    )

    /** A `.fb2.zip` holds one FB2 inside; anything else is the XML itself. */
    private fun unwrap(bytes: ByteArray, limits: Limits): ByteArray {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return bytes
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var fallback: ByteArray? = null
            while (entry != null) {
                if (!entry.isDirectory) {
                    val data = readCapped(zip, limits.maxFb2Bytes, "Inflated FB2")
                    if (entry.name.lowercase().endsWith(".fb2")) return data
                    if (fallback == null) fallback = data
                }
                entry = zip.nextEntry
            }
            return fallback ?: bytes
        }
    }

    private fun parse(xml: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            // Best effort: Android's factory throws here. rejectDoctype() is the guarantee.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun read(doc: Document, limits: Limits): Fb2Book {
        val root = doc.documentElement
        val description = root.child("description")
        val titleInfo = description?.child("title-info")
        val title = titleInfo?.child("book-title")?.text()?.trim().orEmpty().ifBlank { "Untitled" }
        val authors = titleInfo?.children("author")?.map { a ->
            listOf("first-name", "middle-name", "last-name").mapNotNull { a.child(it)?.text()?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                .ifBlank { a.child("nickname")?.text()?.trim().orEmpty() }
        }?.filter { it.isNotBlank() } ?: emptyList()
        val language = titleInfo?.child("lang")?.text()?.trim().orEmpty().ifBlank { "en" }
        val identifier = description?.child("document-info")?.child("id")?.text()?.trim().orEmpty()
            .ifBlank { "urn:uuid:${UUID.nameUUIDFromBytes(title.toByteArray())}" }
        val coverId = titleInfo?.child("coverpage")?.child("image")?.href()?.removePrefix("#")

        var binaryTotal = 0L
        val images = root.children("binary").mapNotNull { b ->
            val id = b.getAttribute("id").ifBlank { return@mapNotNull null }
            val type = b.getAttribute("content-type").ifBlank { "image/jpeg" }
            val encoded = b.text().trim()
            // Upper bound on the decoded size, checked before allocating it.
            val estimate = encoded.length.toLong() / 4 * 3
            if (estimate > limits.maxBinaryBytes + 3 || binaryTotal + estimate > limits.maxTotalBinaryBytes + 3) return@mapNotNull null
            val data = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return@mapNotNull null
            if (data.size > limits.maxBinaryBytes || binaryTotal + data.size > limits.maxTotalBinaryBytes) return@mapNotNull null
            binaryTotal += data.size
            Image(safeName(id), type, data)
        }.distinctBy { it.id } // ids that fold to the same file name would collide in the zip

        val chapters = mutableListOf<Chapter>()
        var n = 0
        fun add(title: String, sources: List<Element>) {
            n++
            chapters += Chapter("chap%03d.xhtml".format(n), title, sources)
        }
        root.children("body").forEach { body ->
            val isNotes = body.getAttribute("name").lowercase().let { it == "notes" || it == "comments" || it == "footnotes" }
            if (isNotes) {
                val bodyTitle = body.child("title")?.titleText().orEmpty().ifBlank { "Notes" }
                add(bodyTitle, body.children("section"))
            } else {
                val sections = body.children("section")
                if (sections.isEmpty()) {
                    add(body.child("title")?.titleText().orEmpty().ifBlank { title }, listOf(body))
                } else {
                    // The body's own title, epigraph and images ride along at the top of its first chapter.
                    val preface = body.elements().filter { it.tagName == "title" || it.tagName == "epigraph" || it.tagName == "image" }
                    sections.forEachIndexed { i, s ->
                        add(s.child("title")?.titleText().orEmpty().ifBlank { "Chapter ${i + 1}" }, if (i == 0) preface + s else listOf(s))
                    }
                }
            }
        }
        if (chapters.isEmpty()) add(title, emptyList())
        chapters.forEach { c -> c.sources.forEach { collectIds(it, c.ids, 1, limits.maxDepth) } }
        return Fb2Book(title, authors, language, identifier, coverId?.let(::safeName), chapters, images, limits.maxDepth)
    }

    private fun collectIds(el: Element, out: MutableSet<String>, depth: Int, maxDepth: Int) {
        checkDepth(depth, maxDepth)
        el.getAttribute("id").takeIf { it.isNotBlank() }?.let { out += it }
        el.elements().forEach { collectIds(it, out, depth + 1, maxDepth) }
    }

    private fun checkDepth(depth: Int, maxDepth: Int) {
        if (depth > maxDepth) throw IllegalArgumentException("FB2 is nested more than $maxDepth levels deep")
    }

    // ── Writing ───────────────────────────────────────────────────────────

    private fun write(book: Fb2Book, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            zip.put("META-INF/container.xml", CONTAINER)
            zip.put("OEBPS/content.opf", opf(book))
            zip.put("OEBPS/nav.xhtml", nav(book))
            zip.put("OEBPS/style.css", CSS)
            book.chapters.forEach { c -> zip.put("OEBPS/text/${c.file}", chapter(book, c)) }
            book.images.forEach { i -> zip.put("OEBPS/images/${i.id}", i.bytes) }
        }
    }

    private fun ZipOutputStream.put(name: String, text: String) = put(name, text.toByteArray())
    private fun ZipOutputStream.put(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun opf(book: Fb2Book): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">""").append('\n')
        append("""<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""").append('\n')
        append("<dc:identifier id=\"uid\">${esc(book.identifier)}</dc:identifier>\n")
        append("<dc:title>${esc(book.title)}</dc:title>\n")
        book.authors.forEach { append("<dc:creator>${esc(it)}</dc:creator>\n") }
        append("<dc:language>${esc(book.language)}</dc:language>\n")
        append("<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n")
        book.coverId?.let { append("<meta name=\"cover\" content=\"img-${esc(it)}\"/>\n") }
        append("</metadata>\n<manifest>\n")
        append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""").append('\n')
        append("""<item id="css" href="style.css" media-type="text/css"/>""").append('\n')
        book.chapters.forEachIndexed { i, c ->
            append("<item id=\"c$i\" href=\"text/${c.file}\" media-type=\"application/xhtml+xml\"/>\n")
        }
        book.images.forEach { img ->
            val props = if (img.id == book.coverId) " properties=\"cover-image\"" else ""
            append("<item id=\"img-${esc(img.id)}\" href=\"images/${esc(img.id)}\" media-type=\"${esc(img.mediaType)}\"$props/>\n")
        }
        append("</manifest>\n<spine>\n")
        book.chapters.indices.forEach { append("<itemref idref=\"c$it\"/>\n") }
        append("</spine>\n</package>\n")
    }

    private fun nav(book: Fb2Book): String = buildString {
        append(XHTML_HEAD.format(esc(book.title), ""))
        append("""<nav epub:type="toc" id="toc"><h1>${esc(book.title)}</h1><ol>""").append('\n')
        book.chapters.forEach { append("<li><a href=\"text/${it.file}\">${esc(it.title)}</a></li>\n") }
        append("</ol></nav>\n</body>\n</html>\n")
    }

    private fun chapter(book: Fb2Book, chapter: Chapter): String = buildString {
        append(XHTML_HEAD.format(esc(chapter.title), """<link rel="stylesheet" type="text/css" href="../style.css"/>"""))
        val ctx = RenderContext(book)
        chapter.sources.forEach { ctx.render(it, this) }
        append("\n</body>\n</html>\n")
    }

    private class RenderContext(private val book: Fb2Book) {
        private fun chapterFor(id: String): Chapter? = book.chapters.firstOrNull { id in it.ids }

        private var depth = 0

        fun render(el: Element, sb: StringBuilder) {
            depth++
            try {
                checkDepth(depth, book.maxDepth)
                renderElement(el, sb)
            } finally {
                depth--
            }
        }

        private fun renderElement(el: Element, sb: StringBuilder) {
            when (el.tagName) {
                "p" -> block("p", el, sb)
                "v" -> block("p", el, sb, "v")
                "text-author" -> block("p", el, sb, "author")
                "date" -> block("p", el, sb, "date")
                "subtitle" -> block("h3", el, sb)
                "title" -> heading(el, sb)
                "empty-line" -> sb.append("<p class=\"empty\"> </p>\n")
                "emphasis" -> inline("em", el, sb)
                "strong" -> inline("strong", el, sb)
                "strikethrough" -> inline("s", el, sb)
                "sub", "sup", "code" -> inline(el.tagName, el, sb)
                "style" -> inline("span", el, sb)
                "a" -> link(el, sb)
                "image" -> image(el, sb)
                "section" -> { sb.append("<section${idAttr(el)}>\n"); children(el, sb); sb.append("</section>\n") }
                "poem" -> block("div", el, sb, "poem")
                "stanza" -> block("div", el, sb, "stanza")
                "epigraph" -> block("blockquote", el, sb, "epigraph")
                "cite" -> block("blockquote", el, sb)
                "annotation" -> block("div", el, sb, "annotation")
                "table", "tr", "td", "th" -> block(el.tagName, el, sb)
                "body" -> children(el, sb)
                else -> children(el, sb)
            }
        }

        private fun children(el: Element, sb: StringBuilder) {
            val nodes = el.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                when (n.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(esc(n.nodeValue))
                    Node.ELEMENT_NODE -> render(n as Element, sb)
                }
            }
        }

        private fun block(tag: String, el: Element, sb: StringBuilder, cls: String? = null) {
            sb.append('<').append(tag).append(idAttr(el))
            if (cls != null) sb.append(" class=\"").append(cls).append('"')
            sb.append('>')
            children(el, sb)
            sb.append("</").append(tag).append(">\n")
        }

        private fun inline(tag: String, el: Element, sb: StringBuilder) {
            sb.append('<').append(tag).append('>')
            children(el, sb)
            sb.append("</").append(tag).append('>')
        }

        /** A title holds `<p>` lines; one heading with line breaks between them. */
        private fun heading(el: Element, sb: StringBuilder) {
            val level = if (el.parentNode is Element && (el.parentNode as Element).tagName == "body") "h1" else "h2"
            sb.append('<').append(level).append(idAttr(el)).append('>')
            if (el.elements().none { it.tagName == "p" }) {
                // Bare text straight inside <title>.
                children(el, sb)
                sb.append("</").append(level).append(">\n")
                return
            }
            var first = true
            el.elements().forEach { line ->
                if (line.tagName == "empty-line") return@forEach
                if (!first) sb.append("<br/>")
                first = false
                children(line, sb)
            }
            sb.append("</").append(level).append(">\n")
        }

        private fun link(el: Element, sb: StringBuilder) {
            val href = el.href().orEmpty()
            val target = if (href.startsWith("#")) {
                val id = href.substring(1)
                chapterFor(id)?.let { "${it.file}#${id}" } ?: href
            } else href
            sb.append("<a href=\"").append(esc(target)).append("\">")
            children(el, sb)
            sb.append("</a>")
        }

        private fun image(el: Element, sb: StringBuilder) {
            val id = el.href()?.removePrefix("#")?.let(::safeName) ?: return
            if (book.images.none { it.id == id }) return
            val alt = el.getAttribute("alt").ifBlank { el.getAttribute("title") }
            sb.append("<div class=\"image\"><img src=\"../images/").append(esc(id)).append("\" alt=\"").append(esc(alt)).append("\"/></div>\n")
        }

        private fun idAttr(el: Element): String = el.getAttribute("id").takeIf { it.isNotBlank() }?.let { " id=\"${esc(it)}\"" } ?: ""
    }

    // ── DOM helpers ───────────────────────────────────────────────────────

    private fun Element.elements(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun Element.children(name: String): List<Element> = elements().filter { it.tagName == name }
    private fun Element.child(name: String): Element? = elements().firstOrNull { it.tagName == name }

    private fun Element.text(): String = textContent ?: ""

    private fun Element.titleText(): String = elements().filter { it.tagName == "p" }.joinToString(" ") { it.text().trim() }.ifBlank { text().trim() }

    /** `l:href`, `xlink:href` or plain `href`, whichever the file used. */
    private fun Element.href(): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName == "href" || a.nodeName.endsWith(":href")) return a.nodeValue
        }
        return null
    }

    internal fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    internal fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""

    private const val XHTML_HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><meta charset="utf-8"/><title>%s</title>%s</head>
<body>
"""

    private const val CSS = """body { line-height: 1.45; }
h1, h2, h3 { text-align: center; font-weight: 600; }
p { margin: 0 0 0.6em 0; text-indent: 1.2em; }
p.empty { text-indent: 0; }
p.v { text-indent: 0; margin: 0; }
p.author, p.date { text-align: right; font-style: italic; text-indent: 0; }
div.poem, div.stanza { margin: 0.8em 1.5em; }
blockquote { margin: 0.8em 1.5em; font-style: italic; }
div.image { text-align: center; margin: 0.8em 0; }
div.image img { max-width: 100%; max-height: 90vh; }
"""
}
