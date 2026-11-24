package com.live.tv.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** Optional artwork fallback. Uses the app-routed client, no media/API credentials, and bounded reads. */
internal object PlaybackPosterTheme {
    fun load(url: String, client: OkHttpClient, request: VodRequest): Int? {
        val target = url.takeIf { it.length <= 8192 }?.toHttpUrlOrNull() ?: return null
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) return null
        val scope = request.bounded(8000)
        return try {
            val response = VodHttp(client).fetch(target, emptyMap(), scope, 2 * 1024 * 1024)
            request.check()
            decode(response.bytes).also { request.check() }
        } catch (_: Exception) { null } finally { scope.cancel() }
    }

    internal fun decode(bytes: ByteArray): Int? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 ||
            bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 128) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try { PlaybackThemeColor.fromBitmap(bitmap) } finally { bitmap.recycle() }
    }
}

/** Opaque black is a real frame; only repeated fully transparent successful copies prove broken readback. */
internal class PlaybackSurfaceCopyHealth {
    private var transparentCopies = 0
    fun reset() { transparentCopies = 0 }
    fun needsPosterFallback(success: Boolean, bitmap: Bitmap): Boolean {
        val empty = success && (0 until bitmap.height).all { y ->
            (0 until bitmap.width).all { x -> Color.alpha(bitmap.getPixel(x, y)) == 0 }
        }
        transparentCopies = if (empty) transparentCopies + 1 else 0
        return transparentCopies >= 3
    }
}
