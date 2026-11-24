package com.live.tv.music.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import com.live.tv.music.core.SynchronizedLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeMusicLyricsViewTest {
    @Test fun centerTouchSelectsTheCurrentCueInAFullHeightViewport() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = NativeMusicLyricsView(activity, {}) {}
        view.layout(0, 0, 826, 1080)
        val lyrics = List(40) { SynchronizedLyricLine(it.toDouble(), "第 ${it + 1} 行") }
        view.render("track", lyrics, 30.0, false)

        val tap = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 100f, 540f, 0)
        try {
            assertTrue(view.onTouchEvent(tap))
            assertEquals("歌词，第 31 行", view.contentDescription)
        } finally {
            tap.recycle()
        }
    }

    @Test fun manualCueSurvivesPlaybackTicksAndOnlyConfirmationSeeks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val seeks = mutableListOf<Double>()
        var transportFocus = 0
        val view = NativeMusicLyricsView(activity, { seeks += it }) { transportFocus++ }
        activity.setContentView(view)
        assertFalse(view.isFocusable)

        val lyrics = listOf(
            SynchronizedLyricLine(0.0, "第一行"),
            SynchronizedLyricLine(3.0, "第二行"),
            SynchronizedLyricLine(7.0, "第三行"),
        )
        view.render("one", lyrics, 0.0, false)
        assertTrue(view.isFocusable)
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, down(KeyEvent.KEYCODE_DPAD_DOWN)))
        view.render("one", lyrics, 9.0, false)
        assertEquals("歌词，第二行", view.contentDescription)
        assertTrue(seeks.isEmpty())
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, down(KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(listOf(3.0), seeks)

        view.render("one", lyrics, 7.0, false)
        assertEquals("歌词，第三行", view.contentDescription)
        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, down(KeyEvent.KEYCODE_DPAD_DOWN)))
        assertEquals(1, transportFocus)
        view.render("two", lyrics, 0.0, false)
        assertEquals("歌词，第一行", view.contentDescription)
    }

    private fun down(key: Int) = KeyEvent(KeyEvent.ACTION_DOWN, key)
}
