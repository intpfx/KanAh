package com.live.tv.media

import org.json.JSONObject
import org.json.JSONArray

/** UI catalog carries labels and opaque references, never playback URLs or credentials. */
internal object PlaybackEpisodeCatalog {
    data class Item(val label: String, val reference: JSONObject)
    data class Catalog(val title: String, val items: List<Item>, val selectedIndex: Int)
    fun read(spec: JSONObject, current: JSONObject): Catalog {
        val scope = listOf("sourceId", "vodId", "lineId", "revision", "epoch")
        val rows = spec.optJSONArray("episodeItems") ?: JSONArray().apply {
            spec.optJSONObject("previousEpisode")?.let { put(JSONObject().put("label", "上一集").put("ref", it)) }
            put(JSONObject().put("label", spec.optString("episodeLabel", "当前集")).put("ref", current))
            spec.optJSONObject("nextEpisode")?.let { put(JSONObject().put("label", "下一集").put("ref", it)) }
        }
        val seen = HashSet<String>()
        val items = mutableListOf<Item>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val raw = row.optJSONObject("ref") ?: continue
            if (scope.any { raw.opt(it) != current.opt(it) }) continue
            val safe = JSONObject()
            var valid = true
            for ((key, limit) in listOf("sourceId" to 128, "vodId" to 256, "lineId" to 128, "episodeKey" to 128)) {
                val text = raw.opt(key) as? String
                if (text.isNullOrBlank() || text.length > limit || text.any { it.code < 32 || it.code == 127 }) { valid = false; break }
                safe.put(key, text)
            }
            if (!valid) continue
            for (key in listOf("revision", "epoch")) {
                val number = raw.opt(key) as? Number
                if (number == null || number.toInt() < 1 || number.toDouble() != number.toInt().toDouble()) { valid = false; break }
                safe.put(key, number.toInt())
            }
            val label = (row.opt("label") as? String)?.trim().orEmpty()
            if (!valid || label.isEmpty() || label.length > 160 || label.any { it.code < 32 } || !seen.add(safe.optString("episodeKey"))) continue
            items.add(Item(label, safe))
        }
        val selected = items.indexOfFirst { it.reference.optString("episodeKey") == current.optString("episodeKey") }
        val label = spec.optString("episodeLabel", "当前集").take(160)
        val title = spec.optString("resourceTitle").ifBlank {
            spec.optString("title").let { if (spec.has("episodeLabel")) it.removeSuffix(" · $label") else it.substringBeforeLast(" · ", it) }
        }
        if (selected < 0) return Catalog(title, listOf(Item(label, JSONObject(current.toString()))), 0)
        return Catalog(title, items, selected)
    }
}
