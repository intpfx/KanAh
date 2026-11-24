package com.live.tv.media

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodAggregateSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(timeout: Long = 20_000, answer: (okhttp3.Interceptor.Chain) -> Response) = VodRepository(
        VodSourceStore(File(temporary.newFolder(), "vod.json"), { it }, { it }),
        OkHttpClient.Builder().addInterceptor(answer).build(), timeout)
    private fun add(repo: VodRepository, name: String, extra: Map<String, String> = emptyMap()): String = repo.command(
        "vod_add_source", mapOf("name" to name, "url" to "https://$name.example/api") + extra).getString("id")
    private fun response(request: Request, pageCount: Int = 2, duplicates: Boolean = false): Response {
        val item = JSONObject().put("vod_id", "same:001").put("vod_name", "同名作品").put("vod_pic", "/poster.jpg")
        val rows = JSONArray().put(item)
        if (duplicates) rows.put(JSONObject(item.toString()))
        val body = JSONObject().put("pagecount", pageCount).put("list", rows)
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }
    private fun search(repo: VodRepository, request: VodRequest = VodRequest(), page: Int = 1) = repo.search("中文 影片", page, request)
    private fun expect(code: String, block: () -> Any?) {
        try { block(); fail("Expected $code") } catch (error: VodException) { assertEquals(code, error.code) }
    }

    @Test fun preferredSourcesRankAheadOfPreviouslyConfiguredSources() {
        val repo = repository { chain -> response(chain.request()) }
        val other = add(repo, "other")
        val modu = add(repo, "魔都资源")
        val threeSixty = add(repo, "360资源")
        val xigua = add(repo, "西瓜资源站")
        val expected = listOf(xigua, threeSixty, modu, other)
        val configured = repo.state().getJSONArray("sources")
        assertEquals(expected, (0 until configured.length()).map { configured.getJSONObject(it).getString("id") })
        val matches = search(repo).getJSONObject("search").getJSONArray("items")
        assertEquals(expected, (0 until matches.length()).map { matches.getJSONObject(it).getString("sourceId") })
    }

    @Test fun streamingSearchPublishesAvailableSourceBeforeEarlierSlowSource() {
        val release = CountDownLatch(1)
        val early = CountDownLatch(1)
        val repo = repository { chain ->
            if (chain.request().url.host == "slow.example") assertTrue(release.await(5, TimeUnit.SECONDS))
            response(chain.request(), duplicates = true)
        }
        val slow = add(repo, "slow")
        val fast = add(repo, "fast")
        val batches = CopyOnWriteArrayList<String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val final = executor.submit<JSONObject> {
                repo.search("片名", 1, VodRequest()) { rows ->
                    assertEquals(1, rows.size)
                    batches.add(rows.single().getString("sourceId"))
                    early.countDown()
                }
            }
            assertTrue(early.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(fast), batches.toList())
            assertFalse(final.isDone)
            release.countDown()
            val items = final.get(5, TimeUnit.SECONDS).getJSONObject("search").getJSONArray("items")
            assertEquals(listOf(fast, slow), batches.toList())
            assertEquals(listOf(slow, fast), (0 until items.length()).map { items.getJSONObject(it).getString("sourceId") })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun xiguaSearchMatchesChineseTitlesEnteredWithSpaces() {
        val queries = CopyOnWriteArrayList<Pair<String, String?>>()
        val repo = repository { chain ->
            queries.add(chain.request().url.host to chain.request().url.queryParameter("wd"))
            response(chain.request())
        }
        add(repo, "xigua", mapOf("url" to "https://xgzy8.tv/api.php/provide/vod/from/xiguam3u8/at/json/"))
        add(repo, "ordinary")
        assertTrue(repo.search("现在就出发　第四季", 1, VodRequest()).getBoolean("ok"))
        assertTrue(repo.search("The Office", 1, VodRequest()).getBoolean("ok"))
        assertTrue(queries.contains("xgzy8.tv" to "现在就出发第四季"))
        assertTrue(queries.contains("ordinary.example" to "现在就出发　第四季"))
        assertTrue(queries.contains("xgzy8.tv" to "The Office"))
    }

    @Test fun posterWallBrowseUsesEveryEligibleSourceAndAdvancesWithoutSearchOrCategory() {
        val requests = CopyOnWriteArrayList<Request>()
        val repo = repository { chain -> requests.add(chain.request()); response(chain.request(), 3) }
        val one = add(repo, "one"); val two = add(repo, "two")
        add(repo, "disabled", mapOf("searchable" to "0"))
        add(repo, "parser", mapOf("playUrl" to "https://parser.example/?url="))
        val first = repo.browse(1, VodRequest()).getJSONObject("browse")
        assertEquals("", first.getString("query")); assertEquals(1, first.getInt("page"))
        assertEquals(2, first.getInt("successCount")); assertTrue(first.getBoolean("hasMore"))
        assertEquals(one, first.getJSONArray("items").getJSONObject(0).getString("sourceId"))
        assertEquals(two, first.getJSONArray("items").getJSONObject(1).getString("sourceId"))
        val next = repo.browse(2, VodRequest()).getJSONObject("browse")
        assertEquals(2, next.getInt("page")); assertEquals(4, requests.size)
        requests.forEach { assertNull(it.url.queryParameter("wd")); assertNull(it.url.queryParameter("t")) }
        requests.drop(2).forEach { assertEquals("2", it.url.queryParameter("pg")); assertEquals("detail", it.url.queryParameter("ac")) }
        assertFalse(repo.browse(10_000, VodRequest()).getJSONObject("browse").getBoolean("hasMore"))
        val count = requests.size
        listOf(0, 10_001).forEach { expect("INVALID_QUERY") { repo.browse(it, VodRequest()) } }
        assertEquals(count, requests.size)
    }

    @Test fun posterWallPartialFailuresRemainVisibleAndAllFailuresRemainRetryable() {
        val repo = repository { chain ->
            if (chain.request().url.host == "failed.example") throw IllegalStateException("synthetic-secret")
            response(chain.request(), 1)
        }
        val good = add(repo, "good"); add(repo, "failed")
        val partial = repo.browse(1, VodRequest())
        assertTrue(partial.getBoolean("ok")); assertFalse(partial.has("search"))
        assertEquals(1, partial.getJSONObject("browse").getInt("errorCount"))
        assertEquals(good, partial.getJSONObject("browse").getJSONArray("items").getJSONObject(0).getString("sourceId"))
        assertTrue(repo.command("vod_remove_source", mapOf("id" to good)).getBoolean("ok"))
        val failed = repo.browse(1, VodRequest())
        assertFalse(failed.getBoolean("ok")); assertEquals("BROWSE_FAILED", failed.getString("code"))
        assertEquals(0, failed.getJSONObject("browse").getJSONArray("items").length())
        assertFalse(failed.toString().contains("synthetic-secret"))
        expect("CANCELLED") { repo.browse(1, VodRequest().apply { cancel() }) }
    }

    @Test fun fanoutRunsFourInParallelButNeverStartsAFifthUntilASlotIsFree() {
        val firstFour = CountDownLatch(4)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val calls = AtomicInteger()
        val repo = repository { chain ->
            calls.incrementAndGet()
            val count = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, count) }
            firstFour.countDown()
            try {
                assertTrue(release.await(4, TimeUnit.SECONDS))
                response(chain.request())
            } finally { active.decrementAndGet() }
        }
        val ids = (0 until 6).map { add(repo, "source$it") }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> { search(repo) }
            assertTrue(firstFour.await(3, TimeUnit.SECONDS))
            assertEquals(4, calls.get()); assertEquals(4, maxActive.get())
            release.countDown()
            val result = future.get(4, TimeUnit.SECONDS).getJSONObject("search")
            assertEquals(6, result.getInt("successCount")); assertEquals(6, calls.get())
            assertTrue(maxActive.get() <= 4)
            val items = result.getJSONArray("items")
            ids.forEachIndexed { i, id -> assertEquals(id, items.getJSONObject(i).getString("sourceId")) }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun partialFailureIsSafeAndCrossSourceNamesRetainDistinctIdentityWithSourceOrder() {
        val repo = repository { chain ->
            if (chain.request().url.host == "failed.example") throw IllegalStateException("https://private.example/synthetic-secret")
            response(chain.request(), if (chain.request().url.host == "one.example") 1 else 4, duplicates = true)
        }
        val one = add(repo, "one"); add(repo, "failed"); val two = add(repo, "two")
        val result = search(repo)
        assertTrue(result.getBoolean("ok")); assertFalse(result.toString().contains("synthetic-secret"))
        val search = result.getJSONObject("search")
        assertEquals(2, search.getInt("successCount")); assertEquals(1, search.getInt("errorCount"))
        assertTrue(search.getBoolean("hasMore"))
        val items = search.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals(one, items.getJSONObject(0).getString("sourceId")); assertEquals(two, items.getJSONObject(1).getString("sourceId"))
        assertNotEquals(items.getJSONObject(0).getString("id"), items.getJSONObject(1).getString("id"))
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            assertEquals("same:001", item.getString("vodId")); assertEquals(1, item.getInt("revision")); assertEquals(1, item.getInt("epoch"))
            assertEquals(if (i == 0) "one" else "two", item.getString("sourceName"))
        }
        val states = search.getJSONArray("sources")
        assertFalse(states.getJSONObject(0).getBoolean("hasMore"))
        assertEquals("error", states.getJSONObject(1).getString("status"))
        assertEquals("NETWORK_ERROR", states.getJSONObject(1).getString("code"))
        assertTrue(states.getJSONObject(2).getBoolean("hasMore"))
    }

    @Test fun allFailuresReturnRetryableSummaryInsteadOfEmptySuccessfulCatalog() {
        val repo = repository { throw IllegalStateException("synthetic-secret") }
        add(repo, "one"); add(repo, "two")
        val result = search(repo)
        assertFalse(result.getBoolean("ok")); assertEquals("SEARCH_FAILED", result.getString("code"))
        val search = result.getJSONObject("search")
        assertEquals(0, search.getInt("successCount")); assertEquals(2, search.getInt("errorCount"))
        assertEquals(0, search.getJSONArray("items").length()); assertFalse(search.getBoolean("hasMore"))
        assertFalse(result.toString().contains("synthetic-secret"))
    }

    @Test fun unsupportedAndDisabledSourcesNeverReceiveSearchAndNoEligibleSourceIsAnError() {
        val requests = CopyOnWriteArrayList<Request>()
        val repo = repository { chain -> requests.add(chain.request()); response(chain.request()) }
        add(repo, "disabled", mapOf("searchable" to "0"))
        add(repo, "parser", mapOf("playUrl" to "https://parser.example/?url="))
        expect("NO_SEARCHABLE_SOURCES") { search(repo) }
        assertTrue(requests.isEmpty())
        val direct = add(repo, "direct")
        assertEquals(direct, search(repo).getJSONObject("search").getJSONArray("items").getJSONObject(0).getString("sourceId"))
        assertEquals(1, requests.size); assertEquals("direct.example", requests.single().url.host)
    }

    @Test fun changedAndRemovedSourcesAreRejectedAgainAfterAnotherSourceCompletes() {
        val earlyFinished = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val repo = repository { chain ->
            if (chain.request().url.host == "slow.example") {
                assertTrue(earlyFinished.await(3, TimeUnit.SECONDS))
                assertTrue(releaseSlow.await(3, TimeUnit.SECONDS))
            }
            response(chain.request()).also { if (chain.request().url.host != "slow.example") earlyFinished.countDown() }
        }
        val changed = add(repo, "changed"); val removed = add(repo, "removed"); add(repo, "slow")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> { search(repo) }
            assertTrue(earlyFinished.await(3, TimeUnit.SECONDS))
            assertTrue(repo.command("vod_update_source", mapOf("id" to changed, "url" to "https://mirror.example/api")).getBoolean("ok"))
            assertTrue(repo.command("vod_remove_source", mapOf("id" to removed)).getBoolean("ok"))
            releaseSlow.countDown()
            val result = future.get(4, TimeUnit.SECONDS).getJSONObject("search")
            assertEquals(1, result.getJSONArray("items").length())
            val states = result.getJSONArray("sources")
            assertEquals("STALE_SELECTION", states.getJSONObject(0).getString("code"))
            assertEquals("SOURCE_NOT_FOUND", states.getJSONObject(1).getString("code"))
            assertEquals("slow", result.getJSONArray("items").getJSONObject(0).getString("sourceName"))
        } finally { releaseSlow.countDown(); executor.shutdownNow() }
    }

    @Test fun cancellationClosesEveryAttachedSourceCallAndNeverStartsQueuedSources() {
        val started = CountDownLatch(4)
        val calls = CopyOnWriteArrayList<Call>()
        val repo = repository { chain ->
            calls.add(chain.call()); started.countDown()
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw java.io.IOException("synthetic-secret")
        }
        (0 until 6).forEach { add(repo, "source$it") }
        val request = VodRequest()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> { try { search(repo, request); "unexpected" } catch (error: VodException) { error.code } }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            request.cancel()
            assertEquals("CANCELLED", future.get(2, TimeUnit.SECONDS))
            assertEquals(4, calls.size); assertTrue(calls.all { it.isCanceled() })
        } finally { request.cancel(); executor.shutdownNow() }
    }

    @Test fun oneDeadlineKeepsFastResultsAndCancelsSlowCallsRatherThanWaitingPerSource() {
        val slowCalls = CopyOnWriteArrayList<Call>()
        val repo = repository(700) { chain ->
            if (chain.request().url.host == "fast.example") response(chain.request()) else {
                slowCalls.add(chain.call())
                while (!chain.call().isCanceled()) Thread.sleep(5)
                throw java.io.IOException("synthetic-secret")
            }
        }
        add(repo, "fast"); (0 until 6).forEach { add(repo, "slow$it") }
        val start = System.nanoTime()
        val result = search(repo)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
        assertTrue(result.getBoolean("ok"))
        val search = result.getJSONObject("search")
        assertEquals(1, search.getInt("successCount")); assertEquals(6, search.getInt("errorCount"))
        assertEquals("fast", search.getJSONArray("items").getJSONObject(0).getString("sourceName"))
        for (i in 1 until 7) assertEquals("SEARCH_TIMEOUT", search.getJSONArray("sources").getJSONObject(i).getString("code"))
        assertTrue(slowCalls.isNotEmpty()); assertTrue(slowCalls.all { it.isCanceled() })
    }

    @Test fun pageAndQueryAreAppliedToEverySourceAndPageCeilingCannotOfferMore() {
        val requests = CopyOnWriteArrayList<Request>()
        val repo = repository { chain -> requests.add(chain.request()); response(chain.request(), 20_000) }
        add(repo, "one"); add(repo, "two")
        val result = search(repo, page = 10_000).getJSONObject("search")
        assertEquals(10_000, result.getInt("page")); assertFalse(result.getBoolean("hasMore"))
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("中文 影片", request.url.queryParameter("wd")); assertEquals("10000", request.url.queryParameter("pg"))
            assertNull(request.url.queryParameter("t"))
        }
        listOf(0, 10_001).forEach { expect("INVALID_QUERY") { search(repo, page = it) } }
        expect("INVALID_QUERY") { repo.search("  ", 1, VodRequest()) }
        expect("INVALID_QUERY") { repo.search("x".repeat(201), 1, VodRequest()) }
        assertEquals(2, requests.size)
    }
}
