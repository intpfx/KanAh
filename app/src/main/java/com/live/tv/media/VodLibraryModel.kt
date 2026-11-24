package com.live.tv.media

import org.json.JSONArray
import org.json.JSONObject

/** Library state contains public metadata and opaque references, never resolved media URLs. */
internal object LibraryReference {
    fun read(value: JSONObject?): JSONObject? {
        value ?: return null
        val safe = JSONObject()
        for ((key, limit) in listOf("sourceId" to 128, "vodId" to 256, "lineId" to 128, "episodeKey" to 128)) {
            val text = value.opt(key) as? String ?: return null
            if (text.isBlank() || text.length > limit || text.any { it.code < 32 || it.code == 127 }) return null
            safe.put(key, text)
        }
        for (key in listOf("revision", "epoch")) {
            val number = value.opt(key) as? Number ?: return null
            if (number.toInt() < 1 || number.toDouble() != number.toInt().toDouble()) return null
            safe.put(key, number.toInt())
        }
        return safe
    }
    fun workKey(source: String, vod: String, revision: Int, epoch: Int): String =
        JSONArray(listOf(source, vod, revision, epoch)).toString()
    fun workKey(ref: JSONObject) = workKey(ref.optString("sourceId"), ref.optString("vodId"), ref.optInt("revision"), ref.optInt("epoch"))
    fun key(ref: JSONObject) = JSONArray(listOf("sourceId", "vodId", "lineId", "episodeKey", "revision", "epoch").map { ref.opt(it) }).toString()
}

internal data class LibrarySource(val id: String, val name: String, val revision: Int, val epoch: Int, val searchable: Boolean) {
    fun matches(ref: JSONObject) = id == ref.optString("sourceId") && revision == ref.optInt("revision") && epoch == ref.optInt("epoch")
}

internal data class LibraryCache(val reference: JSONObject, val title: String, val poster: String, val label: String,
    val state: String, val positionMs: Long, val lastPlayedAt: Long,
    val bytes: Long = 0, val percent: Int = -1, val error: String = "") {
    val complete get() = state == "complete"
    val workKey get() = LibraryReference.workKey(reference)
}

internal data class LibraryWork(val sourceId: String, val vodId: String, val revision: Int, val epoch: Int,
    val title: String, val poster: String = "", val year: String = "", val type: String = "", val remarks: String = "",
    val sourceName: String = "", val caches: List<LibraryCache> = emptyList(), val current: Boolean = true,
    val music: com.live.tv.music.catalog.CatalogTrack? = null) {
    val key get() = LibraryReference.workKey(sourceId, vodId, revision, epoch)
    val completed get() = caches.filter { it.complete }
    fun reference(lineId: String, episodeKey: String) = JSONObject().put("sourceId", sourceId).put("vodId", vodId)
        .put("revision", revision).put("epoch", epoch).put("lineId", lineId).put("episodeKey", episodeKey)
    fun identity() = JSONObject().put("sourceId", sourceId).put("vodId", vodId).put("revision", revision).put("epoch", epoch)
}

internal data class LibraryEpisode(val label: String, val reference: JSONObject, val cached: Boolean = false,
    val cacheState: String = if (cached) "complete" else "", val percent: Int = -1)
internal data class LibraryLine(val id: String, val label: String, val episodes: List<LibraryEpisode>)
internal data class LibraryDetail(val work: LibraryWork, val overview: String, val lines: List<LibraryLine>, val offline: Boolean) {
    /** Refresh status without replacing catalog rows or treating unverified entries as playable. */
    fun withCaches(caches: List<LibraryCache>): LibraryDetail {
        val matching = matchingCaches(work, caches)
        val states = matching.associateBy { LibraryReference.key(it.reference) }
        return copy(work = work.copy(caches = matching), lines = lines.map { line ->
            line.copy(episodes = line.episodes.map { episode -> episode.withCache(states[LibraryReference.key(episode.reference)]) })
        })
    }

    companion object {
        fun online(work: LibraryWork, value: JSONObject): LibraryDetail {
            if (value.optString("sourceId") != work.sourceId || value.optString("vodId") != work.vodId ||
                value.optInt("revision") != work.revision || value.optInt("epoch") != work.epoch)
                throw VodException("STALE_SELECTION", "来源已改变，请刷新资源库")
            if (value.optBoolean("requiresParser")) throw VodException("REQUIRES_PARSER", "此来源需要网页解析，暂不能原生播放")
            val rows = value.optJSONArray("lines") ?: JSONArray()
            val seenLines = mutableSetOf<String>()
            var total = 0
            val lines = (0 until minOf(rows.length(), 64)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id")
                if (id.isBlank() || !seenLines.add(id) || row.optBoolean("requiresParser")) return@mapNotNull null
                val episodes = row.optJSONArray("episodes") ?: JSONArray()
                val seen = mutableSetOf<String>()
                val items = (0 until episodes.length()).mapNotNull episode@ { position ->
                    if (total >= 2000) return@episode null
                    val episode = episodes.optJSONObject(position) ?: return@episode null
                    val ref = LibraryReference.read(work.reference(id, episode.optString("key"))) ?: return@episode null
                    if (!seen.add(ref.getString("episodeKey"))) return@episode null
                    total++
                    LibraryEpisode(episode.optString("label").take(160).ifBlank { "第 ${position + 1} 集" }, ref)
                }
                if (items.isEmpty()) null else LibraryLine(id, row.optString("label").take(160).ifBlank { "线路 ${index + 1}" }, items)
            }
            if (lines.isEmpty()) throw VodException("NO_EPISODES", "此资源暂时没有可播放的剧集")
            return LibraryDetail(work.copy(title = value.optString("title", work.title).take(512),
                poster = value.optString("poster", work.poster), year = value.optString("year", work.year),
                type = value.optString("typeName", work.type)), value.optString("overview").take(4096), lines, false).withCaches(work.caches)
        }

        /** Metadata for cache selection/removal, including entries no longer present in the catalog. */
        fun forCacheSelection(work: LibraryWork, online: LibraryDetail?): LibraryDetail {
            val catalog = online?.takeIf { it.work.key == work.key }
            val caches = matchingCaches(work, work.caches)
            val states = caches.associateBy { LibraryReference.key(it.reference) }
            val labels = linkedMapOf<String, String>()
            val groups = linkedMapOf<String, MutableList<LibraryEpisode>>()
            val seen = mutableSetOf<String>()
            for (line in catalog?.lines.orEmpty()) {
                for (episode in line.episodes) {
                    val ref = LibraryReference.read(episode.reference) ?: continue
                    if (LibraryReference.workKey(ref) != work.key || ref.getString("lineId") != line.id) continue
                    if (!seen.add(LibraryReference.key(ref))) continue
                    val items = groups.getOrPut(line.id) { mutableListOf() }
                    labels.putIfAbsent(line.id, line.label.take(160).ifBlank { "线路 ${groups.size}" })
                    items.add(episode.copy(label = episode.label.take(160).ifBlank { "第 ${items.size + 1} 集" }, reference = ref)
                        .withCache(states[LibraryReference.key(ref)]))
                }
            }
            for (cache in caches) {
                if (!seen.add(LibraryReference.key(cache.reference))) continue
                val id = cache.reference.getString("lineId")
                val items = groups.getOrPut(id) { mutableListOf() }
                labels.putIfAbsent(id, "缓存线路 ${groups.size}")
                items.add(LibraryEpisode(cache.label.take(160).ifBlank { "第 ${items.size + 1} 集" }, cache.reference).withCache(cache))
            }
            val lines = groups.map { (id, episodes) -> LibraryLine(id, labels.getValue(id), episodes) }
            // The selection list is not the verified offline playback path.
            return LibraryDetail((catalog?.work ?: work).copy(caches = caches, current = work.current),
                catalog?.overview.orEmpty(), lines, false)
        }

        fun offline(work: LibraryWork, verified: List<LibraryCache>): LibraryDetail {
            val groups = verified.groupBy { it.reference.getString("lineId") }
            val lines = groups.entries.mapIndexed { index, (id, items) ->
                LibraryLine(id, if (groups.size == 1) "已缓存剧集" else "已缓存线路 ${index + 1}", items.map {
                    LibraryEpisode(it.label.ifBlank { "播放" }, JSONObject(it.reference.toString()), true)
                })
            }
            if (lines.isEmpty()) throw VodException("CACHE_NOT_FOUND", "缓存文件已不完整，请在线重新缓存")
            return LibraryDetail(work, "这些剧集已完整缓存在电视，可直接离线播放。", lines, true)
        }
    }
}

private fun matchingCaches(work: LibraryWork, caches: List<LibraryCache>): List<LibraryCache> {
    val seen = mutableSetOf<String>()
    return caches.mapNotNull { cache ->
        val ref = LibraryReference.read(cache.reference) ?: return@mapNotNull null
        if (LibraryReference.workKey(ref) != work.key || !seen.add(LibraryReference.key(ref))) return@mapNotNull null
        cache.copy(reference = ref)
    }
}

private fun LibraryEpisode.withCache(cache: LibraryCache?) = copy(cached = cache?.complete == true,
    cacheState = cache?.state.orEmpty(), percent = cache?.percent ?: -1)

internal class VodLibraryModel {
    var sources: List<LibrarySource> = emptyList(); private set
    var sourceError = ""; private set
    var caches: List<LibraryCache> = emptyList(); private set
    private val online = linkedMapOf<String, LibraryWork>()
    private val sourcePages = mutableMapOf<String, Boolean>()
    private val limits = linkedMapOf<String, Int>()
    var page = 0; private set
    var hasMore = false; private set
    var failures: List<String> = emptyList(); private set
    var capped = false; private set

    fun updateSources(value: JSONObject): Boolean {
        val rows = value.optJSONArray("sources") ?: JSONArray()
        val next = (0 until rows.length()).mapNotNull {
            val row = rows.optJSONObject(it) ?: return@mapNotNull null
            val id = row.optString("id")
            if (id.isBlank() || row.optInt("revision") < 1 || row.optInt("epoch") < 1) return@mapNotNull null
            LibrarySource(id, row.optString("name").take(120), row.getInt("revision"), row.getInt("epoch"),
                row.optBoolean("searchable", true) && !row.optBoolean("requiresParser"))
        }
        val error = if (value.optString("errorCode").isNotBlank()) "来源配置读取失败，请保留原配置后恢复" else ""
        val changed = sources != next || sourceError != error
        sources = next; sourceError = error
        if (changed) resetOnline()
        return changed
    }

    fun isCurrent(ref: JSONObject) = sources.any { it.matches(ref) }

    fun updateCaches(value: JSONObject) {
        val rows = value.optJSONArray("caches") ?: JSONArray()
        val seen = mutableSetOf<String>()
        caches = (0 until rows.length()).mapNotNull {
            val row = rows.optJSONObject(it) ?: return@mapNotNull null
            val ref = LibraryReference.read(row.optJSONObject("ref")) ?: return@mapNotNull null
            if (!seen.add(LibraryReference.key(ref))) return@mapNotNull null
            val label = row.optString("episodeLabel").take(160)
            val title = row.optString("resourceTitle").ifBlank { row.optString("title").removeSuffix(" · $label") }
            LibraryCache(ref, title.take(512).ifBlank { "未命名影片" }, row.optString("poster").take(8192), label,
                row.optString("state").takeIf { it in CACHE_STATES }.orEmpty(),
                row.optLong("positionMs").coerceAtLeast(0), row.optLong("lastPlayedAt").coerceAtLeast(0),
                row.optLong("bytes").coerceAtLeast(0), row.optDouble("percent", -1.0).let {
                    if (it.isFinite() && it >= 0) it.coerceAtMost(100.0).toInt() else -1
                }, publicCacheError(row.optString("error")))
        }
    }

    fun resetOnline() { online.clear(); sourcePages.clear(); limits.clear(); page = 0; hasMore = false; failures = emptyList(); capped = false }

    fun applyPage(result: JSONObject, query: String, requestedPage: Int) {
        val batch = result.optJSONObject(if (query.isEmpty()) "browse" else "search")
            ?: throw VodException("INVALID_RESPONSE", "来源未返回影视目录")
        if (batch.optInt("page") != requestedPage) throw VodException("INVALID_RESPONSE", "来源返回了重复页，请重试")
        val statuses = batch.optJSONArray("sources") ?: JSONArray()
        val available = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        val reportedMore = linkedMapOf<String, Boolean>()
        for (index in 0 until statuses.length()) {
            val row = statuses.optJSONObject(index) ?: continue
            if (!isCurrent(row)) continue
            val id = row.optString("sourceId")
            if (row.optString("status") != "success") { errors.add(row.optString("sourceName", "此来源")); continue }
            available.add(id); reportedMore[id] = row.optBoolean("hasMore")
            if (row.optBoolean("truncated") && row.optInt("searchLimit") > 0) limits[id] = row.optInt("searchLimit")
        }
        if (available.isEmpty()) throw VodException("SEARCH_FAILED", result.optString("error").ifBlank { "影视来源暂时不可用，请重试" })
        val additions = mutableMapOf<String, Int>()
        val rows = batch.optJSONArray("items") ?: JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (!isCurrent(row) || row.optString("sourceId") !in available) continue
            val id = row.optString("vodId")
            if (id.isBlank() || id.length > 256) continue
            val sourceId = row.getString("sourceId")
            val work = LibraryWork(sourceId, id, row.getInt("revision"), row.getInt("epoch"),
                row.optString("title").take(512).ifBlank { "未命名影片" }, row.optString("poster").take(8192),
                row.optString("year").take(128), row.optString("typeName").take(512), row.optString("remarks").take(512),
                sources.firstOrNull { it.id == sourceId }?.name.orEmpty())
            if (work.key !in online) {
                if (online.size >= MAX_WORKS) { capped = true; continue }
                online[work.key] = work; additions[sourceId] = (additions[sourceId] ?: 0) + 1
            }
        }
        for ((id, more) in reportedMore) {
            val key = "$id:$requestedPage"
            if (key !in sourcePages) sourcePages[key] = more && (additions[id] ?: 0) > 0
        }
        page = maxOf(page, requestedPage); failures = errors.distinct()
        hasMore = !capped && requestedPage < 10_000 && reportedMore.keys.any { sourcePages["$it:$requestedPage"] == true }
    }

    fun notice(): String = buildList {
        if (failures.isNotEmpty()) add("${failures.joinToString("、")}暂时不可用，可重试当前页")
        for ((id, limit) in limits) add("${sources.firstOrNull { it.id == id }?.name ?: "此来源"}最多显示 $limit 条，请搜索更具体的片名")
        if (capped) add("已显示 $MAX_WORKS 部，请搜索更具体的片名")
    }.joinToString(" · ")

    fun works(query: String, cachedOnly: Boolean): List<LibraryWork> {
        val grouped = caches.groupBy { it.workKey }
        val cached = grouped.map { (key, entries) ->
            val first = entries.first(); val ref = first.reference
            (online[key] ?: LibraryWork(ref.getString("sourceId"), ref.getString("vodId"), ref.getInt("revision"), ref.getInt("epoch"),
                first.title, first.poster, sourceName = sources.firstOrNull { it.id == ref.optString("sourceId") }?.name.orEmpty()))
                .copy(caches = entries, current = isCurrent(ref))
        }.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .filter { !cachedOnly || it.completed.isNotEmpty() }
            .sortedWith(compareByDescending<LibraryWork> { it.completed.isNotEmpty() }.thenByDescending { it.caches.maxOfOrNull(LibraryCache::lastPlayedAt) ?: 0 })
        val keys = cached.map { it.key }.toSet()
        if (cachedOnly) return cached
        val works = cached + online.values.filter { it.key !in keys }
        return VodSourcePriority.orderTitles(works, LibraryWork::title) {
            if (it.current) VodSourcePriority.rank(it.sourceName) else Int.MAX_VALUE
        }
    }

    companion object {
        const val MAX_WORKS = 1000
        private val CACHE_STATES = setOf("queued", "resolving", "downloading", "complete", "failed", "removing")
        private val PRIVATE_ERROR = Regex("(?i)([a-z][a-z0-9+.-]*://|\\b(?:authorization|cookie|password|token|secret)\\b)")
        private fun publicCacheError(error: String): String {
            if (PRIVATE_ERROR.containsMatchIn(error)) return "缓存未完成，可重新缓存以继续"
            return error.filterNot { it.code < 32 || it.code == 127 }.take(512)
        }
    }
}
