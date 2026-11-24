package com.live.tv.media

import android.app.Activity
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FluidTitleViewTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun titleKeepsTheInstalledTvSystemTypeface() {
        val title = FluidTitleView(ApplicationProvider.getApplicationContext())
        assertEquals(Typeface.create("sans-serif", Typeface.BOLD), title.typeface)
        assertTrue(title.typeface.isBold)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectedFontProducesReusableVectorGeometryAcrossProgressAndScale() {
        val title = FluidTitleView(ApplicationProvider.getApplicationContext()).apply { layoutParams = FrameLayout.LayoutParams(500,100); text = "现在就出发" }
        fun render() {
            title.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            title.layout(0, 0, 500, 100)
            val bitmap = Bitmap.createBitmap(1000, 200, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.scale(2f, 2f); title.draw(canvas)
            assertTrue((0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> Color.alpha(bitmap.getPixel(x,y)) > 0 } })
            bitmap.recycle()
        }
        render(); val first = title.vectorBuildCount
        assertTrue(first > 0); assertEquals(View.LAYER_TYPE_NONE, title.layerType)
        title.setProgress(50,100); title.scaleX = 2.35f; title.scaleY = 2.35f; render()
        assertEquals("progress and view scaling reuse vector geometry", first, title.vectorBuildCount)
        title.text = "三体·周年纪念版"; render(); assertTrue(title.vectorBuildCount > first)
        val second = title.vectorBuildCount; title.textSize = 32f; render(); assertTrue(title.vectorBuildCount > second)
        assertTrue(title.paint.hasGlyph("现")); assertTrue(title.paint.hasGlyph("发"))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun loadingFadeReversesWithoutRelayoutAndUsesThemeColour() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val title = FluidTitleView(controller.get()).apply { text = "MMMM"; setThemeColor(Color.rgb(240, 50, 70)) }
        controller.get().setContentView(title)
        try {
            title.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY)); title.layout(0, 0, 300, 64)
            val reserve = title.paddingRight
            fun fade(): android.animation.ValueAnimator = ReflectionHelpers.getField(title, "loadingFade")
            title.setBuffering(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299))
            assertEquals(0f, title.loadingOpacity, 0f)
            title.setBuffering(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(0f, title.loadingOpacity, 0f)
            title.setBuffering(true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            fade().currentPlayTime = 100
            val partial = title.loadingOpacity; assertTrue(partial > 0f && partial < 1f)
            title.setBuffering(false); assertEquals(partial, title.loadingOpacity, .001f)
            fade().currentPlayTime = 30
            val fading = title.loadingOpacity; assertTrue(fading < partial)
            title.setBuffering(true); assertEquals(fading, title.loadingOpacity, .001f); fade().end()
            assertEquals(reserve, title.paddingRight)
            title.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY)); title.layout(0, 0, 300, 64)
            val bitmap = Bitmap.createBitmap(300, 64, Bitmap.Config.ARGB_8888); title.draw(Canvas(bitmap))
            var coloured = 0
            for (x in (title.progressTextWidth.toInt() + 6) until 300) for (y in 0 until 64) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 60 && Color.red(pixel) > Color.green(pixel) + 80) coloured++
            }
            assertTrue("loading arc uses resource theme", coloured > 5)
            title.setBuffering(false); fade().end(); assertEquals(0f, title.loadingOpacity, 0f)
            assertEquals(reserve, title.paddingRight)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun focusNeverAddsAShadowOutsideTitleGlyphs() {
        val c = Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val title = FluidTitleView(c.get()).apply { text = "三体2023"; isFocusable = true; isFocusableInTouchMode = true }
        c.get().setContentView(title)
        try {
            assertEquals(0f, title.shadowRadius, 0f)
            assertTrue(title.requestFocus())
            assertEquals(0f, title.shadowRadius, 0f)
            title.clearFocus()
            assertEquals(0f, title.shadowRadius, 0f)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun progressHandlesUnknownDurationBoundsAndLargePositions() {
        val title = FluidTitleView(ApplicationProvider.getApplicationContext())
        title.setProgress(25_000, 100_000)
        assertEquals(.25f, title.progressFraction, .0001f)
        title.setProgress(-1, 100_000)
        assertEquals(0f, title.progressFraction, 0f)
        title.setProgress(Long.MAX_VALUE, 100_000)
        assertEquals(1f, title.progressFraction, 0f)
        title.setProgress(Long.MAX_VALUE / 2, Long.MAX_VALUE)
        assertEquals(.5f, title.progressFraction, .0001f)
        title.setProgress(10_000, -9223372036854775807L)
        assertEquals(0f, title.progressFraction, 0f)
        title.setProgress(10_000, 0)
        assertEquals(0f, title.progressFraction, 0f)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longTitleProgressUsesVisibleWidthInsteadOfOffscreenText() {
        val title = FluidTitleView(ApplicationProvider.getApplicationContext())
        title.text = "An exceptionally long movie title that cannot fit inside this small available area"
        title.setPadding(12, 0, 12, 0)
        title.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY))
        title.layout(0, 0, 240, 64)
        assertTrue(title.paint.measureText(title.text.toString()) > 216f)
        assertTrue(title.layout.getEllipsisCount(0) > 0)
        assertTrue(title.progressTextWidth > 0f)
        assertTrue(title.progressTextWidth <= 216f)
        title.setProgress(50, 100)
        assertTrue(title.progressTextWidth * title.progressFraction <= 108f)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativePixelsFillOnlyThePlayedGlyphsAndTrackProgress() {
        val title = FluidTitleView(ApplicationProvider.getApplicationContext())
        title.text = "MMMMMMMM"
        title.typeface = Typeface.MONOSPACE
        title.textSize = 36f
        title.setThemeColor(Color.rgb(90, 190, 205))
        title.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY))
        title.layout(0, 0, 360, 80)
        var previousColored = -1
        for (position in listOf(0L, 25L, 75L, 100L)) {
            title.setProgress(position, 100)
            val bitmap = Bitmap.createBitmap(360, 80, Bitmap.Config.ARGB_8888)
            title.draw(Canvas(bitmap))
            var colored = 0
            var neutralToRight = 0
            val boundary = title.progressTextWidth * position / 100f
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 100) continue
                val cyan = Color.green(pixel) - Color.red(pixel) > 40 && Color.blue(pixel) - Color.red(pixel) > 40
                if (cyan) {
                    colored++
                    if (position < 100) assertTrue("colour escaped played glyph region at $position%: $x > $boundary", x <= boundary + 4)
                } else if (x > boundary + 4) neutralToRight++
            }
            if (position == 0L) assertEquals(0, colored)
            else assertTrue("more progress must colour more glyphs at $position% ($colored <= $previousColored)", colored > previousColored)
            if (position < 100) assertTrue("unplayed glyphs remain neutral", neutralToRight > 0)
            previousColored = colored
            bitmap.recycle()
        }
    }

    @Test fun resourceThemeRetainsRgbEvenWhenSourceHasTransparency() {
        val title = FluidTitleView(ApplicationProvider.getApplicationContext())
        title.setThemeColor(Color.argb(40, 220, 140, 45))
        assertEquals(Color.rgb(220, 140, 45), title.themeColor)
        title.setThemeColor(Color.rgb(80, 170, 230))
        assertEquals(Color.rgb(80, 170, 230), title.themeColor)
    }

    @Test fun animationStopsWhilePausedHiddenAndDetachedAndResumesWhenShown() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val group = FrameLayout(activity.get())
        val title = FluidTitleView(activity.get())
        title.text = "三体·周年纪念版"
        title.setFlowing(true)
        assertFalse(title.isFlowAnimating)
        group.addView(title)
        activity.get().setContentView(group)
        // setContentView schedules window traversal; wait for actual attachment.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        // Robolectric API 35 attaches the generic activity but has no window
        // server to publish mWindowVisibility. Deliver that platform event explicitly.
        fun windowVisibility(value: Int) {
            val attachInfo = ReflectionHelpers.getField<Any>(title, "mAttachInfo")
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", value)
            group.dispatchWindowVisibilityChanged(value)
        }
        windowVisibility(View.VISIBLE)
        try {
            assertTrue("title must be attached before starting flow", title.isAttachedToWindow)
            assertTrue("title must be shown before starting flow", title.isShown)
            assertTrue("system animation scale is enabled", android.animation.ValueAnimator.areAnimatorsEnabled())
            assertTrue(title.isFlowAnimating)
            title.setFlowing(false)
            assertFalse(title.isFlowAnimating)
            val frozenPhase = title.flowPhase
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertEquals(frozenPhase, title.flowPhase, 0f)
            title.setFlowing(true)
            assertTrue(title.isFlowAnimating)
            group.visibility = View.GONE
            assertFalse(title.isFlowAnimating)
            group.visibility = View.VISIBLE
            assertTrue(title.isFlowAnimating)
            windowVisibility(View.GONE)
            assertFalse(title.isFlowAnimating)
            windowVisibility(View.VISIBLE)
            assertTrue(title.isFlowAnimating)
            group.removeView(title)
            assertFalse(title.isFlowAnimating)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
