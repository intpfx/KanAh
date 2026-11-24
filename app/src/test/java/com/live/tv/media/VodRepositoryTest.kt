package com.live.tv.media

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okio.buffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    // Synthetic encryption seam: these tests verify that only sealed values reach the file.
    // AndroidKeyStore encryption itself belongs to the existing MediaSecrets implementation.
    private val seal: (String) -> String = { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
    private val open: (String) -> String = { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
    private fun store(file: File = File(temporary.newFolder(), "vod.json")) = VodSourceStore(file, seal, open)
    private fun repo(respond: (Request) -> Response): VodRepository = VodRepository(store(),
        OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build())
    private fun answer(request: Request, body: String = catalogFixture().toString(), mime: String = "application/json", code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(code)
            .body(body.toResponseBody(mime.toMediaType())).build()
    private fun add(repository: VodRepository, extra: Map<String, String> = emptyMap()): String {
        val response = repository.command("vod_add_source", mapOf("name" to "Fixture", "url" to "https://api.example/vod") + extra)
        assertTrue(response.toString(), response.getBoolean("ok"))
        return response.getString("id")
    }
    @Test fun xiguaPresetUsesTheVerifiedJsonEndpointAndReusesAnExistingSource() {
        val endpoint = "https://xgzy8.tv/api.php/provide/vod/from/xiguam3u8/at/json/"
        val preset = mapOf("preset" to "xigua")
        val fresh = VodRepository(store(), OkHttpClient())
        val added = fresh.command("vod_add_source", preset)
        assertTrue(added.getBoolean("ok"))
        val source = fresh.state().getJSONArray("sources").getJSONObject(0)
        assertEquals("西瓜资源站", source.getString("name"))
        assertEquals(endpoint, source.getString("url"))
        assertEquals(added.getString("id"), fresh.command("vod_add_source", preset).getString("id"))

        val existing = VodRepository(store(), OkHttpClient())
        val manualId = add(existing, mapOf("url" to endpoint.trimEnd('/')))
        assertEquals(manualId, existing.command("vod_add_source", preset).getString("id"))
        assertEquals(1, existing.state().getJSONArray("sources").length())
    }

    @Test fun importedEpisodesPersistAndResolveWithoutAnExternalCatalog() {
        val file = File(temporary.newFolder(), "vod.json")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("video.example", chain.request().url.host)
            answer(chain.request(), "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\npart.ts\n#EXT-X-ENDLIST\n",
                "application/vnd.apple.mpegurl")
        }.build()
        val repository = VodRepository(store(file), client)
        val form = mapOf("name" to "导入的综艺", "episodes" to
            "先导片 | https://video.example/first.m3u8?token=private\nhttps://video.example/second.m3u8")
        val result = repository.command("vod_import_m3u8", form)
        assertTrue(result.getBoolean("ok"))
        val id = result.getString("id")
        assertEquals(id, repository.command("vod_import_m3u8", form).getString("id"))
        assertFalse(repository.state().toString().contains("video.example"))
        assertFalse(file.readText().contains("token=private"))

        val reopened = VodRepository(store(file), client)
        val item = reopened.search("综艺", 1, VodRequest()).getJSONObject("search").getJSONArray("items").getJSONObject(0)
        assertEquals(id, item.getString("sourceId"))
        val detail = reopened.detail(id, item.getString("vodId"), VodRequest())
        val episodes = detail.getJSONArray("lines").getJSONObject(0).getJSONArray("episodes")
        assertEquals("先导片", episodes.getJSONObject(0).getString("label"))
        assertEquals("第 2 集", episodes.getJSONObject(1).getString("label"))
        val playback = reopened.resolve(ref(detail), VodRequest())
        assertEquals("https://video.example/first.m3u8?token=private", playback.getString("url"))
        assertEquals("2", playback.getJSONObject("nextEpisode").getString("episodeKey"))

        val invalid = reopened.command("vod_import_m3u8", form + ("episodes" to "https://video.example/a.mp4"))
        assertEquals("INVALID_SOURCE", invalid.getString("code"))
        assertEquals(1, reopened.state().getJSONArray("sources").length())
    }

    private fun catalogFixture() = JSONObject().put("code", 1).put("page", "1").put("pagecount", "2").put("limit", 1)
        .put("class", JSONArray().put(JSONObject().put("type_id", 2).put("type_name", "剧集")))
        .put("list", JSONArray().put(JSONObject().put("vod_id", "series:001").put("vod_name", "<b>示例</b>")
            .put("vod_pic", "/poster.jpg").put("vod_content", "<p>简介</p><script>window.secret()</script>")
            .put("vod_year", 2026).put("type_name", "<b>剧集</b>").put("vod_remarks", JSONObject.NULL)))
    private fun detailFixture(urls: String, flag: String = "主线", id: String = "series:001") = JSONObject().put("code", 1)
        .put("list", JSONArray().put(JSONObject().put("vod_id", id).put("vod_name", "示例")
            .put("vod_play_from", flag).put("vod_play_url", urls)))
    private fun ref(detail: JSONObject, lineIndex: Int = 0, episodeIndex: Int = 0): JSONObject {
        val line = detail.getJSONArray("lines").getJSONObject(lineIndex)
        return JSONObject().put("sourceId", detail.getString("sourceId")).put("vodId", detail.getString("vodId"))
            .put("revision", detail.getInt("revision")).put("epoch", detail.getInt("epoch"))
            .put("lineId", line.getString("id")).put("episodeKey", line.getJSONArray("episodes").getJSONObject(episodeIndex).getString("key"))
    }
    private fun expect(code: String, block: () -> Any?) {
        try { block(); fail("Expected $code") }
        catch (e: VodException) { assertEquals(code, e.code); assertFalse(e.toString().contains("synthetic-secret")) }
    }

    @Test fun endpointUpdatePreservesIdentityHeadersAndEncryptedStore() {
        val file = File(temporary.newFolder(), "source.json")
        val sources = store(file)
        val original = sources.add(mapOf("name" to "尤酷 JSON", "url" to "https://api.example/vod?ac=list", "header" to "{\"User-Agent\":\"Fixture\"}", "searchable" to "0", "ext" to "synthetic-secret"))
        val updated = sources.updateEndpoint(mapOf("id" to original.id, "url" to "https://mirror.example/vod?ac=list"))
        assertEquals(original.id, updated.id); assertEquals(original.name, updated.name)
        assertEquals(original.headers, updated.headers); assertEquals(original.ext, updated.ext)
        assertEquals(original.searchable, updated.searchable); assertEquals(original.epoch, updated.epoch)
        assertEquals(original.revision + 1, updated.revision)
        val reloaded = store(file).source(original.id)
        assertEquals(updated, reloaded)
        assertFalse(file.readText().contains("mirror.example")); assertFalse(file.readText().contains("synthetic-secret"))
        assertEquals(updated.revision, sources.updateEndpoint(mapOf("id" to original.id, "url" to updated.api.toString())).revision)
        expect("INVALID_SOURCE") { sources.updateEndpoint(mapOf("id" to original.id, "url" to "https://user:pass@other.example/vod")) }
        assertEquals(updated, sources.source(original.id))
    }

    @Test fun endpointSwitchRejectsOldSelectionsButKeepsPlaybackIdentityForFreshDetails() {
        val repository = repo { request ->
            if (request.url.host in listOf("api.example", "mirror.example")) answer(request, detailFixture("第01集\$https://cdn.example/movie.m3u8").toString())
            else answer(request, "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        }
        val id = add(repository)
        val originalRef = ref(repository.detail(id, "series:001", VodRequest()))
        val originalSpec = repository.resolve(originalRef, VodRequest())
        val response = repository.command("vod_update_source", mapOf("id" to id, "url" to "https://mirror.example/vod"))
        assertTrue(response.toString(), response.getBoolean("ok")); assertEquals(id, response.getString("id"))
        expect("STALE_SELECTION") { repository.resolve(originalRef, VodRequest()) }
        val updatedRef = ref(repository.detail(id, "series:001", VodRequest()))
        assertEquals(originalSpec.getString("id"), repository.resolve(updatedRef, VodRequest()).getString("id"))
    }

    @Test fun requestMatrixPreservesUnknownQueryAndReplacesControlParameters() {
        val requests = mutableListOf<Request>()
        val repository = repo { requests.add(it); answer(it) }
        val id = add(repository, mapOf("url" to "https://api.example/vod?ac=list&token=synthetic-secret&t=wrong&pg=7"))
        val home = repository.catalog(id, "", "", 1, VodRequest())
        assertEquals("list", requests.last().url.queryParameter("ac"))
        assertEquals("7", requests.last().url.queryParameter("pg")) // Naked configured home API.
        assertEquals("示例", home.getJSONArray("items").getJSONObject(0).getString("title"))
        assertEquals("简介", home.getJSONArray("items").getJSONObject(0).getString("overview"))
        assertEquals("剧集", home.getJSONArray("items").getJSONObject(0).getString("typeName"))
        assertEquals("https://api.example/poster.jpg", home.getJSONArray("items").getJSONObject(0).getString("poster"))
        repository.catalog(id, "2", "", 3, VodRequest())
        assertEquals(listOf("detail"), requests.last().url.queryParameterValues("ac"))
        assertEquals("2", requests.last().url.queryParameter("t"))
        assertEquals("3", requests.last().url.queryParameter("pg"))
        assertEquals("synthetic-secret", requests.last().url.queryParameter("token"))
        repository.catalog(id, "", "中文 影片", 1, VodRequest())
        assertEquals("中文 影片", requests.last().url.queryParameter("wd"))
        assertNull(requests.last().url.queryParameter("pg"))
        assertNull(requests.last().url.queryParameter("t"))
        assertEquals("false", requests.last().url.queryParameter("quick"))
        assertEquals("", requests.last().url.queryParameter("extend"))
        repository.catalog(id, "", "中文 影片", 2, VodRequest())
        assertEquals("2", requests.last().url.queryParameter("pg"))
        assertFalse(repository.state().toString().contains("synthetic-secret"))
    }

    @Test fun minimalHomeCatalogHydratesArtworkWithoutChangingPageOrderOrIdentity() {
        val requests = mutableListOf<Request>()
        val home = JSONObject().put("page", 7).put("pagecount", 9).put("class", JSONArray()
            .put(JSONObject().put("type_id", "anime").put("type_name", "动画")))
            .put("list", JSONArray().put(JSONObject().put("vod_id", "series:002").put("vod_name", "第二部"))
                .put(JSONObject().put("vod_id", "series:001").put("vod_name", "第一部").put("vod_remarks", "更新中")))
        val details = JSONObject().put("list", JSONArray()
            .put(JSONObject().put("vod_id", "series:001").put("vod_name", "详情标题不得覆盖")
                .put("vod_pic", "/original-1.jpg").put("vod_pic_slide", "https://images.example/wide-1.jpg")
                .put("vod_year", 2026).put("type_name", "<b>剧集</b>"))
            .put(JSONObject().put("vod_id", "series:002").put("vod_pic", "https://images.example/original-2.jpg")))
        val repository = repo { request ->
            requests.add(request)
            answer(request, (if (request.url.queryParameter("ids") == null) home else details).toString())
        }
        val id = add(repository, mapOf("url" to "https://api.example/vod?ac=list&t=keep&pg=7&token=synthetic-secret"))
        val result = repository.catalog(id, "", "", 1, VodRequest())
        assertEquals(2, requests.size)
        assertEquals("list", requests[0].url.queryParameter("ac"))
        assertEquals("keep", requests[0].url.queryParameter("t")); assertEquals("7", requests[0].url.queryParameter("pg"))
        assertEquals("detail", requests[1].url.queryParameter("ac"))
        assertEquals("series:002,series:001", requests[1].url.queryParameter("ids"))
        assertEquals("synthetic-secret", requests[1].url.queryParameter("token"))
        assertNull(requests[1].url.queryParameter("t")); assertNull(requests[1].url.queryParameter("pg"))
        assertEquals("anime", result.getJSONArray("categories").getJSONObject(0).getString("id"))
        assertEquals(1, result.getInt("page")); assertEquals(7, result.getInt("reportedPage")); assertEquals(9, result.getInt("pageCount"))
        assertTrue(result.getBoolean("hasMore"))
        val items = result.getJSONArray("items")
        assertEquals("series:002", items.getJSONObject(0).getString("vodId"))
        assertEquals(MacCmsProvider.workId(id, "series:002"), items.getJSONObject(0).getString("id"))
        assertEquals("https://images.example/original-2.jpg", items.getJSONObject(0).getString("poster"))
        assertEquals("第一部", items.getJSONObject(1).getString("title"))
        assertEquals("2026", items.getJSONObject(1).getString("year"))
        assertEquals("剧集", items.getJSONObject(1).getString("typeName"))
        assertEquals("更新中", items.getJSONObject(1).getString("remarks"))
        assertEquals("https://api.example/original-1.jpg", items.getJSONObject(1).getString("poster"))
        assertEquals("https://images.example/wide-1.jpg", items.getJSONObject(1).getString("backdrop"))
    }

    @Test fun searchHydratesOnlyMissingArtworkAndPreservesSearchPage() {
        val requests = mutableListOf<Request>()
        val catalog = catalogFixture().put("page", 2).put("pagecount", 3)
        catalog.getJSONArray("list").put(JSONObject().put("vod_id", "found").put("vod_name", "搜索结果"))
        val repository = repo { request ->
            requests.add(request)
            answer(request, (if (request.url.queryParameter("ids") == null) catalog else JSONObject().put("list", JSONArray()
                .put(JSONObject().put("vod_id", "found").put("vod_pic", "/found.jpg")))).toString())
        }
        val id = add(repository, mapOf("url" to "https://api.example/vod?ac=list"))
        val result = repository.catalog(id, "ignored", "中文 影片", 2, VodRequest())
        assertEquals(2, requests.size)
        assertEquals("中文 影片", requests[0].url.queryParameter("wd")); assertEquals("2", requests[0].url.queryParameter("pg"))
        assertEquals("list", requests[0].url.queryParameter("ac"))
        assertEquals("found", requests[1].url.queryParameter("ids")); assertNull(requests[1].url.queryParameter("wd"))
        assertEquals(2, result.getInt("page")); assertEquals(3, result.getInt("pageCount"))
        val items = result.getJSONArray("items")
        assertEquals("https://api.example/poster.jpg", items.getJSONObject(0).getString("poster"))
        assertEquals(MacCmsProvider.workId(id, "found"), items.getJSONObject(1).getString("id"))
        assertEquals("https://api.example/found.jpg", items.getJSONObject(1).getString("poster"))
    }

    @Test fun artworkHydrationIgnoresUnmatchedDuplicateAndUnsafeImages() {
        val catalog = JSONObject().put("list", JSONArray((1..3).map { JSONObject().put("vod_id", "$it") }))
        val details = JSONObject().put("list", JSONArray()
            .put(JSONObject().put("vod_id", "1").put("vod_pic", "/duplicate-a.jpg"))
            .put(JSONObject().put("vod_id", "1").put("vod_pic", "/duplicate-b.jpg"))
            .put(JSONObject().put("vod_id", "2").put("vod_pic", "https://user:secret@images.example/unsafe.jpg")
                .put("vod_pic_slide", "javascript:alert(1)"))
            .put(JSONObject().put("vod_id", "3").put("vod_pic", "/correct.jpg").put("vod_pic_slide", "/wide.jpg"))
            .put(JSONObject().put("vod_id", "wrong").put("vod_pic", "/wrong.jpg")))
        val repository = repo { request -> answer(request,
            (if (request.url.queryParameter("ids") == null) catalog else details).toString()) }
        val result = repository.catalog(add(repository), "", "", 1, VodRequest()).getJSONArray("items")
        assertEquals("", result.getJSONObject(0).getString("poster"))
        assertEquals("", result.getJSONObject(1).getString("poster")); assertEquals("", result.getJSONObject(1).getString("backdrop"))
        assertEquals("https://api.example/correct.jpg", result.getJSONObject(2).getString("poster"))
        assertEquals("https://api.example/wide.jpg", result.getJSONObject(2).getString("backdrop"))
    }

    @Test fun optionalArtworkFailureRetainsCatalogButCancellationPropagates() {
        var cancelled: VodRequest? = null
        var detailBody = "<html>synthetic-secret</html>"
        var detailCode = 500
        val repository = repo { request ->
            if (request.url.queryParameter("ids") == null) answer(request,
                JSONObject().put("list", JSONArray().put(JSONObject().put("vod_id", "1").put("vod_name", "影片"))).toString())
            else {
                cancelled?.cancel()
                answer(request, detailBody, code = detailCode)
            }
        }
        val id = add(repository)
        assertEquals("影片", repository.catalog(id, "", "", 1, VodRequest()).getJSONArray("items").getJSONObject(0).getString("title"))
        detailCode = 200
        assertEquals("", repository.catalog(id, "", "", 1, VodRequest()).getJSONArray("items").getJSONObject(0).getString("poster"))
        detailBody = JSONObject().put("list", JSONArray()).toString()
        val request = VodRequest().also { cancelled = it }
        expect("CANCELLED") { repository.catalog(id, "", "", 1, request) }
    }

    @Test fun artworkHydrationUsesBoundedExactIdBatchesAndSkipsAmbiguousCommaIds() {
        val rows = JSONArray((1..41).map { JSONObject().put("vod_id", "item:$it") })
            .put(JSONObject().put("vod_id", "opaque,one"))
        val requests = mutableListOf<Request>()
        val repository = repo { request ->
            requests.add(request)
            val ids = request.url.queryParameter("ids")
            answer(request, JSONObject().put("list", if (ids == null) rows else JSONArray(ids.split(',').map {
                JSONObject().put("vod_id", it).put("vod_pic", "/$it.jpg")
            })).toString())
        }
        val items = repository.catalog(add(repository), "", "", 1, VodRequest()).getJSONArray("items")
        assertEquals(3, requests.size)
        assertEquals((1..40).joinToString(",") { "item:$it" }, requests[1].url.queryParameter("ids"))
        assertEquals("item:41", requests[2].url.queryParameter("ids"))
        assertEquals(42, items.length()); assertEquals("opaque,one", items.getJSONObject(41).getString("vodId"))
        assertEquals("", items.getJSONObject(41).getString("poster"))
        assertEquals("https://api.example/item:41.jpg", items.getJSONObject(40).getString("poster"))
    }

    @Test fun completeCatalogArtworkSkipsHydrationAndParsesSafeBackdrop() {
        var calls = 0
        val catalog = catalogFixture()
        catalog.getJSONArray("list").getJSONObject(0).put("vod_pic_slide", "/wide-original.jpg")
        val repository = repo { request -> calls++; answer(request, catalog.toString()) }
        val item = repository.catalog(add(repository), "", "", 1, VodRequest()).getJSONArray("items").getJSONObject(0)
        assertEquals(1, calls)
        assertEquals("https://api.example/poster.jpg", item.getString("poster"))
        assertEquals("https://api.example/wide-original.jpg", item.getString("backdrop"))
    }

    @Test fun detailAndLargeExtUseExactOpaqueIdAndBoundedPost() {
        val requests = mutableListOf<Request>()
        val repository = repo { requests.add(it); answer(it, detailFixture("正片\$https://cdn.example/main.mp4").toString()) }
        val ext = JSONObject().put("data", "x".repeat(1100)).toString()
        val id = add(repository, mapOf("url" to "https://api.example/vod?ac=list&token=keep", "ext" to ext))
        val detail = repository.detail(id, "series:001", VodRequest())
        assertEquals("series:001", detail.getString("vodId"))
        val request = requests.single()
        assertEquals("POST", request.method)
        assertNull(request.url.queryParameter("ac"))
        assertEquals("keep", request.url.queryParameter("token"))
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("detail", fields["ac"])
        assertEquals("series:001", fields["ids"])
        assertEquals(ext, fields["extend"])
        assertFalse(detail.toString().contains("cdn.example"))
    }

    @Test fun signedUrlRefreshKeepsUniqueEpisodeIdentityAndNeverPublishesLocator() {
        var detailCalls = 0
        val mediaRequests = mutableListOf<Request>()
        val repository = repo { request ->
            if (request.url.host == "api.example") {
                detailCalls++
                answer(request, detailFixture("第 1 集\$https://cdn.example/main.m3u8?token=version$detailCalls").toString())
            } else {
                mediaRequests.add(request)
                answer(request, "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
            }
        }
        val id = add(repository, mapOf("header" to JSONObject().put("Authorization", "Bearer synthetic-secret")
            .put("Cookie", "synthetic-secret").put("Referer", "https://page.example/").put("User-Agent", "Fixture").toString()))
        val detail = repository.detail(id, "series:001", VodRequest())
        assertFalse(detail.toString().contains("version1"))
        val selected = ref(detail)
        val spec = repository.resolve(selected, VodRequest())
        assertEquals(2, detailCalls)
        assertTrue(spec.getString("url").contains("version2"))
        assertEquals("application/x-mpegURL", spec.getString("mimeType"))
        assertTrue(spec.getBoolean("stable"))
        assertTrue(spec.getString("id").startsWith("vod:"))
        assertNull(mediaRequests.single().header("Authorization"))
        assertNull(mediaRequests.single().header("Cookie"))
        assertEquals("https://page.example/", mediaRequests.single().header("Referer"))
        val again = repository.resolve(selected, VodRequest())
        assertEquals(spec.getString("id"), again.getString("id"))
        assertEquals("bytes=0-65535", mediaRequests.last().header("Range"))
    }

    @Test fun invalidRevisionRemovedSourceAndChangedWeakEpisodeCannotPlay() {
        var body = detailFixture("重复\$https://cdn.example/a.mp4#重复\$https://cdn.example/b.mp4")
        val repository = repo { answer(it, body.toString()) }
        val id = add(repository)
        val detail = repository.detail(id, "series:001", VodRequest())
        assertFalse(detail.getJSONArray("lines").getJSONObject(0).getJSONArray("episodes").getJSONObject(0).getBoolean("stable"))
        val selected = ref(detail)
        expect("STALE_SELECTION") { repository.resolve(JSONObject(selected.toString()).put("revision", 2), VodRequest()) }
        body = detailFixture("重复\$https://cdn.example/changed.mp4#重复\$https://cdn.example/b.mp4")
        expect("STALE_SELECTION") { repository.resolve(selected, VodRequest()) }
        assertTrue(repository.command("vod_remove_source", mapOf("id" to id)).getBoolean("ok"))
        expect("SOURCE_NOT_FOUND") { repository.resolve(selected, VodRequest()) }
    }

    @Test fun resolvedEpisodeNeighborsStayOnTheValidatedLineAndOmitSeriesBoundaries() {
        val requests = mutableListOf<Request>()
        val repository = repo { request ->
            requests.add(request)
            if (request.url.host == "api.example") answer(request, detailFixture(
                "第01集\$https://cdn.example/1.m3u8#第02集\$https://cdn.example/2.m3u8#第03集\$https://cdn.example/3.m3u8" +
                    "\$\$\$独立正片\$https://cdn.example/other.m3u8", "主线\$\$\$其他线路").toString())
            else answer(request, "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        }
        val detail = repository.detail(add(repository), "series:001", VodRequest())
        val expectedKeys = setOf("sourceId", "vodId", "lineId", "episodeKey", "revision", "epoch")
        for (index in 0..2) {
            val spec = repository.resolve(ref(detail, episodeIndex = index), VodRequest())
            val catalog = spec.getJSONArray("episodeItems")
            assertEquals(3, catalog.length())
            assertEquals(detail.getString("title"), spec.getString("resourceTitle"))
            assertEquals(detail.getString("poster"), spec.getString("poster"))
            assertEquals("第0${index + 1}集", spec.getString("episodeLabel"))
            for (catalogIndex in 0..2) {
                val item = catalog.getJSONObject(catalogIndex)
                assertEquals("第0${catalogIndex + 1}集", item.getString("label"))
                assertEquals(expectedKeys, item.getJSONObject("ref").keys().asSequence().toSet())
                assertEquals(ref(detail, episodeIndex = catalogIndex).getString("episodeKey"), item.getJSONObject("ref").getString("episodeKey"))
            }
            assertEquals(index > 0, spec.has("previousEpisode"))
            assertEquals(index < 2, spec.has("nextEpisode"))
            listOf("previousEpisode" to index - 1, "nextEpisode" to index + 1).forEach { (name, neighborIndex) ->
                if (neighborIndex in 0..2) {
                    val neighbor = spec.getJSONObject(name)
                    assertEquals(expectedKeys, neighbor.keys().asSequence().toSet())
                    val expected = ref(detail, episodeIndex = neighborIndex)
                    expectedKeys.forEach { assertEquals(expected.get(it), neighbor.get(it)) }
                    assertFalse(neighbor.toString().contains("cdn.example"))
                }
            }
        }
        val singleton = repository.resolve(ref(detail, lineIndex = 1), VodRequest())
        assertFalse(singleton.has("previousEpisode")); assertFalse(singleton.has("nextEpisode"))
        // One fresh detail and one current-episode media probe per resolution; no neighbor probes.
        assertEquals(5, requests.count { it.url.host == "api.example" })
        assertEquals(4, requests.count { it.url.host == "cdn.example" })
    }

    @Test fun episodeNeighborsFollowFreshDetailOrderAndRetainUpdatedSourceIdentity() {
        var urls = "第01集\$https://cdn.example/1.m3u8#第02集\$https://cdn.example/2.m3u8"
        val repository = repo { request ->
            if (request.url.host in listOf("api.example", "mirror.example")) answer(request, detailFixture(urls).toString())
            else answer(request, "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl")
        }
        val id = add(repository)
        repository.command("vod_update_source", mapOf("id" to id, "url" to "https://mirror.example/vod"))
        val detail = repository.detail(id, "series:001", VodRequest())
        val selected = ref(detail)
        urls = "新增前集\$https://cdn.example/new.m3u8#第01集\$https://cdn.example/1.m3u8?token=changed#第02集\$https://cdn.example/2.m3u8"
        val spec = repository.resolve(selected, VodRequest())
        val previous = spec.getJSONObject("previousEpisode")
        assertEquals(selected.getInt("revision"), previous.getInt("revision"))
        assertEquals(selected.getInt("epoch"), previous.getInt("epoch"))
        assertEquals(id, previous.getString("sourceId"))
        assertEquals(selected.getString("lineId"), previous.getString("lineId"))
        assertNotEquals(selected.getString("episodeKey"), previous.getString("episodeKey"))
        assertFalse(previous.toString().contains("token"))
        val previousSpec = repository.resolve(previous, VodRequest())
        assertFalse(previousSpec.has("previousEpisode"))
        val backToSelected = previousSpec.getJSONObject("nextEpisode")
        selected.keys().forEach { assertEquals(selected.get(it), backToSelected.get(it)) }
    }

    @Test fun parserDeclarationNeverRunsPrefixOrProbesMedia() {
        var calls = 0
        val repository = repo { calls++; answer(it, detailFixture("正片\$https://cdn.example/direct.mp4").toString()) }
        val id = add(repository, mapOf("playUrl" to "json:https://parser.example/?url="))
        val detail = repository.detail(id, "series:001", VodRequest())
        assertTrue(detail.getBoolean("requiresParser"))
        expect("REQUIRES_PARSER") { repository.resolve(ref(detail), VodRequest()) }
        assertEquals(1, calls)
    }

    @Test fun playlistsPreserveFirstDollarAndTreatMissingOrDuplicateIdentityAsWeak() {
        val lines = MacCmsProvider.playlists("主线\$\$\$主线\$\$\$", "正片\$https://example.org/a.mp4?x=\$keep#\$https://example.org/b.mp4\$\$\$第1集\$https://example.org/c.mp4\$\$\$https://example.org/d.mp4")
        assertEquals(3, lines.size)
        assertFalse(lines[0].stable)
        assertFalse(lines[1].stable)
        assertFalse(lines[2].stable)
        assertEquals("https://example.org/a.mp4?x=\$keep", lines[0].episodes[0].locator)
        assertEquals("02", lines[0].episodes[1].label)
        assertFalse(lines[0].episodes[1].stable)
        val unique = MacCmsProvider.playlists("正常", "第1集\$https://example.org/a.mp4#第2集\$https://example.org/b.mp4#")
        assertEquals(2, unique.single().episodes.size)
        assertTrue(unique.single().episodes.all { it.stable })
    }

    @Test fun badResponseBudgetsAndPrivateErrorBodyDoNotLeak() {
        var body = "<html>synthetic-secret</html>"
        var code = 200
        val repository = repo { answer(it, body, code = code) }
        val id = add(repository)
        expect("INVALID_RESPONSE") { repository.catalog(id, "", "", 1, VodRequest()) }
        code = 403
        expect("HTTP_403") { repository.catalog(id, "", "", 1, VodRequest()) }
        code = 200; body = " ".repeat(2 * 1024 * 1024 + 1)
        expect("PAYLOAD_LIMIT") { repository.catalog(id, "", "", 1, VodRequest()) }
        body = JSONObject().put("code", 0).put("msg", "synthetic-secret").put("list", JSONArray()).toString()
        expect("SOURCE_ERROR") { repository.catalog(id, "", "", 1, VodRequest()) }
        body = JSONObject().put("list", JSONArray(List(201) { JSONObject().put("vod_id", it) })).toString()
        expect("PAYLOAD_LIMIT") { repository.catalog(id, "", "", 1, VodRequest()) }
    }

    @Test fun unknownPageCountStillAllowsExplicitNextPageAndEmptyPageStops() {
        var body = catalogFixture().apply { remove("pagecount"); remove("limit") }
        val repository = repo { answer(it, body.toString()) }
        val id = add(repository)
        assertTrue(repository.catalog(id, "", "", 1, VodRequest()).getBoolean("hasMore"))
        body = JSONObject().put("list", JSONArray()).put("pagecount", 0)
        assertFalse(repository.catalog(id, "", "", 2, VodRequest()).getBoolean("hasMore"))
    }

    @Test fun sourceStorePersistsSealedConfigAndRejectsCorruptionWithoutOverwriting() {
        val file = File(temporary.newFolder(), "vod.json")
        val store = store(file)
        val source = store.add(mapOf("name" to "Fixture", "url" to "https://example.org/api?token=synthetic-secret",
            "header" to "{\"Authorization\":\"Bearer synthetic-secret\"}"))
        assertFalse(file.readText().contains("synthetic-secret"))
        assertEquals(source.id, store(file).source(source.id).id)
        assertEquals(source.headers, store(file).source(source.id).headers)
        val original = file.readText()
        val failing = VodSourceStore(file, { throw IllegalStateException("synthetic-secret") }, open)
        expect("SOURCE_STORE") { failing.add(mapOf("name" to "Other", "url" to "https://example.org/other")) }
        assertEquals(original, file.readText())
        file.writeText("broken")
        val broken = store(file)
        assertEquals("SOURCE_STORE", broken.state().getString("errorCode"))
        expect("SOURCE_STORE") { broken.add(mapOf("name" to "Other", "url" to "https://example.org/other")) }
        assertEquals("broken", file.readText())
    }

    @Test fun sourceValidationDoesNotEnableUnsupportedProtocolOrInvalidHeaders() {
        val repository = repo { answer(it) }
        listOf(mapOf("url" to "file:///etc/passwd"), mapOf("url" to "https://u:p@example.org/api"),
            mapOf("apiType" to "0"), mapOf("header" to "{\"Host\":\"other.example\"}"),
            mapOf("header" to "{\"Cookie\":\"bad\\r\\nvalue\"}"), mapOf("header" to "[1]")).forEach { fields ->
            assertFalse(repository.command("vod_add_source", mapOf("name" to "Fixture", "url" to "https://example.org/api") + fields).getBoolean("ok"))
        }
        val id = add(repository, mapOf("searchable" to "0"))
        expect("SEARCH_DISABLED") { repository.catalog(id, "", "film", 1, VodRequest()) }
        assertEquals(1, repository.state().getJSONArray("sources").length())
    }

    @Test fun cancelledRequestNeverStartsNetworkAndInFlightCancellationClosesCall() {
        var calls = 0
        val repository = repo { calls++; answer(it) }
        val id = add(repository)
        val cancelled = VodRequest().apply { cancel() }
        assertTrue(cancelled.isCancelled())
        expect("CANCELLED") { repository.catalog(id, "", "", 1, cancelled) }
        assertEquals(0, calls)

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val accepted = AtomicReference<java.net.Socket?>()
            val worker = Thread {
                runCatching { server.accept().use { socket ->
                    accepted.set(socket)
                    socket.getInputStream().read(ByteArray(1024))
                    started.countDown()
                    release.await(3, TimeUnit.SECONDS)
                } }
            }.apply { isDaemon = true; start() }
            val liveRepository = VodRepository(store(), OkHttpClient.Builder().callTimeout(4, TimeUnit.SECONDS).build())
            val liveId = add(liveRepository, mapOf("url" to "http://127.0.0.1:${server.localPort}/api"))
            val request = VodRequest()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<String> {
                    try { liveRepository.catalog(liveId, "", "", 1, request); "unexpected" }
                    catch (e: VodException) { e.code }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                request.cancel()
                assertEquals("CANCELLED", future.get(2, TimeUnit.SECONDS))
            } finally { release.countDown(); accepted.get()?.close(); executor.shutdownNow(); worker.join(1000) }
        }
    }

    @Test fun mediaProbeRejectsHtmlAndChecksMp4BytesInsteadOfExtension() {
        val source = VodSource("source", "Fixture", VodSourceStore.httpUrl("https://api.example/vod"))
        var payload = "<html>synthetic-secret</html>".toByteArray()
        var mime = "text/html"
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(payload.toResponseBody(mime.toMediaType())).build() }.build()
        val provider = MacCmsProvider(source, client)
        expect("REQUIRES_PARSER") { provider.probe("https://cdn.example/movie.mp4", VodRequest()) }
        payload = "<html>error</html>".toByteArray(); mime = "video/mp4"
        expect("REQUIRES_PARSER") { provider.probe("https://cdn.example/movie.mp4", VodRequest()) }
        payload = "garbage".toByteArray()
        expect("UNSUPPORTED_MEDIA") { provider.probe("https://cdn.example/movie.mp4", VodRequest()) }
        payload = byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray() + ByteArray(8)
        mime = "application/octet-stream"
        assertEquals("video/mp4", provider.probe("https://cdn.example/no-extension", VodRequest()).getString("mimeType"))
        expect("REQUIRES_PARSER") { provider.probe("opaque-id", VodRequest()) }
    }

    @Test fun apiRedirectRemovesEveryCustomCredentialOnDifferentOrigin() {
        val requests = mutableListOf<Request>()
        val repository = repo { request ->
            requests.add(request)
            if (request.url.host == "api.example") answer(request, code = 302).newBuilder()
                .header("Location", "https://other.example/catalog").build()
            else answer(request)
        }
        val id = add(repository, mapOf("header" to "{\"Authorization\":\"Bearer synthetic-secret\",\"Cookie\":\"synthetic-secret\",\"X-Api-Key\":\"synthetic-secret\",\"User-Agent\":\"Fixture\"}"))
        repository.catalog(id, "", "", 1, VodRequest())
        assertEquals(2, requests.size)
        assertEquals("Bearer synthetic-secret", requests[0].header("Authorization"))
        assertNull(requests[1].header("Authorization")); assertNull(requests[1].header("Cookie")); assertNull(requests[1].header("X-Api-Key"))
        assertEquals("Fixture", requests[1].header("User-Agent"))
    }

    @Test fun crossOriginPostRedirectDoesNotForwardExtBody() {
        var calls = 0
        val repository = repo { request ->
            calls++
            answer(request, code = 307).newBuilder().header("Location", "https://other.example/api").build()
        }
        val id = add(repository, mapOf("ext" to "synthetic-secret".repeat(100)))
        expect("UNSAFE_REDIRECT") { repository.detail(id, "series:001", VodRequest()) }
        assertEquals(1, calls)
    }

    @Test fun removalDuringNetworkRejectsLateResponse() {
        lateinit var repository: VodRepository
        var id = ""
        repository = repo { request ->
            repository.command("vod_remove_source", mapOf("id" to id))
            answer(request)
        }
        id = add(repository)
        expect("SOURCE_NOT_FOUND") { repository.catalog(id, "", "", 1, VodRequest()) }
    }

    @Test fun unknownLengthResponseIsBoundedAndMediaProbeDoesNotConsumeMovie() {
        var consumed = 0L
        var payload = ByteArray(2 * 1024 * 1024 + 8192) { 32 }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val counted = object : okio.ForwardingSource(okio.Buffer().write(payload)) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0) consumed += it }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = counted
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body).build()
        }.build()
        val repository = VodRepository(store(), client)
        val id = add(repository)
        expect("PAYLOAD_LIMIT") { repository.catalog(id, "", "", 1, VodRequest()) }
        assertTrue(consumed <= 2 * 1024 * 1024 + 8192L) // At most one buffered read beyond the logical limit.
        consumed = 0
        payload = (byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray() + ByteArray(8)) + ByteArray(1024 * 1024)
        val source = VodSource("source", "Fixture", VodSourceStore.httpUrl("https://api.example/vod"))
        assertEquals("video/mp4", MacCmsProvider(source, client).probe("https://cdn.example/movie", VodRequest()).getString("mimeType"))
        assertTrue("Media probe consumed $consumed bytes", consumed <= 65_536)
    }
}
