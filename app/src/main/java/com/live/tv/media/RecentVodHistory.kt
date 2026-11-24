package com.live.tv.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local history keeps one episode per film, ordered by actual playback. */
internal class RecentVodHistory(context: Context) {
    private val prefs = context.getSharedPreferences("vod-recent", Context.MODE_PRIVATE)

    fun entries(): List<JSONObject> {
        val rows = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrNull()
            ?: return emptyList()
        return (0 until minOf(rows.length(), LIMIT)).mapNotNull { index ->
            rows.optJSONObject(index)?.takeIf { LibraryReference.read(it) != null }
        }
    }

    fun clear() { prefs.edit().remove("entries").apply() }

    fun record(work: LibraryWork, reference: JSONObject, episodeLabel: String) {
        val row = LibraryReference.read(reference) ?: return
        if (LibraryReference.workKey(row) != work.key) return
        row.put("title", work.title).put("episodeLabel", episodeLabel)
            .put("poster", work.poster).put("sourceName", work.sourceName)
        val previous = entries()
        if (previous.firstOrNull()?.toString() == row.toString()) return
        val latest = listOf(row) + previous.filterNot {
            it.optString("sourceId") == work.sourceId && it.optString("vodId") == work.vodId
        }.take(LIMIT - 1)
        prefs.edit().putString("entries", JSONArray(latest).toString()).apply()
    }

    private companion object { const val LIMIT = 12 }
}
