package com.live.tv.media

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Online manifests and segments use routing rules without opening a disk media cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object VodMediaHttp {
    fun factory(context: Context, spec: JSONObject, previewRequest: VodRequest? = null): DataSource.Factory {
        val origin = spec.getString("url").toHttpUrl()
        require(origin.username.isEmpty() && origin.password.isEmpty()) { "Credentials must be request headers" }
        val headers = linkedMapOf<String, String>()
        spec.optJSONObject("headers")?.let { values ->
            values.keys().forEach { name -> headers[name] = values.getString(name) }
        }
        val network = OkHttpClient.Builder().followSslRedirects(false).addNetworkInterceptor { chain ->
            val request = chain.request()
            val sameOrigin = request.url.scheme == origin.scheme && request.url.host == origin.host && request.url.port == origin.port
            val builder = request.newBuilder()
            if (!sameOrigin) headers.keys.filterNot { portable(it) }.forEach { builder.removeHeader(it) }
            chain.proceed(builder.build())
        }.apply {
            if (previewRequest != null) eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) {
                    try { previewRequest.attach(call) } catch (_: VodException) { call.cancel() }
                }
                override fun callEnd(call: okhttp3.Call) { previewRequest.detach(call) }
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) { previewRequest.detach(call) }
            })
        }.build()
        return OkHttpDataSource.Factory(MediaNetwork.get(context).client(network)).setDefaultRequestProperties(headers)
    }

    private fun portable(name: String) = name.equals("User-Agent", true) || name.equals("Referer", true) || name.equals("Origin", true)
}
