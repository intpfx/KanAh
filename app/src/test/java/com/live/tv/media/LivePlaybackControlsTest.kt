package com.live.tv.media

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.live.tv.Channel
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LivePlaybackControlsTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var controls: LivePlaybackControls
    private val selected = mutableListOf<Int>()
    private var toggles = 0
    private var settings = 0
    private var desktop = 0

    @Before fun attach() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        controls = LivePlaybackControls(controller.get(), object : LivePlaybackControls.Actions {
            override fun select(index: Int) { selected += index }
            override fun toggle() { toggles++ }
            override fun settings() { settings++ }
            override fun desktopMode() { desktop++ }
        })
        val root = FrameLayout(controller.get()).apply { addView(controls, FrameLayout.LayoutParams(-1, -1)) }
        controller.get().setContentView(root)
        controls.setChannels((0..17).map(Channel::web), 13)
        controls.playback(true, false)
        idle(64)
    }

    @After fun close() {
        controls.dispose(); controller.pause().stop().destroy()
    }

    @Test fun liveUsesTheSameTitleIconStatusAndChannelStrip() {
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals("CCTV-13 新闻", controls.chrome.titleView.text.toString())
        assertEquals(13, controls.chrome.selectedIndex)
        assertNull(controls.findViewWithTag<TextView>("live-speed"))
        assertNull(controls.findViewWithTag<TextView>("live-source"))
    }

    @Test fun pauseChangesOnlyTheIconAndStatus() {
        controls.playback(true, true)
        assertEquals(13, controls.chrome.selectedIndex)
    }

    @Test fun utilityControlsAreNotPartOfThePlayerChrome() {
        assertNull(controls.findViewWithTag<TextView>("live-settings"))
        assertNull(controls.findViewWithTag<TextView>("live-desktop"))
    }

    @Test fun verticalListPreviewAndConfirmAloneChangesChannel() {
        controls.hide()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(controls.isSelectingChannels)
        assertEquals(14, controls.chrome.candidateIndex)
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(12, controls.chrome.candidateIndex)
        assertEquals(13, controls.chrome.selectedIndex)
        assertTrue(selected.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(12), selected)
        assertEquals(0, toggles)
        assertFalse(controls.isSelectingChannels)
        assertFalse(controls.isShowingControls)
    }

    @Test fun backCancelsPreviewWithoutChangingChannel() {
        controls.openChannels()
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_BACK)
        assertFalse(controls.isShowingControls)
        controls.openChannels()
        assertEquals(13, controls.chrome.candidateIndex)
        assertTrue(selected.isEmpty()); assertEquals(0, toggles)
    }

    @Test fun horizontalKeysDoNotChangeChannelOrFocusTitle() {
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(selected.isEmpty())
        assertFalse(controls.chrome.titleView.isFocusable)
    }

    private fun press(code: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val up = KeyEvent(KeyEvent.ACTION_UP, code)
        if (controls.handleKey(down)) controls.handleKey(up) else {
            controls.dispatchKeyEvent(down); controls.dispatchKeyEvent(up)
        }
        idle(32)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
