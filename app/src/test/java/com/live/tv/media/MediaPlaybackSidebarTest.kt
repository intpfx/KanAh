package com.live.tv.media

import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.live.tv.AppSidebar
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaPlaybackSidebarTest {
    private lateinit var controller: ActivityController<MediaPlaybackActivity>
    private lateinit var activity: MediaPlaybackActivity
    private lateinit var controls: TvPlaybackControls

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        // No media reference: the real resolver rejects locally before opening any source.
        controller = Robolectric.buildActivity(MediaPlaybackActivity::class.java,
            Intent(ApplicationProvider.getApplicationContext(), MediaPlaybackActivity::class.java)).setup().visible()
        activity = controller.get()
        val drained = CountDownLatch(1)
        (field("worker") as ExecutorService).execute { drained.countDown() }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        idle(32)
        controller.windowFocusChanged(true)
        controls = field("controls") as TvPlaybackControls
    }

    @After fun tearDown() { controller.pause().stop().destroy() }

    @Test fun physicalAndLanMenuPreserveBothPlayingAndPausedSessionsWithoutRevealingPlaybackControls() {
        for (playing in listOf(true, false)) {
            val mutations = mutableListOf<String>()
            val player = player(playing, mutations)
            set("player", player)
            controls.bind(player); controls.hide()
            val session = field("sessionId")
            val generation = field("generation")
            if (playing) {
                key(KeyEvent.KEYCODE_MENU)
                key(KeyEvent.KEYCODE_MENU, repeat = 1)
                key(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_UP)
            } else assertTrue(MediaPlaybackActivity.remoteKey("menu"))
            idle(520)
            val popup = ShadowDialog.getLatestDialog()
            assertTrue(popup.isShowing)
            assertFalse(controls.isShowingControls)
            key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP)
            key(KeyEvent.KEYCODE_BACK)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            idle(330)
            key(KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
            key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
            key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)
            assertFalse(popup.isShowing)
            assertFalse(activity.isFinishing)
            assertSame(player, field("player"))
            assertEquals(session, field("sessionId"))
            assertEquals(generation, field("generation"))
            assertEquals(playing, MediaPlaybackActivity.playbackState().getBoolean("playing"))
            assertEquals(10_000L, MediaPlaybackActivity.playbackState().getLong("positionMs"))
            assertFalse(controls.isShowingControls)
            assertTrue("A menu never mutates the decoder or playback state: $mutations", mutations.isEmpty())
            controls.bind(null)
        }
    }

    @Test fun pauseWithoutStopReleasesTheDecoderAndResumeCreatesANewPlaybackSession() {
        val mutations = mutableListOf<String>()
        val decoder = player(true, mutations)
        set("player", decoder); controls.bind(decoder); controls.show()
        val session = field("sessionId")
        val request = field("vodRequest") as VodRequest
        assertSame(activity, MediaPlaybackActivity.currentActivity())
        assertTrue(MediaPlaybackActivity.playbackState().getBoolean("playing"))

        controller.windowFocusChanged(false); controller.pause()
        try {
            assertEquals("onPause must release the decoder before onStop", listOf("pause", "release"), mutations)
            assertNull(field("player")); assertNull(field("vodRequest"))
            assertTrue(request.isCancelled())
            assertFalse(controls.isShowingControls)
            assertNull(MediaPlaybackActivity.currentActivity())
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("active"))
            assertFalse("paused playback cannot own LAN remote commands", MediaPlaybackActivity.remoteKey("menu"))
        } finally {
            controller.resume(); controller.windowFocusChanged(true)
        }

        val drained = CountDownLatch(1)
        (field("worker") as ExecutorService).execute { drained.countDown() }
        assertTrue(drained.await(2, TimeUnit.SECONDS)); idle(32)
        assertSame(activity, MediaPlaybackActivity.currentActivity())
        assertTrue(MediaPlaybackActivity.playbackState().getBoolean("active"))
        assertNotEquals("resume must initialize a new playback session", session, field("sessionId"))
        assertNotSame(request, field("vodRequest"))
        assertFalse((field("vodRequest") as VodRequest).isCancelled())
        assertEquals("the resumed initialization still rejects the missing reference locally", "INVALID_REFERENCE", field("playbackError"))
        assertNull("the released decoder must never be reused", field("player"))
        assertEquals(listOf("pause", "release"), mutations)
    }

    @Test fun closingRestoresTheExistingPlaybackControlAndDirectionsStillRevealControls() {
        val mutations = mutableListOf<String>()
        val player = player(true, mutations)
        set("player", player); controls.bind(player); controls.show()
        val shownAt = android.os.SystemClock.uptimeMillis()
        assertTrue("Controls must open before the menu fixture", controls.isShowingControls)
        idle(32)
        // A programmatic show does not perform ViewRoot's transition from touch to remote focus.
        // Establish the already-focused control whose focus the menu must later restore.
        val hierarchy = generateSequence(controls.heading as android.view.View?) { it.parent as? android.view.View }
            .joinToString(" | ") { "${it.javaClass.simpleName} ${it.width}x${it.height} visibility=${it.visibility} shown=${it.isShown} enabled=${it.isEnabled} focusable=${it.isFocusable} touch=${it.isInTouchMode} descendants=${(it as? android.view.ViewGroup)?.descendantFocusability}" }
        assertTrue("elapsed=${android.os.SystemClock.uptimeMillis() - shownAt}; $hierarchy", controls.heading.requestFocusFromTouch())
        assertTrue(controls.heading.hasFocus())
        press(KeyEvent.KEYCODE_MENU); idle(520)
        press(KeyEvent.KEYCODE_BACK); idle(330)
        assertTrue(controls.isShowingControls)
        assertTrue(controls.heading.hasFocus())
        controls.hide()
        activity.dispatchRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(controls.isShowingControls)
        assertTrue(controls.heading.hasFocus())
        assertTrue(mutations.isEmpty())
    }

    @Test fun aFailedPlaybackCanNavigateToTheLibraryWithTheSameSidebar() {
        assertEquals("INVALID_REFERENCE", field("playbackError"))
        press(KeyEvent.KEYCODE_MENU); idle(520)
        press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_ENTER)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(VodLibraryActivity::class.java.name, intent.component!!.className)
        assertEquals(AppSidebar.DESTINATION_LIBRARY, intent.getStringExtra(AppSidebar.EXTRA_DESTINATION))
        assertEquals("INVALID_REFERENCE", field("playbackError"))
        assertNull(field("player"))
    }

    private fun player(playing: Boolean, mutations: MutableList<String>): ExoPlayer = Proxy.newProxyInstance(
        ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { instance, method, args ->
        when (method.name) {
            "isPlaying", "getPlayWhenReady" -> playing
            "isCurrentMediaItemSeekable", "isCommandAvailable" -> true
            "getPlaybackState" -> Player.STATE_READY
            "getDuration" -> 90_000L
            "getCurrentPosition", "getBufferedPosition" -> 10_000L
            "getCurrentTracks" -> Tracks.EMPTY
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "getApplicationLooper" -> Looper.getMainLooper()
            "play", "pause", "prepare", "stop", "release", "seekTo", "setPlayWhenReady", "setMediaItem" -> { mutations += method.name; null }
            "equals" -> instance === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(instance)
            "toString" -> "Offline test player"
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    } as ExoPlayer

    private fun field(name: String): Any? = MediaPlaybackActivity::class.java.getDeclaredField(name).run { isAccessible = true; get(activity) }
    private fun set(name: String, value: Any?) { MediaPlaybackActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) {
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 0, action, code, repeat)))
    }
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
