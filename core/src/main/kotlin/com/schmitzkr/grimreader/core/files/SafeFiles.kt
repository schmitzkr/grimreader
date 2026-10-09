package com.schmitzkr.grimreader.core.files

/**
 * Naming and typing rules for files whose names and extensions come from the
 * server. Nothing here trusts those strings: a hostile server must not be able
 * to pick a path or hand another app an installable package.
 */
object SafeFiles {
    /** The only types "Open with" may hand to another app. Never extended with a wildcard type. */
    private val OPENABLE: Map<String, String> = mapOf(
        "mobi" to "application/x-mobipocket-ebook",
        "prc" to "application/x-mobipocket-ebook",
        "azw" to "application/vnd.amazon.ebook",
        "azw3" to "application/vnd.amazon.ebook",
        "kfx" to "application/vnd.amazon.ebook",
        "epub" to "application/epub+zip",
        "fb2" to "application/x-fictionbook+xml",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        "cb7" to "application/x-cb7",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
    )

    /** `[a-z0-9]{0,10}` from [raw]: lower-cased, a leading dot and every other character dropped. */
    fun sanitizeExtension(raw: String?): String =
        raw.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(10)

    /** [sanitizeExtension] of [raw], or of [fallback] when nothing usable is left. */
    fun sanitizeExtension(raw: String?, fallback: String): String =
        sanitizeExtension(raw).ifEmpty { sanitizeExtension(fallback) }

    /**
     * The lower-case allow-listed extension for [raw] (leading dot and case
     * ignored), or null for anything else -- including every package format.
     */
    fun openableExtension(raw: String?): String? {
        val ext = raw.orEmpty().trim().removePrefix(".").lowercase()
        return ext.takeIf { it in OPENABLE }
    }

    /** The MIME type to open a file of extension [raw] with, or null when it must not be handed out. */
    fun openableMime(raw: String?): String? = openableExtension(raw)?.let { OPENABLE[it] }
}
