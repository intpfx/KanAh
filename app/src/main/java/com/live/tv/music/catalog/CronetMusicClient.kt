package com.live.tv.music.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CronetResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentType: String?,
)

/**
 * The music API is protected by a Cloudflare rule that treats OkHttp differently from a
 * browser. Cronet is Chromium's network stack, so its TLS/HTTP2 fingerprint is the same shape
 * as the browser that the public API expects. Only the catalog uses this client; media playback
 * remains on ExoPlayer/OkHttp.
 */
class CronetMusicClient(private val engine: CronetEngine) {
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "music-cronet").apply { isDaemon = true }
    }

    suspend fun get(url: String, headers: Map<String, String>): CronetResponse =
        suspendCancellableCoroutine { continuation ->
            val output = ByteArrayOutputStream()
            var status = 0
            var contentType: String? = null
            var redirects = 0
            var buffer: ByteBuffer? = null

            fun fail(error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                    if (++redirects > 5) {
                        fail(IOException("too many redirects"))
                        request.cancel()
                    } else {
                        request.followRedirect()
                    }
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    status = info.httpStatusCode
                    contentType = info.allHeaders.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value?.firstOrNull()
                    if (status !in 200..299) {
                        if (continuation.isActive) {
                            continuation.resume(CronetResponse(status, ByteArray(0), contentType))
                        }
                        request.cancel()
                        return
                    }
                    val next = ByteBuffer.allocateDirect(16 * 1024)
                    buffer = next
                    request.read(next)
                }

                override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                    byteBuffer.flip()
                    if (byteBuffer.hasRemaining()) {
                        val chunk = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(chunk)
                        output.write(chunk)
                    }
                    byteBuffer.clear()
                    if (output.size() > MAX_GD_RESPONSE_BYTES) {
                        fail(CatalogException.InvalidResponse("catalog response exceeds size limit"))
                        request.cancel()
                        return
                    }
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    if (continuation.isActive) {
                        continuation.resume(CronetResponse(status, output.toByteArray(), contentType))
                    }
                }

                override fun onFailed(request: UrlRequest, info: UrlResponseInfo, error: org.chromium.net.CronetException) {
                    fail(error)
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo) {
                    if (continuation.isActive) continuation.cancel()
                }
            }

            val request = engine.newUrlRequestBuilder(url, callback, executor).apply {
                headers.forEach { (name, value) -> addHeader(name, value) }
            }.build()
            continuation.invokeOnCancellation { request.cancel() }
            request.start()
        }
}
