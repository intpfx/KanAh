package com.live.tv.music.cache

import com.live.tv.music.network.isAllowedMusicCdnUrl
import com.live.tv.music.network.readCancellable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request

class MusicAssetDownloader(private val client: Call.Factory) {
    suspend fun download(
        url: HttpUrl,
        target: File,
        artwork: Boolean,
        maximumBytes: Long,
        onProgress: (Double) -> Unit,
    ) {
        if (!isAllowedMusicCdnUrl(url)) throw IOException("Untrusted music asset")
        val call = client.newCall(Request.Builder().url(url)
            .header("Accept", if (artwork) "image/*" else "audio/*, application/octet-stream")
            .header("Accept-Encoding", "identity")
            .build())
        call.readCancellable { response ->
            // A 206 can contain a valid MP3 header but still be only a fragment of the song.
            if (response.code != 200 || response.header("Content-Range") != null) {
                throw IOException("Music download is not a complete response")
            }
            if (!isAllowedMusicCdnUrl(response.request.url)) throw IOException("Untrusted music asset redirect")
            val body = response.body
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
            if (if (artwork) !mime.startsWith("image/") else !mime.startsWith("audio/") && mime != "application/octet-stream") {
                throw IOException("Music asset has an invalid content type")
            }
            val expected = body.contentLength()
            if (expected == 0L || expected > maximumBytes) throw IOException("Music asset exceeds cache limit")
            var received = 0L
            FileOutputStream(target).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32 * 1_024)
                    while (true) {
                        if (call.isCanceled()) throw IOException("Music download cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > maximumBytes || target.usableSpace < RESERVE_BYTES) {
                            throw IOException("Music cache is full")
                        }
                        output.write(buffer, 0, count)
                        if (expected > 0) onProgress((received.toDouble() / expected).coerceIn(0.0, 0.99))
                    }
                }
                output.fd.sync()
            }
            if (received == 0L || expected >= 0 && received != expected) throw IOException("Music download was truncated")
        }
        onProgress(1.0)
    }

    private companion object { const val RESERVE_BYTES = 8L * 1_024 * 1_024 }
}
