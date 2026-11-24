package com.live.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import com.live.tv.media.LivePlaybackControls
import java.io.File
import java.time.Duration
import java.util.function.Consumer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvUiSidebarNavigationTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var ui: TvUi
    private lateinit var actions: Recorder
    private var originalScale = 1f
    private val programme = Color.rgb(75, 93, 111)
    private val labels = listOf("直播", "流媒体", "桌面模式", "设置")
    private val routes = listOf("live", "library", "desktopMode", "settings")

    @Before fun showSidebar() {
        originalScale = ValueAnimator.getDurationScale()
        animatorScale(1f)
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE)
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        controller.setup().visible()
        idle(32)
        controller.windowFocusChanged(true)
        actions = Recorder { ui.isOpen }
        ui = TvUi(activity, actions)
        ui.setBackgroundColor(programme)
        activity.setContentView(ui)
        ui.loading(13)
        idle(100)
        ui.revealPlain {}
        ui.open(13, true)
        idle(500)
        assertTrue(ui.isAttachedToWindow)
        assertTrue(ui.isShown)
        assertEquals(View.VISIBLE, ui.windowVisibility)
        assertFalse("Explicit navigation must enter remote focus mode even without ViewRoot key dispatch", ui.isInTouchMode)
        assertTrue("Opening selects the television destination", items[0].hasFocus())
    }

    @After fun release() {
        if (::ui.isInitialized) ui.dispose()
        if (::controller.isInitialized) controller.pause().stop().destroy()
        animatorScale(originalScale)
    }

    @Test fun confirmationClosesTheSidebarBeforeRoutingEachDestinationExactlyOnce() {
        assertEquals(labels, items.map { it.contentDescription.toString() })
        for ((index, destination) in routes.withIndex()) {
            ui.open(13, true); idle(500)
            focusItem(index)
            assertTrue(items[index].hasFocus())
            key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
            key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1)
            key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            assertFalse(ui.isOpen)
            assertTrue(ui.isNavigationVisible)
            assertEquals(destination, actions.destinations.last())
            assertEquals(index + 1, actions.destinations.size)
            assertTrue("The exit animation still owns the sidebar layer", view("browser").visibility == View.VISIBLE)
            items.forEach { it.performClick() }
            assertEquals("Closing controls cannot route stale clicks", index + 1, actions.destinations.size)
            idle(320)
            assertEquals(View.GONE, view("browser").visibility)
            assertFalse(ui.isNavigationVisible)
        }
        assertEquals(routes, actions.destinations)
        assertTrue(actions.openDuringCallback.none { it })
        assertEquals(0, actions.channelSelections)
        assertEquals(0, actions.retries)
    }

    @Test fun touchClicksUseTheSameDestinationsAndAccessibleButtonLabels() {
        for ((index, destination) in routes.withIndex()) {
            ui.open(0, true); idle(500)
            val item = items[index]
            assertEquals(labels[index], item.contentDescription.toString())
            assertEquals("android.widget.Button", item.accessibilityClassName.toString())
            assertTrue(item.performClick())
            assertEquals(destination, actions.destinations.last())
            assertFalse(actions.openDuringCallback.last())
        }
    }

    @Test fun tappingEachVisibleLabelRoutesItsMatchingDestination() {
        for ((index, destination) in routes.withIndex()) {
            ui.open(0, true); idle(500)
            val item = items[index]
            val label = Bitmap.createBitmap(item.width, item.height, Bitmap.Config.ARGB_8888)
            val bounds = try {
                val canvas = Canvas(label)
                canvas.translate(-item.scrollX.toFloat(), -item.scrollY.toFloat())
                item.draw(canvas)
                alphaInkBounds(label)
            } finally { label.recycle() }
            val x = view("navigation").left + item.left + bounds.exactCenterX()
            val y = view("navigation").top + item.top + bounds.exactCenterY()
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, x, y, 0)
                try { assertTrue(ui.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            idle(32)
            assertEquals(destination, actions.destinations.last())
            assertEquals(index + 1, actions.destinations.size)
        }
    }

    @Test fun hiddenBackgroundAndDisposedControlsCannotDispatchStaleClicks() {
        ui.close(); items.forEach { it.performClick() }
        ui.open(0, true); ui.pauseCard(); items.forEach { it.performClick() }
        ui.resumeCard()
        controller.get().setContentView(View(controller.get())); items.forEach { it.performClick() }
        assertTrue(actions.destinations.isEmpty())
        controller.get().setContentView(ui); ui.resumeCard(); ui.open(0, true); idle(500)
        assertTrue(items[0].performClick())
        assertEquals(listOf("live"), actions.destinations)
        ui.open(0, true); ui.dispose(); items.forEach { it.performClick() }
        assertEquals(listOf("live"), actions.destinations)
    }

    @Test fun loadingAndFailureKeepTheTelevisionNavigationAvailableForChannelSelection() {
        ui.loading(13); ui.error()
        assertEquals(View.VISIBLE, view("curtain").visibility)
        assertTrue(items[0].performClick())
        assertEquals(listOf("live"), actions.destinations)
        assertFalse(actions.openDuringCallback.single())
        assertEquals(0, actions.retries)
    }

    @Test fun textDestinationsHaveConsistentTypeAndFocusEmphasizesOnlyTheSelectedDestination() {
        val focusedCoverage = LongArray(items.size)
        val unfocusedCoverage = LongArray(items.size)
        for (selected in items.indices) {
            assertTrue(items[selected].requestFocus())
            for ((index, item) in items.withIndex()) {
                assertTextContent(index, item)
                val bitmap = Bitmap.createBitmap(item.width, item.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.translate(-item.scrollX.toFloat(), -item.scrollY.toFloat())
                    item.draw(canvas)
                    assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                    assertEquals(0, Color.alpha(bitmap.getPixel(item.width - 1, item.height - 1)))
                    var maximumAlpha = 0
                    var coverage = 0L
                    for (y in 0 until item.height) for (x in 0 until item.width) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = Color.alpha(pixel)
                        maximumAlpha = maxOf(maximumAlpha, alpha)
                        coverage += alpha
                    }
                    assertTextInkFitsRow(alphaInkBounds(bitmap), item)
                    if (index == selected) {
                        assertEquals(255, maximumAlpha)
                        focusedCoverage[index] = coverage
                    } else {
                        assertTrue("Unfocused ${labels[index]} remains dimmed", maximumAlpha < 255)
                        unfocusedCoverage[index] = coverage
                    }
                    assertEquals(if (index == selected) 1.06f else 1f, item.scaleX, .001f)
                    assertEquals(item.scaleX, item.scaleY, .001f)
                } finally { bitmap.recycle() }
            }
        }
        for (index in items.indices)
            assertTrue("Focus increases the visible coverage of ${labels[index]}",
                focusedCoverage[index] > unfocusedCoverage[index] && unfocusedCoverage[index] > 0)
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi")
    fun navigationAt720pPreservesTheProgrammeWithoutATimeDisplay() = assertViewport(1280, 720)

    @Test
    fun navigationAt1080pPreservesTheProgrammeWithoutATimeDisplay() = assertViewport(1920, 1080)

    @Test @Config(qualifiers = "w3840dp-h2160dp-land-mdpi")
    fun navigationAt4kPreservesTheProgrammeWithoutATimeDisplay() = assertViewport(3840, 2160)

    private fun assertViewport(width: Int, height: Int) {
        assertEquals(width, ui.width)
        assertEquals(height, ui.height)
        val navigation = view("navigation")
        val edgeWidth = Math.round(width * .07f)
        val pitch = Math.round(height * 56f / 1080f)
        assertEquals(edgeWidth, navigation.width)
        assertEquals(items.size * pitch, navigation.height)
        assertEquals((height - navigation.height) / 2, navigation.top)
        assertEquals("The complete content is vertically centered", height / 2f,
            (navigation.top + navigation.bottom) / 2f, .5f)
        val panel = Bitmap.createBitmap(edgeWidth, height, Bitmap.Config.ARGB_8888)
        view("browserScrim").draw(Canvas(panel))
        try {
            for (selected in items.indices) {
                assertTrue(items[selected].requestFocus()); idle(32)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    ui.draw(Canvas(bitmap))
                    assertEquals(programme, bitmap.getPixel(width / 2, height / 2))
                    assertEquals(programme, bitmap.getPixel(width - 1, height - 1))
                    assertEquals(Color.BLACK, bitmap.getPixel(2, height / 2))
                    for ((index, item) in items.withIndex()) {
                        assertEquals(index * pitch, item.top)
                        assertEquals(pitch, item.height)
                        assertEquals(edgeWidth, item.width)
                        assertTextContent(index, item)
                        val top = navigation.top + item.top
                        // Include the focus transform when checking that the complete label fits.
                        val label = Bitmap.createBitmap(item.width, item.height, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(label)
                            canvas.scale(item.scaleX, item.scaleY, item.pivotX, item.pivotY)
                            canvas.translate(-item.scrollX.toFloat(), -item.scrollY.toFloat())
                            item.draw(canvas)
                            assertTextInkFitsRow(alphaInkBounds(label), item)
                            for (y in 0 until item.height) for (x in 0 until item.width) {
                                if (Color.alpha(label.getPixel(x, y)) > 32)
                                    assertTrue("Label $index at $x,${top + y} must sit inside the black contour",
                                        Color.alpha(panel.getPixel(x, top + y)) >= 250)
                            }
                        } finally { label.recycle() }
                    }
                    assertFalse("The area below the navigation has no time display",
                        visibleInk(bitmap, 0, navigation.bottom + Math.round(height * 42f / 1080f),
                            edgeWidth, Math.round(height * 4f / 1080f)))
                    capture(bitmap, "sidebar-${width}x$height-$selected")
                } finally { bitmap.recycle() }
            }
        } finally { panel.recycle() }
    }

    private fun focusItem(index: Int) {
        // The live destination (index 1) is focused when the sidebar opens.
        repeat(index) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
    }

    private fun assertTextContent(index: Int, item: View) {
        assertTrue(item is androidx.appcompat.widget.AppCompatTextView)
        val text = item as TextView
        assertEquals(labels[index], text.text.toString())
        assertEquals(text.text.toString(), text.contentDescription.toString())
        assertTrue("Navigation has no compound icons", text.compoundDrawables.all { it == null })
        assertTrue("Navigation has no relative compound icons", text.compoundDrawablesRelative.all { it == null })
        assertEquals(Gravity.CENTER, text.gravity)
        assertTrue(text.typeface.isBold)
        val expectedSize = minOf(item.height * 32f / 56f, item.width * .78f / maxOf(3, labels[index].length))
        assertEquals(expectedSize, text.textSize, .001f)
        assertEquals(if (item.hasFocus()) Color.WHITE else 0xCCFFFFFF.toInt(), text.currentTextColor)
        assertEquals(if (item.hasFocus()) 1.06f else 1f, item.scaleX, .001f)
        assertEquals(item.scaleX, item.scaleY, .001f)
        val layout = requireNotNull(text.layout)
        assertEquals("The full label occupies one line", 1, layout.lineCount)
        assertEquals(text.text.length, layout.getLineEnd(0))
        assertEquals("The full label is never ellipsized", 0, layout.getEllipsisCount(0))
        assertTrue("The full label fits horizontally",
            text.paint.measureText(text.text.toString()) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight)
        assertTrue("The full label fits vertically",
            layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
    }

    private fun assertTextInkFitsRow(bounds: Rect, item: View) {
        assertTrue("Text retains a transparent margin on all sides: $bounds in ${item.width}x${item.height}",
            bounds.left > 0 && bounds.top > 0 && bounds.right < item.width && bounds.bottom < item.height)
    }

    private fun isWhiteInk(pixel: Int) = Color.alpha(pixel) > 100 &&
        Color.red(pixel) > 120 && Color.green(pixel) > 120 && Color.blue(pixel) > 120

    private fun alphaInkBounds(bitmap: Bitmap) = inkBounds(bitmap, 0, 0, bitmap.width, bitmap.height) {
        Color.alpha(it) > 32
    }

    private fun whiteInkBounds(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int) =
        inkBounds(bitmap, left, top, width, height, ::isWhiteInk)

    private fun inkBounds(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int, hasInk: (Int) -> Boolean): Rect {
        var minimumX = left + width
        var minimumY = top + height
        var maximumX = left - 1
        var maximumY = top - 1
        for (y in top until top + height) for (x in left until left + width) {
            if (hasInk(bitmap.getPixel(x, y))) {
                minimumX = minOf(minimumX, x); minimumY = minOf(minimumY, y)
                maximumX = maxOf(maximumX, x); maximumY = maxOf(maximumY, y)
            }
        }
        assertTrue("Visible ink inside $left,$top ${width}x$height", maximumX >= minimumX && maximumY >= minimumY)
        return Rect(minimumX, minimumY, maximumX + 1, maximumY + 1)
    }

    private fun visibleInk(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): Boolean {
        for (y in top until top + height) for (x in left until left + width)
            if (Color.red(bitmap.getPixel(x, y)) > 120) return true
        return false
    }

    private val items: List<View> get() = (field("navigationItems") as Array<*>).map { it as View }
    private fun field(name: String): Any? = TvUi::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(ui)
    }
    private fun view(name: String) = field(name) as View
    private fun key(code: Int, action: Int, repeat: Int = 0) {
        val now = SystemClock.uptimeMillis()
        ui.dispatchKeyEvent(KeyEvent(now, now, action, code, repeat))
    }
    private fun press(code: Int) {
        key(code, KeyEvent.ACTION_DOWN)
        key(code, KeyEvent.ACTION_UP)
    }
    private fun capture(bitmap: Bitmap, name: String) {
        System.getenv("LIVE_SIDEBAR_CAPTURE_DIR")?.let { destination ->
            val directory = File(destination).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun animatorScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, scale))
    }
    private fun idle(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }
    private class Recorder(private val isOpen: () -> Boolean) : TvUi.Actions {
        val destinations = mutableListOf<String>()
        val openDuringCallback = mutableListOf<Boolean>()
        var channelSelections = 0
        var retries = 0
        private fun record(destination: String) {
            openDuringCallback += isOpen()
            destinations += destination
        }
        override fun live() = record("live")
        override fun library() = record("library")
        override fun desktopMode() = record("desktopMode")
        override fun settings() = record("settings")
        override fun select(channel: Int) { channelSelections++ }
        override fun retry() { retries++ }
        override fun exit() {}
        override fun setPictureHidden(hidden: Boolean) {}
        override fun captureFrame(ready: Consumer<Bitmap>) {}
    }
}
