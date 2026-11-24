package com.live.tv.media

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Every network response is synthetic and intercepted before DNS or a socket is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WsyzyProviderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source(url: String = ENDPOINT) = VodSource("wsyzy-source", "无损云", url.toHttpUrl())
    private fun client(answer: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { answer(it.request()) }.build()
    private fun repository(answer: (Request) -> Response) = VodRepository(
        VodSourceStore(File(temporary.newFolder(), "vod.json"), { it }, { it }), client(answer))
    private fun add(repository: VodRepository, extra: Map<String, String> = emptyMap()): String {
        val result = repository.command("vod_add_source", mapOf("name" to "无损云", "url" to ENDPOINT) + extra)
        assertTrue(result.toString(), result.getBoolean("ok"))
        return result.getString("id")
    }
    private fun answer(request: Request, body: String, code: Int = 200, mime: String = "application/json") =
        answer(request, body.toByteArray(Charsets.UTF_8), code, mime)
    private fun answer(request: Request, body: ByteArray, code: Int = 200, mime: String = "application/octet-stream") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody(mime.toMediaType())).build()
    private fun suggestion(id: String, name: String = "影片 $id", picture: String = "/suggest-$id.jpg") =
        JSONObject().put("id", id).put("name", name).put("pic", picture)
    private fun rows(vararg values: JSONObject) = JSONObject().put("list", JSONArray(values.toList()))
    private fun details(request: Request) = JSONObject().put("list", JSONArray(
        request.url.queryParameter("ids").orEmpty().split(',').filter { it.isNotEmpty() }.map {
            JSONObject().put("vod_id", it).put("vod_name", "详情标题").put("vod_year", 2026)
                .put("type_name", "电影")
        }))
    private fun expect(code: String, operation: () -> Any?) {
        try { operation(); fail("Expected $code") } catch (error: VodException) {
            assertEquals(code, error.code)
            assertFalse(error.toString().contains("synthetic-secret"))
        }
    }

    @Test fun factoryRecognizesOnlyTheExactHttpsEndpointWithoutChangingSourceSchema() {
        val http = client { throw AssertionError("Factory must not make a network request") }
        listOf(ENDPOINT, "$ENDPOINT/", "$ENDPOINT?token=fixture", "https://api.wsyzy.net:443/api.php/provide/vod/")
            .forEach { url ->
                val value = source(url)
                assertTrue(url, WsyzyProvider.supports(value))
                assertTrue(url, VodProvider.create(value, http) is WsyzyProvider)
                assertEquals(1, value.publicJson().getInt("apiType"))
            }
        listOf("http://api.wsyzy.net/api.php/provide/vod", "https://api.wsyzy.net:444/api.php/provide/vod",
            "https://wsyzy.net/api.php/provide/vod", "https://api.wsyzy.net.example/api.php/provide/vod",
            "https://other.example/api.php/provide/vod", "https://api.wsyzy.net/api.php/provide/vod/extra",
            "https://api.wsyzy.net/api.php/provide/vod//", "https://api.wsyzy.net/api.php/provide/vod2",
            "https://api.wsyzy.net/other", "https://api.wsyzy.net/API.php/provide/vod")
            .forEach { url ->
                val value = source(url)
                assertFalse(url, WsyzyProvider.supports(value))
                assertTrue(url, VodProvider.create(value, http) is MacCmsProvider)
            }
    }

    @Test fun concurrentAndRepeatedPresetAdditionCreatesOneCanonicalSourceAndLeavesManualAddsIndependent() {
        val sources = VodSourceStore(File(temporary.newFolder(), "vod.json"), { it }, { it })
        val repository = VodRepository(sources, client { throw AssertionError("Adding a preset must not use the network") })
        val ready = CountDownLatch(8)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val overrides = mapOf("name" to "意外覆盖", "url" to "https://other.example/api", "apiType" to "2",
            "header" to "invalid JSON", "ext" to "synthetic-secret", "playUrl" to "https://parser.example/", "searchable" to "0")
        try {
            val pending = (0 until 8).map { executor.submit<JSONObject> {
                ready.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
                repository.command("vod_add_source", mapOf("preset" to "wsyzy") + overrides)
            } }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            release.countDown()
            val results = pending.map { it.get(5, TimeUnit.SECONDS) }
            results.forEach { assertTrue(it.toString(), it.getBoolean("ok")) }
            val ids = results.map { it.getString("id") }.toSet()
            assertEquals(1, ids.size)
            val id = ids.single()
            assertEquals(1, repository.state().getJSONArray("sources").length())
            val configured = sources.source(id)
            assertEquals("无损云", configured.name)
            assertEquals(ENDPOINT, configured.api.toString().removeSuffix("/"))
            assertTrue(configured.headers.isEmpty()); assertEquals("", configured.ext)
            assertEquals("", configured.playUrl); assertTrue(configured.searchable)
            assertEquals(1, configured.revision); assertEquals(1, configured.epoch)
            val repeated = repository.command("vod_add_source", mapOf("preset" to "wsyzy") + overrides)
            assertTrue(repeated.toString(), repeated.getBoolean("ok")); assertEquals(id, repeated.getString("id"))
            assertEquals(configured, sources.source(id))

            val unknown = repository.command("vod_add_source", mapOf("preset" to "unknown", "name" to "未知", "url" to ENDPOINT))
            assertFalse(unknown.getBoolean("ok")); assertEquals(1, repository.state().getJSONArray("sources").length())
            val manualId = add(repository, mapOf("name" to "手动来源"))
            assertNotEquals(id, manualId)
            assertEquals(2, repository.state().getJSONArray("sources").length())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun presetReusesRecognizedManualSourceAtCapacityWithoutChangingIdentityOrConfiguration() {
        val sources = VodSourceStore(File(temporary.newFolder(), "vod.json"), { it }, { it })
        val manual = sources.add(mapOf("name" to "我的无损云", "url" to "$ENDPOINT/?token=fixture",
            "header" to "{\"User-Agent\":\"Fixture\"}", "ext" to "fixture-extension", "searchable" to "0"))
        val updated = sources.updateEndpoint(mapOf("id" to manual.id, "url" to "$ENDPOINT/?token=updated"))
        (1..31).forEach { index -> sources.add(mapOf("name" to "来源 $index", "url" to "https://source$index.example/api")) }
        val repository = VodRepository(sources, client { throw AssertionError("Adding a preset must not use the network") })
        val before = repository.state().toString()
        val result = repository.command("vod_add_source", mapOf("preset" to "wsyzy"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(manual.id, result.getString("id"))
        assertEquals(updated, sources.source(manual.id))
        assertEquals(2, sources.source(manual.id).revision)
        assertEquals(before, repository.state().toString())
        val manualAtCapacity = repository.command("vod_add_source", mapOf("name" to "另一个手动来源", "url" to ENDPOINT))
        assertFalse(manualAtCapacity.getBoolean("ok")); assertEquals("SOURCE_LIMIT", manualAtCapacity.getString("code"))
    }

    @Test fun suggestCapsUniqueValidIdsAndNeverFetchesIgnoredUpstreamPages() {
        val requests = mutableListOf<Request>()
        val values = JSONArray().put(JSONObject.NULL).put("not a row").put(suggestion(""))
            .put(suggestion("x".repeat(257)))
        (1..55).forEach { index ->
            values.put(suggestion("opaque:$index"))
            if (index == 1) values.put(suggestion("opaque:1", "重复条目"))
        }
        val provider = WsyzyProvider(source(), client { request ->
            requests.add(request)
            answer(request, (if (request.url.host == "wsyzy.cc") JSONObject().put("code", 1)
                .put("list", values).put("total", 100).put("page", 8).put("pagecount", 999)
                else details(request)).toString())
        })
        val result = provider.catalog("ignored", "中文 影片", 1, VodRequest())
        val items = result.getJSONArray("items")
        assertEquals(50, items.length())
        (1..50).forEach { index ->
            val item = items.getJSONObject(index - 1)
            assertEquals("opaque:$index", item.getString("vodId"))
            assertEquals(MacCmsProvider.workId("wsyzy-source", "opaque:$index"), item.getString("id"))
        }
        assertEquals(1, result.getInt("page")); assertEquals(1, result.getInt("reportedPage"))
        assertEquals(1, result.getInt("pageCount")); assertEquals(50, result.getInt("searchLimit"))
        assertFalse(result.getBoolean("hasMore")); assertTrue(result.getBoolean("truncated"))
        assertEquals(3, requests.size)
        assertEquals("51", requests.first().url.queryParameter("limit"))
        assertEquals("中文 影片", requests.first().url.queryParameter("wd"))
        assertEquals((1..40).joinToString(",") { "opaque:$it" }, requests[1].url.queryParameter("ids"))
        assertEquals((41..50).joinToString(",") { "opaque:$it" }, requests[2].url.queryParameter("ids"))

        listOf(2, 10_000).forEach { page ->
            val later = provider.catalog("", "中文 影片", page, VodRequest())
            assertEquals(page, later.getInt("page")); assertEquals(1, later.getInt("pageCount"))
            assertEquals(0, later.getJSONArray("items").length())
            assertFalse(later.getBoolean("hasMore")); assertFalse(later.getBoolean("truncated"))
        }
        assertEquals("Later pages must not repeat suggest or hydration requests", 3, requests.size)
    }

    @Test fun finiteEmptyAndExactlyAtLimitSearchesReportWhetherResultsWereTruncated() {
        var count = 3
        var reportedTotal: Int? = 3
        val requests = mutableListOf<Request>()
        val provider = WsyzyProvider(source(), client { request ->
            requests.add(request)
            val result = if (request.url.host == "wsyzy.cc") JSONObject().put("code", "200")
                .put("list", JSONArray((1..count).map { suggestion(it.toString()) })).apply {
                    reportedTotal?.let { put("total", it.toString()) }
                } else details(request)
            answer(request, result.toString())
        })
        val finite = provider.catalog("", "影片", 1, VodRequest())
        assertEquals(3, finite.getJSONArray("items").length())
        assertFalse(finite.getBoolean("truncated")); assertFalse(finite.getBoolean("hasMore"))

        count = 0; reportedTotal = 0
        val beforeEmpty = requests.size
        val empty = provider.catalog("", "不存在", 1, VodRequest())
        assertEquals(0, empty.getJSONArray("items").length())
        assertFalse(empty.getBoolean("truncated")); assertFalse(empty.getBoolean("hasMore"))
        assertEquals("An empty suggestion response needs no detail lookup", beforeEmpty + 1, requests.size)

        count = 50; reportedTotal = 50
        assertFalse(provider.catalog("", "影片", 1, VodRequest()).getBoolean("truncated"))
        reportedTotal = null
        assertTrue(provider.catalog("", "影片", 1, VodRequest()).getBoolean("truncated"))
    }

    @Test fun metadataEnrichmentPreservesSearchOrderIdentityAndSanitizedTitlesWithPosterFallback() {
        val suggestions = rows(suggestion("second", "<b>第二部</b><script>window.secret()</script>"),
            suggestion("first", "<em>第一部</em>"), suggestion("missing", "未补全", ""),
            suggestion("duplicate", "重复详情"), suggestion("unsafe", "不安全图片", "https://user:synthetic-secret@images.example/a.jpg"))
        val enriched = rows(
            JSONObject().put("vod_id", "first").put("vod_name", "不应覆盖搜索标题")
                .put("vod_pic", "/original.jpg").put("vod_pic_slide", "/wide.jpg")
                .put("vod_year", 2026).put("type_name", "<b>电影</b>").put("vod_remarks", "<i>高清</i>"),
            JSONObject().put("vod_id", "second").put("vod_year", 2025).put("type_name", "剧集"),
            JSONObject().put("vod_id", "duplicate").put("vod_year", 1991).put("vod_pic", "/wrong-a.jpg"),
            JSONObject().put("vod_id", "duplicate").put("vod_year", 1992).put("vod_pic", "/wrong-b.jpg"),
            JSONObject().put("vod_id", "unsafe").put("vod_pic", "javascript:alert(1)"),
            JSONObject().put("vod_id", "unrequested").put("vod_pic", "/unrequested.jpg"))
        val provider = WsyzyProvider(source(), client { request ->
            answer(request, (if (request.url.host == "wsyzy.cc") suggestions else enriched).toString())
        })
        val items = provider.catalog("", "电影", 1, VodRequest()).getJSONArray("items")
        assertEquals(listOf("second", "first", "missing", "duplicate", "unsafe"),
            (0 until items.length()).map { items.getJSONObject(it).getString("vodId") })
        assertEquals("第二部", items.getJSONObject(0).getString("title"))
        assertEquals("https://wsyzy.cc/suggest-second.jpg", items.getJSONObject(0).getString("poster"))
        assertEquals("2025", items.getJSONObject(0).getString("year"))
        assertEquals("剧集", items.getJSONObject(0).getString("typeName"))
        val first = items.getJSONObject(1)
        assertEquals("第一部", first.getString("title"))
        assertEquals(MacCmsProvider.workId("wsyzy-source", "first"), first.getString("id"))
        assertEquals("https://api.wsyzy.net/original.jpg", first.getString("poster"))
        assertEquals("https://api.wsyzy.net/wide.jpg", first.getString("backdrop"))
        assertEquals("2026", first.getString("year")); assertEquals("电影", first.getString("typeName"))
        assertEquals("高清", first.getString("remarks"))
        assertEquals("", items.getJSONObject(2).getString("poster"))
        assertEquals("", items.getJSONObject(2).getString("year"))
        assertEquals("https://wsyzy.cc/suggest-duplicate.jpg", items.getJSONObject(3).getString("poster"))
        assertEquals("", items.getJSONObject(3).getString("year"))
        assertEquals("", items.getJSONObject(4).getString("poster"))
        assertFalse(items.toString().contains("synthetic-secret"))
    }

    @Test fun optionalMetadataFailuresKeepSearchResultsButCancellationStillPropagates() {
        var hydrationCode = 500
        var hydrationBody = "<html>synthetic-secret</html>"
        var cancelDuringHydration: VodRequest? = null
        val provider = WsyzyProvider(source(), client { request ->
            if (request.url.host == "wsyzy.cc") answer(request, rows(suggestion("one", "影片")).toString())
            else {
                cancelDuringHydration?.cancel()
                answer(request, hydrationBody, hydrationCode)
            }
        })
        val fallback = provider.catalog("", "影片", 1, VodRequest()).getJSONArray("items").getJSONObject(0)
        assertEquals("影片", fallback.getString("title"))
        assertEquals("https://wsyzy.cc/suggest-one.jpg", fallback.getString("poster"))
        hydrationCode = 200
        assertEquals(1, provider.catalog("", "影片", 1, VodRequest()).getJSONArray("items").length())
        hydrationBody = "{\"list\":[]}"
        val request = VodRequest().also { cancelDuringHydration = it }
        expect("CANCELLED") { provider.catalog("", "影片", 1, request) }
    }

    @Test fun malformedErrorAndOversizedSuggestionResponsesFailWithoutPublishingRows() {
        val fixtures = listOf(
            "<html>synthetic-secret</html>" to "INVALID_RESPONSE",
            "[]" to "INVALID_RESPONSE",
            "{}" to "INVALID_RESPONSE",
            "{\"list\":{}}" to "INVALID_RESPONSE",
            "{\"code\":0,\"msg\":\"synthetic-secret\",\"list\":[]}" to "SOURCE_ERROR",
            JSONObject().put("list", JSONArray((1..201).map { suggestion(it.toString()) })).toString() to "PAYLOAD_LIMIT",
            " ".repeat(2 * 1024 * 1024 + 1) to "PAYLOAD_LIMIT")
        fixtures.forEach { (body, code) ->
            var calls = 0
            val provider = WsyzyProvider(source(), client { request -> calls++; answer(request, body) })
            expect(code) { provider.catalog("", "影片", 1, VodRequest()) }
            assertEquals("Invalid suggestions must never start metadata requests", 1, calls)
        }
        val unavailable = WsyzyProvider(source(), client { request -> answer(request, "synthetic-secret", 403) })
        expect("HTTP_403") { unavailable.catalog("", "影片", 1, VodRequest()) }
    }

    @Test fun invalidDisabledOrCancelledSearchDoesNotStartAnyRequest() {
        val http = client { throw AssertionError("Invalid searches must not make network requests") }
        val provider = WsyzyProvider(source(), http)
        listOf(0, 10_001).forEach { page -> expect("INVALID_QUERY") { provider.catalog("", "影片", page, VodRequest()) } }
        expect("INVALID_QUERY") { provider.catalog("", "x".repeat(201), 1, VodRequest()) }
        expect("INVALID_QUERY") { provider.catalog("x".repeat(257), "影片", 1, VodRequest()) }
        expect("SEARCH_DISABLED") { WsyzyProvider(source().copy(searchable = false), http).catalog("", "影片", 1, VodRequest()) }
        expect("SEARCH_DISABLED") { WsyzyProvider(source().copy(searchable = false), http).catalog("", "影片", 2, VodRequest()) }
        expect("CANCELLED") { provider.catalog("", "影片", 1, VodRequest().apply { cancel() }) }
        expect("CANCELLED") { provider.catalog("", "影片", 2, VodRequest().apply { cancel() }) }
    }

    @Test fun homeAndCategoryBrowsingStillUseTheConfiguredMacCmsEndpoint() {
        val requests = mutableListOf<Request>()
        val provider = WsyzyProvider(source("$ENDPOINT?ac=list&token=fixture"), client { request ->
            requests.add(request)
            answer(request, rows(JSONObject().put("vod_id", "one").put("vod_name", "影片")
                .put("vod_pic", "/poster.jpg")).put("pagecount", 3).toString())
        })
        val home = provider.catalog("", "", 1, VodRequest())
        assertTrue(home.getBoolean("hasMore")); assertFalse(home.has("searchLimit"))
        provider.catalog("movie", "", 2, VodRequest())
        assertEquals(2, requests.size)
        requests.forEach { assertEquals("api.wsyzy.net", it.url.host); assertEquals("fixture", it.url.queryParameter("token")) }
        assertEquals("list", requests[0].url.queryParameter("ac"))
        assertEquals("detail", requests[1].url.queryParameter("ac"))
        assertEquals("movie", requests[1].url.queryParameter("t")); assertEquals("2", requests[1].url.queryParameter("pg"))
        assertNull(requests[1].url.queryParameter("wd"))
    }

    @Test fun websiteReceivesOnlyUserAgentWhileMetadataKeepsConfiguredApiCredentials() {
        val requests = mutableListOf<Request>()
        val headers = linkedMapOf("uSeR-aGeNt" to "Fixture", "Authorization" to "Bearer synthetic-secret",
            "Cookie" to "session=synthetic-secret", "X-Api-Key" to "synthetic-secret",
            "Referer" to "https://private.example/synthetic-secret", "Origin" to "https://private.example")
        val configured = source("$ENDPOINT?token=synthetic-secret&ac=list&t=wrong&wd=old&pg=7&ids=old")
            .copy(headers = headers, ext = "synthetic-extension")
        val provider = WsyzyProvider(configured, client { request ->
            requests.add(request)
            answer(request, (if (request.url.host == "wsyzy.cc") rows(suggestion("one")) else details(request)).toString())
        })
        val result = provider.catalog("", "中文 & 影片", 1, VodRequest())
        assertEquals(2, requests.size)
        val suggest = requests[0]
        assertEquals("GET", suggest.method); assertNull(suggest.body)
        assertEquals("https", suggest.url.scheme); assertEquals("/index.php/ajax/suggest", suggest.url.encodedPath)
        assertEquals(setOf("mid", "wd", "limit"), suggest.url.queryParameterNames)
        assertEquals("1", suggest.url.queryParameter("mid")); assertEquals("51", suggest.url.queryParameter("limit"))
        assertEquals("中文 & 影片", suggest.url.queryParameter("wd"))
        assertEquals(setOf("uSeR-aGeNt"), suggest.headers.names())
        assertEquals("Fixture", suggest.header("User-Agent"))
        assertFalse(suggest.url.toString().contains("synthetic"))
        val api = requests[1]
        assertEquals("api.wsyzy.net", api.url.host)
        headers.forEach { (name, value) -> assertEquals(value, api.header(name)) }
        assertEquals("synthetic-secret", api.url.queryParameter("token"))
        assertEquals("synthetic-extension", api.url.queryParameter("extend"))
        assertEquals("detail", api.url.queryParameter("ac")); assertEquals("one", api.url.queryParameter("ids"))
        assertNull(api.url.queryParameter("wd")); assertNull(api.url.queryParameter("pg")); assertNull(api.url.queryParameter("t"))
        assertFalse(result.toString().contains("synthetic-secret"))
    }

    @Test fun aggregateCarriesTheSearchLimitAndAdvancesOtherSourcesWithoutRefetchingWsyzy() {
        val requests = CopyOnWriteArrayList<Request>()
        val repository = repository { request ->
            requests.add(request)
            val body = when (request.url.host) {
                "wsyzy.cc" -> rows(suggestion("same"), suggestion("second")).put("total", 99)
                "api.wsyzy.net" -> details(request)
                else -> rows(JSONObject().put("vod_id", "generic-${request.url.queryParameter("pg") ?: "1"}")
                    .put("vod_name", "通用来源影片").put("vod_pic", "/poster.jpg")).put("pagecount", 2)
            }
            answer(request, body.toString())
        }
        val wsyzyId = add(repository)
        val genericId = add(repository, mapOf("name" to "通用来源", "url" to "https://generic.example/api"))
        val first = repository.search("影片", 1, VodRequest()).getJSONObject("search")
        assertEquals(2, first.getInt("successCount")); assertEquals(0, first.getInt("errorCount"))
        assertTrue(first.getBoolean("hasMore")); assertEquals(3, first.getJSONArray("items").length())
        val wsyzyStatus = first.getJSONArray("sources").getJSONObject(0)
        assertEquals(wsyzyId, wsyzyStatus.getString("sourceId")); assertEquals(50, wsyzyStatus.getInt("searchLimit"))
        assertTrue(wsyzyStatus.getBoolean("truncated")); assertFalse(wsyzyStatus.getBoolean("hasMore"))
        val firstItem = first.getJSONArray("items").getJSONObject(0)
        assertEquals(wsyzyId, firstItem.getString("sourceId")); assertEquals("无损云", firstItem.getString("sourceName"))
        assertEquals(1, firstItem.getInt("revision")); assertEquals(1, firstItem.getInt("epoch"))
        assertEquals("2026", firstItem.getString("year")); assertEquals("电影", firstItem.getString("typeName"))
        assertEquals(1, requests.count { it.url.host == "wsyzy.cc" })
        assertEquals(1, requests.count { it.url.host == "api.wsyzy.net" })

        val next = repository.search("影片", 2, VodRequest()).getJSONObject("search")
        assertEquals(2, next.getInt("successCount")); assertFalse(next.getBoolean("hasMore"))
        assertEquals(1, next.getJSONArray("items").length())
        assertEquals(genericId, next.getJSONArray("items").getJSONObject(0).getString("sourceId"))
        assertEquals("generic-2", next.getJSONArray("items").getJSONObject(0).getString("vodId"))
        assertEquals(1, requests.count { it.url.host == "wsyzy.cc" })
        assertEquals(1, requests.count { it.url.host == "api.wsyzy.net" })
        assertEquals(2, requests.count { it.url.host == "generic.example" })
    }

    @Test fun sourceRemovalOrEndpointUpdateDuringHydrationCannotPublishStaleSearchResults() {
        listOf("vod_remove_source" to "SOURCE_NOT_FOUND", "vod_update_source" to "STALE_SELECTION")
            .forEach { (action, code) ->
                lateinit var repository: VodRepository
                var sourceId = ""
                repository = repository { request ->
                    if (request.url.host == "wsyzy.cc") answer(request, rows(suggestion("one")).toString())
                    else {
                        val changed = repository.command(action, mapOf("id" to sourceId, "url" to "https://generic.example/api"))
                        assertTrue(changed.toString(), changed.getBoolean("ok"))
                        answer(request, details(request).toString())
                    }
                }
                sourceId = add(repository)
                val result = repository.search("影片", 1, VodRequest())
                assertFalse(result.getBoolean("ok"))
                val search = result.getJSONObject("search")
                assertEquals(0, search.getJSONArray("items").length()); assertEquals(1, search.getInt("errorCount"))
                assertEquals(code, search.getJSONArray("sources").getJSONObject(0).getString("code"))
            }
    }

    @Test fun searchedWorkKeepsExistingEpisodeIdentityNavigationAndBoundedHlsPlaybackProbe() {
        val requests = mutableListOf<Request>()
        val detailVersions = AtomicInteger()
        val repository = repository { request ->
            requests.add(request)
            when (request.url.host) {
                "wsyzy.cc" -> answer(request, rows(suggestion("series:001", "示例剧集")).put("total", 1).toString())
                "api.wsyzy.net" -> {
                    val version = detailVersions.incrementAndGet()
                    val playlist = (1..3).joinToString("#") { "第${it}集\$https://cdn.example/$it/index.m3u8?token=version$version" }
                    answer(request, rows(JSONObject().put("vod_id", "series:001").put("vod_name", "示例剧集")
                        .put("vod_year", 2026).put("type_name", "剧集").put("vod_pic", "/original.jpg")
                        .put("vod_play_from", "主线").put("vod_play_url", playlist)).toString())
                }
                else -> if (request.url.encodedPath.endsWith(".m3u8")) answer(request,
                    "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n", mime = "application/vnd.apple.mpegurl")
                else answer(request, ByteArray(65_536).apply { this[0] = 0x47; this[188] = 0x47 }, mime = "video/mp2t")
            }
        }
        val sourceId = add(repository)
        val work = repository.catalog(sourceId, "", "示例", 1, VodRequest()).getJSONArray("items").getJSONObject(0)
        val detail = repository.detail(work.getString("sourceId"), work.getString("vodId"), VodRequest())
        assertEquals(work.getString("id"), detail.getString("id"))
        assertFalse(detail.toString().contains("cdn.example"))
        val line = detail.getJSONArray("lines").getJSONObject(0)
        val episodes = line.getJSONArray("episodes")
        assertEquals(3, episodes.length())
        fun reference(index: Int) = JSONObject().put("sourceId", sourceId).put("vodId", work.getString("vodId"))
            .put("revision", detail.getInt("revision")).put("epoch", detail.getInt("epoch"))
            .put("lineId", line.getString("id")).put("episodeKey", episodes.getJSONObject(index).getString("key"))
        val specs = (0..2).map { index ->
            val spec = repository.resolve(reference(index), VodRequest(), sample = true, sampleLimit = 65_536)
            assertEquals("application/x-mpegURL", spec.getString("mimeType"))
            assertEquals("第${index + 1}集", spec.getString("episodeLabel")); assertTrue(spec.getBoolean("stable"))
            assertEquals(index > 0, spec.has("previousEpisode")); assertEquals(index < 2, spec.has("nextEpisode"))
            assertEquals(65_536, spec.getJSONObject("probe").getInt("sampleBytes"))
            assertEquals(3, spec.getJSONArray("episodeItems").length())
            if (index > 0) assertEquals(episodes.getJSONObject(index - 1).getString("key"),
                spec.getJSONObject("previousEpisode").getString("episodeKey"))
            if (index < 2) assertEquals(episodes.getJSONObject(index + 1).getString("key"),
                spec.getJSONObject("nextEpisode").getString("episodeKey"))
            assertFalse(spec.getJSONObject("vodRef").toString().contains("version"))
            spec
        }
        val refreshed = repository.resolve(reference(0), VodRequest())
        assertEquals(specs[0].getString("id"), refreshed.getString("id"))
        assertNotEquals(specs[0].getString("url"), refreshed.getString("url"))
        val segments = requests.filter { it.url.encodedPath.endsWith(".ts") }
        assertEquals(3, segments.size)
        segments.forEach { assertEquals("bytes=0-65535", it.header("Range")) }
        assertEquals(1, requests.count { it.url.host == "wsyzy.cc" })
    }

    companion object {
        private const val ENDPOINT = "https://api.wsyzy.net/api.php/provide/vod"
    }
}
