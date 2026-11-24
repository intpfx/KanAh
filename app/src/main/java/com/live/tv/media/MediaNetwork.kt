package com.live.tv.media

import android.content.Context
import okhttp3.OkHttpClient
import java.net.Proxy

/** Application HTTP clients. Requests always go direct; no proxy routing or WebView override. */
class MediaNetwork private constructor() {
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder().proxy(Proxy.NO_PROXY).build()

    companion object {
        @Volatile private var instance: MediaNetwork? = null
        @JvmStatic fun get(@Suppress("UNUSED_PARAMETER") context: Context): MediaNetwork =
            instance ?: synchronized(this) { instance ?: MediaNetwork().also { instance = it } }
    }
}
