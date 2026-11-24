package com.live.tv.media

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Starts on the live screen and survives navigation; the library joins the same first-page work. */
object LibraryWarmup {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vod-library-warmup").apply { isDaemon = true }
    }
    @Volatile private var loader: LibraryWarmupLoader? = null

    @JvmStatic fun start(context: Context) {
        loader(context).start()
    }

    internal fun landing(sources: JSONObject): LibraryLanding? = loader?.landing(sources)

    internal fun firstPage(context: Context, sources: JSONObject, request: VodRequest): JSONObject? =
        loader(context).firstPage(sources, request)

    private fun loader(context: Context): LibraryWarmupLoader = loader ?: synchronized(this) {
        val app = context.applicationContext
        // Construction on the UI thread is cheap; repository, cache and image setup run on worker.
        val repository by lazy { VodRepository.get(app) }
        val posters by lazy { LibraryPosterImages(app) }
        loader ?: LibraryWarmupLoader(worker, { repository.state() }, { VodCacheManager.get(app).status() },
            { request -> repository.browse(1, request) }, { works, identity -> posters.warmFirstPage(works, identity) }).also { loader = it }
    }
}

/** Shared background request, with independently cancellable foreground waiters. */
internal class LibraryWarmupLoader(
    private val executor: Executor,
    private val sources: () -> JSONObject,
    private val caches: () -> JSONObject,
    private val browse: (VodRequest) -> JSONObject,
    private val warmPosters: (List<LibraryWork>, String) -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Outcome(val identity: String, val page: String)
    private data class ReadyLanding(val identity: String, val sources: String, val caches: String, val page: String)
    private class Job {
        @Volatile var identity: String? = null
        lateinit var future: FutureTask<Outcome?>
    }
    private val pages = LibraryFirstPageCache(now)
    private var pending: Job? = null
    @Volatile private var readyLanding: ReadyLanding? = null

    /** A first-frame handoff. It never joins a task or validates files on the UI thread. */
    fun landing(state: JSONObject): LibraryLanding? {
        val identity = LibraryFirstPageCache.identity(state)
        val ready = readyLanding?.takeIf { it.identity == identity } ?: return null
        if (identity != LibraryFirstPageCache.identity(sources())) return null
        return LibraryLanding(JSONObject(ready.sources), JSONObject(ready.caches), JSONObject(ready.page))
    }

    @Synchronized fun start(): Future<*> = task().future

    @Synchronized private fun task(): Job {
        pending?.takeUnless { it.future.isDone }?.let { return it }
        return Job().also { job ->
            job.future = FutureTask { warm(job) }
            pending = job
            executor.execute(job.future)
        }
    }

    fun firstPage(state: JSONObject, request: VodRequest): JSONObject? {
        request.check()
        if (LibraryFirstPageCache.identity(state) != LibraryFirstPageCache.identity(sources())) return null
        val work = task()
        // A stale but usable landing can render immediately while a throttled refresh runs.
        pages.firstPage(state)?.let { return it }
        while (true) {
            request.check()
            // A newly configured source must not wait for a request against the previous source.
            if (work.identity?.let { it != LibraryFirstPageCache.identity(state) } == true) return null
            try {
                val result = work.future.get(50, TimeUnit.MILLISECONDS)
                request.check()
                if (LibraryFirstPageCache.identity(state) != LibraryFirstPageCache.identity(sources())) return null
                return pages.firstPage(state) ?: result?.takeIf {
                    it.identity == LibraryFirstPageCache.identity(state)
                }?.let { JSONObject(it.page) }
            } catch (_: TimeoutException) { /* Navigation cancellation only stops this waiter. */ }
        }
    }

    private fun warm(job: Job): Outcome? = try {
        val state = sources()
        job.identity = LibraryFirstPageCache.identity(state)
        val ticket = pages.begin(state)
        if (ticket == null) null else {
            val model = VodLibraryModel().apply { updateSources(state); updateCaches(caches()) }
            pages.firstPage(state)?.let { model.applyPage(it, "", 1) }
            warmPosters(model.works("", false).take(PosterImageStore.MAX_PREFETCH_PER_CLIENT), ticket.identity)
            if (model.sourceError.isNotEmpty() || model.sources.none(LibrarySource::searchable)) null else {
                // The repository owns its fan-out deadline and returns healthy sources when others
                // time out. A matching outer deadline would discard that usable partial directory.
                val request = VodRequest()
                try {
                    val result = browse(request)
                    request.check()
                    // Validate the directory before replacing either ready snapshot.
                    model.resetOnline()
                    model.applyPage(result, "", 1)
                    val current = sources()
                    if (pages.complete(ticket, current, result)) {
                        val cacheState = caches()
                        model.updateCaches(cacheState)
                        readyLanding = ReadyLanding(ticket.identity, state.toString(), cacheState.toString(), result.toString())
                        warmPosters(model.works("", false).take(PosterImageStore.MAX_PREFETCH_PER_CLIENT), ticket.identity)
                    }
                    if (ticket.identity == LibraryFirstPageCache.identity(current)) Outcome(ticket.identity, result.toString()) else null
                } finally { request.cancel() }
            }
        }
    } catch (_: Exception) { null } // Optional warming must not interrupt live playback.
}

/** Retains a successful landing snapshot. TTL throttles refreshes, never expires the ready UI. */
internal class LibraryFirstPageCache(private val now: () -> Long = SystemClock::elapsedRealtime) {
    internal data class Ticket(val identity: String, val startedAt: Long)
    private var attempt: Ticket? = null
    private var result: String? = null

    @Synchronized fun begin(sources: JSONObject): Ticket? {
        val identity = identity(sources)
        val previous = attempt
        if (previous != null && previous.identity == identity && now() - previous.startedAt in 0 until TTL_MILLIS) return null
        if (previous?.identity != identity) result = null
        return Ticket(identity, now()).also { attempt = it }
    }

    @Synchronized fun complete(ticket: Ticket, currentSources: JSONObject, page: JSONObject): Boolean {
        if (attempt !== ticket) return false
        if (ticket.identity != identity(currentSources)) { result = null; attempt = null; return false }
        val batch = page.optJSONObject("browse") ?: return false
        if (!page.optBoolean("ok") || batch.optInt("page") != 1) return false
        val value = page.toString()
        if (value.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) return false
        result = value
        return true
    }

    @Synchronized fun firstPage(sources: JSONObject): JSONObject? {
        // A reader may hold an older source snapshot. It must never invalidate a newer writer.
        if (attempt?.identity != identity(sources)) return null
        return result?.let(::JSONObject)
    }

    companion object {
        const val TTL_MILLIS = 120_000L
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        fun identity(sources: JSONObject): String {
            val input = sources.optJSONArray("sources") ?: JSONArray()
            val rows = (0 until input.length()).map { index ->
                val row = input.optJSONObject(index) ?: JSONObject()
                JSONArray(listOf(row.optString("id"), row.optInt("revision"), row.optInt("epoch"),
                    row.optBoolean("searchable", true), row.optBoolean("requiresParser"), row.optString("name")))
            }
            return JSONArray(listOf(sources.optString("errorCode"), JSONArray(rows))).toString()
        }
    }
}
