package com.live.tv.media

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MediaChromeViewTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val picked = mutableListOf<Int>()
    private val chrome = MediaChromeView(activity, object : MediaChromeView.Actions {
        override fun select(index: Int) { picked += index }
    })

    init { activity.setContentView(chrome) }

    @Test fun firstMiddleAndLastCenterAfterLayout() {
        for (size in listOf(1, 3, 40)) {
            for (index in setOf(0, size / 2, size - 1)) {
                render(index, size)
                chrome.resetFocus()
                layout()
                assertCentered(index)
            }
        }
    }

    @Test fun navigationAndPlaybackRefreshKeepBrowsePositionUntilSelectionChanges() {
        render(4)
        layout()
        chrome.resetFocus()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        render(4)
        layout()
        assertEquals(4, chrome.selectedIndex)
        assertEquals(5, chrome.candidateIndex)
        assertCentered(5)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(5), picked)
        render(5)
        layout()
        assertCentered(5)
        chrome.resetFocus()
        assertEquals(5, chrome.candidateIndex)
    }

    @Test fun musicLyricsAndResultsShareTransport() {
        val lyrics = TextView(activity)
        chrome.lyricsView = lyrics
        val control = chrome.titleView
        chrome.render("歌曲", "歌手", emptyList(), -1, music = true)
        layout()
        assertEquals(View.VISIBLE, lyrics.visibility)
        assertEquals(0, lyrics.top)
        assertEquals(chrome.height, lyrics.bottom)
        assertEquals("歌曲", chrome.titleView.text.toString())
        chrome.render("歌曲", "歌手", listOf(MediaChromeView.Item("另一首")), 0,
            music = true, browsing = true)
        layout()
        assertEquals(View.GONE, lyrics.visibility)
        assertTrue(control === chrome.titleView)
        assertTrue(!control.isFocusable)
    }

    @Test fun cacheProgressUpdatesTheFocusedRowInPlace() {
        val item = MediaChromeView.Item("第 1 集")
        chrome.render("影片", "", listOf(item), 0)
        layout()
        chrome.resetFocus()
        val row = chrome.findFocus()
        chrome.render("影片", "", listOf(item.copy(cache = EpisodeDecoration(active = true, fraction = .42f))), 0)
        assertTrue(row === chrome.findFocus())
        assertEquals("第 1 集，缓存中 42%", row.contentDescription)
        chrome.render("影片", "", listOf(item.copy(cache = EpisodeDecoration(complete = true))), 0)
        assertTrue(row === chrome.findFocus())
        assertEquals("第 1 集，已缓存", row.contentDescription)
    }

    @Test fun longTitleWrapsWithoutEllipsis() {
        val title = "2026江苏卫视荔枝春节联欢晚会特别节目完整版".repeat(8)
        chrome.render(title, "", emptyList(), -1)
        layout()
        val layout = chrome.titleView.layout
        assertTrue(layout.lineCount > 1)
        assertEquals(title.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
    }

    @Test fun titleProgressPaintsThePlayedPartAndClearsWhenDurationIsUnknown() {
        chrome.render("MMMM", "", emptyList(), -1)
        layout()
        val title = chrome.titleView
        val bitmap = Bitmap.createBitmap(title.width, title.height, Bitmap.Config.ARGB_8888)
        val middle = (title.layout.getLineLeft(0) + title.layout.getLineRight(0)) / 2
        fun count(color: Int, from: Int, until: Int): Int =
            (0 until bitmap.height).sumOf { y -> (from until until).count { x -> bitmap.getPixel(x, y) == color } }
        chrome.setTitleProgress(50, 100, null)
        title.draw(Canvas(bitmap))
        assertTrue(count(Color.RED, 0, middle.toInt()) > 0)
        assertEquals(0, count(Color.RED, middle.toInt() + 1, bitmap.width))
        assertTrue(count(Color.WHITE, middle.toInt() + 1, bitmap.width) > 0)
        bitmap.eraseColor(Color.TRANSPARENT)
        chrome.setTitleProgress(50, 100, Color.GREEN)
        title.draw(Canvas(bitmap))
        assertTrue(count(Color.GREEN, 0, middle.toInt()) > 0)
        assertEquals(0, count(Color.RED, 0, bitmap.width))
        bitmap.eraseColor(Color.TRANSPARENT)
        chrome.setTitleProgress(50, 0, Color.RED)
        title.draw(Canvas(bitmap))
        assertEquals(0, count(Color.RED, 0, bitmap.width))
    }

    @Test fun watchProgressAndCacheAreSeparateAndKeepFocus() {
        val item = MediaChromeView.Item("第 1 集", cache = EpisodeDecoration(complete = true))
        chrome.render("影片", "", listOf(item), 0)
        layout()
        chrome.resetFocus()
        val row = chrome.findFocus()
        chrome.render("影片", "", listOf(item.copy(watched = .37f)), 0)
        assertTrue(row === chrome.findFocus())
        assertEquals("第 1 集，已观看 37%，已缓存", row.contentDescription)
    }

    private fun press(code: Int) {
        chrome.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        chrome.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun render(index: Int, size: Int = 18) {
        chrome.render("标题", "", List(size) { MediaChromeView.Item("条目 ${it + 1}", "描述") }, index)
    }

    private fun layout() {
        chrome.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        chrome.layout(0, 0, 1920, 1080)
    }

    private fun assertCentered(index: Int) {
        val list = chrome.getChildAt(0) as ScrollView
        assertEquals(0, list.top)
        assertEquals(chrome.height, list.bottom)
        val row = (list.getChildAt(0) as android.view.ViewGroup).getChildAt(index)
        assertTrue("row ${index + 1}", abs(list.top + row.top + row.height / 2 - list.scrollY - chrome.height / 2) <= 1)
    }
}
