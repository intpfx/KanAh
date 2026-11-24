package com.live.tv.media

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

/** On-demand native VOD directory. Public methods are synchronous and must run off the UI thread. */
class VodRepository @JvmOverloads internal constructor(private val store: VodSourceStore, internal var client: OkHttpClient,
    private val searchTimeoutMillis: Long = 20_000) {
    private constructor(context: Context) : this(VodSourceStore(context), MediaNetwork.get(context).client(
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()))

    fun state(): JSONObject = store.state()

    fun command(action: String, form: Map<String, String>): JSONObject = try {
        val id = when (action) {
            "vod_add_source" -> store.add(form).id
            "vod_import_m3u8" -> store.importM3u8(form["name"].orEmpty(), form["episodes"].orEmpty()).id
            "vod_update_source" -> store.updateEndpoint(form).id
            "vod_remove_source" -> form["id"].orEmpty().also { store.remove(it) }
            else -> throw VodException("INVALID_ACTION", "未知点播操作")
        }
        JSONObject().put("ok", true).put("id", id).put("vod", state())
    } catch (e: VodException) { JSONObject().put("ok", false).put("code", e.code).put("error", e.message) }

    fun catalog(sourceId: String, categoryId: String, query: String, page: Int, request: VodRequest): JSONObject {
        request.check()
        val source = store.source(sourceId)
        val result = VodProvider.create(source, client).catalog(categoryId, query.trim(), page, request)
        current(source, request)
        return result
    }

    fun search(query: String, page: Int, request: VodRequest): JSONObject =
        search(query, page, request, null)

    internal fun search(query: String, page: Int, request: VodRequest,
        onSourceItems: ((List<JSONObject>) -> Unit)?): JSONObject {
        val term = query.trim()
        if (term.isEmpty() || term.length > 200 || term.any { it.code < 32 || it.code == 127 } || page !in 1..10_000)
            throw VodException("INVALID_QUERY", "请填写 1–200 字的搜索词")
        return aggregate(term, page, request, "search", onSourceItems)
    }

    /** The poster wall requests one page at a time, with the same bounded fanout as search. */
    fun browse(page: Int, request: VodRequest): JSONObject {
        if (page !in 1..10_000) throw VodException("INVALID_QUERY", "查询参数无效")
        return aggregate("", page, request, "browse")
    }

    /** One bounded operation across a configuration snapshot; completion order never changes result order. */
    private fun aggregate(term: String, page: Int, request: VodRequest, kind: String,
        onSourceItems: ((List<JSONObject>) -> Unit)? = null): JSONObject {
        request.check()
        val snapshot = store.state()
        if (snapshot.optString("errorCode").isNotEmpty()) throw VodException("SOURCE_STORE", "点播来源读取失败，请保留原文件后恢复")
        val configured = snapshot.getJSONArray("sources")
        val sources = (0 until configured.length()).map { configured.getJSONObject(it) }
            .filter { it.optBoolean("searchable") && !it.optBoolean("requiresParser") }
        if (sources.isEmpty()) throw VodException("NO_SEARCHABLE_SOURCES", "请先在设置中添加可搜索的影视来源")
        val scope = request.bounded(searchTimeoutMillis)
        val pool = Executors.newFixedThreadPool(minOf(4, sources.size)) { runnable ->
            Thread(runnable, "vod-$kind").apply { isDaemon = true }
        }
        val completed = ExecutorCompletionService<Pair<Int, SearchResult>>(pool)
        val results = arrayOfNulls<SearchResult>(sources.size)
        val futures = sources.mapIndexed { index, row -> completed.submit(java.util.concurrent.Callable {
            val result = try {
                scope.check()
                val source = store.source(row.getString("id"))
                matching(row, source)
                val catalog = VodProvider.create(source, client).catalog("", term, page, scope)
                current(source, scope)
                SearchResult(source, catalog, null)
            } catch (error: VodException) { SearchResult(null, null, error) }
            catch (_: Exception) { SearchResult(null, null, VodException("NETWORK_ERROR", "此来源暂时不可用，请重试")) }
            index to result
        }) }
        try {
            var remaining = sources.size
            while (remaining > 0 && !scope.expired()) {
                request.check()
                val ready = completed.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val (index, result) = ready.get()
                results[index] = result
                remaining--
                if (onSourceItems != null && result.source != null && result.catalog != null) {
                    try {
                        current(result.source, request)
                    } catch (_: VodException) {
                        continue
                    }
                    onSourceItems(sourceItems(result.catalog, sources[index]))
                }
            }
            request.check()
            // Recheck even early completions after other sources finish. No deleted/changed source
            // may publish selectable works from the old snapshot.
            val items = JSONArray()
            val statuses = JSONArray()
            var successes = 0
            var hasMore = false
            sources.forEachIndexed { index, row ->
                request.check()
                val result = results[index]
                var failure = result?.error ?: if (result == null) VodException("SEARCH_TIMEOUT", "此来源搜索超时，请重试") else null
                if (failure == null && result?.source != null) {
                    try { current(result.source, request) } catch (error: VodException) { failure = error }
                }
                val status = JSONObject().put("sourceId", row.getString("id")).put("sourceName", row.getString("name"))
                    .put("revision", row.getInt("revision")).put("epoch", row.getInt("epoch"))
                if (failure != null) {
                    status.put("status", "error").put("hasMore", false).put("code", failure.code).put("error", failure.message)
                } else {
                    successes++
                    val catalog = result!!.catalog!!
                    val more = page < 10_000 && catalog.optBoolean("hasMore")
                    hasMore = hasMore || more
                    status.put("status", "success").put("hasMore", more)
                    if (catalog.has("searchLimit")) status.put("searchLimit", catalog.getInt("searchLimit"))
                        .put("truncated", catalog.optBoolean("truncated"))
                    sourceItems(catalog, row).forEach { items.put(it) }
                }
                statuses.put(status)
            }
            val aggregate = JSONObject().put("query", term).put("page", page).put("items", items).put("hasMore", hasMore)
                .put("sources", statuses).put("successCount", successes).put("errorCount", sources.size - successes)
            return JSONObject().put("ok", successes > 0).put(kind, aggregate).apply {
                if (successes == 0) put("code", if (kind == "search") "SEARCH_FAILED" else "BROWSE_FAILED")
                    .put("error", if (kind == "search") "所有影视来源搜索失败，请重试" else "影视海报暂时无法加载，请重试")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VodException("CANCELLED", "请求已取消")
        } finally {
            scope.cancel()
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    private fun sourceItems(catalog: JSONObject, source: JSONObject): List<JSONObject> {
        val rows = catalog.getJSONArray("items")
        val seen = mutableSetOf<String>()
        return (0 until rows.length()).mapNotNull { index ->
            val item = rows.getJSONObject(index)
            if (!seen.add(item.getString("vodId"))) return@mapNotNull null
            JSONObject(item.toString()).put("sourceName", source.getString("name"))
                .put("revision", source.getInt("revision")).put("epoch", source.getInt("epoch"))
        }
    }

    private data class SearchResult(val source: VodSource?, val catalog: JSONObject?, val error: VodException?)

    private fun matching(snapshot: JSONObject, source: VodSource) {
        if (snapshot.getInt("revision") != source.revision || snapshot.getInt("epoch") != source.epoch)
            throw VodException("STALE_SELECTION", "来源已改变，请重新查询")
    }

    fun detail(sourceId: String, vodId: String, request: VodRequest): JSONObject {
        request.check()
        val source = store.source(sourceId)
        val result = MacCmsProvider(source, client).detail(vodId, request)
        current(source, request)
        return result.metadata.put("lines", JSONArray(result.lines.map { it.publicJson(source.playUrl.isNotEmpty()) }))
    }

    fun resolve(ref: JSONObject, request: VodRequest, sample: Boolean = false, sampleLimit: Int = 4096): JSONObject {
        val started = System.nanoTime()
        request.check()
        val source = store.source(ref.optString("sourceId"))
        validateReference(ref, request)
        if (source.playUrl.isNotEmpty()) throw VodException("REQUIRES_PARSER", "此来源需要解析，当前版本暂不支持")
        val vodId = ref.optString("vodId")
        val provider = MacCmsProvider(source, client)
        val detail = provider.detail(vodId, request) // Fresh detail; never persist signed media URLs as identity.
        val line = detail.lines.singleOrNull { it.id == ref.optString("lineId") }
            ?: throw VodException("STALE_SELECTION", "播放线路已改变，请重新选择")
        val episode = line.episodes.singleOrNull { it.key == ref.optString("episodeKey") }
            ?: throw VodException("STALE_SELECTION", "剧集已改变，请重新选择")
        current(source, request)
        val spec = provider.probe(episode.locator, request, sample, sampleLimit)
        current(source, request)
        fun referenceFor(item: VodEpisode) = JSONObject().put("sourceId", source.id).put("vodId", vodId)
            .put("lineId", line.id).put("episodeKey", item.key).put("revision", source.revision).put("epoch", source.epoch)
        val episodeIndex = line.episodes.indexOf(episode)
        if (sample) spec.optJSONObject("probe")?.put("elapsedMs",
            maxOf(1L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)))
        return spec.put("id", MacCmsProvider.playbackId(source.id, vodId, line.id, episode.key))
            .put("resourceTitle", detail.metadata.optString("title"))
            .put("poster", detail.metadata.optString("poster"))
            .put("episodeLabel", episode.label)
            .put("episodeItems", JSONArray(line.episodes.map { JSONObject().put("label", it.label).put("ref", referenceFor(it)) }))
            .put("title", detail.metadata.optString("title") + if (line.episodes.size > 1) " · ${episode.label}" else "")
            .put("stable", episode.stable).put("vodRef", referenceFor(episode)).apply {
                // Carry opaque references, never signed locators. Navigation resolves and validates
                // the neighboring episode again, and cannot cross into a different playback line.
                line.episodes.getOrNull(episodeIndex - 1)?.let { put("previousEpisode", referenceFor(it)) }
                line.episodes.getOrNull(episodeIndex + 1)?.let { put("nextEpisode", referenceFor(it)) }
            }
    }

    /** Cache playback may be offline; it still must match the current source identity exactly.
     * The cache manager separately verifies the full episode reference and downloaded files.
     */
    fun validateReference(ref: JSONObject, request: VodRequest) {
        request.check()
        val source = store.source(ref.optString("sourceId"))
        if (ref.optInt("revision", -1) != source.revision || ref.optInt("epoch", -1) != source.epoch)
            throw VodException("STALE_SELECTION", "来源已改变，请重新选择影片")
    }

    private fun current(source: VodSource, request: VodRequest) {
        request.check()
        val latest = store.source(source.id)
        if (latest.revision != source.revision || latest.epoch != source.epoch)
            throw VodException("STALE_SELECTION", "来源已改变，请重新查询")
    }

    companion object {
        @Volatile private var instance: VodRepository? = null
        @JvmStatic fun get(context: Context): VodRepository = instance ?: synchronized(this) {
            instance ?: VodRepository(context.applicationContext).also { instance = it }
        }
    }
}
