package com.live.tv.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.live.tv.music.cache.OfflineMusicCache
import com.live.tv.music.network.SmartisaxNetwork
import com.live.tv.music.network.isAllowedMusicCdnUrl
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

internal object NativeMusicArtwork {
    fun cached(file: File?): Bitmap? {
        if (file == null || file.length() !in 1..OfflineMusicCache.ARTWORK_LIMIT) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        val sample = sampleSize(options) ?: return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    suspend fun remote(source: String?): Bitmap? {
        val url = source?.toHttpUrlOrNull()?.takeIf(::isAllowedMusicCdnUrl) ?: return null
        return suspendCancellableCoroutine { continuation ->
            val call = SmartisaxNetwork.streamingClient.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resume(null)

                override fun onResponse(call: Call, response: Response) {
                    val bitmap = response.use {
                        if (!response.isSuccessful || !isAllowedMusicCdnUrl(response.request.url)) return@use null
                        val body = response.body
                        val maximum = OfflineMusicCache.ARTWORK_LIMIT
                        if (body.contentLength() > maximum) return@use null
                        runCatching {
                            val stream = body.source()
                            if (stream.request(maximum + 1)) return@runCatching null
                            val bytes = stream.readByteArray()
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            val sample = sampleSize(options) ?: return@runCatching null
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inSampleSize = sample })
                        }.getOrNull()
                    }
                    continuation.resume(bitmap)
                }
            })
        }
    }

    /** Preserve full-size covers up to 4K while limiting inflated image dimensions. */
    private fun sampleSize(options: BitmapFactory.Options): Int? {
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0 || width > 32_768 || height > 32_768) return null
        var sample = 1
        while (maxOf(width, height) / sample > 4096 ||
            (width / sample).toLong() * (height / sample) > 8_500_000) {
            sample *= 2
        }
        return sample
    }

    fun tint(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(256)
        sample.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (sample !== bitmap) sample.recycle()
        val sums = DoubleArray(3)
        var total = 0.0
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val high = maxOf(r, g, b)
            val low = minOf(r, g, b)
            if (high < 25 || low > 205 || Color.alpha(pixel) < 128) continue
            val weight = 1 + (high - low) / 30.0
            sums[0] += r * weight
            sums[1] += g * weight
            sums[2] += b * weight
            total += weight
        }
        val palette = if (total == 0.0) listOf(55, 43, 48) else sums.map { (it / total).roundToInt() }
        val high = palette.max()
        val low = palette.min()
        val range = high - low
        if (range < 3) return Color.rgb(200, 167, 164)
        val spread = (range.toDouble() / high.coerceAtLeast(1) * 170).coerceIn(50.0, 65.0)
        val ink = palette.map { (200 - spread + (it - low).toDouble() / range * spread).roundToInt() }
        return Color.rgb(ink[0], ink[1], ink[2])
    }
}
