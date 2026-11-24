package com.live.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ArrayAdapter
import com.live.tv.media.VodLibraryActivity
import java.time.Duration
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSidebarTest {
    class HostActivity : Activity() {
        lateinit var button: Button
        lateinit var sidebar: AppSidebar
        var pauses = 0
        var clicks = 0
        override fun onCreate(state: Bundle?) {
            super.onCreate(state)
            button = Button(this).apply {
                text = "Original page"
                isFocusableInTouchMode = true
                setOnClickListener { clicks++ }
            }
            setContentView(button)
            button.requestFocus()
            sidebar = AppSidebar(this, intent.getStringExtra("current").orEmpty())
        }
        override fun dispatchKeyEvent(event: KeyEvent) = sidebar.handleKey(event) || super.dispatchKeyEvent(event)
        override fun onPause() { pauses++; super.onPause() }
        override fun onStop() { sidebar.dismiss(); super.onStop() }
        override fun onDestroy() { sidebar.dispose(); super.onDestroy() }
    }

    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var activity: HostActivity
    private var scale = 1f

    @Before fun setUp() {
        scale = ValueAnimator.getDurationScale()
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, 1f))
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(HostActivity::class.java).setup().visible()
        activity = controller.get()
        idle(32)
        controller.windowFocusChanged(true)
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, scale))
    }

    @Test fun menuIsTransparentAndRepeatedEdgesNeverPauseOrReachTheOriginalPage() {
        key(KeyEvent.KEYCODE_MENU)
        val dialog = ShadowDialog.getLatestDialog()
        key(KeyEvent.KEYCODE_MENU, repeat = 1)
        key(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)
        idle(500)
        assertTrue(activity.sidebar.isShowing())
        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertEquals(0, activity.pauses)
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertEquals(Color.TRANSPARENT, (dialog.window!!.decorView.background as ColorDrawable).color)
        val ui = sidebarUi(dialog)
        assertEquals(View.GONE, (field(ui, "curtain") as View).visibility)
        key(KeyEvent.KEYCODE_BACK)
        idle(100)
        assertTrue("The closing layer continues to own input", activity.sidebar.isShowing())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        idle(230)
        assertFalse(activity.sidebar.isShowing())
        key(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
        key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
        key(KeyEvent.KEYCODE_BACK, repeat = 1)
        key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
        assertFalse(activity.isFinishing)
        assertEquals(0, activity.clicks)
        assertTrue(activity.button.hasFocus())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, activity.pauses)
    }

    @Test fun menuCanReopenDuringExitAndTheIdleCloseRestoresFocus() {
        press(KeyEvent.KEYCODE_MENU); idle(500)
        press(KeyEvent.KEYCODE_MENU); idle(100)
        press(KeyEvent.KEYCODE_MENU); idle(500)
        assertTrue(activity.sidebar.isShowing())
        assertTrue(sidebarUi(ShadowDialog.getLatestDialog()).isOpen)
        idle(10_400)
        assertFalse(activity.sidebar.isShowing())
        assertTrue(activity.button.hasFocus())
        assertFalse(activity.isFinishing)
        assertEquals(0, activity.pauses)
    }

    @Test fun anOemReinjectedCanceledMenuDownStillTogglesOnlyOncePerPress() {
        // MiTV marks both reinjected MENU edges FLAG_CANCELED; only the initial DOWN is an action.
        fun canceled(action: Int, repeat: Int = 0) = KeyEvent.changeFlags(
            event(KeyEvent.KEYCODE_MENU, action, repeat), KeyEvent.FLAG_CANCELED)

        assertTrue(activity.dispatchKeyEvent(canceled(KeyEvent.ACTION_DOWN)))
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.dispatchKeyEvent(canceled(KeyEvent.ACTION_DOWN, 1)))
        assertTrue(dialog.dispatchKeyEvent(canceled(KeyEvent.ACTION_UP)))
        idle(520)
        assertTrue(activity.sidebar.isShowing())
        assertTrue(sidebarUi(dialog).isOpen)
        assertSame(dialog, ShadowDialog.getLatestDialog())
        assertEquals(0, activity.pauses)
        assertNull(shadowOf(activity).nextStartedActivity)

        assertTrue(dialog.dispatchKeyEvent(canceled(KeyEvent.ACTION_DOWN)))
        assertTrue(dialog.dispatchKeyEvent(canceled(KeyEvent.ACTION_DOWN, 1)))
        idle(330)
        assertFalse(activity.sidebar.isShowing())
        assertTrue(activity.dispatchKeyEvent(canceled(KeyEvent.ACTION_DOWN, 2)))
        assertTrue(activity.dispatchKeyEvent(canceled(KeyEvent.ACTION_UP)))
        assertFalse(activity.sidebar.isShowing())
        assertTrue(activity.button.hasFocus())
        assertEquals(0, activity.clicks)
        assertEquals(0, activity.pauses)
    }

    @Test fun navigationDestinationsStartTheMatchingPageExactlyOnce() {
        val routes = listOf(0 to MainActivity::class.java, 1 to VodLibraryActivity::class.java)
        for ((index, target) in routes) {
            press(KeyEvent.KEYCODE_MENU); idle(500)
            repeat(index) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
            key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(target.name, intent.component!!.className)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertFalse(activity.isFinishing)
            idle(320)
        }
    }

    @Test fun settingsDestinationOpensTheSystemSettingsPage() {
        press(KeyEvent.KEYCODE_MENU); idle(500)
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER); idle(320)
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.provider.Settings.ACTION_SETTINGS, intent.action)
    }

    @Test fun selectingTheCurrentPageClosesWithoutCreatingAnotherActivity() {
        activity.sidebar.dispose()
        activity.sidebar = AppSidebar(activity, AppSidebar.DESTINATION_LIBRARY)
        press(KeyEvent.KEYCODE_MENU); idle(500)
        val ui = sidebarUi(ShadowDialog.getLatestDialog())
        assertEquals("流媒体", ui.findFocus().contentDescription.toString())
        press(KeyEvent.KEYCODE_DPAD_LEFT); press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("流媒体", ui.findFocus().contentDescription.toString())
        press(KeyEvent.KEYCODE_ENTER); idle(320)
        assertFalse(activity.sidebar.isShowing())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.button.hasFocus())
    }

    @Test fun menuOverAnExistingDialogRestoresItsFocusAndPreservesItsOwnKeyHandler() {
        val underlying = Dialog(activity)
        val input = Button(activity).apply { text = "Dialog action"; isFocusableInTouchMode = true }
        var fallbackKeys = 0
        underlying.setContentView(input)
        activity.sidebar.attachTo(underlying, DialogInterface.OnKeyListener { _, key, _ ->
            if (key == KeyEvent.KEYCODE_A) { fallbackKeys++; true } else false
        })
        underlying.show(); input.requestFocusFromTouch(); idle(32)
        try {
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_A)))
            assertEquals(1, fallbackKeys)
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU)))
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)))
            idle(500)
            val menu = ShadowDialog.getLatestDialog()
            assertNotSame(underlying, menu)
            assertTrue(menu.dispatchKeyEvent(event(KeyEvent.KEYCODE_BACK)))
            idle(320)
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)))
            assertTrue(underlying.isShowing)
            assertFalse(activity.sidebar.isShowing())
            assertTrue(input.hasFocus())
            assertEquals(1, fallbackKeys)
        } finally { underlying.dismiss() }
    }

    @Test fun stoppingDismissesImmediatelyAndWarmReturnCanOpenAFreshMenu() {
        press(KeyEvent.KEYCODE_MENU); idle(500)
        val first = ShadowDialog.getLatestDialog()
        controller.pause().stop()
        assertFalse(first.isShowing)
        assertFalse(activity.sidebar.isShowing())
        controller.start().resume()
        press(KeyEvent.KEYCODE_MENU); idle(500)
        assertTrue(activity.sidebar.isShowing())
        assertNotSame(first, ShadowDialog.getLatestDialog())
    }

    @Test fun dismissAndShowInOneTurnCannotReuseTheDetachedWindowOrLoseTheReplacement() {
        press(KeyEvent.KEYCODE_MENU); idle(500)
        val first = ShadowDialog.getLatestDialog()
        activity.sidebar.dismiss()
        assertFalse(activity.sidebar.isShowing())
        activity.sidebar.show()
        val replacement = ShadowDialog.getLatestDialog()
        assertNotSame(first, replacement)
        assertTrue(activity.sidebar.isShowing())
        idle(520)
        assertFalse(first.isShowing)
        assertTrue(replacement.isShowing)
        assertTrue(sidebarUi(replacement).isOpen)
    }

    @Test fun aCheckedDialogRowBecomesTheRemoteSelectionBeforeTheNextDirectionKey() {
        var chosen = -1
        val underlying = AlertDialog.Builder(activity).setSingleChoiceItems(arrayOf("Main", "Backup", "Third"), 0) { owner, index ->
            chosen = index; owner.dismiss()
        }.create()
        activity.sidebar.attachTo(underlying)
        underlying.show(); idle(32)
        try {
            val list = underlying.listView
            assertTrue(list.requestFocusFromTouch())
            // Native single-choice dialogs opened from touch can have this valid state: a
            // checked current value but no keyboard selection, as on the library line chooser.
            val root = ReflectionHelpers.callInstanceMethod<Any>(underlying.window!!.decorView, "getViewRootImpl")
            ReflectionHelpers.callInstanceMethod<Boolean>(root, "ensureTouchMode",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
            idle(32)
            assertTrue(list.isInTouchMode)
            assertEquals(-1, list.selectedItemPosition)
            assertEquals(0, list.checkedItemPosition)
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU)))
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)))
            idle(520)
            press(KeyEvent.KEYCODE_BACK); idle(330)
            assertTrue(underlying.isShowing)
            assertTrue(list.hasFocus())
            assertEquals(0, list.selectedItemPosition)
            assertEquals(0, list.checkedItemPosition)
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_DPAD_DOWN)))
            underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP))
            idle(32)
            assertEquals(1, list.selectedItemPosition)
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_DPAD_CENTER)))
            assertTrue(underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)))
            assertEquals(1, chosen)
        } finally { underlying.dismiss() }
    }

    @Test fun anExistingRemoteSelectionWinsOverTheCheckedRowAndAReplacedAdapterIsLeftAlone() {
        val underlying = AlertDialog.Builder(activity).setSingleChoiceItems(arrayOf("Main", "Backup", "Third"), 0, null).create()
        activity.sidebar.attachTo(underlying)
        underlying.show(); idle(32)
        try {
            val list = underlying.listView
            assertTrue(list.requestFocusFromTouch()); list.setSelection(2); idle(32)
            assertEquals(2, list.selectedItemPosition)
            underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU))
            underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)); idle(520)
            press(KeyEvent.KEYCODE_BACK); idle(330)
            assertEquals(2, list.selectedItemPosition)
            assertEquals(0, list.checkedItemPosition)
            underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU))
            underlying.dispatchKeyEvent(event(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)); idle(520)
            list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice, arrayOf("New one", "New two", "New three"))
            list.setSelection(1); idle(32)
            press(KeyEvent.KEYCODE_BACK); idle(330)
            assertEquals("A changed data set owns its new selection", 1, list.selectedItemPosition)
        } finally { underlying.dismiss() }
    }

    private fun sidebarUi(dialog: Dialog): TvUi = dialog.window!!.decorView.findViewWithTag("app-sidebar")
    private fun field(ui: TvUi, name: String): Any? = TvUi::class.java.getDeclaredField(name).run { isAccessible = true; get(ui) }
    private fun event(key: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) = KeyEvent(0, 0, action, key, repeat)
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) { assertTrue(activity.dispatchKeyEvent(event(code, action, repeat))) }
    private fun press(code: Int) { key(code); key(code, KeyEvent.ACTION_UP) }
    private fun idle(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }
}
