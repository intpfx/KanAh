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
class StartupCurtainViewTest {
    private val sizes = listOf(1920 to 1080, 1280 to 720)

    @Test fun `startup begins as an opaque full screen black surface`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((width, height) in sizes) {
            val curtain = curtain(activity, width, height)
            val image = render(curtain, 0f)
            for (y in 0 until height) for (x in 0 until width) {
                assertEquals("initial black at $x,$y", Color.BLACK, image.getPixel(x, y))
            }
            assertFalse(curtain.isClickable)
            assertFalse(curtain.isFocusable)
            image.recycle()
        }
    }

    @Test fun `first phase retreats sideways while keeping a full height curtain`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((width, height) in sizes) {
            val curtain = curtain(activity, width, height)
            var previousWidth = width
            for (progress in listOf(.15f, .30f, .45f)) {
                val image = render(curtain, progress)
                val topWidth = opaqueWidth(image, 0)
                val bottomWidth = opaqueWidth(image, height - 1)
                assertTrue("curtain moves left at $progress", topWidth in 1 until previousWidth)
                assertTrue("top and bottom retreat together", kotlin.math.abs(topWidth - bottomWidth) <= 1)
                assertEquals(Color.BLACK, image.getPixel(0, 0))
                assertEquals(Color.BLACK, image.getPixel(0, height - 1))
                assertTrue("centre remains a curtain, not a shrinking card", opaqueWidth(image, height / 2) >= topWidth)
                previousWidth = topWidth
                image.recycle()
            }
        }
    }

    @Test fun `left attachment remains pinned throughout the body's rebound`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((width, height) in sizes) {
            val curtain = curtain(activity, width, height)
            val final = render(curtain, 1f)
            val top = (height * .28f).toInt()
            val bottom = (height * .72f).toInt()
            for (progress in listOf(.54f, .56f, .60f, .68f, .76f, .84f, .95f)) {
                val image = render(curtain, progress)
                for (y in 0 until height) {
                    // Exclude only the three anti-aliased pixels around either endpoint.
                    // The attachment and the solid spine must not move with the body.
                    if (kotlin.math.abs(y - top) <= 3 || kotlin.math.abs(y - bottom) <= 3) continue
                    assertTrue("left edge moved at $progress, y=$y",
                        kotlin.math.abs(Color.alpha(image.getPixel(0, y)) -
                            Color.alpha(final.getPixel(0, y))) <= 2)
                }
                assertEquals("middle spine remains attached", Color.BLACK, image.getPixel(0, height / 2))
                image.recycle()
            }
            final.recycle()
        }
    }

    @Test fun `only the free body squashes and rebounds before settling`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((width, height) in sizes) {
            val curtain = curtain(activity, width, height)
            val squeezed = render(curtain, .56f)
            val rebound = render(curtain, .68f)
            val settled = render(curtain, 1f)
            val minimum = opaqueWidth(squeezed, height / 2)
            val maximum = opaqueWidth(rebound, height / 2)
            val resting = opaqueWidth(settled, height / 2)
            assertTrue("free body visibly compresses", minimum < resting * .85f)
            assertTrue("free body rebounds beyond its resting width", maximum > resting * 1.04f)
            assertTrue("rebound follows compression", maximum > minimum)
            squeezed.recycle(); rebound.recycle(); settled.recycle()
        }
    }

    @Test fun `deformation remains one opaque surface attached to the screen edge`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val curtain = curtain(activity, 1280, 720)
        for (progress in listOf(.46f, .50f, .54f, .56f, .60f, .68f, .76f, .84f, .95f)) {
            val image = render(curtain, progress)
            for (y in 0 until image.height) {
                var ended = false
                for (x in 0 until image.width) {
                    val opaque = Color.alpha(image.getPixel(x, y)) >= 128
                    if (!opaque) ended = true
                    else assertFalse("floating segment or hole at $progress, $x,$y", ended)
                }
            }
            image.recycle()
        }
    }

    @Test fun `settled curtain matches the native sidebar pixel for pixel`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        for ((width, height) in sizes + (3840 to 2160)) {
            val curtain = curtain(activity, width, height)
            val edge = kotlin.math.round(width * .07f).toInt()
            val panel = EdgePanelView(activity)
            layout(panel, edge, height)
            val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(expected))
            val actual = render(curtain, 1f)
            assertTrue("final native sidebar at ${width}x$height", expected.sameAs(actual))
            expected.recycle(); actual.recycle()
        }
    }

    private fun curtain(activity: Activity, width: Int, height: Int): StartupCurtainView =
        StartupCurtainView(activity).also { layout(it, width, height) }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun render(curtain: StartupCurtainView, progress: Float): Bitmap {
        curtain.setProgress(progress)
        return Bitmap.createBitmap(curtain.width, curtain.height, Bitmap.Config.ARGB_8888).also {
            curtain.draw(Canvas(it))
        }
    }

    private fun opaqueWidth(image: Bitmap, y: Int): Int {
        var width = 0
        while (width < image.width && Color.alpha(image.getPixel(width, y)) >= 128) width++
        return width
    }
}
