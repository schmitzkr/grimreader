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

/**
 * Renders a PDF's pages with the platform renderer, one at a time (it is
 * not thread-safe), keeping the last few bitmaps. Pages are rendered at the
 * requested width so they are crisp on the screen they are shown on.
 */
class PdfPages(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val lock = Mutex()
    private val cache = object : LruCache<Int, Bitmap>(8) {
        override fun sizeOf(key: Int, value: Bitmap) = 1
    }

    val pageCount: Int get() = renderer.pageCount

    /** Width ÷ height of page [index], for sizing before it is rendered. */
    suspend fun aspectRatio(index: Int): Float = lock.withLock {
        renderer.openPage(index).use { it.width.toFloat() / it.height }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap = withContext(Dispatchers.IO) {
        cache.get(index)?.takeIf { it.width == widthPx }?.let { return@withContext it }
        lock.withLock {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(index, bitmap)
                bitmap
            }
        }
    }

    override fun close() {
        cache.evictAll()
        renderer.close()
        descriptor.close()
    }
}
