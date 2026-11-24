package com.live.tv

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.live.tv.media.LibraryWork
import com.live.tv.media.RecentVodHistory
import com.live.tv.media.VodLibraryActivity
import com.live.tv.music.MusicActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.json.JSONObject
import java.time.Duration
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlobalSearchOverlayTest {
    @Test fun pageSelectsItsOwnSearchScopeAndDisabledInputCannotTakeFocus() {
        checkPage(MainActivity::class.java, "直播", null)
        checkPage(TvSourcesActivity::class.java, "设置", null)
        checkPage(VodLibraryActivity::class.java, "流媒体", "搜索影视")
        checkPage(MusicActivity::class.java, "音乐", "搜索歌曲或歌手")
    }

    @Test fun blankVodSearchShowsLatestEpisodePerFilmAndOpensItsPlayback() {
        val activity = Robolectric.buildActivity(VodLibraryActivity::class.java).get()
        val history = RecentVodHistory(activity)
        val first = LibraryWork("source", "one", 1, 1, "第一部")
        val second = first.copy(vodId = "two", title = "第二部")
        history.record(first, first.reference("line", "ep1"), "第 1 集")
        history.record(second, second.reference("line", "ep1"), "第 1 集")
        history.record(first, first.reference("line", "ep2"), "第 2 集")

        val overlay = GlobalSearchOverlay(activity)
        overlay.show()
        val input = overlay.findViewWithTag<EditText>("global-search-input")
        val results = overlay.findViewWithTag<ViewGroup>("global-search-results")
        assertEquals(2, results.childCount)
        input.setText("新搜索")
        assertEquals(0, results.childCount)
        input.setText("")
        assertEquals(2, results.childCount)
        layoutOverlay(overlay)
        val surface = overlay.getChildAt(0) as ViewGroup
        val toolbar = surface.getChildAt(0)
        val panel = surface.getChildAt(1)
        assertEquals(toolbar.bottom, panel.top)
        assertEquals(toolbar.left, panel.left)
        assertEquals(toolbar.right, panel.right)
        val row = results.getChildAt(0) as ViewGroup
        val labels = row.getChildAt(1) as ViewGroup
        assertEquals("第一部", (labels.getChildAt(0) as TextView).text)
        assertEquals("第 2 集", (labels.getChildAt(1) as TextView).text)
        row.performClick()

        val launch = shadowOf(activity).nextStartedActivity
        assertEquals(VodLibraryActivity::class.java.name, launch.component?.className)
        val item = JSONObject(launch.getStringExtra("recent_vod")!!)
        assertEquals("one", item.getString("vodId"))
        assertEquals("ep2", item.getString("episodeKey"))
    }

    @Test fun clearingHistoryRemovesRecentRowsAndKeepsPlaybackProgress() {
        val activity = Robolectric.buildActivity(VodLibraryActivity::class.java).get()
        val history = RecentVodHistory(activity)
        val work = LibraryWork("source", "work", 1, 1, "电影")
        val progress = activity.getSharedPreferences("media-progress", 0)
        progress.edit().putLong("sample", 1234L).apply()
        history.record(work, work.reference("line", "episode"), "第 1 集")
        history.clear()

        val overlay = GlobalSearchOverlay(activity)
        overlay.show()
        assertEquals(0, overlay.findViewWithTag<ViewGroup>("global-search-results").childCount)
        assertEquals(1234L, progress.getLong("sample", 0L))
        history.record(work, work.reference("line", "episode"), "第 1 集")
        assertEquals(1, history.entries().size)
    }

    @Test fun streamingSameFilmSharesDecodedPosterWithoutWaitingForTheBrokenImage() {
        val activity = Robolectric.buildActivity(VodLibraryActivity::class.java).get()
        val overlay = GlobalSearchOverlay(activity)
        overlay.show()
        val addFilm = overlay.javaClass.getDeclaredMethod("addFilmResult", JSONObject::class.java).apply { isAccessible = true }
        val results = overlay.findViewWithTag<ViewGroup>("global-search-results")
        fun add(source: String, year: String) {
            addFilm.invoke(overlay, JSONObject().put("title", "现在就出发第四季")
                .put("sourceId", source).put("sourceName", source).put("vodId", "test")
                .put("revision", 1).put("epoch", 1).put("year", year).put("typeName", "大陆综艺"))
        }
        fun poster(index: Int) = (results.getChildAt(index) as ViewGroup).getChildAt(0) as android.widget.ImageView
        fun bitmap(index: Int) = (poster(index).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

        add("西瓜资源站", "2026")
        add("360资源", "2026")
        add("重制版", "2025")
        val artwork = android.graphics.Bitmap.createBitmap(120, 180, android.graphics.Bitmap.Config.ARGB_8888)
        poster(1).setImageBitmap(artwork)
        assertSame(artwork, bitmap(0))
        assertNull(bitmap(2))
        add("魔都资源", "2026")
        assertSame(artwork, bitmap(2))
        assertNull(bitmap(3))
    }

    @Test fun panelAnimatesBelowStationaryToolbarWithoutMovingFocus() {
        val host = Robolectric.buildActivity(Activity::class.java).setup().get()
        val vod = Robolectric.buildActivity(VodLibraryActivity::class.java).get()
        val film = LibraryWork("source", "one", 1, 1, "第一部")
        RecentVodHistory(vod).record(film, film.reference("line", "ep1"), "第 1 集")
        val overlay = GlobalSearchOverlay(vod)
        host.setContentView(overlay, ViewGroup.LayoutParams(1920, 1080))
        overlay.show()
        layoutOverlay(overlay)
        assertTrue(overlay.isAttachedToWindow)
        val surface = overlay.getChildAt(0) as ViewGroup
        val toolbar = surface.getChildAt(0) as ViewGroup
        val focused = toolbar.getChildAt(3)
        val top = surface.top
        val collapsed = toolbar.height
        assertEquals(collapsed, surface.height)
        assertSame(focused, overlay.findFocus())

        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(250))
        val expanded = surface.height
        assertTrue(expanded > collapsed)
        assertEquals(top, surface.top)
        assertSame(focused, overlay.findFocus())

        overlay.hide()
        assertTrue(overlay.isOpen())
        assertEquals(expanded, surface.height)
        assertEquals(top, surface.top)
        assertSame(focused, overlay.findFocus())
        looper.idleFor(Duration.ofMillis(200))
        assertEquals(collapsed, surface.height)
        assertEquals(View.GONE, overlay.visibility)
    }

    private fun checkPage(type: Class<out Activity>, selected: String, hint: String?) {
        val activity = Robolectric.buildActivity(type).get()
        val overlay = GlobalSearchOverlay(activity)
        overlay.show()
        layoutOverlay(overlay)
        val surface = overlay.getChildAt(0) as ViewGroup
        val toolbar = surface.getChildAt(0) as ViewGroup
        assertNotNull(surface.background)
        assertNull(toolbar.background)
        assertEquals(5, toolbar.childCount)
        val tabs = (1..4).map(toolbar::getChildAt)
        assertEquals(listOf("设置", "直播", "流媒体", "音乐"), tabs.map { it.contentDescription })
        assertEquals(selected, tabs.single { it.isSelected }.contentDescription)
        tabs.forEach { assertNull(it.background) }
        val input = toolbar.getChildAt(0) as EditText
        assertEquals(hint != null, input.isEnabled)
        assertEquals(hint != null, input.isFocusable)
        assertEquals(hint, input.hint)
        assertEquals(if (hint == null) View.GONE else View.VISIBLE, input.visibility)
        if (hint != null) assertTrue(input.right <= tabs.first().left)
        assertTrue(kotlin.math.abs(surface.left + surface.right - 1920) <= 1)
    }

    private fun layoutOverlay(overlay: GlobalSearchOverlay) {
        overlay.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, 1920, 1080)
    }
}
