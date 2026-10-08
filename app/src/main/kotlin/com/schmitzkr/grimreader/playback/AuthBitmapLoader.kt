package com.schmitzkr.grimreader.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.schmitzkr.grimreader.data.ClientHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import java.io.IOException

/**
 * Loads notification artwork through the API client, so every cover
 * carries the bearer token; the default loader has none and would 401.
 */
@UnstableApi
class AuthBitmapLoader(
    @Suppress("unused") private val context: Context,
    private val clients: ClientHolder,
) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("Could not decode image")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        if (uri.scheme == "file") {
            return@future BitmapFactory.decodeFile(uri.path) ?: throw IOException("Could not decode cover")
        }
        val bytes = clients.current().fetchBytes(uri.toString())
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Could not decode cover")
    }
}
