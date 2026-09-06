package com.schmitzkr.grimreader.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import okhttp3.Call
import okhttp3.Request
import java.io.IOException

/**
 * Loads notification artwork through the authenticated OkHttp client; the
 * default loader has no bearer token and every cover would 401.
 */
@UnstableApi
class AuthBitmapLoader(
    @Suppress("unused") private val context: Context,
    private val callFactory: Call.Factory,
) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = scope.future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("Could not decode image")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        callFactory.newCall(Request.Builder().url(uri.toString()).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Cover ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("Empty cover")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Could not decode cover")
        }
    }
}
