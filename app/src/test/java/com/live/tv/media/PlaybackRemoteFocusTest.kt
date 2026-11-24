package com.live.tv.media

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.PlaybackParameters
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackRemoteFocusTest {
    @Test fun lanDirectionsTraverseAndConfirmFocusedControlWhileRevealDoesNotSkipTitle() {
        val c = Robolectric.buildActivity(MediaPlaybackActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MediaPlaybackActivity::class.java)).create().start().resume().visible().windowFocusChanged(true)
        val a = c.get()
        val controls = MediaPlaybackActivity::class.java.getDeclaredField("controls").apply { isAccessible = true }.get(a) as TvPlaybackControls
        var speed = 1f
        var position = 10_000L
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "isPlaying", "getPlayWhenReady", "isCurrentMediaItemSeekable", "isCommandAvailable" -> true
                "getPlaybackState" -> Player.STATE_READY
                "getDuration" -> 90_000L
                "getCurrentPosition", "getBufferedPosition" -> position
                "seekTo" -> { position = args!!.last() as Long; null }
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "setPlaybackSpeed" -> { speed = args!![0] as Float; null }
                "getCurrentTracks" -> Tracks.EMPTY
                else -> when (m.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null }
            }
        } as Player
        try {
            controls.bind(player)
            controls.show()
            controls.episodes(listOf("第01集", "第02集", "第03集"), 0)
            a.window.decorView.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            a.window.decorView.layout(0, 0, 1920, 1080)
            controls.hide()
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(controls.heading.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("Web right must seek without leaving title", controls.heading.hasFocus())
            assertEquals(20_000L, position)
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue("Web down must reach episode picker", controls.episodePicker.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue(controls.episodePicker.hasFocus())
            assertTrue(controls.episodePicker.contentDescription.contains("第02集"))
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
            assertTrue(controls.speed.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
            assertNotEquals(1f, speed)
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("Missing audio still has a navigable status control", controls.audio.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("Missing subtitles still have a navigable status control", controls.subtitles.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            assertTrue("Right edge stays inside action row", controls.subtitles.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
            assertTrue(controls.audio.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(controls.episodePicker.hasFocus())
            a.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
            assertTrue(controls.heading.hasFocus())
        } finally { controls.bind(null); c.pause().stop().destroy() }
    }
}
