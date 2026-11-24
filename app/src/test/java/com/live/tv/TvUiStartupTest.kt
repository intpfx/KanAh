package com.live.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
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
class TvUiStartupTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var ui: TvUi
    private lateinit var actions: Recorder
    private var originalScale = 1f

    @Before fun createStartup() {
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
        actions = Recorder()
        ui = TvUi(activity, actions)
        // A neutral stand-in for the live programme makes the opaque cover and its retreat measurable.
        ui.setBackgroundColor(Color.rgb(75, 93, 111))
        activity.setContentView(ui)
        ui.loading(13)
        idle(100)
    }

    @After fun releaseStartup() {
        if (::ui.isInitialized) ui.dispose()
        if (::controller.isInitialized) controller.pause().stop().destroy()
        animatorScale(originalScale)
    }

    @Test fun loadingShowsChannelIdentityWithoutParticleView() {
        assertEquals(View.VISIBLE, view("curtain").visibility)
        assertEquals("CCTV-13", (view("loadingChannel") as TextView).text.toString())
        assertEquals("新闻", (view("loadingName") as TextView).text.toString())
        assertEquals(View.GONE, view("loadingStatus").visibility)
        assertFalse(ui.isOpen)
        fun hasParticles(view: View): Boolean = view is LightDustView ||
            (view is android.view.ViewGroup && (0 until view.childCount).any { hasParticles(view.getChildAt(it)) })
        assertFalse("Loading must not mount the previous cloud renderer", hasParticles(view("curtain")))
    }

    @Test fun acceptedFrameMorphsBeforeCompletingAndOpeningSidebar() {
        var completions = 0
        ui.reveal { completions++ }
        idle(200)
        assertEquals(0, completions)
        assertEquals(View.VISIBLE, view("curtain").visibility)
        assertTrue(field("curtainMorphing") as Boolean)
        assertTrue((field("curtainProgress") as Float) in .001f.. .999f)
        assertTrue(ui.isOpen)
        idle(1_600)
        assertEquals(1, completions)
        assertFalse(field("curtainMorphing") as Boolean)
        assertEquals(View.GONE, view("curtain").visibility)
        assertTrue(ui.isOpen)
        val destinations = field("navigationItems") as Array<*>
        assertTrue((destinations[0] as View).hasFocus())
        assertEquals(listOf("直播", "流媒体", "桌面模式", "设置"),
            destinations.map { (it as View).contentDescription.toString() })
        assertEquals(listOf("直播", "流媒体", "桌面模式", "设置"),
            destinations.map { (it as android.widget.TextView).text.toString() })
    }

    @Test fun duplicateReadinessDoesNotRestartMorphOrRunSecondCompletion() {
        var first = 0
        var duplicate = 0
        ui.reveal { first++ }
        idle(180)
        val running = field("curtainAnimator")
        val progress = field("curtainProgress") as Float
        ui.reveal { duplicate++ }
        assertSame(running, field("curtainAnimator"))
        assertEquals(progress, field("curtainProgress") as Float, 0f)
        idle(1_620)
        assertEquals(1, first)
        assertEquals(0, duplicate)
    }

    @Test fun newChannelLoadingCancelsOldCompletionAndRestoresOpaqueCover() {
        var oldCompletion = 0
        var currentCompletion = 0
        ui.reveal { oldCompletion++ }
        idle(240)
        ui.loading(3)
        idle(1_850)
        assertEquals(0, oldCompletion)
        assertEquals(View.VISIBLE, view("curtain").visibility)
        assertEquals(1f, view("loadingBox").alpha, 0f)
        assertEquals("CCTV-4", (view("loadingChannel") as TextView).text.toString())
        ui.reveal { currentCompletion++ }
        idle(1_850)
        assertEquals(0, oldCompletion)
        assertEquals(1, currentCompletion)
        assertEquals(View.GONE, view("curtain").visibility)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun firstFrameKeepsTheManuallySelectedLibraryDestination() {
        ui.open(13, false)
        idle(500)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(navigationItem(1).hasFocus())

        var completions = 0
        ui.reveal { completions++ }
        idle(1_850)
        assertEquals(1, completions)
        assertTrue(ui.isOpen)
        val retainedFocus = navigationItem(1).hasFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("First-frame readiness must not reroute the user's confirmation", listOf("library"), actions.destinations)
        assertTrue("The library destination remains focused throughout readiness", retainedFocus)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun manuallyOpenedNavigationSurvivesAnInterruptedStartupAndItsNextReveal() {
        var oldCompletions = 0
        ui.reveal { oldCompletions++ }
        idle(200)
        ui.close()
        idle(320)
        ui.open(13, true)
        idle(32)
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertTrue(navigationItem(3).hasFocus())

        ui.loading(3)
        ui.error()
        assertTrue("A new loading attempt must not close manually opened navigation", ui.isOpen)
        assertTrue(navigationItem(3).hasFocus())
        assertEquals(View.VISIBLE, view("browser").visibility)
        assertEquals(View.VISIBLE, view("curtain").visibility)
        idle(1_850)
        assertEquals(0, oldCompletions)
        var currentCompletions = 0
        ui.reveal { currentCompletions++ }
        idle(1_850)
        assertEquals(1, currentCompletions)
        assertEquals(0, oldCompletions)
        assertTrue(navigationItem(3).hasFocus())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("settings"), actions.destinations)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun navigatingAnAutomaticallyOpenedSidebarClaimsItBeforeLoadingIsInterrupted() {
        var oldCompletions = 0
        ui.reveal { oldCompletions++ }
        idle(200)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(navigationItem(1).hasFocus())

        ui.loading(3)
        assertTrue("An explicit destination selection must survive a playback retry", ui.isOpen)
        assertTrue(navigationItem(1).hasFocus())
        idle(1_850)
        assertEquals(0, oldCompletions)
        ui.reveal {}
        idle(1_850)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("library"), actions.destinations)
    }

    @Test fun successfulStartupIsOnlyMorphedOnce() {
        ui.reveal {}
        idle(1_850)
        ui.close()
        ui.loading(7)
        var completed = false
        ui.reveal { completed = true }
        assertTrue(completed)
        assertNull(field("curtainAnimator"))
        assertEquals(View.GONE, view("curtain").visibility)
        assertFalse("Ordinary channel switches do not reopen the startup sidebar", ui.isOpen)
    }

    @Test fun returningToCoveredLivePlaybackRearmsTheEntryMorph() {
        ui.reveal {}
        idle(1_850)
        ui.pauseCard()
        ui.loading(13)
        ui.close()
        ui.resumeCard()
        var completed = false
        ui.reveal { completed = true }
        idle(200)
        assertFalse(completed)
        assertTrue(field("curtainMorphing") as Boolean)
        idle(1_600)
        assertTrue(completed)
        assertTrue(ui.isOpen)
    }

    @Test fun explicitChannelsOverrideTheWarmSidebarWithoutRevealingBeforeReadiness() {
        ui.reveal {}
        idle(1_850)
        ui.pauseCard(); ui.loading(13); ui.close(); ui.resumeCard()
        ui.suppressStartupSidebar()
        ui.showLiveBrowser(); ui.close()
        idle(330)
        assertFalse(ui.isOpen)
        assertEquals("Navigation must not reveal an unready decoder", View.VISIBLE, view("curtain").visibility)
        assertNull(field("curtainAnimator"))

        var completions = 0
        ui.reveal { completions++ }
        assertEquals(1, completions)
        assertEquals(View.GONE, view("curtain").visibility)
        assertFalse("First-frame readiness must preserve the explicit channel destination", ui.isOpen)
        assertNull(field("curtainAnimator"))

        // A later ordinary warm entry still owns its normal opening animation.
        ui.pauseCard(); ui.loading(13); ui.close(); ui.resumeCard()
        ui.reveal { completions++ }
        idle(200)
        assertTrue(field("curtainMorphing") as Boolean)
        assertTrue(ui.isOpen)
        assertEquals(1, completions)
        idle(1_650)
        assertEquals(2, completions)
    }

    @Test fun explicitChannelsDuringTheEntryMorphKeepItsAcceptedCompletion() {
        var completions = 0
        ui.reveal { completions++ }
        idle(200)
        val acceptedMorph = field("curtainAnimator")
        assertNotNull(acceptedMorph)
        ui.suppressStartupSidebar()
        ui.showLiveBrowser(); ui.close()
        assertSame("The accepted decoder's completion must not be abandoned", acceptedMorph, field("curtainAnimator"))
        assertEquals(0, completions)
        idle(1_850)
        assertEquals(1, completions)
        assertEquals(View.GONE, view("curtain").visibility)
        assertEquals(View.GONE, view("browser").visibility)
        assertFalse(ui.isOpen)
        ui.reveal {}
        assertFalse("A duplicate frame must not reopen navigation", ui.isOpen)
    }

    @Test fun errorDuringMorphRestoresLoadingInsteadOfRunningOldCompletion() {
        var completed = false
        ui.reveal { completed = true }
        idle(200)
        ui.loading(13)
        ui.error()
        idle(1_000)
        assertFalse(completed)
        assertFalse(ui.isOpen)
        assertEquals(View.VISIBLE, view("curtain").visibility)
        assertEquals(View.VISIBLE, view("loadingStatus").visibility)
        assertEquals(1f, view("loadingBox").alpha, 0f)
    }

    @Test fun pauseDuringMorphCancelsOldCompletionAndResumeCanRevealAgain() {
        var old = false
        ui.reveal { old = true }
        idle(200)
        ui.pauseCard()
        idle(1_000)
        assertFalse(old)
        assertEquals(1f, view("loadingBox").alpha, 0f)
        ui.resumeCard()
        var current = false
        ui.reveal { current = true }
        idle(1_850)
        assertTrue(current)
        assertFalse(old)
    }

    @Test fun navigationPauseKeepsTheAcceptedFirstFrameCompletionWhileBrowsing() {
        var completions = 0
        ui.reveal { completions++ }
        idle(200)
        assertEquals(0, completions)
        assertTrue(field("curtainMorphing") as Boolean)
        assertEquals(View.VISIBLE, view("curtain").visibility)

        ui.pauseCardForNavigation()
        idle(1_850)
        assertTrue("The browsing page still owns the foreground", field("cardPaused") as Boolean)
        assertEquals("Accepted playback must complete its reveal before returning from browsing", 1, completions)
        assertEquals(View.GONE, view("curtain").visibility)
        assertFalse(field("curtainMorphing") as Boolean)
        assertNull(field("curtainAnimator"))

        ui.resumeCard()
        idle(1_850)
        assertEquals("Returning must not repeat the accepted playback completion", 1, completions)
        assertEquals(View.GONE, view("curtain").visibility)
        assertNull(field("curtainAnimator"))
    }

    @Test fun firstFrameAcceptedDuringNavigationPauseCompletesWithoutWaitingForReturn() {
        var completions = 0
        ui.pauseCardForNavigation()
        idle(2_000)
        assertEquals(View.VISIBLE, view("curtain").visibility)
        assertFalse(ui.isOpen)

        ui.reveal { completions++ }
        assertTrue(field("cardPaused") as Boolean)
        assertEquals("Background first-frame readiness must release the playback gate immediately", 1, completions)
        assertEquals(View.GONE, view("curtain").visibility)
        assertNull(field("curtainAnimator"))
        assertFalse("Readiness beneath a browsing page must not open live navigation", ui.isOpen)

        ui.resumeCard()
        idle(1_850)
        assertEquals(1, completions)
        assertEquals(View.GONE, view("curtain").visibility)
        assertNull(field("curtainAnimator"))
        assertFalse("Returning to ready playback must not restart the startup sidebar", ui.isOpen)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun startupBodyCanCompressInsideTheRestingSidebarWithoutAnUnderlyingBlackPanel() {
        ui.reveal {}
        idle(1_000)
        val rebound = frame("body-compressed")
        assertEquals("The body remains attached", Color.BLACK, rebound.getPixel(2, 540))
        assertEquals("The settled panel must not fill in the compressed body's free edge",
            Color.rgb(75, 93, 111), rebound.getPixel(118, 540))
        rebound.recycle()
        idle(850)
        val settled = frame("body-settled")
        assertEquals(Color.BLACK, settled.getPixel(118, 540))
        settled.recycle()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun manualCloseDuringMorphLeavesNoBlackCoverOrReopenedSidebar() {
        var completed = false
        ui.reveal { completed = true }
        idle(200)
        ui.close()
        idle(400)
        val closed = frame("manual-mid-close")
        assertEquals(Color.rgb(75, 93, 111), closed.getPixel(2, 540))
        closed.recycle()
        idle(1_200)
        assertTrue(completed)
        assertFalse(ui.isOpen)
        assertEquals(View.GONE, view("curtain").visibility)
        assertEquals(View.GONE, view("browser").visibility)
    }

    @Test fun sidebarDefaultsToTenSecondTimeout() {
        animatorScale(0f)
        ui.reveal {}
        idle(9_999)
        assertTrue(ui.isOpen)
        idle(1)
        assertFalse(ui.isOpen)
        assertEquals(View.GONE, view("browser").visibility)
    }

    @Test fun physicalAndRepeatedKeysExtendSidebarTimeout() {
        animatorScale(0f)
        ui.reveal {}
        idle(9_000)
        ui.dispatchKeyEvent(KeyEvent(0, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN, 2))
        ui.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        idle(9_999)
        assertTrue(ui.isOpen)
        idle(1)
        assertFalse(ui.isOpen)
    }

    @Test fun validLanInputExtendsSidebarTimeout() {
        animatorScale(0f)
        ui.reveal {}
        idle(9_000)
        ui.interacted()
        idle(9_999)
        assertTrue(ui.isOpen)
        idle(1)
        assertFalse(ui.isOpen)
    }

    @Test fun touchMovementExtendsSidebarTimeout() {
        animatorScale(0f)
        ui.reveal {}
        idle(9_000)
        val now = SystemClock.uptimeMillis()
        val move = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 45f, 430f, 0)
        try { ui.dispatchTouchEvent(move) } finally { move.recycle() }
        idle(9_999)
        assertTrue(ui.isOpen)
        idle(1)
        assertFalse(ui.isOpen)
    }

    @Test fun timerStartsAfterMorphCompletesAndRestartsOnManualOpen() {
        ui.reveal {}
        idle(1_850)
        idle(9_000)
        assertTrue(ui.isOpen)
        idle(1_200)
        assertFalse(ui.isOpen)
        idle(300)
        ui.open(13, true)
        idle(9_999)
        assertTrue(ui.isOpen)
        idle(1)
        assertFalse(ui.isOpen)
    }

    @Test fun pauseCancelsTimeoutAndResumeStartsFreshLease() {
        animatorScale(0f)
        ui.reveal {}
        idle(9_000)
        ui.pauseCard()
        idle(20_000)
        assertTrue(ui.isOpen)
        ui.resumeCard()
        idle(9_999)
        assertTrue(ui.isOpen)
        idle(1)
        assertFalse(ui.isOpen)
    }

    @Test fun disposeCancelsMorphWithoutCallingReadinessCompletion() {
        var completed = false
        ui.reveal { completed = true }
        idle(200)
        ui.dispose()
        idle(20_000)
        assertFalse(completed)
        assertNull(field("curtainAnimator"))
        assertEquals(View.VISIBLE, view("curtain").visibility)
    }

    @Test fun detachingSidebarCancelsItsPendingTimeout() {
        animatorScale(0f)
        ui.reveal {}
        controller.get().setContentView(View(controller.get()))
        idle(20_000)
        assertTrue("Detached UI must not run a stale close callback", ui.isOpen)
    }

    @Test fun musicPlainHandoffCancelsMorphAndDoesNotReopenLiveSidebar() {
        var completed = false
        ui.revealPlain { completed = true }
        assertTrue(completed)
        assertEquals(View.GONE, view("curtain").visibility)
        assertFalse(ui.isOpen)
        idle(20_000)
        assertFalse(ui.isOpen)
        assertNull(field("curtainAnimator"))
    }

    @Test fun disabledAnimationsCompleteImmediatelyButRetainDefaultTimeout() {
        animatorScale(0f)
        var completed = false
        ui.reveal { completed = true }
        assertTrue(completed)
        assertEquals(1f, field("curtainProgress") as Float, 0f)
        assertEquals(View.GONE, view("curtain").visibility)
        assertTrue(ui.isOpen)
        assertNull(field("curtainAnimator"))
        idle(10_000)
        assertFalse(ui.isOpen)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualNativeFramesShowBlackRetreatingIntoExistingSidebar() {
        assertEquals(1920, ui.width)
        assertEquals(1080, ui.height)
        val loading = frame("loading")
        for ((x, y) in listOf(0 to 0, 1919 to 0, 0 to 1079, 1919 to 1079, 200 to 540))
            assertEquals("Loading cover at $x,$y", Color.BLACK, loading.getPixel(x, y))
        loading.recycle()
        ui.reveal {}
        for (index in 0 until 6) {
            frame("frame-%03d".format(index)).recycle()
            idle(32)
        }
        frame("frame-006").recycle()
        idle(8)
        val middle = frame("mid-transition")
        assertEquals("The screen edge remains opaque during the morph", Color.BLACK, middle.getPixel(2, 540))
        assertEquals("The programme is progressively revealed", Color.rgb(75, 93, 111), middle.getPixel(1910, 540))
        middle.recycle()
        idle(24)
        for (index in 7 until 56) {
            frame("frame-%03d".format(index)).recycle()
            idle(32)
        }
        idle(32)
        val sidebar = frame("sidebar")
        assertEquals(Color.BLACK, sidebar.getPixel(2, 540))
        assertEquals("The upper contour remains black beside the focused label",
            Color.BLACK, sidebar.getPixel(118, 424))
        assertEquals("The upper screen edge remains clear above the centered contour", Color.rgb(75, 93, 111), sidebar.getPixel(2, 290))
        assertEquals("The lower screen edge remains clear below the centered contour", Color.rgb(75, 93, 111), sidebar.getPixel(2, 790))
        assertEquals(Color.rgb(75, 93, 111), sidebar.getPixel(1910, 540))
        sidebar.recycle()
        idle(9_500)
        for (index in 0 until 26) {
            frame("hide-frame-%03d".format(index)).recycle()
            idle(32)
        }
        assertFalse(ui.isOpen)
        assertEquals(View.GONE, view("browser").visibility)
        assertEquals(View.GONE, view("curtain").visibility)
        assertTrue("Remote focus returns to the transparent input root", ui.isFocused)
        val hidden = frame("hidden")
        for ((x, y) in listOf(2 to 540, 67 to 400, 67 to 600, 67 to 680, 960 to 540, 1910 to 540))
            assertEquals("Closing navigation must not tint the programme at $x,$y",
                Color.rgb(75, 93, 111), hidden.getPixel(x, y))
        hidden.recycle()
    }

    private fun frame(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(ui.width, ui.height, Bitmap.Config.ARGB_8888)
        ui.draw(Canvas(bitmap))
        System.getenv("LIVE_STARTUP_CAPTURE_DIR")?.let { destination ->
            val directory = File(destination).apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }
    private fun field(name: String): Any? = TvUi::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(ui)
    }
    private fun view(name: String) = field(name) as View
    private fun navigationItem(index: Int) = (field("navigationItems") as Array<*>)[index] as View
    private fun press(code: Int) {
        ui.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ui.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
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
    private class Recorder : TvUi.Actions {
        val destinations = mutableListOf<String>()
        override fun live() { destinations += "live" }
        override fun library() { destinations += "library" }
        override fun desktopMode() { destinations += "desktopMode" }
        override fun settings() { destinations += "settings" }
        override fun select(channel: Int) {}
        override fun retry() {}
        override fun exit() {}
        override fun setPictureHidden(hidden: Boolean) {}
        override fun captureFrame(ready: Consumer<Bitmap>) {}
    }
}
