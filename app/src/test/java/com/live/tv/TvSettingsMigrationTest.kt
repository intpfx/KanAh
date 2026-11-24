package com.live.tv

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Smoke coverage for the settings pages that replaced the web panel configuration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-mdpi")
class TvSettingsMigrationTest {
    private fun titles(activity: android.app.Activity): List<String> {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val found = mutableListOf<String>()
        fun visit(view: View) {
            if (view is TextView) found.add(view.text.toString())
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        return found
    }

    @Test fun sourcesPageShowsItsTitleAndRows() {
        val controller = Robolectric.buildActivity(TvSourcesActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertTrue(titles(activity).contains("影视来源"))
            assertNotNull(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("sources-root"))
        } finally { controller.pause().stop().destroy() }
    }
}
