package com.live.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgePanelViewTest {
    @Test fun `screen edge contour has full shoulders and clear picture space at both ends`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((width, height) in listOf(134 to 1080, 90 to 720, 269 to 2160)) {
            val panel = EdgePanelView(activity)
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, width, height)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(image))
            for (y in listOf(0, (height * .27f).toInt(), (height * .73f).toInt(), height - 1)) {
                for (x in 0 until width) assertEquals("outside contour $x,$y", Color.TRANSPARENT, image.getPixel(x, y))
            }
            assertEquals(Color.BLACK, image.getPixel(1, height / 2))
            assertEquals(Color.BLACK, image.getPixel(width / 2, height / 2))
            assertEquals(Color.BLACK, image.getPixel(width - 2, height / 2))
            // The transition occupies a square-width shoulder, rather than flattening its
            // vertical radius into the short, pinched neck of the previous contour.
            val top = (height * .28f).toInt()
            val earlyShoulder = opaqueWidth(image, top + width / 4)
            val beforeJoint = opaqueWidth(image, top + width / 2 - 2)
            val afterJoint = opaqueWidth(image, top + width / 2 + 2)
            val lateShoulder = opaqueWidth(image, top + 3 * width / 4)
            assertTrue("shoulder starts close to the hardware edge", earlyShoulder in 1 until width / 4)
            assertTrue("upper shoulder approaches the tangent join", beforeJoint > earlyShoulder && beforeJoint < width / 2)
            assertTrue("lower shoulder continues from the tangent join", afterJoint > width / 2 && afterJoint < lateShoulder)
            assertTrue("shoulder grows smoothly into the full menu", lateShoulder > 3 * width / 4)
            assertEquals(width, opaqueWidth(image, top + width + 2))
            for (y in 0 until height / 2)
                assertTrue("The contour is symmetric around screen center at y=$y",
                    kotlin.math.abs(opaqueWidth(image, y) - opaqueWidth(image, height - 1 - y)) <= 1)
            assertFalse(panel.isFocusable)
            assertFalse(panel.isClickable)
            image.recycle()
        }
    }

    @Test fun `menu outline remains one attached surface without holes or floating pieces`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val panel = EdgePanelView(activity)
        panel.measure(View.MeasureSpec.makeMeasureSpec(134, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, 134, 1080)
        val image = Bitmap.createBitmap(134, 1080, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(image))
        for (y in 0 until image.height) {
            var ended = false
            for (x in 0 until image.width) {
                val opaque = Color.alpha(image.getPixel(x, y)) >= 128
                if (!opaque) ended = true
                else assertFalse("separate segment or hole at $x,$y", ended)
            }
        }
        image.recycle()
    }

    private fun opaqueWidth(image: Bitmap, y: Int): Int {
        var width = 0
        while (width < image.width && Color.alpha(image.getPixel(width, y)) >= 128) width++
        return width
    }
}
