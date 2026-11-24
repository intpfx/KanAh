package com.live.tv.media

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal data class LibraryLanding(val sources: JSONObject, val caches: JSONObject, val page: JSONObject)

/** Only landing() runs on the UI thread; all other methods run on the library worker. */
internal interface LibraryData {
    /** Reads already prepared metadata only. Never waits for cache files or network requests. */
    fun landing(): LibraryLanding? = null
    fun sources(): JSONObject
    fun caches(): JSONObject
    fun catalog(query: String, page: Int, request: VodRequest): JSONObject
    fun detail(work: LibraryWork, request: VodRequest): JSONObject
    fun validate(reference: JSONObject, request: VodRequest)
    fun completed(reference: JSONObject, request: VodRequest): JSONObject?
    fun addSource(form: Map<String, String>): JSONObject
    fun cache(reference: JSONObject, request: VodRequest): JSONObject
    fun removeCache(reference: JSONObject, request: VodRequest): JSONObject
}

internal class NativeLibraryData(context: Context,
    private val repository: VodRepository = VodRepository.get(context),
    private val warmLanding: (JSONObject) -> LibraryLanding? = LibraryWarmup::landing,
    private val refreshWarmup: () -> Unit = { LibraryWarmup.start(context.applicationContext) },
    private val warmFirstPage: (JSONObject, VodRequest) -> JSONObject? = { sources, request ->
        LibraryWarmup.firstPage(context.applicationContext, sources, request)
    }
) : LibraryData {
    private val downloads by lazy { VodCacheManager.get(context.applicationContext) }
    private val initialBrowse = AtomicBoolean(true)
    override fun landing(): LibraryLanding? {
        val ready = warmLanding(repository.state()) ?: return null
        initialBrowse.set(false)
        // First paint consumes the warm page. Keep TTL refreshes independent of page-one retries.
        refreshWarmup()
        return ready
    }
    override fun sources() = repository.state()
    override fun caches() = downloads.status()
    override fun catalog(query: String, page: Int, request: VodRequest): JSONObject {
        request.check()
        // Only the initial landing may join/reuse live warmup. Explicit retries always fetch afresh.
        if (query.isEmpty() && page == 1 && initialBrowse.getAndSet(false)) {
            warmFirstPage(repository.state(), request)?.let { request.check(); return it }
        }
        return if (query.isEmpty()) repository.browse(page, request) else repository.search(query, page, request)
    }
    override fun detail(work: LibraryWork, request: VodRequest): JSONObject {
        repository.validateReference(work.identity(), request)
        return repository.detail(work.sourceId, work.vodId, request).also { repository.validateReference(work.identity(), request) }
    }
    override fun validate(reference: JSONObject, request: VodRequest) = repository.validateReference(reference, request)
    override fun completed(reference: JSONObject, request: VodRequest): JSONObject? {
        validate(reference, request)
        return downloads.completedSpec(reference).also { validate(reference, request) }
    }
    override fun addSource(form: Map<String, String>) = repository.command("vod_add_source", form)
    override fun cache(reference: JSONObject, request: VodRequest): JSONObject {
        validate(reference, request)
        return downloads.enqueue(reference)
    }
    override fun removeCache(reference: JSONObject, request: VodRequest): JSONObject {
        request.check()
        val safe = LibraryReference.read(reference) ?: throw VodException("INVALID_SELECTION", "缓存参数无效")
        // A removed or revised source must not prevent deleting its local files.
        return downloads.cancel(VodCacheManager.identity(safe))
    }
}
