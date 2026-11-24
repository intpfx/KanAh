package com.live.tv.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackEpisodeCatalogTest {
    private fun ref(key: String) = JSONObject().put("sourceId", "source").put("vodId", "series")
        .put("lineId", "line").put("episodeKey", key).put("revision", 1).put("epoch", 1)
    private fun row(label: String, reference: JSONObject) = JSONObject().put("label", label).put("ref", reference)
    @Test fun completeLineRetainsOrderSelectedIndexAndOnlyOpaqueReferences() {
        val items = JSONArray().put(row("第01集", ref("one"))).put(row("第02集", ref("two").put("url", "private")))
            .put(row("第03集", ref("three")))
        val catalog = PlaybackEpisodeCatalog.read(JSONObject().put("title", "资源 · 第02集").put("resourceTitle", "资源")
            .put("episodeItems", items), ref("two"))
        assertEquals("资源", catalog.title); assertEquals(1, catalog.selectedIndex)
        assertEquals(listOf("第01集", "第02集", "第03集"), catalog.items.map { it.label })
        assertEquals(6, catalog.items[1].reference.length()); assertFalse(catalog.items[1].reference.has("url"))
    }
    @Test fun foreignStaleDuplicateAndMalformedRowsCannotBecomeSelectable() {
        val items = JSONArray().put(row("当前", ref("one"))).put(row("跨线路", ref("two").put("lineId", "other")))
            .put(row("过期", ref("three").put("epoch", 2))).put(row("重复", ref("one")))
            .put(row("缺字段", ref("four").apply { remove("revision") })).put(row("", ref("five")))
        val catalog = PlaybackEpisodeCatalog.read(JSONObject().put("episodeItems", items), ref("one"))
        assertEquals(listOf("当前"), catalog.items.map { it.label }); assertEquals(0, catalog.selectedIndex)
    }
    @Test fun legacyCacheRetainsSafeNeighborNavigationWhenFullCatalogIsUnavailable() {
        val spec = JSONObject().put("previousEpisode", ref("one")).put("nextEpisode", ref("three"))
        val catalog = PlaybackEpisodeCatalog.read(spec, ref("two"))
        assertEquals(listOf("上一集", "当前集", "下一集"), catalog.items.map { it.label })
        assertEquals(1, catalog.selectedIndex)
        spec.put("nextEpisode", ref("three").put("lineId", "other"))
        assertEquals(2, PlaybackEpisodeCatalog.read(spec, ref("two")).items.size)
    }
    @Test fun legacyCacheWithoutCatalogShowsOnlyCurrentEpisodeAndResourceTitle() {
        val current = ref("two")
        val catalog = PlaybackEpisodeCatalog.read(JSONObject().put("title", "资源 · 第02集").put("episodeLabel", "第02集"), current)
        assertEquals("资源", catalog.title); assertEquals(0, catalog.selectedIndex); assertEquals(1, catalog.items.size)
        assertEquals("two", catalog.items.single().reference.getString("episodeKey"))
        current.put("episodeKey", "changed"); assertEquals("two", catalog.items.single().reference.getString("episodeKey"))
    }
}
