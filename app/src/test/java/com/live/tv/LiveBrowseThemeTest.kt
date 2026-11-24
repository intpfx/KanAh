package com.live.tv

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.live.tv.media.LibraryData
import com.live.tv.media.LibraryImages
import com.live.tv.media.LibraryWork
import com.live.tv.media.VodLibraryActivity
import com.live.tv.media.VodRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class LiveBrowseThemeTest {
    @Test fun libraryRetainsTheLiveWindowWithoutAVisibleTransparentPageOrFixedOrientation() {
        val controller = Robolectric.buildActivity(VodLibraryActivity::class.java)
        controller.get().libraryData = CreationOnlyData()
        controller.get().libraryImages = object : LibraryImages {
            override fun load(view: ImageView, url: String) = Unit
            override fun cancel() = Unit
            override fun close() = Unit
        }
        controller.create()
        try { assertBrowseWindow(controller.get(), "vod-library") }
        finally { controller.destroy() }
    }

    @Test fun sourcesRetainsTheLiveWindowWithoutAVisibleTransparentPageOrFixedOrientation() {
        val controller = Robolectric.buildActivity(TvSourcesActivity::class.java).create()
        try { assertBrowseWindow(controller.get(), "sources-root") }
        finally { controller.destroy() }
    }

    private fun assertBrowseWindow(activity: Activity, contentTag: String) {
        val info = activity.packageManager.getActivityInfo(ComponentName(activity, activity.javaClass), 0)
        assertEquals("browse pages inherit the live window's orientation", ActivityInfo.SCREEN_ORIENTATION_BEHIND, info.screenOrientation)
        assertTrue("a translucent activity must not request a fixed orientation on Android 8",
            activity.requestedOrientation in listOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_BEHIND))
        val attributes = activity.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating,
            android.R.attr.windowBackground, android.R.attr.backgroundDimEnabled
        ))
        try {
            assertTrue("the live activity must remain visible to the window manager", attributes.getBoolean(0, false))
            assertFalse(attributes.getBoolean(1, true))
            assertEquals(0, Color.alpha((attributes.getDrawable(2) as ColorDrawable).color))
            assertFalse(attributes.getBoolean(3, true))
        } finally { attributes.recycle() }

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        content.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
        content.layout(0, 0, 960, 540)
        val page = content.getChildAt(0)
        assertEquals(960, page.width); assertEquals(540, page.height)
        assertEquals("the page must completely cover live video", 255, Color.alpha((page.background as ColorDrawable).color))
        val tagged = content.findViewWithTag<View>(contentTag)
        assertNotNull("the existing page hierarchy remains present", tagged)
        assertEquals(255, Color.alpha((tagged.background as ColorDrawable).color))
    }

    /** Theme checks only create the page, so they must not require a source or cache read. */
    private class CreationOnlyData : LibraryData {
        private fun unexpected(): Nothing = error("theme inspection must not start library requests")
        override fun sources(): JSONObject = unexpected()
        override fun caches(): JSONObject = unexpected()
        override fun catalog(query: String, page: Int, request: VodRequest): JSONObject = unexpected()
        override fun detail(work: LibraryWork, request: VodRequest): JSONObject = unexpected()
        override fun validate(reference: JSONObject, request: VodRequest): Unit = unexpected()
        override fun completed(reference: JSONObject, request: VodRequest): JSONObject? = unexpected()
        override fun addSource(form: Map<String, String>): JSONObject = unexpected()
        override fun cache(reference: JSONObject, request: VodRequest): JSONObject = unexpected()
        override fun removeCache(reference: JSONObject, request: VodRequest): JSONObject = unexpected()
    }
}
