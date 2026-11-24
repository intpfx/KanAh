package com.live.tv.media

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryWarmupLoadingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val workers = mutableListOf<ExecutorService>()
    private val releases = mutableListOf<CountDownLatch>()
    private val clients = mutableListOf<OkHttpClient>()

    @After fun releaseWorkers() {
        releases.forEach { it.countDown() }
        workers.forEach { it.shutdownNow() }
        workers.forEach { it.awaitTermination(2, TimeUnit.SECONDS) }
        clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
    }

    private fun worker(name: String): ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, name).apply { isDaemon = true }
    }.also { workers.add(it) }

    private fun releaseGate() = CountDownLatch(1).also { releases.add(it) }
    private fun await(latch: CountDownLatch, description: String) =
        assertTrue(description, latch.await(5, TimeUnit.SECONDS))

    private fun assertWaiting(future: Future<*>) {
        try {
            future.get(100, TimeUnit.MILLISECONDS)
            fail("library entry must wait for the shared warm request")
        } catch (_: TimeoutException) { /* The browse fixture remains deliberately blocked. */ }
    }

    private fun sources(revision: Int = 1) = JSONObject().put("sources", JSONArray().put(
        JSONObject().put("id", "source").put("name", "Fixture").put("revision", revision)
            .put("epoch", 1).put("searchable", true).put("requiresParser", false)))

    private fun emptyCaches() = JSONObject().put("caches", JSONArray())

    private fun page(title: String, revision: Int = 1, sourceId: String = "source", epoch: Int = 1): JSONObject {
        val item = JSONObject().put("sourceId", sourceId).put("vodId", "work-$revision")
            .put("revision", revision).put("epoch", epoch).put("title", title)
            .put("poster", "https://images.example/poster-$revision.jpg")
        val status = JSONObject().put("sourceId", sourceId).put("sourceName", "Fixture")
            .put("revision", revision).put("epoch", epoch).put("status", "success").put("hasMore", false)
        return JSONObject().put("ok", true).put("browse", JSONObject().put("page", 1)
            .put("items", JSONArray().put(item)).put("sources", JSONArray().put(status)))
    }

    private fun title(page: JSONObject) = page.getJSONObject("browse").getJSONArray("items")
        .getJSONObject(0).getString("title")

    private fun repository(timeoutMillis: Long = 5_000, respond: (Request) -> Response): VodRepository {
        val seal: (String) -> String = { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        val open: (String) -> String = { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
        val store = VodSourceStore(File(temporary.newFolder(), "vod.json"), seal, open)
        val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build().also { clients.add(it) }
        return VodRepository(store, client, searchTimeoutMillis = timeoutMillis)
    }

    private fun addSource(repository: VodRepository, name: String, host: String): String {
        val result = repository.command("vod_add_source", mapOf("name" to name, "url" to "https://$host/vod"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        return result.getString("id")
    }

    private fun answer(request: Request, title: String = "Ready"): Response {
        val body = JSONObject().put("code", 1).put("page", request.url.queryParameter("pg")?.toInt() ?: 1)
            .put("pagecount", 3).put("limit", 1).put("list", JSONArray().put(
                JSONObject().put("vod_id", "ready").put("vod_name", title)
                    .put("vod_pic", "https://images.example/ready.jpg").put("vod_year", 2026)
                    .put("type_name", "电影").put("vod_content", "Fixture overview")))
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture").code(200)
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }

    @Test fun liveWarmupAndEarlyLibraryEntryShareOneBrowse() {
        val entered = CountDownLatch(1); val release = releaseGate(); val calls = AtomicInteger()
        val posters = ConcurrentLinkedQueue<List<LibraryWork>>()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, { emptyCaches() }, {
            calls.incrementAndGet(); entered.countDown(); await(release, "release the first browse")
            page("Shared landing")
        }, { works, _ -> posters.add(works) })
        val warming = loader.start()
        await(entered, "live playback must begin the browse before library entry")
        assertSame("repeated live resumes coalesce while the browse is pending", warming, loader.start())
        val waiting = worker("library-entry").submit<JSONObject?> { loader.firstPage(sources(), VodRequest()) }
        assertWaiting(waiting)

        release.countDown()
        val result = waiting.get(5, TimeUnit.SECONDS)
        warming.get(5, TimeUnit.SECONDS)
        assertNotNull(result)
        assertEquals("Shared landing", title(result!!))
        assertEquals("the entry joins the in-flight browse", 1, calls.get())
        assertEquals(listOf("Shared landing"), posters.last().map { it.title })
    }

    @Test fun firstLibraryEntryStartsWarmupEvenBeforeTheLiveScreenDoes() {
        val entered = CountDownLatch(1); val release = releaseGate(); val calls = AtomicInteger()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, { emptyCaches() }, {
            calls.incrementAndGet(); entered.countDown(); await(release, "release the entry-triggered browse")
            page("Early entry")
        }, { _, _ -> })
        val waiting = worker("library-entry").submit<JSONObject?> { loader.firstPage(sources(), VodRequest()) }
        await(entered, "library entry must start the shared work itself")
        val warming = loader.start()
        assertWaiting(waiting)

        release.countDown()
        assertEquals("Early entry", title(waiting.get(5, TimeUnit.SECONDS)!!))
        warming.get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
    }

    @Test fun cancellingLibraryEntryKeepsTheSharedBrowseAndPostersRunning() {
        val entered = CountDownLatch(1); val release = releaseGate(); val calls = AtomicInteger()
        val warmRequest = AtomicReference<VodRequest>()
        val posters = ConcurrentLinkedQueue<List<LibraryWork>>()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, { emptyCaches() }, { request ->
            warmRequest.set(request); calls.incrementAndGet(); entered.countDown()
            await(release, "release the shared browse after navigation away")
            request.check(); page("Ready on return")
        }, { works, _ -> posters.add(works) })
        val warming = loader.start()
        await(entered, "background browse started")
        val request = VodRequest()
        val waiting = worker("library-entry").submit<JSONObject?> { loader.firstPage(sources(), request) }
        assertWaiting(waiting)
        request.cancel()
        try {
            waiting.get(5, TimeUnit.SECONDS)
            fail("cancelled entry must stop waiting")
        } catch (error: ExecutionException) {
            assertTrue(error.toString(), error.cause is VodException)
            assertEquals("CANCELLED", (error.cause as VodException).code)
        }
        assertFalse("leaving the library must not cancel live preloading", warmRequest.get().isCancelled())
        assertFalse(warming.isDone)

        release.countDown()
        warming.get(5, TimeUnit.SECONDS)
        assertEquals("Ready on return", title(loader.firstPage(sources(), VodRequest())!!))
        assertEquals(listOf("Ready on return"), posters.last().map { it.title })
        assertEquals(1, calls.get())
    }

    @Test fun longLiveSessionReturnsReadyPageWhileRefreshRunsInBackground() {
        val clock = AtomicLong(100); val calls = AtomicInteger()
        val refreshEntered = CountDownLatch(1); val releaseRefresh = releaseGate()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, { emptyCaches() }, {
            if (calls.incrementAndGet() == 1) page("Ready before watching live") else {
                refreshEntered.countDown(); await(releaseRefresh, "release the background refresh after entry returns")
                page("Refreshed landing")
            }
        }, { _, _ -> }, clock::get)
        loader.start().get(5, TimeUnit.SECONDS)
        clock.addAndGet(LibraryFirstPageCache.TTL_MILLIS * 4)

        val entry = worker("library-entry").submit<JSONObject?> { loader.firstPage(sources(), VodRequest()) }
        assertEquals("Ready before watching live", title(entry.get(1, TimeUnit.SECONDS)!!))
        await(refreshEntered, "entry after a long live session must refresh in the background")
        val refreshing = loader.start()
        assertFalse("entry must return before the refresh finishes", refreshing.isDone)
        assertEquals("Ready before watching live", title(loader.firstPage(sources(), VodRequest())!!))
        assertEquals(2, calls.get())

        releaseRefresh.countDown()
        refreshing.get(5, TimeUnit.SECONDS)
        assertEquals("Refreshed landing", title(loader.firstPage(sources(), VodRequest())!!))
        assertEquals("repeated entry must coalesce into one background refresh", 2, calls.get())
    }

    @Test fun sourceChangeRejectsPendingOldPageAndWarmsTheReplacement() {
        val revision = AtomicInteger(1); val calls = AtomicInteger()
        val entered = CountDownLatch(1); val release = releaseGate()
        val posters = ConcurrentLinkedQueue<List<LibraryWork>>()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources(revision.get()) }, { emptyCaches() }, {
            val capturedRevision = revision.get()
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); await(release, "release the old-source browse")
            }
            page("Revision $capturedRevision", capturedRevision)
        }, { works, _ -> posters.add(works) })
        val oldWarmup = loader.start()
        await(entered, "old-source browse started")
        revision.set(2)
        release.countDown()
        oldWarmup.get(5, TimeUnit.SECONDS)

        val current = loader.firstPage(sources(2), VodRequest())
        assertNotNull("current sources must get a replacement landing page", current)
        assertEquals("Revision 2", title(current!!))
        assertEquals(2, calls.get())
        assertFalse("an obsolete response must not warm its posters", posters.flatten().any { it.title == "Revision 1" })
        assertEquals(listOf("Revision 2"), posters.last().map { it.title })
    }

    @Test fun newSourceEntryDoesNotWaitForThePreviousSourceWarmup() {
        val revision = AtomicInteger(1); val calls = AtomicInteger()
        val entered = CountDownLatch(1); val release = releaseGate()
        val posters = ConcurrentLinkedQueue<List<LibraryWork>>()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources(revision.get()) }, { emptyCaches() }, {
            val capturedRevision = revision.get()
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); await(release, "release the old-source browse after entry has returned")
            }
            page("Revision $capturedRevision", capturedRevision)
        }, { works, _ -> posters.add(works) })
        val oldWarmup = loader.start()
        await(entered, "old-source browse started")
        revision.set(2)

        val currentEntry = worker("library-entry").submit<JSONObject?> { loader.firstPage(sources(2), VodRequest()) }
        assertNull("new sources must fall back without waiting for an unrelated pending browse",
            currentEntry.get(1, TimeUnit.SECONDS))
        assertFalse("the old source is still blocked when entry returns", oldWarmup.isDone)
        assertEquals(1, calls.get())

        release.countDown()
        oldWarmup.get(5, TimeUnit.SECONDS)
        assertFalse("the old completion must not publish obsolete posters", posters.flatten().any { it.title == "Revision 1" })
        assertEquals("Revision 2", title(loader.firstPage(sources(2), VodRequest())!!))
        assertEquals(2, calls.get())
    }

    @Test fun sourceChangeBetweenDirectoryCompletionAndPosterDeliveryKeepsOriginalIdentity() {
        val revision = AtomicInteger(1); val cacheReads = AtomicInteger()
        val completed = CountDownLatch(1); val release = releaseGate()
        val posterCalls = ConcurrentLinkedQueue<Pair<List<LibraryWork>, String>>()
        val originalIdentity = LibraryFirstPageCache.identity(sources())
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources(revision.get()) }, {
            if (cacheReads.incrementAndGet() == 2) {
                completed.countDown(); await(release, "release cache reconciliation after the source change")
            }
            emptyCaches()
        }, { page("Revision 1") }, { works, expectedIdentity ->
            posterCalls.add(works to expectedIdentity)
        })
        val warming = loader.start()
        await(completed, "the directory has completed before poster reconciliation")
        revision.set(2)
        release.countDown()
        warming.get(5, TimeUnit.SECONDS)

        val delivered = posterCalls.single { it.first.isNotEmpty() }
        assertEquals("Revision 1", delivered.first.single().title)
        assertEquals("poster delivery must carry the identity that produced the directory", originalIdentity, delivered.second)
        assertTrue("cached and online batches belong to the same original warmup", posterCalls.all { it.second == originalIdentity })
        assertNotEquals(LibraryFirstPageCache.identity(sources(2)), delivered.second)
        assertNull("the original directory must also be rejected after the source change", loader.firstPage(sources(), VodRequest()))
    }

    @Test fun readyPageCannotBeReadThroughAStaleCallerSourceSnapshot() {
        val revision = AtomicInteger(1); val calls = AtomicInteger()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources(revision.get()) }, { emptyCaches() }, {
            calls.incrementAndGet(); val current = revision.get(); page("Revision $current", current)
        }, { _, _ -> })
        val oldState = sources()
        loader.start().get(5, TimeUnit.SECONDS)
        revision.set(2)

        assertNull("the ready fast path must validate currently configured sources", loader.firstPage(oldState, VodRequest()))
        assertEquals("a stale caller does not issue a browse for its old configuration", 1, calls.get())
        assertEquals("Revision 2", title(loader.firstPage(sources(2), VodRequest())!!))
        assertEquals(2, calls.get())
    }

    @Test fun slowSourceTimeoutStillHandsOffTheFastSourceAndWarmsItsPosters() {
        val slowEntered = CountDownLatch(1); val releaseSlow = releaseGate()
        val requests = ConcurrentLinkedQueue<Request>(); val browseCalls = AtomicInteger()
        val repository = repository(timeoutMillis = 1_000) { request ->
            requests.add(request)
            if (request.url.host == "slow.example") {
                slowEntered.countDown(); await(releaseSlow, "release the slow-source fixture")
            }
            answer(request)
        }
        val fastId = addSource(repository, "Fast", "fast.example")
        val slowId = addSource(repository, "Slow", "slow.example")
        val posters = ConcurrentLinkedQueue<List<LibraryWork>>()
        val loader = LibraryWarmupLoader(worker("live-warmup"), repository::state, { emptyCaches() }, { request ->
            browseCalls.incrementAndGet(); repository.browse(1, request)
        }, { works, _ -> posters.add(works) })
        val warming = loader.start()
        await(slowEntered, "the slow source participates in the real repository fan-out")
        warming.get(5, TimeUnit.SECONDS)

        val result = loader.firstPage(repository.state(), VodRequest())
        assertNotNull("a timed-out source must not discard healthy warm results", result)
        assertTrue(result!!.toString(), result.getBoolean("ok"))
        val batch = result.getJSONObject("browse")
        assertEquals(1, batch.getInt("successCount")); assertEquals(1, batch.getInt("errorCount"))
        assertEquals(1, batch.getJSONArray("items").length())
        assertEquals(fastId, batch.getJSONArray("items").getJSONObject(0).getString("sourceId"))
        val statuses = batch.getJSONArray("sources")
        val slow = (0 until statuses.length()).map { statuses.getJSONObject(it) }.single { it.getString("sourceId") == slowId }
        assertEquals("error", slow.getString("status")); assertEquals("SEARCH_TIMEOUT", slow.getString("code"))
        assertEquals(listOf("Ready"), posters.last().map { it.title })
        assertEquals(listOf(fastId), posters.last().map { it.sourceId })
        assertEquals(1, browseCalls.get())
        assertEquals("complete posters do not trigger extra catalog/detail requests", 2, requests.size)
    }

    @Test fun searchAndLaterPagesPreserveInitialWarmHandoffButRetryFetchesFresh() {
        val requests = ConcurrentLinkedQueue<Request>(); val warmCalls = AtomicInteger()
        val repository = repository { request -> requests.add(request); answer(request, "Fresh from repository") }
        val sourceId = addSource(repository, "Fixture", "api.example")
        val data = NativeLibraryData(RuntimeEnvironment.getApplication(), repository) { state, _ ->
            warmCalls.incrementAndGet()
            val source = state.getJSONArray("sources").getJSONObject(0)
            page("Already warmed", source.getInt("revision"), sourceId, source.getInt("epoch"))
        }

        val search = data.catalog("query", 1, VodRequest())
        assertEquals("Fresh from repository", search.getJSONObject("search").getJSONArray("items").getJSONObject(0).getString("title"))
        assertEquals(2, data.catalog("", 2, VodRequest()).getJSONObject("browse").getInt("page"))
        assertEquals("search and pagination must not consume the landing handoff", 0, warmCalls.get())
        assertEquals(2, requests.size)

        assertEquals("Already warmed", title(data.catalog("", 1, VodRequest())))
        assertEquals(1, warmCalls.get()); assertEquals(2, requests.size)
        assertEquals("Fresh from repository", title(data.catalog("", 1, VodRequest())))
        assertEquals("an explicit first-page retry must bypass the warm snapshot", 1, warmCalls.get())
        assertEquals(3, requests.size)
    }

    @Test fun landingWithoutPublishedMetadataReturnsImmediatelyWithoutStartingOrJoiningWork() {
        val cacheReads = AtomicInteger(); val browseCalls = AtomicInteger()
        val cacheEntered = CountDownLatch(1); val releaseCache = releaseGate()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, {
            cacheReads.incrementAndGet(); cacheEntered.countDown()
            await(releaseCache, "release the first cache read after landing has returned")
            emptyCaches()
        }, { browseCalls.incrementAndGet(); page("Eventually ready") }, { _, _ -> })
        val reader = worker("landing-reader")
        assertNull(reader.submit<LibraryLanding?> { loader.landing(sources()) }.get(1, TimeUnit.SECONDS))
        assertEquals(0, cacheReads.get()); assertEquals(0, browseCalls.get())

        val warming = loader.start()
        await(cacheEntered, "initial metadata preparation has started")
        assertNull(reader.submit<LibraryLanding?> { loader.landing(sources()) }.get(1, TimeUnit.SECONDS))
        assertFalse("a missing landing must not wait for pending preparation", warming.isDone)
        assertEquals(1, cacheReads.get()); assertEquals(0, browseCalls.get())
        releaseCache.countDown(); warming.get(5, TimeUnit.SECONDS)
        assertEquals("Eventually ready", title(loader.landing(sources())!!.page))
    }

    @Test fun readyLandingNeverRevalidatesCacheFilesWhileBackgroundRefreshIsBlocked() {
        val clock = AtomicLong(100); val cacheReads = AtomicInteger(); val browseCalls = AtomicInteger()
        val refreshEntered = CountDownLatch(1); val releaseRefresh = releaseGate()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, {
            val read = cacheReads.incrementAndGet()
            if (read == 3) {
                refreshEntered.countDown(); await(releaseRefresh, "release the background cache refresh")
            }
            emptyCaches().put("read", read)
        }, { page("Landing ${browseCalls.incrementAndGet()}") }, { _, _ -> }, clock::get)
        loader.start().get(5, TimeUnit.SECONDS)
        assertEquals(2, cacheReads.get())
        clock.addAndGet(LibraryFirstPageCache.TTL_MILLIS + 1)
        val refreshing = loader.start()
        await(refreshEntered, "refresh is deliberately blocked in cache validation")
        val reader = worker("landing-reader")
        repeat(3) {
            val ready = reader.submit<LibraryLanding?> { loader.landing(sources()) }.get(1, TimeUnit.SECONDS)!!
            assertEquals("Landing 1", title(ready.page))
            assertEquals("publish the reconciled second cache read", 2, ready.caches.getInt("read"))
        }
        assertFalse(refreshing.isDone)
        assertEquals("ready reads must not enter cache validation", 3, cacheReads.get())
        assertEquals("ready reads must not issue or join another browse", 1, browseCalls.get())
        releaseRefresh.countDown(); refreshing.get(5, TimeUnit.SECONDS)
        val updated = loader.landing(sources())!!
        assertEquals("Landing 2", title(updated.page)); assertEquals(4, updated.caches.getInt("read"))
    }

    @Test fun landingDeepCopiesPublishedInputsAndEveryReturnedMetadataTree() {
        val state = sources()
        val reference = JSONObject().put("sourceId", "source").put("vodId", "cached")
            .put("revision", 1).put("epoch", 1).put("lineId", "main").put("episodeKey", "episode-1")
        val cached = emptyCaches().put("caches", JSONArray().put(JSONObject().put("ref", reference)
            .put("resourceTitle", "Cached title").put("episodeLabel", "Episode 1").put("state", "complete")))
        val catalog = page("Online title")
        val loader = LibraryWarmupLoader(worker("live-warmup"), { state }, { cached }, { catalog }, { _, _ -> })
        loader.start().get(5, TimeUnit.SECONDS)
        val first = loader.landing(state)!!
        first.sources.getJSONArray("sources").getJSONObject(0).put("name", "Mutated reader source")
        first.caches.getJSONArray("caches").getJSONObject(0).put("resourceTitle", "Mutated reader cache")
            .getJSONObject("ref").put("episodeKey", "reader-episode")
        first.page.getJSONObject("browse").getJSONArray("items").getJSONObject(0).put("title", "Mutated reader page")
        cached.getJSONArray("caches").getJSONObject(0).put("resourceTitle", "Mutated producer cache")
        reference.put("episodeKey", "producer-episode")
        catalog.getJSONObject("browse").getJSONArray("items").getJSONObject(0).put("title", "Mutated producer page")

        val second = loader.landing(state)!!
        assertNotSame(first, second)
        assertEquals("Fixture", second.sources.getJSONArray("sources").getJSONObject(0).getString("name"))
        val entry = second.caches.getJSONArray("caches").getJSONObject(0)
        assertEquals("Cached title", entry.getString("resourceTitle"))
        assertEquals("episode-1", entry.getJSONObject("ref").getString("episodeKey"))
        assertEquals("Online title", title(second.page))
        assertEquals("returning a mutable copy must not change configured sources", "Fixture",
            state.getJSONArray("sources").getJSONObject(0).getString("name"))
    }

    @Test fun failedNetworkOrInvalidDirectoryRefreshKeepsTheLastPublishedLanding() {
        val clock = AtomicLong(100); val browseCalls = AtomicInteger()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources() }, { emptyCaches() }, {
            when (browseCalls.incrementAndGet()) {
                1 -> page("Last usable landing")
                2 -> throw VodException("SEARCH_FAILED", "fixture network failure")
                3 -> page("Unusable refresh").apply {
                    getJSONObject("browse").getJSONArray("sources").getJSONObject(0).put("status", "error")
                }
                else -> page("Recovered landing")
            }
        }, { _, _ -> }, clock::get)
        loader.start().get(5, TimeUnit.SECONDS)
        repeat(2) {
            clock.addAndGet(LibraryFirstPageCache.TTL_MILLIS + 1)
            loader.start().get(5, TimeUnit.SECONDS)
            assertEquals("a failed refresh must not replace already published metadata", "Last usable landing",
                title(loader.landing(sources())!!.page))
        }
        assertEquals("exercise both a request failure and applyPage rejection", 3, browseCalls.get())
        assertEquals("invalid refresh must not poison the shared first-page cache", "Last usable landing",
            title(loader.firstPage(sources(), VodRequest())!!))
        clock.addAndGet(LibraryFirstPageCache.TTL_MILLIS + 1)
        loader.start().get(5, TimeUnit.SECONDS)
        assertEquals(4, browseCalls.get())
        assertEquals("Recovered landing", title(loader.landing(sources())!!.page))
    }

    @Test fun landingRejectsChangedSourcesAndStaleReadersWithoutStartingAnotherWarmup() {
        val revision = AtomicInteger(1); val cacheReads = AtomicInteger(); val browseCalls = AtomicInteger()
        val loader = LibraryWarmupLoader(worker("live-warmup"), { sources(revision.get()) }, {
            cacheReads.incrementAndGet(); emptyCaches()
        }, {
            browseCalls.incrementAndGet(); val current = revision.get(); page("Revision $current", current)
        }, { _, _ -> })
        val oldState = sources()
        loader.start().get(5, TimeUnit.SECONDS)
        revision.set(2)
        assertNull(loader.landing(oldState))
        assertNull(loader.landing(sources(2)))
        assertEquals(1, browseCalls.get()); assertEquals(2, cacheReads.get())

        loader.start().get(5, TimeUnit.SECONDS)
        assertNull("an old caller cannot read or invalidate the newer snapshot", loader.landing(oldState))
        assertEquals("Revision 2", title(loader.landing(sources(2))!!.page))
        assertEquals(2, browseCalls.get()); assertEquals(4, cacheReads.get())
    }

    @Test fun nativeLandingHitReturnsDuringBackgroundRefreshAndExplicitRetryFetchesFresh() {
        val requests = ConcurrentLinkedQueue<Request>(); val warmCalls = AtomicInteger(); val refreshCalls = AtomicInteger()
        val repository = repository { request -> requests.add(request); answer(request, "Fresh retry") }
        val sourceId = addSource(repository, "Fixture", "api.example")
        val clock = AtomicLong(100); val cacheReads = AtomicInteger(); val browseCalls = AtomicInteger()
        val refreshEntered = CountDownLatch(1); val releaseRefresh = releaseGate()
        val loader = LibraryWarmupLoader(worker("live-warmup"), repository::state, {
            if (cacheReads.incrementAndGet() == 3) {
                refreshEntered.countDown(); await(releaseRefresh, "release NativeLibraryData's background refresh")
            }
            emptyCaches()
        }, {
            browseCalls.incrementAndGet()
            val source = repository.state().getJSONArray("sources").getJSONObject(0)
            page("Ready snapshot", source.getInt("revision"), sourceId, source.getInt("epoch"))
        }, { _, _ -> }, clock::get)
        loader.start().get(5, TimeUnit.SECONDS)
        clock.addAndGet(LibraryFirstPageCache.TTL_MILLIS + 1)
        val data = NativeLibraryData(RuntimeEnvironment.getApplication(), repository,
            warmLanding = loader::landing, refreshWarmup = { refreshCalls.incrementAndGet(); loader.start(); Unit }) { _, _ ->
            warmCalls.incrementAndGet(); fail("landing hit must consume the initial first-page handoff"); null
        }
        val ready = worker("native-landing").submit<LibraryLanding?> { data.landing() }.get(1, TimeUnit.SECONDS)!!
        assertEquals("Ready snapshot", title(ready.page))
        assertEquals(1, refreshCalls.get())
        await(refreshEntered, "a landing hit schedules background refresh")
        val refreshing = loader.start()
        assertFalse("Native landing must not wait for the refresh", refreshing.isDone)
        assertEquals(0, warmCalls.get()); assertTrue(requests.isEmpty())

        val retry = worker("native-retry").submit<JSONObject> { data.catalog("", 1, VodRequest()) }
            .get(5, TimeUnit.SECONDS)
        assertEquals("Fresh retry", title(retry))
        assertEquals("explicit retry must fetch a new repository page", 1, requests.size)
        assertEquals("explicit retry must not join the blocked warm request", 0, warmCalls.get())
        assertFalse(refreshing.isDone)
        releaseRefresh.countDown(); refreshing.get(5, TimeUnit.SECONDS)
        assertEquals(2, browseCalls.get())
    }
}
