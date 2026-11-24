package com.live.tv.media

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.KeyEvent
import android.widget.FrameLayout
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class EpisodePickerViewTest {
    @Test fun holdingConfirmLongPressesWithoutAlsoPlaying() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val picker = EpisodePickerView(activity)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(picker)
        picker.layout(0, 0, 480, 48)
        picker.setEpisodes(listOf("第01集", "第02集"), 0)
        picker.requestFocus()
        val selected = mutableListOf<Int>()
        val longPressed = mutableListOf<Int>()
        picker.onSelected = { selected.add(it) }
        picker.onLongSelected = { longPressed.add(it) }
        picker.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1150))
        assertEquals(listOf(0), longPressed)
        picker.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue("long press must not also play", selected.isEmpty())
    }

    @Test fun shortConfirmStillPlays() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val picker = EpisodePickerView(activity)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(picker)
        picker.layout(0, 0, 480, 48)
        picker.setEpisodes(listOf("第01集", "第02集"), 1)
        picker.requestFocus()
        val selected = mutableListOf<Int>()
        picker.onSelected = { selected.add(it) }
        picker.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        picker.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf(1), selected)
    }

    @Test fun keyUpWithoutAMatchingDownDoesNotRestartTheEpisode() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val picker = EpisodePickerView(activity)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(picker)
        picker.layout(0, 0, 480, 48)
        picker.setEpisodes(listOf("第01集", "第02集"), 1)
        picker.requestFocus()
        val selected = mutableListOf<Int>()
        picker.onSelected = { selected.add(it) }
        picker.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue("a reveal key-up must only show controls, not play", selected.isEmpty())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun progressFillsTheEpisodeTextInsteadOfDrawingABarBelow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val picker = EpisodePickerView(activity).apply { setThemeColor(Color.rgb(210, 40, 50)) }
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(picker)
        picker.layout(0, 0, 240, 48)
        fun render(fraction: Float): Bitmap {
            picker.setEpisodes(listOf("第01集"), 0, floatArrayOf(fraction))
            return Bitmap.createBitmap(240, 48, Bitmap.Config.ARGB_8888).also { picker.draw(Canvas(it)) }
        }
        fun opaquePixels(bitmap: Bitmap): Int {
            var count = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) >= 250) count++
            }
            return count
        }
        val half = render(.5f)
        val full = render(1f)
        try {
            assertTrue("watched text must stay painted", opaquePixels(half) > 0)
            assertTrue("full progress must cover more glyph pixels than half progress",
                opaquePixels(full) > opaquePixels(half))
            for (bitmap in listOf(half, full)) {
                for (y in 36 until 40) for (x in 0 until bitmap.width) {
                    assertEquals("progress must not draw a bar under the text",
                        0, Color.alpha(bitmap.getPixel(x, y)))
                }
            }
        } finally {
            half.recycle(); full.recycle()
        }
    }
}
