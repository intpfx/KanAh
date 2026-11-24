package com.live.tv.media

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.time.Duration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog

/** The redesigned streaming page: top controls, one horizontal row, bottom focused-item detail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class VodLibraryScreenTest {
    private fun idle(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun await(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        var waited = 0L
        while (waited <= timeoutMs) { idle(16); if (condition()) return; waited += 16 }
        fail("library did not settle")
    }
    private fun layout(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 960, 540)
    }
    private fun view(a: Activity, tag: String): View? = a.findViewById<ViewGroup>(android.R.id.content).findViewWithTag(tag)
    private fun text(a: Activity, tag: String) = (view(a, tag) as TextView).text.toString()
    private fun cards(a: Activity): List<View> {
        val found = mutableListOf<View>()
        fun visit(v: View) {
            if (v.tag == "library-card") found.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(a.findViewById(android.R.id.content))
        return found
    }
    private fun press(a: Activity, code: Int) {
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0))
        a.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0))
        idle(32)
    }
    private fun open(data: LibraryData = Fixture()): ActivityController<VodLibraryActivity> {
        val controller = Robolectric.buildActivity(VodLibraryActivity::class.java)
        controller.get().libraryData = data
        controller.get().libraryImages = object : LibraryImages {
            override fun load(view: ImageView, url: String) = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        controller.get().musicSearchOverride = { emptyList() }
        controller.create().start().resume().visible(); controller.windowFocusChanged(true)
        layout(controller.get()); return controller
    }
    private fun dispose(c: ActivityController<VodLibraryActivity>) { c.pause().stop().destroy() }

    @Test fun coldStartKeepsLastPlayedWorkEvenWhenItIsAbsentFromTheDefaultCatalogue() {
        val app = RuntimeEnvironment.getApplication()
        val watched = LibraryWork("source", "catalogue-absent", 1, 2, "上次播放的片")
        val ref = watched.reference("line", "episode-7")
        RecentVodHistory(app).record(watched, ref, "第 7 集")
        val wrong = LibraryWork("source", "夹具影片 1", 1, 2, "夹具影片 1")
        val snapshot = JSONObject().put("workKey", wrong.key).put("title", wrong.title)
            .put("lineId", "line").put("selectedEpisode", "episode-1").put("episodes", JSONArray().put(
                JSONObject().put("label", "第 1 集").put("reference", wrong.reference("line", "episode-1"))))
        val prefs = app.getSharedPreferences("vod-session", 0)
        prefs.edit().putString("snapshot", snapshot.toString()).apply()

        val controller = open()
        val activity = controller.get()
        try {
            await { activity.libraryModel.page == 1 && text(activity, "library-detail-title") == watched.title }
            assertEquals(watched.key, activity.javaClass.getDeclaredField("selectedWorkKey").apply { isAccessible = true }.get(activity))
            assertEquals("episode-7", activity.javaClass.getDeclaredField("selectedEpisode").apply { isAccessible = true }.get(activity))
            assertEquals(snapshot.toString(), prefs.getString("snapshot", null))
        } finally { dispose(controller) }
    }

    @Test fun currentEpisodeOwnsInitialFocusAndFramesAreBelow() {
        val c = open(); val a = c.get()
        try {
            await { view(a, "library-hero") != null }
            layout(a); idle(500)
            assertTrue("shared player owns initial focus", view(a, "library-episodes")!!.hasFocus())
            assertEquals("夹具影片 1", text(a, "library-detail-title"))
            await { view(a, "library-episodes")!!.visibility == View.VISIBLE }
            assertTrue(view(a, "library-detail")!!.height > 0)
        } finally { dispose(c) }
    }

    @Test fun horizontalSeekKeysDoNotChangeTheSelectedWork() {
        val c = open(); val a = c.get()
        try {
            layout(a); idle(500)
            press(a, KeyEvent.KEYCODE_DPAD_RIGHT)
            press(a, KeyEvent.KEYCODE_DPAD_LEFT)
            assertEquals("夹具影片 1", text(a, "library-detail-title"))
        } finally { dispose(c) }
    }

    @Test fun musicResultUsesTheHeroAndOpensTheMusicPlayer() {
        val c = open(); val a = c.get()
        try {
            await { view(a, "library-hero") != null }; layout(a); idle(500)
            val track = com.live.tv.music.catalog.CatalogTrack("t1", "夹具歌曲", listOf("夹具歌手"), "夹具专辑", "", "")
            val setter = a.javaClass.getDeclaredMethod("setMusicResults", List::class.java).apply { isAccessible = true }
            setter.invoke(a, listOf(track)); layout(a)
            view(a, "library-hero")!!.requestFocusFromTouch(); idle(32)
            a.javaClass.getDeclaredMethod("selectGalleryIndex", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(a, 0)
            await { text(a, "library-detail-title") == "夹具歌曲" }
            view(a, "library-hero")!!.performClick(); idle(32)
            assertEquals(com.live.tv.music.MusicActivity::class.java.name,
                shadowOf(a).nextStartedActivity?.component?.className)
        } finally { dispose(c) }
    }

    @Test fun sharedEpisodeListExposesTheCurrentSelection() {
        val c = open(); val a = c.get()
        try {
            await { view(a, "library-hero") != null }; layout(a); idle(500)
            val strip = view(a, "library-episodes") as MediaChromeView
            assertEquals(0, strip.selectedIndex)
        } finally { dispose(c) }
    }

    @Test fun topLeftWallStatusOverlayIsGone() {
        val c = open(); val a = c.get()
        try {
            await { view(a, "library-hero") != null }; layout(a); idle(200)
            assertNull("the player must not show the old wall status at the top left",
                view(a, "library-status"))
        } finally { dispose(c) }
    }

    @Test fun playerOmitsLowerStatusAndTransportButtons() {
        val c = open(); val a = c.get()
        try {
            await { view(a, "library-detail-title") != null }; layout(a)
            assertNull(view(a, "library-detail-status"))
            assertNull(view(a, "library-play-pause"))
        } finally { dispose(c) }
    }

    @Test fun titleIsDisplayOnly() {
        val c = open()
        try {
            assertFalse(view(c.get(), "library-detail-title")!!.isFocusable)
        } finally { dispose(c) }
    }

    @Test fun revealingControlsFocusesThePlayingEpisode() {
        val c = open(); val a = c.get()
        try {
            await { view(a, "library-hero") != null }; layout(a); idle(500)
            await { view(a, "library-episodes")!!.visibility == View.VISIBLE }
            val strip = view(a, "library-episodes") as MediaChromeView
            a.javaClass.getDeclaredField("selectedEpisode").apply { isAccessible = true }.set(a, "episode-6")
            a.javaClass.getDeclaredMethod("renderEpisodes").apply { isAccessible = true }.invoke(a)
            view(a, "library-hero")!!.requestFocusFromTouch(); idle(32)
            assertFalse(strip.hasFocus())
            press(a, KeyEvent.KEYCODE_DPAD_UP)
            idle(32)
            assertTrue("revealed controls must focus the playing episode", strip.hasFocus())
            assertEquals(5, strip.selectedIndex)
            assertEquals(4, strip.candidateIndex)
        } finally { dispose(c) }
    }

    @Test fun backKeepsTheCurrentPlayerOpen() {
        val c = open()
        try {
            val a = c.get()
            await { text(a, "library-detail-title") == "夹具影片 1" }
            a.onBackPressed()
            assertFalse(a.isFinishing)
            assertFalse(view(a, "library-detail-title")!!.isShown)
            press(a, KeyEvent.KEYCODE_DPAD_CENTER)
            assertTrue(view(a, "library-detail-title")!!.isShown)
            assertEquals("夹具影片 1", text(a, "library-detail-title"))
            a.onBackPressed()
            assertNull(shadowOf(a).nextStartedActivity)
            a.onBackPressed()
            assertTrue(shadowOf(a).nextStartedActivity.hasCategory(android.content.Intent.CATEGORY_HOME))
        } finally { dispose(c) }
    }

    @Test fun playbackIdleTimerHidesTheSharedControls() {
        val c = open(); val a = c.get()
        try {
            await { text(a, "library-detail-title") == "夹具影片 1" }
            val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
                when (method.name) {
                    "isPlaying" -> true
                    else -> null
                }
            }
            val playerField = a.javaClass.getDeclaredField("player").apply { isAccessible = true }
            playerField.set(a, player)
            try {
                a.javaClass.getDeclaredMethod("scheduleHideChrome").apply { isAccessible = true }.invoke(a)
                idle(3999)
                assertTrue(view(a, "library-detail-title")!!.isShown)
                idle(1)
                assertFalse(view(a, "library-detail-title")!!.isShown)
            } finally { playerField.set(a, null) }
        } finally { dispose(c) }
    }

    @Test fun confirmPausesFromHiddenControlsAndResumesTheFocusedCurrentEpisode() {
        val c = open(); val a = c.get()
        try {
            val strip = view(a, "library-episodes") as MediaChromeView
            val adapter = a.javaClass.getDeclaredField("episodeAdapter").apply { isAccessible = true }
                .get(a) as LibraryEpisodeAdapter
            await { adapter.items.isNotEmpty() }
            layout(a)
            val reference = adapter.items[strip.selectedIndex].reference
            a.javaClass.getDeclaredField("playerRef").apply { isAccessible = true }.set(a, reference)
            var playing = true
            val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
                when (method.name) {
                    "isPlaying", "getPlayWhenReady" -> playing
                    "getPlaybackState" -> androidx.media3.common.Player.STATE_READY
                    "getDuration", "getCurrentPosition" -> 0L
                    "pause" -> { playing = false; null }
                    "play" -> { playing = true; null }
                    else -> null
                }
            }
            val field = a.javaClass.getDeclaredField("player").apply { isAccessible = true }
            field.set(a, player)
            try {
                a.onBackPressed()
                press(a, KeyEvent.KEYCODE_DPAD_CENTER)
                assertFalse(playing)
                assertTrue(strip.hasFocus())
                press(a, KeyEvent.KEYCODE_DPAD_CENTER)
                assertTrue(playing)
                press(a, KeyEvent.KEYCODE_DPAD_CENTER)
                assertFalse(playing)
            } finally { field.set(a, null) }
        } finally { dispose(c) }
    }

    @Test fun catalogueRefreshHidesAnOldPreviewWhenPlaybackHasAFrameEvenDuringBuffering() {
        val c = open(); val a = c.get()
        try {
            await { text(a, "library-detail-title") == "夹具影片 1" }
            val hero = view(a, "library-hero")!!
            val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
                when (method.name) {
                    "getPlayWhenReady" -> true
                    "isPlaying" -> false
                    "getDuration", "getCurrentPosition" -> 0L
                    else -> null
                }
            }
            val field = a.javaClass.getDeclaredField("player").apply { isAccessible = true }
            field.set(a, player)
            try {
                a.javaClass.getDeclaredField("renderedFrame").apply { isAccessible = true }.setBoolean(a, true)
                a.javaClass.getDeclaredField("showingFrame").apply { isAccessible = true }.setBoolean(a, true)
                hero.visibility = View.VISIBLE
                a.javaClass.getDeclaredMethod("renderHero").apply { isAccessible = true }.invoke(a)
                assertEquals(View.GONE, hero.visibility)
            } finally { field.set(a, null) }
        } finally { dispose(c) }
    }

    private class Fixture : LibraryData {
        private val titles = listOf("夹具影片 1", "夹具影片 2", "夹具影片 3")
        override fun sources() = JSONObject().put("sources", JSONArray(listOf(JSONObject()
            .put("id", "source").put("name", "离线夹具").put("revision", 1).put("epoch", 2).put("searchable", true))))
        override fun caches() = JSONObject().put("caches", JSONArray())
        override fun catalog(query: String, page: Int, request: VodRequest): JSONObject {
            request.check()
            return JSONObject().put(if (query.isEmpty()) "browse" else "search", JSONObject()
                .put("page", page)
                .put("items", JSONArray(titles.map { title -> JSONObject().put("sourceId", "source").put("vodId", title)
                    .put("revision", 1).put("epoch", 2).put("title", title).put("poster", "fixture://$title")
                    .put("year", "2026").put("typeName", "剧情") }))
                .put("sources", JSONArray(listOf(JSONObject().put("sourceId", "source").put("sourceName", "离线夹具")
                    .put("revision", 1).put("epoch", 2).put("status", "success").put("hasMore", false)))))
        }
        override fun detail(work: LibraryWork, request: VodRequest): JSONObject {
            request.check()
            return work.identity().put("title", work.title).put("overview", "夹具简介")
                .put("lines", JSONArray(listOf(JSONObject().put("id", "line").put("label", "主线路").put("episodes",
                    JSONArray((1..12).map { JSONObject().put("key", "episode-$it").put("label", "第 $it 集") })))))
        }
        override fun validate(reference: JSONObject, request: VodRequest) { request.check() }
        override fun completed(reference: JSONObject, request: VodRequest): JSONObject? = null
        override fun addSource(form: Map<String, String>) = JSONObject().put("ok", true)
        override fun cache(reference: JSONObject, request: VodRequest) = JSONObject().put("ok", true)
        override fun removeCache(reference: JSONObject, request: VodRequest) = JSONObject().put("ok", true)
    }
}
