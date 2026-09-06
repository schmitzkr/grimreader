package com.schmitzkr.grimreader

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.ClientHolder
import com.schmitzkr.grimreader.data.SessionRepository
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Call
import okhttp3.Request
import javax.inject.Inject

@HiltAndroidApp
class GrimReaderApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var clientHolder: ClientHolder
    @Inject lateinit var auth: AuthRepository
    @Inject lateinit var sessions: SessionRepository

    override fun onCreate() {
        super.onCreate()
        auth.start()
        sessions.start()
    }

    /**
     * Covers are fetched through the API client's OkHttp, so they carry the
     * bearer token and follow a server change without a restart.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            Call.Factory { request: Request ->
                                clientHolder.current().okHttp.newCall(request)
                            }
                        },
                    ),
                )
            }
            .crossfade(true)
            .build()
}

val Context.app: GrimReaderApp get() = applicationContext as GrimReaderApp
