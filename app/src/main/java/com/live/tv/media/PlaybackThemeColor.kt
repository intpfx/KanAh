package com.live.tv.media

import android.graphics.Bitmap
import android.graphics.Color

/** Small in-memory frame sample; no screenshots are retained or sent off-device. */
internal object PlaybackThemeColor {
    val FALLBACK: Int = Color.rgb(210, 215, 220)
    fun fromBitmap(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return null
        val weights = DoubleArray(24)
        val saturation = DoubleArray(24)
        val hueSin = DoubleArray(24)
        val hueCos = DoubleArray(24)
        val hsv = FloatArray(3)
        val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 64)
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) < 128) continue
            Color.colorToHSV(pixel, hsv)
            // Ignore letterboxing, clipped highlights and grey subtitles.
            if (hsv[2] < .12f || hsv[2] > .96f || hsv[1] < .16f) continue
            val bin = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            val weight = (hsv[1] * hsv[2]).toDouble()
            weights[bin] += weight; saturation[bin] += hsv[1] * weight
            val angle = Math.toRadians(hsv[0].toDouble())
            hueSin[bin] += kotlin.math.sin(angle) * weight; hueCos[bin] += kotlin.math.cos(angle) * weight
        }
        val best = weights.indices.maxByOrNull { weights[it] } ?: return null
        if (weights[best] < 1.0) return null
        val hue = ((Math.toDegrees(kotlin.math.atan2(hueSin[best], hueCos[best])) + 360) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, (saturation[best] / weights[best]).toFloat().coerceIn(.35f, .70f), .95f))
    }
}
