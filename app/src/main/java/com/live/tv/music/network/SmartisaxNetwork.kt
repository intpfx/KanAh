package com.live.tv.music.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

object SmartisaxNetwork {
    val catalogClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    val streamingClient: OkHttpClient by lazy {
        catalogClient.newBuilder()
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor(MusicCdnOnlyInterceptor)
            .build()
    }

    private object MusicCdnOnlyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            requireAllowed(chain.request().url)
            val response = chain.proceed(chain.request())
            return try {
                requireAllowed(response.request.url)
                response
            } catch (error: IOException) {
                response.close()
                throw error
            }
        }

        private fun requireAllowed(url: HttpUrl) {
            if (!isAllowedMusicCdnUrl(url)) {
                throw IOException("Rejected media redirect host")
            }
        }
    }

    private const val MUSIC_CDN_HOST = "music.126.net"
}

internal fun isAllowedMusicCdnUrl(url: HttpUrl): Boolean {
    val host = url.host.lowercase()
    return url.isHttps && (host == "music.126.net" || host.endsWith(".music.126.net"))
}
