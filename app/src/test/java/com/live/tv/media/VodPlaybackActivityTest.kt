package com.live.tv.media

import android.content.Intent
import android.os.Looper
import android.widget.TextView
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodPlaybackActivityTest {
    private fun field(activity: MediaPlaybackActivity, name: String): Any? =
        MediaPlaybackActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun controller() = Robolectric.buildActivity(MediaPlaybackActivity::class.java,
        Intent(ApplicationProvider.getApplicationContext(), MediaPlaybackActivity::class.java).putExtra("vod_ref", "{sourceId:'s',vodId:'v'}")).create()
    private fun reference(episode: String = "episode-1") = JSONObject().put("sourceId", "safe-source").put("vodId", "film-1")
        .put("lineId", "line-1").put("episodeKey", episode).put("revision", 3).put("epoch", 7)
    private fun controller(ref: JSONObject) = Robolectric.buildActivity(MediaPlaybackActivity::class.java,
        Intent(ApplicationProvider.getApplicationContext(), MediaPlaybackActivity::class.java).putExtra("vod_ref", ref.toString())).create()
    private fun set(activity: MediaPlaybackActivity, name: String, value: Any?) {
        MediaPlaybackActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue(condition())
    }
    @Test fun legacyIdsCannotLoadHistoricalMediaAndMissingVodReferenceRejectsBeforeResolver() {
        val context:android.content.Context=ApplicationProvider.getApplicationContext()
        val legacy=context.getFileStreamPath("media-library.json").apply{writeText("historical media database")}
        val cache=context.getFileStreamPath("media-cache.json").apply{writeText("historical cache metadata")}
        val c=Robolectric.buildActivity(MediaPlaybackActivity::class.java,Intent(context,MediaPlaybackActivity::class.java)
            .putExtra("id","old-media").putExtra("media_id","old-media")).create()
        val a=c.get();var calls=0
        a.vodResolver={_,_->calls++;error("Legacy identifiers cannot invoke online resolver")}
        try {
            c.start().resume()
            await{MediaPlaybackActivity.playbackState().optString("error")=="INVALID_REFERENCE"}
            assertEquals(0,calls);assertNull(field(a,"player"));assertEquals("",field(a,"id"))
            assertEquals("historical media database",legacy.readText());assertEquals("historical cache metadata",cache.readText())
        }finally{c.pause().stop().destroy()}
    }
    @Test fun stableVodProgressPersistsAndUnstableReferencesCannotOverwriteIt() {
        val c=controller();val a=c.get()
        fun set(name:String,value:Any){MediaPlaybackActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(a,value)}
        var position=120_000L;var state=androidx.media3.common.Player.STATE_READY
        val player=java.lang.reflect.Proxy.newProxyInstance(androidx.media3.common.Player::class.java.classLoader,arrayOf(androidx.media3.common.Player::class.java)){_,method,_->
            when(method.name){"getCurrentPosition"->position;"getPlaybackState"->state;"getDuration"->0L;else->error("Unexpected player method ${method.name}")}
        } as androidx.media3.common.Player
        val save=MediaPlaybackActivity::class.java.getDeclaredMethod("saveProgress",androidx.media3.common.Player::class.java).apply{isAccessible=true}
        try{
            set("id","stable-reference");set("resumeAllowed",true);save.invoke(a,player)
            val preferences=a.getSharedPreferences("media-progress",0)
            assertEquals(position,preferences.getLong("stable-reference",-1))
            set("resumeAllowed",false);position=1_000;save.invoke(a,player);assertEquals(120_000L,preferences.getLong("stable-reference",-1))
            set("resumeAllowed",true);state=androidx.media3.common.Player.STATE_ENDED;save.invoke(a,player);assertEquals(0L,preferences.getLong("stable-reference",-1))
        }finally{c.destroy()}
    }
    @Test fun parserFailureShowsActionableMessageWithoutStartingPlayer() {
        val c = controller(); val a = c.get()
        a.vodResolver = { _, _ -> throw VodException("REQUIRES_PARSER", "此来源需要解析，当前版本暂不支持") }
        try {
            c.start().resume()
            await { MediaPlaybackActivity.playbackState().optString("error") == "REQUIRES_PARSER" }
            assertNull(field(a, "player"))
            assertEquals("此来源需要解析，当前版本暂不支持", (field(a, "message") as TextView).text.toString())
            assertTrue(MediaPlaybackActivity.playbackState().getBoolean("vod"))
        } finally { c.pause().stop().destroy() }
    }
    @Test fun stoppingPlaybackCancelsResolutionAndIgnoresLateFailure() {
        val c = controller(); val a = c.get(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var pending: VodRequest? = null
        a.vodResolver = { _, request -> pending = request; entered.countDown(); release.await(); throw VodException("STALE_SELECTION", "late error") }
        try {
            c.start().resume(); assertTrue(entered.await(2, TimeUnit.SECONDS)); c.pause().stop()
            assertTrue(pending!!.isCancelled()); release.countDown()
            val marker = CountDownLatch(1)
            (field(a, "worker") as java.util.concurrent.ExecutorService).execute { marker.countDown() }
            assertTrue(marker.await(2, TimeUnit.SECONDS)); shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", field(a, "playbackError")); assertNull(field(a, "player"))
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("active"))
        } finally { release.countDown(); c.destroy() }
    }
    @Test fun playbackSessionPublishesOnlyEpisodeIdentityAndOlderActivityCannotClearNewerSession() {
        val privateRef = reference().put("url", "https://private.invalid/film.m3u8?token=private-token")
            .put("headers", JSONObject().put("Authorization", "Bearer private-credential"))
            .put("nested", JSONObject().put("locator", "private-locator"))
        val first = controller(privateRef); val firstEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val second = controller(reference("episode-2")); val secondEntered = CountDownLatch(1)
        first.get().vodResolver = { _, _ -> firstEntered.countDown(); release.await(); throw VodException("CANCELLED", "fixture") }
        second.get().vodResolver = { _, _ -> secondEntered.countDown(); release.await(); throw VodException("CANCELLED", "fixture") }
        try {
            first.start().resume(); assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val initial = MediaPlaybackActivity.playbackState()
            val firstSession = initial.getString("sessionId")
            assertTrue(firstSession.isNotBlank())
            assertTrue(initial.getBoolean("active"))
            assertFalse(initial.getBoolean("playing"))
            assertFalse(initial.getBoolean("renderedFirstFrame"))
            assertEquals("", initial.getString("id"))
            val safe = initial.getJSONObject("vodRef")
            assertEquals(setOf("sourceId", "vodId", "lineId", "episodeKey", "revision", "epoch"), safe.keys().asSequence().toSet())
            assertEquals("episode-1", safe.getString("episodeKey"))
            assertEquals(3, safe.getInt("revision")); assertEquals(7, safe.getInt("epoch"))
            assertFalse(initial.toString().contains("private"))
            // Published JSON is a snapshot, so a caller cannot poison a later state response.
            safe.put("url", "injected")
            assertFalse(MediaPlaybackActivity.playbackState().getJSONObject("vodRef").has("url"))
            second.start().resume(); assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
            val replacement = MediaPlaybackActivity.playbackState()
            assertNotEquals(firstSession, replacement.getString("sessionId"))
            assertEquals("episode-2", replacement.getJSONObject("vodRef").getString("episodeKey"))
            first.pause().stop()
            assertEquals(replacement.getString("sessionId"), MediaPlaybackActivity.playbackState().getString("sessionId"))
            second.pause().stop()
            val inactive = MediaPlaybackActivity.playbackState()
            assertFalse(inactive.getBoolean("active"))
            assertEquals("", inactive.getString("sessionId"))
            assertTrue(inactive.isNull("vodRef"))
        } finally { release.countDown(); first.destroy(); second.destroy() }
    }
    @Test fun readyAndPlayingDoNotClaimFirstFrameAndLateCallbacksCannotVerifyNewSession() {
        val c = controller(reference()); val a = c.get(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        a.vodResolver = { _, _ -> entered.countDown(); release.await(); throw VodException("CANCELLED", "fixture") }
        val seekPositions = mutableListOf<Long>()
        var playing = true; var seekable = true; var seekCommand = true; var duration = 60_000L
        fun nativePlayer() = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)) { _, method, args ->
                when (method.name) {
                    "getCurrentPosition" -> 4_000L
                    "getDuration" -> duration
                    "isCurrentMediaItemSeekable" -> seekable
                    "isCommandAvailable" -> seekCommand
                    "getPlaybackState" -> androidx.media3.common.Player.STATE_READY
                    "isPlaying" -> playing
                    "seekTo" -> { seekPositions.add(args!!.last() as Long); null }
                    else -> null
                }
            } as androidx.media3.exoplayer.ExoPlayer
        val frame = MediaPlaybackActivity::class.java.getDeclaredMethod("onSessionFirstFrame", Int::class.javaPrimitiveType,
            androidx.media3.common.Player::class.java).apply { isAccessible = true }
        try {
            c.start().resume(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val firstSession = MediaPlaybackActivity.playbackState().getString("sessionId")
            val generation = field(a, "generation") as Int
            val player = nativePlayer(); set(a, "player", player)
            val ready = MediaPlaybackActivity.playbackState()
            assertTrue(ready.getBoolean("playing"))
            assertTrue(ready.getBoolean("seekable"))
            assertTrue(MediaPlaybackActivity.remoteSeekTo(firstSession, 42_123))
            assertTrue(MediaPlaybackActivity.remoteSeekTo(firstSession, 20_007))
            assertFalse(MediaPlaybackActivity.remoteSeekTo("stale-session", 20_000))
            assertTrue(MediaPlaybackActivity.remoteSeekTo(firstSession, 0))
            assertTrue(MediaPlaybackActivity.remoteSeekTo(firstSession, 99_999))
            assertEquals(listOf(42_123L, 20_007L, 0L, 60_000L), seekPositions)
            assertFalse(MediaPlaybackActivity.remoteSeekTo(firstSession, -1))
            playing = false
            assertFalse(MediaPlaybackActivity.remoteSeekTo(firstSession, 42_123))
            playing = true; seekable = false
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("seekable"))
            assertFalse(MediaPlaybackActivity.remoteSeekTo(firstSession, 20_007))
            seekable = true; seekCommand = false
            assertFalse(MediaPlaybackActivity.remoteSeekTo(firstSession, 42_123))
            seekCommand = true; duration = -1
            assertFalse(MediaPlaybackActivity.remoteSeekTo(firstSession, 42_123))
            duration = 60_000L

            assertEquals(androidx.media3.common.Player.STATE_READY, ready.getInt("state"))
            assertFalse(ready.getBoolean("renderedFirstFrame"))
            frame.invoke(a, generation - 1, player)
            frame.invoke(a, generation, nativePlayer())
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("renderedFirstFrame"))
            frame.invoke(a, generation, player)
            assertTrue(MediaPlaybackActivity.playbackState().getBoolean("renderedFirstFrame"))
            c.pause().stop(); c.start().resume()
            val restarted = MediaPlaybackActivity.playbackState()
            assertNotEquals(firstSession, restarted.getString("sessionId"))
            assertFalse(restarted.getBoolean("renderedFirstFrame"))
            assertFalse(restarted.getBoolean("verifiedPlayback"))
            assertFalse(restarted.getBoolean("playing"))
            frame.invoke(a, generation, player)
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("renderedFirstFrame"))
        } finally { c.pause().stop(); release.countDown(); c.destroy() }
    }
    @Test fun remotePreviewNeverSeeksAndTerminalOrExpiredGesturesCannotReopen() {
        val c = controller(reference()); val a = c.get(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        a.vodResolver = { _, _ -> entered.countDown(); release.await(); throw VodException("CANCELLED", "fixture") }
        val seeks = mutableListOf<Long>(); var playing = true
        val player = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.exoplayer.ExoPlayer::class.java.classLoader,
            arrayOf(androidx.media3.exoplayer.ExoPlayer::class.java)) { _, method, args ->
                when (method.name) {
                    "getCurrentPosition" -> 4_000L
                    "getDuration" -> 60_000L
                    "isPlaying" -> playing
                    "isCurrentMediaItemSeekable", "isCommandAvailable" -> true
                    "seekTo" -> { seeks.add(args!!.last() as Long); null }
                    else -> null
                }
            } as androidx.media3.exoplayer.ExoPlayer
        try {
            c.start().resume(); assertTrue(entered.await(2, TimeUnit.SECONDS)); set(a, "player", player)
            val session = field(a, "sessionId") as String
            fun send(gesture: String, seq: Long, phase: String, target: Long = 42_123) =
                MediaPlaybackActivity.remoteScrub(session, gesture, seq, phase, target)
            assertFalse(MediaPlaybackActivity.remoteScrub("old-session", "old", 1, "preview", 100))
            assertTrue(send("first", 1, "preview")); assertTrue(seeks.isEmpty())
            assertEquals("first", field(a, "remoteScrubGesture"))
            assertFalse(send("first", 1, "preview", 10))
            assertTrue(send("first", 2, "commit", 99_999)); assertEquals(listOf(60_000L), seeks)
            assertNull(field(a, "remoteScrubGesture")); assertFalse(send("first", 3, "preview"))
            assertTrue(send("cancelled-before-preview", 4, "cancel"))
            assertFalse(send("cancelled-before-preview", 1, "preview"))
            assertTrue(send("paused-cancel", 1, "preview")); playing = false
            assertTrue(send("paused-cancel", 2, "cancel")); assertNull(field(a, "remoteScrubGesture")); playing = true
            assertTrue(send("expired", 1, "preview"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
            assertTrue(send("expired", 2, "preview"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2000))
            assertEquals("expired", field(a, "remoteScrubGesture"))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(501))
            assertNull(field(a, "remoteScrubGesture")); assertFalse(send("expired", 3, "preview"))
            assertTrue(send("replaced", 1, "preview")); assertTrue(send("new", 1, "preview"))
            assertFalse(send("replaced", 2, "preview")); assertTrue(send("new", 2, "cancel"))
            assertEquals(listOf(60_000L), seeks)
        } finally { c.pause().stop(); release.countDown(); c.destroy() }
    }
    @Test fun malformedReferenceCannotLeakObjectsThroughPublicEpisodeIdentity() {
        val ref = reference().put("vodId", JSONObject().put("url", "https://private.invalid/token"))
        val c = controller(ref); val a = c.get(); val release = CountDownLatch(1)
        a.vodResolver = { _, _ -> release.await(); throw VodException("INVALID_REFERENCE", "fixture") }
        try {
            c.start().resume()
            val state = MediaPlaybackActivity.playbackState()
            assertTrue(state.getBoolean("active"))
            assertTrue(state.isNull("vodRef"))
            assertFalse(state.toString().contains("private.invalid"))
        } finally { c.pause().stop(); release.countDown(); c.destroy() }
    }
    @Test fun destroyingAnActiveAttemptWithoutStopReleasesRemoteOwnerAndCancelsResolution() {
        val c = controller(reference()); val a = c.get(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var request: VodRequest? = null
        a.vodResolver = { _, pending -> request = pending; entered.countDown(); release.await(); throw VodException("CANCELLED", "fixture") }
        try {
            c.start().resume(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertSame(a, MediaPlaybackActivity.currentActivity())
            assertTrue(MediaPlaybackActivity.isForeground())
            c.destroy()
            assertTrue(request!!.isCancelled())
            assertFalse(MediaPlaybackActivity.isForeground())
            assertNull(MediaPlaybackActivity.currentActivity())
            assertFalse(MediaPlaybackActivity.remoteKey("back"))
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("active"))
        } finally {
            release.countDown()
            if (!a.isDestroyed) c.pause().stop().destroy()
        }
    }
    @Test fun staleForegroundFlagWithoutALiveOwnerCannotCaptureRemoteKeys() {
        val foreground = MediaPlaybackActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        val current = MediaPlaybackActivity::class.java.getDeclaredField("current").apply { isAccessible = true }
        current.set(null, java.lang.ref.WeakReference<MediaPlaybackActivity>(null))
        foreground.setBoolean(null, true)
        try {
            assertFalse(MediaPlaybackActivity.isForeground())
            assertNull(MediaPlaybackActivity.currentActivity())
            assertFalse(MediaPlaybackActivity.remoteKey("back"))
            assertFalse(MediaPlaybackActivity.playbackState().getBoolean("active"))
        } finally { foreground.setBoolean(null, false); current.set(null, null) }
    }
    @Test fun remoteConfirmationRevealsControlsAndBackHidesBeforeExit() {
        val c = controller().visible(); val a = c.get()
        val controls = field(a, "controls") as TvPlaybackControls
        fun press(key: Int) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)) }
        try {
            assertEquals(View.GONE, controls.visibility)
            press(KeyEvent.KEYCODE_DPAD_CENTER); assertEquals(View.VISIBLE, controls.visibility); assertFalse(a.isFinishing)
            press(KeyEvent.KEYCODE_BACK); assertEquals(View.GONE, controls.visibility); assertFalse(a.isFinishing)
            press(KeyEvent.KEYCODE_BACK); assertTrue(a.isFinishing)
        } finally { c.destroy() }
    }
    @Test fun adjacentReferencesRejectAnotherFilmLineRevisionOrCurrentEpisode() {
        val c = controller(reference()); val a = c.get()
        val method = MediaPlaybackActivity::class.java.getDeclaredMethod("adjacentReference", JSONObject::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            for ((key, value) in listOf("sourceId" to "other", "vodId" to "other", "lineId" to "other", "revision" to 4, "epoch" to 8, "episodeKey" to "episode-1")) {
                assertNull(method.invoke(a, reference("episode-2").put(key, value), reference()))
            }
            val safe = method.invoke(a, reference("episode-2").put("url", "private-locator"), reference()) as JSONObject
            assertEquals("episode-2", safe.getString("episodeKey")); assertFalse(safe.has("url"))
        } finally { c.destroy() }
    }
    @Test fun episodeSwitchCancelsOldAttemptResetsSessionAndIgnoresLateFailure() {
        val c = controller(reference()); val a = c.get()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var firstRequest: VodRequest? = null
        a.vodResolver = { ref, request ->
            if (ref.getString("episodeKey") == "episode-1") {
                firstRequest = request; entered.countDown(); release.await()
                throw VodException("OLD_ERROR", "old attempt")
            }
            throw VodException("NEW_ERROR", "new attempt")
        }
        try {
            c.start().resume(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val session = MediaPlaybackActivity.playbackState().getString("sessionId")
            set(a, "nextEpisode", reference("episode-2")); set(a, "renderedFirstFrame", true)
            val controls = field(a, "controls") as TvPlaybackControls
            set(a, "episodeItems", listOf(PlaybackEpisodeCatalog.Item("第01集", reference("episode-1")), PlaybackEpisodeCatalog.Item("第02集", reference("episode-2"))))
            set(a, "selectedEpisodeIndex", 0)
            controls.episodes(listOf("第01集", "第02集"), 0)
            controls.onEpisodeSelected?.invoke(0)
            controls.onEpisodeSelected?.invoke(9)
            assertEquals(session, MediaPlaybackActivity.playbackState().getString("sessionId"))
            controls.onEpisodeSelected?.invoke(1)
            assertTrue(firstRequest!!.isCancelled())
            val switched = MediaPlaybackActivity.playbackState()
            assertNotEquals(session, switched.getString("sessionId"))
            assertEquals("episode-2", switched.getJSONObject("vodRef").getString("episodeKey"))
            assertFalse(switched.getBoolean("renderedFirstFrame")); assertEquals("", switched.getString("id"))
            assertEquals(-1, field(a, "selectedEpisodeIndex"))
            release.countDown()
            await { MediaPlaybackActivity.playbackState().optString("error") == "NEW_ERROR" }
            assertFalse(a.isFinishing)
        } finally { release.countDown(); c.pause().stop().destroy() }
    }
}
