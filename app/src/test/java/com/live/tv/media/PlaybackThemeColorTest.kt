package com.live.tv.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackThemeColorTest {
    @Test fun dominantFrameHueSurvivesBlackBarsAndWhiteSubtitlesWithReadableBrightness() {
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLACK)
            for (y in 5..21) for (x in 0..47) bitmap.setPixel(x, y, Color.HSVToColor(floatArrayOf(28f, .8f, .7f)))
            for (x in 10..30) bitmap.setPixel(x, 20, Color.WHITE)
            val hsv = FloatArray(3); Color.colorToHSV(PlaybackThemeColor.fromBitmap(bitmap)!!, hsv)
            assertEquals(28f, hsv[0], 2f); assertTrue(hsv[1] in .34f.. .71f); assertTrue(hsv[2] > .9f)
        } finally { bitmap.recycle() }
    }
    @Test fun blackGreyTransparentAndRecycledFramesDoNotInventATheme() {
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        for (color in listOf(Color.BLACK, Color.GRAY, Color.WHITE, Color.TRANSPARENT)) {
            bitmap.eraseColor(color); assertNull(PlaybackThemeColor.fromBitmap(bitmap))
        }
        bitmap.recycle(); assertNull(PlaybackThemeColor.fromBitmap(bitmap))
    }
    @Test fun unrelatedResourcesCanProduceDifferentThemeHues() {
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        try {
            val colors = listOf(35f, 205f).map { hue ->
                bitmap.eraseColor(Color.HSVToColor(floatArrayOf(hue, .8f, .75f))); PlaybackThemeColor.fromBitmap(bitmap)
            }
            assertNotNull(colors[0]); assertNotEquals(colors[0], colors[1])
        } finally { bitmap.recycle() }
    }
}
