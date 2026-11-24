package com.live.tv.media

import android.app.Activity
import android.os.Looper
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayPauseIconViewTest {
    @Test fun togglesImmediatelyAndAnimates() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val picker = PlayPauseIconView(activity)
        val root = FrameLayout(activity)
        activity.setContentView(root)
        root.addView(picker)
        picker.measure(MeasureSpec.makeMeasureSpec(30, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(30, MeasureSpec.EXACTLY))
        picker.layout(0, 0, 30, 30)
        picker.setPlaying(true, animate = false)
        assertEquals(1f, picker.progressFraction, 0.01f)
        picker.setPlaying(false, animate = false)
        assertEquals(0f, picker.progressFraction, 0.01f)
        picker.setPlaying(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(1f, picker.progressFraction, 0.02f)
    }
}
