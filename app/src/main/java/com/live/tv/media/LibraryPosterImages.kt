package com.live.tv.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal interface LibraryImages {
    fun load(view: ImageView, url: String)
    fun load(view: ImageView, work: LibraryWork) = load(view, work.poster)
    /** Replace this screen's speculative window; visible loads always have priority. */
    fun prefetch(urls: List<String>) = Unit
    fun prefetchWorks(works: List<LibraryWork>) = prefetch(works.map(LibraryWork::poster))
    fun cancel()
    fun close()
}

/** A view/lifecycle scope over the process cache. Closing a screen never discards reusable posters. */
internal class LibraryPosterImages internal constructor(private val store: PosterImageStore) : LibraryImages {
    constructor(context: Context) : this(shared(context.applicationContext))

    private val owner = Any()
    private val pending = IdentityHashMap<ImageView, PosterSubscription>()
    private var closed = false

    override fun load(view: ImageView, url: String) = load(view, store.address(url), null)
    override fun load(view: ImageView, work: LibraryWork) = load(view, store.address(work), work)

    fun loadKeepingPlaceholder(view: ImageView, work: LibraryWork) =
        load(view, store.address(work), work, keepPlaceholder = true)

    private fun load(view: ImageView, address: PosterAddress?, work: LibraryWork?, keepPlaceholder: Boolean = false) {
        val key = address?.key
        if (!closed && key != null && pending[view]?.url == key && pending[view]?.work?.key == work?.key) return
        pending.remove(view)?.let(store::remove)
        if (!keepPlaceholder) view.setImageDrawable(null)
        if (closed) return
        if (address == null) return
        store.cached(address.key)?.let { view.setImageBitmap(it); return }
        val subscription = PosterSubscription(owner, address.key, this, view, work)
        pending[view] = subscription
        store.observe(address, subscription)
    }

    override fun prefetch(urls: List<String>) {
        if (!closed) store.prefetch(owner, urls)
    }
    override fun prefetchWorks(works: List<LibraryWork>) {
        if (!closed) store.prefetchWorks(owner, works)
    }
    /** Keep the landing page ready while live TV is playing; ordinary lookahead never pins images. */
    fun warmFirstPage(works: List<LibraryWork>, expectedIdentity: String? = null) {
        if (!closed) store.warmFirstPage(owner, works, expectedIdentity)
    }

    internal fun deliver(subscription: PosterSubscription, view: ImageView, bitmap: Bitmap?) {
        if (closed || pending[view] !== subscription) return
        pending.remove(view)
        if (bitmap != null) view.setImageBitmap(bitmap)
    }

    override fun cancel() { pending.clear(); store.cancel(owner) }
    override fun close() { closed = true; cancel() }

    companion object {
        @Volatile private var sharedStore: PosterImageStore? = null
        private fun shared(context: Context): PosterImageStore = sharedStore ?: synchronized(this) {
            sharedStore ?: run {
                val client = MediaNetwork.get(context).client(OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS).build())
                val main = Handler(Looper.getMainLooper())
                val fetch: (String, VodRequest) -> Bitmap? = { url, request ->
                    val target = url.toHttpUrlOrNull()
                    if (target == null || !PosterMatch.usableUrl(url)) null else {
                        val response = VodHttp(client).fetch(target, emptyMap(), request, 2 * 1024 * 1024)
                        request.check()
                        if (!PosterMatch.usableUrl(response.url.toString())) null else decode(response.bytes).also { request.check() }
                    }
                }
                val repository = VodRepository.get(context)
                val resolver = LibraryPosterResolver(repository::state,
                    { source, title, request -> repository.catalog(source, "", title, 1, request) }, fetch)
                PosterImageStore(Executors.newFixedThreadPool(2) { runnable ->
                    Thread(runnable, "vod-library-art").apply { isDaemon = true }
                }, { callback -> main.post(callback) }, fetch, resolver = resolver,
                    sourceIdentity = { LibraryFirstPageCache.identity(repository.state()) }).also { sharedStore = it }
            }
        }

        internal fun decode(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384 ||
                bounds.outWidth.toLong() * bounds.outHeight > 64_000_000) return null
            val edge = maxOf(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                // Decode near the target, then scale once. A 960px poster must not fall to 240px.
                while ((edge + inSampleSize * 2 - 1) / (inSampleSize * 2) >= MAX_POSTER_EDGE) inSampleSize *= 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val decodedEdge = maxOf(decoded.width, decoded.height)
            if (decodedEdge <= MAX_POSTER_EDGE && decoded.config == Bitmap.Config.ARGB_8888) return decoded
            val scale = minOf(1.0, MAX_POSTER_EDGE.toDouble() / decodedEdge)
            val width = maxOf(1, (decoded.width * scale).toInt())
            val height = maxOf(1, (decoded.height * scale).toInt())
            // An explicit 8888 destination caps each retained bitmap at 512 * 512 * 4 bytes.
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(decoded, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            return result
        }

        internal const val MAX_POSTER_EDGE = 512
    }
}

/** The application cache holds only weak view/client references, including posted completions. */
internal class PosterSubscription(val owner: Any, @Volatile var url: String, client: LibraryPosterImages, view: ImageView,
    val work: LibraryWork? = null) {
    private val client = WeakReference(client)
    private val view = WeakReference(view)
    fun deliver(bitmap: Bitmap?) { view.get()?.let { client.get()?.deliver(this, it, bitmap) } }
}

internal data class PosterAddress(val key: String, val lookup: PosterLookup? = null)

/** One URL has one queued/running job. At most two fetches and 32 speculative jobs are active. */
internal class PosterImageStore(
    private val executor: Executor,
    private val post: (Runnable) -> Unit,
    private val fetch: (String, VodRequest) -> Bitmap?,
    memoryBytes: Int = 12 * 1024 * 1024,
    private val resolver: PosterResolver? = null,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sourceIdentity: () -> String = { "" }
) {
    private class Job(val address: PosterAddress) {
        val url get() = address.key
        val xiguaOrigin = address.lookup == null && url.toHttpUrlOrNull()?.host == "vod.xiguazyimg.com"
        val request = VodRequest()
        val observers = linkedSetOf<PosterSubscription>()
        val prefetchOwners = mutableMapOf<Any, MutableList<LibraryWork?>>()
        var running = false
        val unused get() = observers.isEmpty() && prefetchOwners.isEmpty()
    }
    private val failed = LruCache<String, Long>(128)
    private val memory = object : LruCache<String, Bitmap>(memoryBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private class LandingBatch(val owner: Any, val identity: String, works: List<LibraryWork>) {
        val token = Any()
        val ranks = works.mapIndexed { index, work -> work.key to index }.toMap()
        val keysByWork = mutableMapOf<String, String>()
    }
    // One replaceable landing batch, at most 24 decoded posters. These are shared bitmap references,
    // not copies; the independent byte budget prevents lookahead from evicting the next entry page.
    private val landing = object : LruCache<String, Bitmap>(LANDING_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var landingBatch: LandingBatch? = null
    private val jobs = linkedMapOf<String, Job>()
    private var active = 0
    private var activeFallback = 0
    private var xiguaOriginActive = false

    @Synchronized fun cached(url: String): Bitmap? {
        invalidateLanding()
        return cachedImage(url)
    }

    private fun cachedImage(url: String): Bitmap? = landing[url] ?: memory[url]

    @Synchronized fun address(url: String): PosterAddress? = key(url)?.takeIf(PosterMatch::usableUrl)
        ?.takeUnless(::recentlyFailed)?.let(::PosterAddress)

    @Synchronized fun address(work: LibraryWork): PosterAddress? {
        invalidateLanding()
        val fallback = fallbackAddress(work)
        if (fallback != null && cachedImage(fallback.key) != null) return fallback
        return address(work.poster) ?: fallback
    }

    private fun fallbackAddress(work: LibraryWork): PosterAddress? = resolver?.prepare(work)
        ?.takeUnless { recentlyFailed(it.key) }?.let { PosterAddress(it.key, it) }

    private fun recentlyFailed(key: String): Boolean {
        val timestamp = failed[key] ?: return false
        if (now() - timestamp in 0 until FAILURE_TTL_MILLIS) return true
        failed.remove(key)
        return false
    }

    @Synchronized fun observe(address: PosterAddress, subscription: PosterSubscription) {
        invalidateLanding()
        cachedImage(address.key)?.let { bitmap -> post(Runnable { subscription.deliver(bitmap) }); return }
        jobs.getOrPut(address.key) { Job(address) }.observers.add(subscription)
        pump()
    }

    @Synchronized fun remove(subscription: PosterSubscription) {
        jobs.values.toList().forEach { job ->
            job.observers.remove(subscription)
            discardUnused(job)
        }
        trimSpeculation()
    }

    @Synchronized fun prefetch(owner: Any, urls: List<String>) {
        invalidateLanding()
        replacePrefetch(owner, urls.take(MAX_PREFETCH_PER_CLIENT * 4).mapNotNull { value -> address(value)?.let { it to null } })
    }

    @Synchronized fun prefetchWorks(owner: Any, works: List<LibraryWork>) {
        invalidateLanding()
        replacePrefetch(owner, works.take(MAX_PREFETCH_PER_CLIENT * 4).mapNotNull { work -> address(work)?.let { it to work } })
    }

    @Synchronized fun warmFirstPage(owner: Any, works: List<LibraryWork>, expectedIdentity: String? = null) {
        invalidateLanding()
        val identity = sourceIdentity()
        if (expectedIdentity != null && expectedIdentity != identity) { invalidateLanding(); return }
        val selected = works.take(MAX_PREFETCH_PER_CLIENT * 4).distinctBy(LibraryWork::key)
            .take(MAX_PREFETCH_PER_CLIENT).map { it.copy(caches = emptyList()) }
        val targets = selected.mapNotNull { work -> address(work)?.let { it to work } }
        // Capture reusable overlap before replacing the old batch, including posters absent from LRU.
        val ready = targets.mapNotNull { (target, work) -> cachedImage(target.key)?.let { Triple(target.key, work, it) } }
        if (identity != sourceIdentity()) { invalidateLanding(); return }
        val batch = LandingBatch(owner, identity, selected)
        for ((target, work) in targets) jobs[target.key]?.let { addPrefetch(it, batch.token, work) }
        releaseLanding()
        if (selected.isEmpty()) return
        landingBatch = batch
        for ((key, work, bitmap) in ready) retainLanding(batch.token, work, key, bitmap)
        replacePrefetch(batch.token, targets)
    }

    private fun replacePrefetch(owner: Any, targets: List<Pair<PosterAddress, LibraryWork?>>) {
        val requested = targets.distinctBy { it.first.key to it.second?.key }.take(MAX_PREFETCH_PER_CLIENT)
        val keys = requested.map { it.first.key }.toSet()
        jobs.values.toList().forEach { job ->
            if (job.url !in keys) job.prefetchOwners.remove(owner)
            else if (owner in job.prefetchOwners) job.prefetchOwners[owner] = requested.filter { it.first.key == job.url }.map { it.second }.toMutableList()
            discardUnused(job)
        }
        trimSpeculation()
        for ((target, work) in requested) {
            if (cachedImage(target.key) != null) continue
            val existing = jobs[target.key]
            if (existing != null) { addPrefetch(existing, owner, work); continue }
            if (!makeRoomForPrefetch(owner)) break
            jobs[target.key] = Job(target).apply { prefetchOwners[owner] = mutableListOf(work) }
        }
        pump()
    }

    @Synchronized fun cancel(owner: Any) {
        if (landingBatch?.owner === owner) releaseLanding()
        jobs.values.toList().forEach { job ->
            job.observers.removeAll { it.owner === owner }
            job.prefetchOwners.remove(owner)
            discardUnused(job)
        }
        trimSpeculation()
    }

    private fun discardUnused(job: Job) {
        if (job.unused) { jobs.remove(job.url); job.request.cancel() }
    }

    private fun addPrefetch(job: Job, owner: Any, work: LibraryWork?) {
        val works = job.prefetchOwners.getOrPut(owner) { mutableListOf() }
        if (works.none { it?.key == work?.key }) works.add(work)
    }

    private fun trimSpeculation() {
        val queued = jobs.values.filter { !it.running && it.observers.isEmpty() }
        queued.sortedByDescending(::warmRank).take(maxOf(0, queued.size - MAX_QUEUED_PREFETCH)).forEach { job ->
            jobs.remove(job.url); job.request.cancel()
        }
    }

    private fun makeRoomForPrefetch(owner: Any): Boolean {
        if (jobs.values.count { !it.running && it.observers.isEmpty() } < MAX_QUEUED_PREFETCH) return true
        if (owner !== landingBatch?.token) return false
        val ordinary = jobs.values.firstOrNull { !it.running && it.observers.isEmpty() && warmRank(it) == Int.MAX_VALUE }
            ?: return false
        jobs.remove(ordinary.url); ordinary.request.cancel()
        return true
    }

    private fun warmRank(job: Job): Int {
        val batch = landingBatch ?: return Int.MAX_VALUE
        return job.prefetchOwners[batch.token]?.mapNotNull { it?.key?.let(batch.ranks::get) }?.minOrNull() ?: Int.MAX_VALUE
    }

    private fun retainLanding(owner: Any, work: LibraryWork?, key: String, bitmap: Bitmap) {
        val batch = landingBatch ?: return
        work ?: return
        if (owner !== batch.token || work.key !in batch.ranks) return
        val previous = batch.keysByWork.put(work.key, key)
        if (previous != null && previous != key && previous !in batch.keysByWork.values) landing.remove(previous)
        landing.put(key, bitmap)
    }

    private fun invalidateLanding() {
        if (landingBatch?.let { it.identity != sourceIdentity() } == true) releaseLanding()
    }

    private fun releaseLanding() {
        val batch = landingBatch ?: return
        landingBatch = null
        landing.evictAll()
        jobs.values.toList().forEach { job ->
            job.prefetchOwners.remove(batch.token)
            discardUnused(job)
        }
    }

    /** Called with this monitor held; workers select visible jobs before any queued speculation. */
    private fun pump() {
        while (active < 2) {
            // Leave a slot free for other poster hosts when the Xigua CDN is timing out.
            val ready = jobs.values.filter { !it.running && (!it.xiguaOrigin || !xiguaOriginActive) }
            val next = ready.firstOrNull { it.observers.isNotEmpty() && it.address.lookup == null }
                ?: ready.firstOrNull { it.observers.isNotEmpty() && it.address.lookup != null && activeFallback == 0 }
                ?: ready.filter { (it.address.lookup == null || activeFallback == 0) && warmRank(it) != Int.MAX_VALUE }
                    .minByOrNull(::warmRank)
                ?: ready.firstOrNull { it.address.lookup == null }
                ?: ready.firstOrNull { activeFallback == 0 } ?: return
            next.running = true; active++
            if (next.address.lookup != null) activeFallback++
            if (next.xiguaOrigin) xiguaOriginActive = true
            executor.execute {
                val request = next.request.bounded(if (next.address.lookup == null) 8000 else 18_000)
                val bitmap = try {
                    request.check()
                    (next.address.lookup?.let { resolver?.resolve(it, request) } ?: if (next.address.lookup == null) fetch(next.url, request) else null)
                        .also { request.check() }
                }
                    catch (_: Exception) { null }
                synchronized(this) {
                    active--
                    if (next.address.lookup != null) activeFallback--
                    if (next.xiguaOrigin) xiguaOriginActive = false
                    invalidateLanding()
                    if (jobs[next.url] === next) {
                        jobs.remove(next.url)
                        if (bitmap != null) {
                            failed.remove(next.url); memory.put(next.url, bitmap)
                            for ((owner, works) in next.prefetchOwners) for (work in works) retainLanding(owner, work, next.url, bitmap)
                        }
                        else if (!request.isCancelled()) failed.put(next.url, now())
                        for (subscription in next.observers) {
                            val fallback = if (bitmap == null && next.address.lookup == null) subscription.work?.let(::fallbackAddress) else null
                            val cachedFallback = fallback?.let { cachedImage(it.key) }
                            if (fallback == null || cachedFallback != null) post(Runnable { subscription.deliver(cachedFallback ?: bitmap) })
                            else {
                                subscription.url = fallback.key
                                jobs.getOrPut(fallback.key) { Job(fallback) }.observers.add(subscription)
                            }
                        }
                        if (bitmap == null && next.address.lookup == null) {
                            for ((owner, works) in next.prefetchOwners) for (work in works.filterNotNull()) {
                                val fallback = fallbackAddress(work) ?: continue
                                val ready = cachedImage(fallback.key)
                                if (ready != null) { retainLanding(owner, work, fallback.key, ready); continue }
                                if (fallback.key !in jobs && !makeRoomForPrefetch(owner)) break
                                addPrefetch(jobs.getOrPut(fallback.key) { Job(fallback) }, owner, work)
                            }
                        }
                    }
                    pump()
                }
            }
        }
    }

    companion object {
        const val MAX_PREFETCH_PER_CLIENT = 24
        const val MAX_QUEUED_PREFETCH = 32
        const val LANDING_MEMORY_BYTES = 24 * 1024 * 1024
        const val FAILURE_TTL_MILLIS = 60_000L
        fun key(value: String): String? {
            if (value.length > 8192) return null
            return value.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }?.toString()
        }
    }
}
