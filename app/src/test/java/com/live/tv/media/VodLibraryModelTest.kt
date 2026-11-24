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
class VodLibraryModelTest {
    private fun source(id: String = "s", revision: Int = 1) = JSONObject().put("id", id).put("name", id)
        .put("revision", revision).put("epoch", 2).put("searchable", true)
    private fun sources(vararg values: JSONObject) = JSONObject().put("sources", JSONArray(values.toList()))
    private fun ref(vod: String = "cached", episode: String = "ep1", revision: Int = 1) = JSONObject()
        .put("sourceId", "s").put("vodId", vod).put("lineId", "line").put("episodeKey", episode).put("revision", revision).put("epoch", 2)
    private fun item(vod: String, source: String = "s", revision: Int = 1) = JSONObject().put("sourceId", source)
        .put("vodId", vod).put("revision", revision).put("epoch", 2).put("title", vod)
    private fun status(id: String = "s", more: Boolean = false, error: Boolean = false) = JSONObject()
        .put("sourceId", id).put("sourceName", id).put("revision", 1).put("epoch", 2).put("status", if (error) "error" else "success").put("hasMore", more)
    private fun page(number: Int, items: List<JSONObject>, statuses: List<JSONObject> = listOf(status()), query: String = "") = JSONObject()
        .put(if (query.isEmpty()) "browse" else "search", JSONObject().put("page", number).put("items", JSONArray(items)).put("sources", JSONArray(statuses)))
    private fun cache(vod: String = "cached", episode: String = "ep1", state: String = "complete", played: Long = 0) = JSONObject()
        .put("ref", ref(vod, episode)).put("resourceTitle", vod).put("episodeLabel", episode).put("state", state).put("lastPlayedAt", played)
    private fun model() = VodLibraryModel().apply { updateSources(sources(source())) }
    private fun cached(reference: JSONObject = ref(), state: String = "complete", label: String = "已存剧集", percent: Int = -1) =
        LibraryCache(reference, "Film", "", label, state, 0, 0, percent = percent)
    private fun detail(work: LibraryWork, vararg episodes: String) = work.identity().put("lines", JSONArray(listOf(
        JSONObject().put("id", "line").put("label", "主线路").put("episodes", JSONArray(episodes.map {
            JSONObject().put("key", it).put("label", "剧集 $it")
        })))))

    @Test fun completeEpisodesGroupByWorkAndSortAheadOfOnlineAndPartialDownloads() {
        val model = model()
        model.applyPage(page(1, listOf(item("online"), item("cached"))), "", 1)
        model.updateCaches(JSONObject().put("caches", JSONArray(listOf(cache("pending", state = "downloading"),
            cache("cached", "ep1"), cache("cached", "ep2"), cache("recent", played = 100)))))
        val works = model.works("", false)
        assertEquals(listOf("recent", "cached", "pending", "online"), works.map { it.title })
        assertEquals(2, works[1].completed.size)
        assertEquals(listOf("recent", "cached"), model.works("", true).map { it.title })
        assertEquals(listOf("cached"), model.works("CACHED", true).map { it.title })
    }

    @Test fun cacheReferencesDiscardLocatorsAndCredentialsAndRejectInvalidIdentity() {
        val unsafe = ref().put("url", "https://private.invalid/?token=secret").put("headers", JSONObject().put("Authorization", "secret"))
        val safe = LibraryReference.read(unsafe)!!
        assertEquals(setOf("sourceId", "vodId", "lineId", "episodeKey", "revision", "epoch"), safe.keys().asSequence().toSet())
        assertFalse(safe.toString().contains("secret"))
        assertNull(LibraryReference.read(ref().put("revision", 1.2)))
        assertNull(LibraryReference.read(ref().put("episodeKey", "")))
        assertNull(LibraryReference.read(ref().put("sourceId", "s\n")))
        val model = model()
        model.updateCaches(JSONObject().put("caches", JSONArray(listOf(cache().put("ref", unsafe), cache(), cache().put("ref", ref().put("epoch", 0))))))
        assertEquals(1, model.caches.size); assertFalse(model.caches.single().reference.has("url"))
    }

    @Test fun duplicateNextPageStopsPaginationAndRetryingTheCurrentPagePreservesItsCursor() {
        val model = model()
        model.applyPage(page(1, listOf(item("one")), listOf(status(more = true))), "", 1)
        assertTrue(model.hasMore)
        model.applyPage(page(1, listOf(item("one")), listOf(status(more = true))), "", 1)
        assertTrue("retry must not mistake a previously successful page for broken pagination", model.hasMore)
        model.applyPage(page(2, listOf(item("one")), listOf(status(more = true))), "", 2)
        assertFalse(model.hasMore); assertEquals(1, model.works("", false).size)
    }

    @Test fun truncatedSearchNoticePersistsWhileAnotherSourceContinuesAndPartialRetryAddsOnlyMissingItems() {
        val model = VodLibraryModel().apply { updateSources(sources(source("s"), source("t"))) }
        model.applyPage(page(1, listOf(item("one")), listOf(status().put("truncated", true).put("searchLimit", 50), status("t", error = true)), "film"), "film", 1)
        assertEquals(listOf("t"), model.failures)
        model.applyPage(page(1, listOf(item("one"), item("two", "t")), listOf(status(), status("t", more = true)), "film"), "film", 1)
        assertTrue(model.hasMore); assertTrue(model.failures.isEmpty())
        model.applyPage(page(2, listOf(item("three", "t")), listOf(status(), status("t", more = true)), "film"), "film", 2)
        assertTrue(model.hasMore); assertEquals(3, model.works("", false).size)
        assertTrue(model.notice().contains("50"))
    }

    @Test fun sourceRevisionChangesDiscardOnlineRowsAndLeaveOldCachedWorksExplicitlyUnavailable() {
        val model = model(); model.updateCaches(JSONObject().put("caches", JSONArray(listOf(cache()))))
        model.applyPage(page(1, listOf(item("online")), listOf(status(more = true))), "", 1)
        assertTrue(model.updateSources(sources(source(revision = 2))))
        assertEquals(0, model.page); assertFalse(model.hasMore)
        val stale = model.works("", true).single()
        assertFalse(stale.current); assertFalse(model.isCurrent(stale.identity()))
        try { model.applyPage(page(1, listOf(item("old"))), "", 1); fail("stale source response accepted") }
        catch (expected: VodException) { assertEquals("SEARCH_FAILED", expected.code) }
        assertEquals(listOf("cached"), model.works("", false).map { it.title })
    }

    @Test fun oversizedCatalogAndWrongPageCannotProduceUnboundedOrRepeatedPagination() {
        val model = model()
        try { model.applyPage(page(1, listOf(item("bad"))), "", 2); fail("wrong page accepted") }
        catch (expected: VodException) { assertEquals("INVALID_RESPONSE", expected.code) }
        assertEquals(0, model.page)
        model.applyPage(page(1, (0..1000).map { item("film-$it") }, listOf(status(more = true))), "", 1)
        assertEquals(1000, model.works("", false).size); assertTrue(model.capped); assertFalse(model.hasMore)
    }

    @Test fun offlineDetailContainsOnlyVerifiedEpisodesAndOnlineDetailCannotCrossIdentity() {
        val model = model(); model.updateCaches(JSONObject().put("caches", JSONArray(listOf(cache(episode = "ep1"), cache(episode = "ep2")))))
        val work = model.works("", true).single()
        val detail = LibraryDetail.offline(work, listOf(work.completed.last()))
        assertTrue(detail.offline); assertEquals("ep2", detail.lines.single().episodes.single().reference.getString("episodeKey"))
        try { LibraryDetail.offline(work, emptyList()); fail("empty cache accepted") }
        catch (expected: VodException) { assertEquals("CACHE_NOT_FOUND", expected.code) }
        try { LibraryDetail.online(work, work.identity().put("revision", 2)); fail("stale detail accepted") }
        catch (expected: VodException) { assertEquals("STALE_SELECTION", expected.code) }
    }

    @Test fun nativeDetailSkipsParserLinesAndDuplicateEpisodesWithoutKeepingResolvedUrls() {
        val work = LibraryWork("s", "v", 1, 2, "Film")
        val episode = JSONObject().put("key", "ep").put("label", "第一集").put("url", "secret")
        val raw = work.identity().put("lines", JSONArray(listOf(
            JSONObject().put("id", "parser").put("requiresParser", true).put("episodes", JSONArray(listOf(episode))),
            JSONObject().put("id", "line").put("label", "主线路").put("episodes", JSONArray(listOf(episode, episode))))))
        val result = LibraryDetail.online(work, raw)
        assertEquals(1, result.lines.size); assertEquals(1, result.lines.single().episodes.size)
        assertFalse(result.lines.single().episodes.single().reference.toString().contains("secret"))
    }

    @Test fun cacheStatusMetadataIsBoundedAndOmitsPrivateTransportDetails() {
        val model = model()
        model.updateCaches(JSONObject().put("caches", JSONArray(listOf(
            cache(episode = "normal", state = "downloading").put("bytes", 4096).put("percent", 42.8)
                .put("positionMs", -1).put("lastPlayedAt", -2).put("error", "可重试\n"),
            cache(episode = "bounded", state = "failed").put("bytes", -10).put("percent", 125)
                .put("error", "失败".repeat(400)),
            cache(episode = "private", state = "failed").put("ref", ref(episode = "private")
                .put("url", "https://private.invalid/media?token=secret").put("headers", JSONObject().put("Authorization", "secret")))
                .put("percent", "NaN").put("error", "https://private.invalid/media?token=secret")
                .put("spec", JSONObject().put("url", "secret")),
            cache(episode = "unknown", state = "unknown".repeat(1000)).put("percent", -5),
            cache(episode = "defaults")
        ))))
        val entries = model.caches.associateBy { it.reference.getString("episodeKey") }
        val normal = entries.getValue("normal")
        assertEquals(4096L, normal.bytes); assertEquals(42, normal.percent)
        assertEquals(0L, normal.positionMs); assertEquals(0L, normal.lastPlayedAt); assertEquals("可重试", normal.error)
        assertEquals(0L, entries.getValue("bounded").bytes); assertEquals(100, entries.getValue("bounded").percent)
        assertEquals(512, entries.getValue("bounded").error.length)
        val privateEntry = entries.getValue("private")
        assertEquals(-1, privateEntry.percent); assertEquals("缓存未完成，可重新缓存以继续", privateEntry.error)
        assertFalse(privateEntry.toString().contains("secret")); assertFalse(privateEntry.reference.has("url"))
        assertEquals("", entries.getValue("unknown").state); assertEquals(-1, entries.getValue("unknown").percent)
        assertEquals(0L, entries.getValue("defaults").bytes); assertEquals(-1, entries.getValue("defaults").percent)
        assertEquals("", entries.getValue("defaults").error)
        assertEquals("complete", LibraryEpisode("已存", ref(), true).cacheState)
    }

    @Test fun onlineDetailExposesDownloadStatesAndOnlyCompleteEpisodesAreCached() {
        val states = listOf("queued", "resolving", "downloading", "complete", "failed", "removing")
        val work = LibraryWork("s", "film", 1, 2, "Film", caches = states.mapIndexed { index, state ->
            cached(ref("film", state), state, percent = index * 20)
        } + cached(ref("film", "uncached", revision = 2)))
        val result = LibraryDetail.online(work, detail(work, *(states + "uncached").toTypedArray()))
        val episodes = result.lines.single().episodes
        assertEquals(states + "", episodes.map { it.cacheState })
        assertEquals(listOf(false, false, false, true, false, false, false), episodes.map { it.cached })
        assertEquals(listOf(0, 20, 40, 60, 80, 100, -1), episodes.map { it.percent })
        assertEquals(states.size, result.work.caches.size)
    }

    @Test fun cacheRefreshPreservesCatalogAndReferencesAcrossDownloadAndRemovalTransitions() {
        val work = LibraryWork("s", "film", 1, 2, "Film")
        val catalog = LibraryDetail.online(work, detail(work, "first", "second"))
        val originalRef = catalog.lines.single().episodes.first().reference
        var updated = catalog
        for ((state, percent) in listOf("queued" to -1, "downloading" to 35, "complete" to 100, "failed" to 100, "removing" to 100)) {
            updated = updated.withCaches(listOf(cached(ref("film", "first"), state, percent = percent),
                cached(ref("film", "missing")), cached(ref("other", "second"))))
            assertEquals(listOf("first", "second"), updated.lines.single().episodes.map { it.reference.getString("episodeKey") })
            assertEquals("主线路", updated.lines.single().label)
            val first = updated.lines.single().episodes.first()
            assertSame(originalRef, first.reference); assertEquals("剧集 first", first.label)
            assertEquals(state, first.cacheState); assertEquals(state == "complete", first.cached); assertEquals(percent, first.percent)
            assertFalse(updated.lines.single().episodes.last().cached)
            assertEquals(2, updated.work.caches.size)
        }
        updated = updated.withCaches(emptyList())
        assertTrue(updated.work.caches.isEmpty()); assertFalse(updated.lines.single().episodes.first().cached)
        assertEquals("", updated.lines.single().episodes.first().cacheState)
        assertEquals(-1, updated.lines.single().episodes.first().percent)
    }

    @Test fun cacheSelectionMergesCatalogWithMissingCachedEpisodesAndLinesWithoutDuplicates() {
        val work = LibraryWork("s", "film", 1, 2, "Film")
        val catalog = LibraryDetail.online(work, detail(work, "first", "second"))
        val caches = listOf(cached(ref("film", "first"), percent = 100),
            cached(ref("film", "missing"), "failed", "", 18),
            cached(ref("film", "queued").put("lineId", "old-line"), "queued", "旧线路剧集"),
            cached(ref("film", "removing").put("lineId", "old-line"), "removing"),
            cached(ref("film", "first"), "failed"))
        val selection = LibraryDetail.forCacheSelection(work.copy(caches = caches), catalog)
        assertFalse(selection.offline)
        assertEquals(listOf("line", "old-line"), selection.lines.map { it.id })
        assertEquals("主线路", selection.lines.first().label)
        assertTrue(selection.lines.last().label.isNotBlank())
        val episodes = selection.lines.flatMap { it.episodes }
        assertEquals(listOf("first", "second", "missing", "queued", "removing"), episodes.map { it.reference.getString("episodeKey") })
        assertEquals(listOf("complete", "", "failed", "queued", "removing"), episodes.map { it.cacheState })
        assertTrue(episodes[0].cached); assertTrue(episodes.drop(1).none { it.cached })
        assertEquals("第 3 集", episodes[2].label); assertEquals("旧线路剧集", episodes[3].label)
        assertEquals(4, selection.work.caches.size)
    }

    @Test fun cacheSelectionAllowsStaleWorkManagementButKeepsEveryIdentityBoundary() {
        val work = LibraryWork("s", "film", 1, 2, "Old film", current = false)
        val old = cached(ref("film", "old").put("url", "https://private.invalid/?token=secret")
            .put("headers", JSONObject().put("Authorization", "secret")), "failed")
        val mixed = listOf(old, cached(ref("film", "wrong-revision", revision = 2)),
            cached(ref("film", "wrong-epoch").put("epoch", 3)),
            cached(ref("film", "wrong-source").put("sourceId", "another")),
            cached(ref("another-film", "wrong-film")), cached(ref("film", "invalid").put("episodeKey", "")))
        val current = work.copy(revision = 2, current = true)
        val currentDetail = LibraryDetail.online(current, detail(current, "new"))
        val selection = LibraryDetail.forCacheSelection(work.copy(caches = mixed), currentDetail)
        assertFalse(selection.work.current); assertFalse(selection.offline)
        assertEquals(1, selection.work.caches.size)
        val episode = selection.lines.single().episodes.single()
        assertEquals("old", episode.reference.getString("episodeKey")); assertEquals("failed", episode.cacheState)
        assertFalse(episode.cached); assertFalse(episode.reference.toString().contains("secret"))
        assertFalse(selection.work.caches.single().reference.toString().contains("secret"))
        assertTrue(LibraryDetail.forCacheSelection(work, currentDetail).lines.isEmpty())
        assertTrue(LibraryDetail.forCacheSelection(work, null).lines.isEmpty())
    }
}
