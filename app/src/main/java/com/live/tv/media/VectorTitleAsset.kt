package com.live.tv.media

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Final title geometry. No image texture, bitmap cache or raster enlargement.
 * Future extracted artwork must pass through a vector producer before it can
 * join this rendering path; the bundled font is the first production producer.
 */
internal class VectorTitleAsset private constructor(val key: Key, private val outline: Path) {
    data class Key(
        val text: String, val size: Float, val typeface: Typeface?,
        val scaleX: Float, val skewX: Float, val letterSpacing: Float,
        val features: String?, val left: Float, val baseline: Float,
    )

    fun draw(canvas: Canvas, paint: Paint) = canvas.drawPath(outline, paint)
    internal val isEmpty: Boolean get() = outline.isEmpty

    companion object {
        fun create(key: Key, paint: Paint): VectorTitleAsset {
            val path = Path()
            if (key.text.isNotEmpty()) paint.getTextPath(key.text, 0, key.text.length, key.left, key.baseline, path)
            return VectorTitleAsset(key, path)
        }
    }
}
