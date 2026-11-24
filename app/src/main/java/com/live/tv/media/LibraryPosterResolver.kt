package com.live.tv.media

import android.graphics.Bitmap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal data class PosterSource(val id: String, val revision: Int, val epoch: Int)
internal data class PosterLookup(val key: String, val work: LibraryWork, val snapshot: String, val sources: List<PosterSource>,
    val embeddedOriginal: String? = null)

internal interface PosterResolver {
    fun prepare(work: LibraryWork): PosterLookup?
    fun resolve(lookup: PosterLookup, request: VodRequest): Bitmap?
}

/** Artwork lookup only: never replaces the work, source, episode reference or playback locator. */
internal class LibraryPosterResolver(
    private val sources: () -> JSONObject,
    private val catalog: (String, String, VodRequest) -> JSONObject,
    private val image: (String, VodRequest) -> Bitmap?
) : PosterResolver {
    override fun prepare(work: LibraryWork): PosterLookup? {
        val embedded = PosterMatch.embeddedOriginal(work.poster)
        val canMatch = PosterMatch.title(work.title).isNotEmpty() && work.title != "未命名影片" &&
            PosterMatch.year(work.year) != null && PosterMatch.kind(work.type) != null
        if (!canMatch && embedded == null) return null
        val state = sources()
        if (state.optString("errorCode").isNotEmpty()) return null
        val rows = state.optJSONArray("sources") ?: return null
        val alternatives = if (!canMatch) emptyList() else (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.optString("id") == work.sourceId || !row.optBoolean("searchable", true) || row.optBoolean("requiresParser") ||
                row.optInt("revision") < 1 || row.optInt("epoch") < 1) return@mapNotNull null
            PosterSource(row.optString("id"), row.getInt("revision"), row.getInt("epoch"))
        }.filter { it.id.isNotEmpty() }.take(MAX_SOURCES)
        if (alternatives.isEmpty() && embedded == null) return null
        val snapshot = snapshot(state)
        val identity = JSONArray(listOf(work.key, PosterMatch.title(work.title), PosterMatch.year(work.year),
            PosterMatch.kind(work.type), work.poster, snapshot)).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PosterLookup("fallback:$digest", work.copy(caches = emptyList()), snapshot, alternatives, embedded)
    }

    override fun resolve(lookup: PosterLookup, request: VodRequest): Bitmap? {
        var imageAttempts = 0
        val seen = mutableSetOf(PosterImageStore.key(lookup.work.poster))
        // Reserve both time and an image attempt for a verified same-poster origin when available.
        val crossSource = request.bounded(if (lookup.embeddedOriginal != null) 12_000 else 18_000)
        val candidateLimit = MAX_IMAGES - if (lookup.embeddedOriginal != null) 1 else 0
        try {
            sourceLoop@ for (source in lookup.sources) {
                current(lookup, request)
                if (crossSource.expired()) break
                val search = crossSource.bounded(3000)
                val result = try { catalog(source.id, lookup.work.title, search) }
                catch (_: Exception) { request.check(); continue }
                finally { search.cancel() }
                current(lookup, request)
                if (result.optString("sourceId") != source.id || result.optInt("revision") != source.revision ||
                    result.optInt("epoch") != source.epoch) continue
                val rows = result.optJSONArray("items") ?: continue
                for (index in 0 until minOf(rows.length(), 200)) {
                    request.check()
                    if (crossSource.expired()) break@sourceLoop
                    val row = rows.optJSONObject(index) ?: continue
                    if (!PosterMatch.same(lookup.work, row)) continue
                    val url = PosterImageStore.key(row.optString("poster"))?.takeIf(PosterMatch::usableUrl) ?: continue
                    if (!seen.add(url)) continue
                    if (imageAttempts++ >= candidateLimit) break@sourceLoop
                    fetchImage(lookup, url, crossSource.bounded(3000), request)?.let { return it }
                }
            }
        } finally { crossSource.cancel() }
        val original = lookup.embeddedOriginal ?: return null
        return fetchImage(lookup, original, request.bounded(4000), request)
    }

    private fun fetchImage(lookup: PosterLookup, url: String, attempt: VodRequest, request: VodRequest): Bitmap? {
        current(lookup, request)
        val bitmap = try { image(url, attempt) } catch (_: Exception) { request.check(); null }
            finally { attempt.cancel() }
        current(lookup, request)
        return bitmap?.takeIf { it.width >= 80 && it.height >= 100 && it.width.toFloat() / it.height in .3f..2f }
    }

    private fun current(lookup: PosterLookup, request: VodRequest) {
        request.check()
        if (snapshot(sources()) != lookup.snapshot) throw VodException("STALE_SELECTION", "海报来源已改变")
    }

    private fun snapshot(state: JSONObject): String {
        val rows = state.optJSONArray("sources") ?: JSONArray()
        return JSONArray(listOf(state.optString("errorCode"), JSONArray((0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: JSONObject()
            JSONArray(listOf(row.optString("id"), row.optInt("revision"), row.optInt("epoch"),
                row.optBoolean("searchable", true), row.optBoolean("requiresParser")))
        }))).toString()
    }

    companion object { const val MAX_SOURCES = 3; const val MAX_IMAGES = 3 }
}

/** Conservative matching: remakes, seasons, and different kinds of programmes must not share art. */
internal object PosterMatch {
    fun title(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), "").trim()
    fun year(value: String): String? = Regex("(?<!\\d)(?:18|19|20|21)\\d{2}(?!\\d)")
        .findAll(value).map { it.value }.distinct().toList().singleOrNull()
    fun kind(value: String): String? {
        val type = title(value)
        return when {
            type.contains("动漫") || type.contains("动画") -> "animation"
            type.contains("综艺") -> "variety"
            type.contains("纪录") || type.contains("记录") -> "documentary"
            type.endsWith("剧") || type.contains("电视剧") -> "series"
            type.endsWith("片") || type.contains("电影") -> "movie"
            else -> null
        }
    }
    fun same(work: LibraryWork, candidate: JSONObject): Boolean {
        val expectedYear = year(work.year) ?: return false
        val expectedKind = kind(work.type) ?: return false
        return title(work.title) == title(candidate.optString("title")) &&
            expectedYear == year(candidate.optString("year")) && expectedKind == kind(candidate.optString("typeName"))
    }
    fun usableUrl(value: String): Boolean {
        val path = value.substringBefore('?').lowercase(Locale.ROOT)
        return !Regex("(?:^|[/_\\-.])(?:default|no_?pic(?:ture)?|no_?photo|placeholder|loading|404|not_?found)(?:[/_\\-.]|$)")
            .containsMatchIn(path)
    }

    /** This proxy embeds the complete origin URL; only the observed poster route is unwrapped. */
    fun embeddedOriginal(value: String): String? {
        val proxy = value.toHttpUrlOrNull() ?: return null
        if (proxy.scheme != "https" || proxy.host != "img.ukuapi.com" || proxy.port != 443 ||
            proxy.username.isNotEmpty() || proxy.password.isNotEmpty() || proxy.query != null || proxy.fragment != null) return null
        val original = proxy.encodedPath.removePrefix("/").toHttpUrlOrNull() ?: return null
        if (original.scheme != "https" || original.host != "www.pianku0.cc" || original.port != 443 ||
            original.username.isNotEmpty() || original.password.isNotEmpty() || original.query != null || original.fragment != null ||
            !Regex("/doubanio/view/photo/s_ratio_poster/public/p[0-9]{6,16}\\.jpg").matches(original.encodedPath)) return null
        return original.toString()
    }
}
