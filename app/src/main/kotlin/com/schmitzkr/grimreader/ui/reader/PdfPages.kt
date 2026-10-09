package com.schmitzkr.grimreader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a PDF's pages with the platform renderer, one at a time (it is
 * not thread-safe), keeping the last few bitmaps. Pages are rendered at the
 * requested width so they are crisp on the screen they are shown on.
 */
class PdfPages(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    // Keyed by (page, width) so thumbnails and full-width renders never replace each
    // other, and sized by bytes so a few full-width pages on a big screen can't exhaust the heap.
    private val cache = object : LruCache<Pair<Int, Int>, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Pair<Int, Int>, value: Bitmap) = value.byteCount
    }

    val pageCount: Int get() = renderer.pageCount

    /** Width ÷ height of page [index], for sizing before it is rendered. */
    suspend fun aspectRatio(index: Int): Float = try {
        lock.withLock {
            check(!closed.get()) { "PdfPages is closed" }
            renderer.openPage(index).use { page ->
                // Same sanity bounds as render(), so a hostile MediaBox can't skew layout either.
                if (page.width > 0 && page.height > 0) {
                    (page.width.toFloat() / page.height).coerceAtLeast(MIN_ASPECT)
                } else {
                    DEFAULT_ASPECT
                }
            }
        }
    } finally {
        releaseIfClosedAndIdle()
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        cache.get(index to widthPx)?.let { return@withContext it }
        try {
            lock.withLock {
                check(!closed.get()) { "PdfPages is closed" }
                renderer.openPage(index).use { page ->
                    // The page's own size is untrusted: bound the bitmap (height <= 4x width,
                    // <= 16 M pixels) rather than letting an extreme MediaBox request gigabytes.
                    check(page.width > 0 && page.height > 0) { "PDF page has no size" }
                    var w = widthPx.coerceAtLeast(1)
                    var h = (w.toLong() * page.height / page.width).coerceIn(1L, w * 4L)
                    if (w * h > MAX_PIXELS) {
                        w = kotlin.math.sqrt(MAX_PIXELS.toDouble() * w / h).toInt().coerceAtLeast(1)
                        h = (w.toLong() * page.height / page.width).coerceIn(1L, w * 4L)
                    }
                    val bitmap = Bitmap.createBitmap(w, h.toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(index to widthPx, bitmap)
                    bitmap
                }
            }
        } finally {
            releaseIfClosedAndIdle()
        }
    }

    /**
     * PdfRenderer.close() throws while a page is open, so it must never run while a render
     * holds the lock. close() only flags; whoever can take the lock afterwards (here, or the
     * render that was in flight) does the actual release.
     */
    override fun close() {
        closed.set(true)
        releaseIfClosedAndIdle()
    }

    private fun releaseIfClosedAndIdle() {
        if (!closed.get() || !lock.tryLock()) return
        try {
            if (released.compareAndSet(false, true)) {
                cache.evictAll()
                renderer.close()
                descriptor.close()
            }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val MAX_PIXELS = 16L * 1024 * 1024
        const val MIN_ASPECT = 0.25f // height <= 4x width, as in render()
        const val DEFAULT_ASPECT = 0.7071f
        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(32L shl 20, 128L shl 20).toInt()
    }
}
