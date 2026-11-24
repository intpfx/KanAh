package com.live.tv.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodSearchPreviewTest {
    @Test fun openingFallsBackToFirstEpisodeAndResumeUsesLatestPositiveProgressAcrossLines() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = LibraryWork("fixture", "movie", 1, 1, "测试影片")
        fun episode(line: String, key: String) = LibraryEpisode(key, work.identity()
            .put("lineId", line).put("episodeKey", key))
        val opening = episode("a", "opening")
        val earlier = episode("a", "earlier")
        val latest = episode("b", "latest")
        val finished = episode("b", "finished")
        val detail = LibraryDetail(work, "", listOf(
            LibraryLine("a", "A", listOf(opening, earlier)),
            LibraryLine("b", "B", listOf(latest, finished)),
        ), false)
        assertEquals(opening, detail.preferredEpisode(context))

        fun id(ref: JSONObject) = MacCmsProvider.playbackId("fixture", "movie",
            ref.getString("lineId"), ref.getString("episodeKey"))
        val progress = context.getSharedPreferences("media-progress", Context.MODE_PRIVATE).edit()
        listOf(earlier to 1_000L, latest to 30_000L, finished to 0L).forEachIndexed { index, (item, offset) ->
            val key = id(item.reference)
            progress.putLong(key, offset).putLong("$key:updatedAt", (index + 1).toLong())
        }
        progress.apply()

        assertEquals(latest, detail.preferredEpisode(context))
    }
}
