package com.live.tv.media

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale

internal data class VodEpisode(val key: String, val label: String, val stable: Boolean, val locator: String) {
    fun publicJson() = JSONObject().put("key", key).put("label", label).put("stable", stable)
}

internal data class VodLine(val id: String, val label: String, val stable: Boolean, val episodes: List<VodEpisode>) {
    fun publicJson(requiresParser: Boolean) = JSONObject().put("id", id).put("label", label).put("stable", stable)
        .put("requiresParser", requiresParser).put("episodes", JSONArray(episodes.map { it.publicJson() }))
}

internal data class VodParsedDetail(val metadata: JSONObject, val lines: List<VodLine>)

/** MacCMS type 1 protocol only: no remote code, browser parsing or plugin calls. */
internal class MacCmsProvider(private val source: VodSource, client: OkHttpClient) : VodProvider {
    private val http = VodHttp(client)

    override fun catalog(categoryId: String, query: String, page: Int, request: VodRequest): JSONObject {
        if (page !in 1..10_000 || categoryId.length > 256 || query.length > 200)
            throw VodException("INVALID_QUERY", "查询参数无效")
        if (query.isNotEmpty() && !source.searchable) throw VodException("SEARCH_DISABLED", "此来源已关闭搜索")
        if (source.importedEpisodes.isNotEmpty()) {
            request.check()
            val items = JSONArray()
            if (page == 1 && categoryId.isEmpty() && source.name.contains(query, ignoreCase = true))
                items.put(importedSummary())
            return JSONObject().put("sourceId", source.id).put("revision", source.revision).put("epoch", source.epoch)
                .put("categories", JSONArray()).put("items", items).put("page", page).put("reportedPage", page)
                .put("pageCount", 1).put("hasMore", false)
        }
        val parameters = linkedMapOf<String, String>()
        val operation = when {
            query.isNotEmpty() -> {
                // This catalog matches CJK titles with the original punctuation but without inter-word spaces.
                parameters["wd"] = if (source.api.host == "xgzy8.tv") CJK_TITLE_SPACES.replace(query, "") else query
                parameters["quick"] = "false"
                parameters["extend"] = source.ext
                if (page > 1) parameters["pg"] = page.toString()
                "search"
            }
            categoryId.isEmpty() && page == 1 -> "home"
            else -> {
                parameters["ac"] = "detail"
                if (categoryId.isNotEmpty()) parameters["t"] = categoryId
                parameters["pg"] = page.toString()
                "category"
            }
        }
        val root = fetch(operation, parameters, 2 * 1024 * 1024, request)
        return catalogResult(root, page, request)
    }

    /** Share catalog identity/sanitization with sources whose search uses a different endpoint. */
    internal fun catalogResult(root: JSONObject, page: Int, request: VodRequest, enrichMetadata: Boolean = false): JSONObject {
        val rows = list(root)
        if (rows.length() > 200) throw VodException("PAYLOAD_LIMIT", "目录条目过多")
        val categories = JSONArray()
        root.optJSONArray("class")?.let { types ->
            if (types.length() > 256) throw VodException("PAYLOAD_LIMIT", "分类条目过多")
            val seen = mutableSetOf<String>()
            for (index in 0 until types.length()) {
                val row = types.optJSONObject(index) ?: continue
                val id = scalar(row, "type_id").ifBlank { scalar(row, "id") }
                val name = plain(scalar(row, "type_name").ifBlank { scalar(row, "name") }, 512)
                if (id.isNotBlank() && seen.add(id)) categories.put(JSONObject().put("id", id).put("name", name.ifBlank { id }))
            }
        }
        val items = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = scalar(row, "vod_id")
            if (id.isNotBlank() && id.length <= 256 && seen.add(id)) items.put(summary(row, id))
        }
        hydrateCatalog(items, request, enrichMetadata)
        val reportedPage = positive(root, "page") ?: page
        val pageCount = positive(root, "pagecount")
        val limit = positive(root, "limit")
        val hasMore = items.length() > 0 && when {
            pageCount != null -> page < pageCount
            limit != null -> rows.length() >= limit
            else -> true // Explicit load-more, bounded page number, no automatic whole-library crawl.
        }
        return JSONObject().put("sourceId", source.id).put("revision", source.revision).put("epoch", source.epoch)
            .put("categories", categories).put("items", items).put("page", page).put("reportedPage", reportedPage)
            .put("pageCount", pageCount ?: JSONObject.NULL).put("hasMore", hasMore)
    }

    override fun detail(vodId: String, request: VodRequest): VodParsedDetail {
        if (vodId.isBlank() || vodId.length > 256) throw VodException("INVALID_SELECTION", "作品引用无效")
        if (source.importedEpisodes.isNotEmpty()) {
            request.check()
            if (vodId != "imported") throw VodException("DETAIL_NOT_FOUND", "未找到导入的作品")
            val episodes = source.importedEpisodes.mapIndexed { index, episode ->
                VodEpisode((index + 1).toString(), episode.label, true, episode.url.toString())
            }
            return VodParsedDetail(importedSummary(), listOf(VodLine("m3u8", "M3U8", true, episodes)))
        }
        val root = fetch("detail", linkedMapOf("ac" to "detail", "ids" to vodId), 4 * 1024 * 1024, request)
        val rows = list(root)
        if (rows.length() > 200) throw VodException("PAYLOAD_LIMIT", "详情作品过多")
        val row = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .singleOrNull { scalar(it, "vod_id") == vodId }
            ?: throw VodException("DETAIL_NOT_FOUND", "来源未返回选定作品详情")
        val metadata = summary(row, vodId).put("revision", source.revision).put("epoch", source.epoch)
            .put("area", plain(scalar(row, "vod_area"), 512)).put("actor", plain(scalar(row, "vod_actor"), 2048))
            .put("director", plain(scalar(row, "vod_director"), 2048)).put("typeName", plain(scalar(row, "type_name"), 512))
            .put("requiresParser", source.playUrl.isNotEmpty())
        return VodParsedDetail(metadata, playlists(scalar(row, "vod_play_from"), scalar(row, "vod_play_url")))
    }

    override fun probe(locator: String, request: VodRequest): JSONObject = probe(locator, request, false)

    /** Selection-time availability check only; ordinary playback keeps the lighter header probe. */
    internal fun probe(locator: String, request: VodRequest, sample: Boolean, sampleLimit: Int = 4096): JSONObject {
        require(sampleLimit in 1..65_536)
        if (source.playUrl.isNotEmpty()) throw VodException("REQUIRES_PARSER", "此线路需要解析，当前版本暂不支持")
        val url = mediaUrl(locator) ?: throw VodException("REQUIRES_PARSER", "此线路需要解析，当前版本暂不支持")
        val headers = source.headers.filterKeys { it.equals("User-Agent", true) || it.equals("Referer", true) || it.equals("Origin", true) }
        val response = http.fetch(url, headers, request, 65_536, prefixOnly = true, range = "bytes=0-65535")
        val mime = response.mime.lowercase(Locale.ROOT).substringBefore(';').trim()
        if (mime in setOf("text/html", "application/xhtml+xml", "application/json"))
            throw VodException("REQUIRES_PARSER", "此线路返回网页或解析结果，暂不支持")
        val bytes = response.bytes
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        if (text.startsWith("<") || text.startsWith("{") || text.startsWith("["))
            throw VodException("REQUIRES_PARSER", "此线路返回网页或解析结果，暂不支持")
        val resultMime = when {
            text.startsWith("#EXTM3U") && text.lineSequence().first().trim() == "#EXTM3U" &&
                (text.contains("#EXTINF:") || text.contains("#EXT-X-STREAM-INF:") || text.contains("#EXT-X-TARGETDURATION:")) -> "application/x-mpegURL"
            bytes.size >= 16 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                java.nio.ByteBuffer.wrap(bytes, 0, 4).int in 16..65_536 -> "video/mp4"
            else -> throw VodException("UNSUPPORTED_MEDIA", "未识别为直接 HLS 或 MP4 媒体")
        }
        val measured = if (sample && resultMime == "application/x-mpegURL")
            validateHls(response, headers, request, sampleLimit) else response
        return JSONObject().put("url", response.url.toString()).put("mimeType", resultMime).put("headers", JSONObject(headers)).apply {
            if (sample) put("probe", JSONObject().put("sampleBytes", measured.bytes.size)
                .put("sampleElapsedMs", measured.elapsedMs).put("method", "bounded-startup-sample"))
        }
    }

    private fun validateHls(initial: VodHttpResult, headers: Map<String, String>, request: VodRequest,
                            sampleLimit: Int): VodHttpResult {
        var manifest = initial
        val visited = mutableSetOf<String>()
        for (depth in 0..3) {
            request.check()
            if (!visited.add(manifest.url.toString())) throw VodException("UNSUPPORTED_MEDIA", "播放清单循环引用")
            val text = String(manifest.bytes, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
            if (text.lineSequence().firstOrNull()?.trim() != "#EXTM3U")
                throw VodException("UNSUPPORTED_MEDIA", "播放清单无效")
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            lines.filter { it.startsWith("#EXT-X-SESSION-KEY:") }.forEach { key -> keyAttributes(key) }
            val master = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
            if (master >= 0) {
                if (depth == 3) throw VodException("PAYLOAD_LIMIT", "播放清单层级过多")
                val variant = lines.drop(master + 1).firstOrNull { !it.startsWith('#') }
                    ?: throw VodException("UNSUPPORTED_MEDIA", "播放清单缺少线路")
                manifest = http.fetch(relativeMedia(manifest.url, variant), headers, request, 65_536,
                    prefixOnly = true, range = "bytes=0-65535")
                continue
            }
            var key: Map<String, String>? = null
            var init: Map<String, String>? = null
            var byteRange: String? = null
            var expectSegment = false
            var gap = false
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-KEY:") -> key = keyAttributes(line)
                    line.startsWith("#EXT-X-MAP:") -> init = attributes(line.substringAfter(':'))
                    line.startsWith("#EXT-X-BYTERANGE:") -> byteRange = line.substringAfter(':')
                    line.startsWith("#EXTINF:") -> { expectSegment = true; gap = false }
                    line == "#EXT-X-GAP" -> gap = true
                    !line.startsWith('#') && expectSegment -> {
                        if (gap) { expectSegment = false; byteRange = null; continue }
                        val encrypted = key?.get("METHOD") == "AES-128"
                        if (encrypted) {
                            val keyUri = key?.get("URI") ?: throw VodException("UNSUPPORTED_MEDIA", "播放清单缺少密钥地址")
                            val keyBytes = http.fetch(relativeMedia(manifest.url, keyUri), headers, request, 16).bytes
                            if (keyBytes.size != 16) throw VodException("UNSUPPORTED_MEDIA", "播放密钥格式无效")
                        }
                        init?.let { map ->
                            val uri = map["URI"] ?: throw VodException("UNSUPPORTED_MEDIA", "播放清单缺少初始化地址")
                            val range = sampleRange(map["BYTERANGE"])
                            val response = http.fetch(relativeMedia(manifest.url, uri), headers, request, rangeLength(range),
                                prefixOnly = true, range = range)
                            requireMediaSample(response, encrypted, true)
                        }
                        val range = sampleRange(byteRange, sampleLimit)
                        val response = http.fetch(relativeMedia(manifest.url, line), headers, request, rangeLength(range),
                            prefixOnly = true, range = range)
                        requireMediaSample(response, encrypted, false)
                        request.check()
                        return response
                    }
                }
            }
            throw VodException("UNSUPPORTED_MEDIA", "播放清单缺少可读取的媒体片段")
        }
        throw VodException("PAYLOAD_LIMIT", "播放清单层级过多")
    }

    private fun keyAttributes(line: String): Map<String, String> {
        val values = attributes(line.substringAfter(':'))
        val method = values["METHOD"]
        if (method !in setOf("NONE", "AES-128") || values["KEYFORMAT"]?.let { it != "identity" } == true)
            throw VodException("UNSUPPORTED_MEDIA", "此线路的加密格式暂不支持")
        return values
    }

    private fun attributes(value: String): Map<String, String> =
        Regex("(?:^|,)([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(value).associate {
            it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")
        }

    private fun relativeMedia(base: HttpUrl, value: String): HttpUrl {
        if (value.isBlank() || value.length > 8192) throw VodException("UNSUPPORTED_MEDIA", "媒体地址无效")
        return base.resolve(value)?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && it.toString().length <= 8192 }
            ?: throw VodException("UNSUPPORTED_MEDIA", "媒体地址无效")
    }

    private fun sampleRange(value: String?, limit: Int = 4096): String {
        if (value == null) return "bytes=0-${limit - 1}"
        val parts = value.split('@')
        val length = parts.getOrNull(0)?.toLongOrNull()
        val offset = if (parts.size == 1) 0L else parts.getOrNull(1)?.toLongOrNull()
        if (parts.size > 2 || length == null || length <= 0 || offset == null || offset < 0 || offset > Long.MAX_VALUE - limit)
            throw VodException("UNSUPPORTED_MEDIA", "媒体片段范围无效")
        return "bytes=$offset-${offset + minOf(length, limit.toLong()) - 1}"
    }

    private fun rangeLength(range: String): Int {
        val bounds = range.removePrefix("bytes=").split('-')
        return (bounds[1].toLong() - bounds[0].toLong() + 1).toInt()
    }

    private fun requireMediaSample(response: VodHttpResult, encrypted: Boolean, initialization: Boolean) {
        val bytes = response.bytes
        val mime = response.mime.lowercase(Locale.ROOT).substringBefore(';').trim()
        val text = String(bytes.take(128).toByteArray(), Charsets.UTF_8).trimStart()
        if (mime in setOf("text/html", "application/xhtml+xml", "application/json") ||
            (!encrypted && (text.startsWith('<') || text.startsWith('{') || text.startsWith('['))) ||
            text.startsWith("<html", true) || text.startsWith("<!doctype", true) || text.startsWith("#EXTM3U"))
            throw VodException("UNSUPPORTED_MEDIA", "媒体片段返回了网页或无效内容")
        val box = if (bytes.size >= 8) String(bytes, 4, 4, Charsets.US_ASCII) else ""
        val mp4 = bytes.size >= 8 && box in (if (initialization) setOf("ftyp", "moov") else setOf("styp", "moof", "ftyp", "sidx"))
        val ts = bytes.size >= 189 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()
        val audio = bytes.size >= 16 && (text.startsWith("ID3") || ((bytes[0].toInt() and 255) == 255 && (bytes[1].toInt() and 224) == 224))
        if (!(encrypted && bytes.size >= 16 || mp4 || !initialization && (ts || audio)))
            throw VodException("UNSUPPORTED_MEDIA", "媒体片段格式无效")
    }

    private fun mediaUrl(locator: String): HttpUrl? {
        val raw = locator.trim()
        if (raw.length > 8192) return null
        val url = raw.toHttpUrlOrNull() ?: if (raw.startsWith("/") || raw.startsWith("./") || raw.startsWith("../")) source.api.resolve(raw) else null
        return url?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
    }

    private fun importedSummary() = summary(JSONObject().put("vod_name", source.name), "imported")
        .put("revision", source.revision).put("epoch", source.epoch).put("requiresParser", false)

    private fun summary(row: JSONObject, id: String): JSONObject {
        return JSONObject().put("id", workId(source.id, id)).put("sourceId", source.id).put("vodId", id)
            .put("title", plain(scalar(row, "vod_name"), 512).ifBlank { "未命名影片" })
            .put("poster", artworkUrl(scalar(row, "vod_pic"))).put("backdrop", artworkUrl(scalar(row, "vod_pic_slide")))
            .put("overview", plain(scalar(row, "vod_content"), 16_384)).put("year", plain(scalar(row, "vod_year"), 128))
            .put("remarks", plain(scalar(row, "vod_remarks"), 512)).put("typeName", plain(scalar(row, "type_name"), 512))
    }

    private fun artworkUrl(value: String): String {
        if (value.isBlank() || value.length > 8192) return ""
        return source.api.resolve(value)?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() && it.toString().length <= 8192
        }?.toString().orEmpty()
    }

    /** Enrich optional catalog metadata without replacing the page's identity or ordering. */
    private fun hydrateCatalog(items: JSONArray, request: VodRequest, enrichMetadata: Boolean) {
        val missing = (0 until items.length()).map { items.getJSONObject(it) }
            .filter { (enrichMetadata || it.optString("poster").isEmpty()) && !it.getString("vodId").contains(',') }
        // Catalogs are limited to 200 rows; at most five optional detail requests per page.
        for (batch in missing.chunked(40)) {
            request.check()
            val rows = try {
                val root = fetch("detail", linkedMapOf("ac" to "detail", "ids" to batch.joinToString(",") { it.getString("vodId") }),
                    4 * 1024 * 1024, request)
                list(root).also { if (it.length() > 200) throw VodException("PAYLOAD_LIMIT", "详情作品过多") }
            } catch (e: VodException) {
                request.check()
                if (e.code == "CANCELLED") throw e
                continue // Artwork is optional; a failed detail endpoint must not hide the catalog.
            }
            val requested = batch.associateBy { it.getString("vodId") }
            val matches = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                .filter { scalar(it, "vod_id") in requested }.groupBy { scalar(it, "vod_id") }
            for ((id, candidates) in matches) {
                val row = candidates.singleOrNull() ?: continue
                val item = requested.getValue(id)
                val poster = artworkUrl(scalar(row, "vod_pic"))
                val backdrop = artworkUrl(scalar(row, "vod_pic_slide"))
                if (poster.isNotEmpty()) item.put("poster", poster)
                if (item.optString("year").isEmpty()) item.put("year", plain(scalar(row, "vod_year"), 128))
                if (item.optString("typeName").isEmpty()) item.put("typeName", plain(scalar(row, "type_name"), 512))
                if (enrichMetadata && item.optString("remarks").isEmpty()) item.put("remarks", plain(scalar(row, "vod_remarks"), 512))
                if (item.optString("backdrop").isEmpty() && backdrop.isNotEmpty()) item.put("backdrop", backdrop)
            }
        }
        request.check()
    }

    private fun fetch(operation: String, params: LinkedHashMap<String, String>, maxBytes: Int, request: VodRequest): JSONObject {
        val builder = source.api.newBuilder()
        if (operation != "home") {
            listOf("t", "ids", "wd", "quick", "pg", "extend", "f").forEach(builder::removeAllQueryParameters)
            if (params.containsKey("ac")) builder.removeAllQueryParameters("ac")
            if (source.ext.isNotEmpty()) params["extend"] = source.ext
        }
        val usePost = operation != "home" && source.ext.length > 1000
        val body = if (usePost) FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build() else null
        if (!usePost) params.forEach { (key, value) -> builder.setQueryParameter(key, value) }
        val response = http.fetch(builder.build(), source.headers, request, maxBytes, body = body)
        return try {
            val root = JSONObject(String(response.bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
            if (root.has("list") && root.opt("list") !is JSONArray || root.has("class") && root.opt("class") !is JSONArray)
                throw VodException("INVALID_RESPONSE", "来源数据结构无效")
            if (!root.has("list") && !root.has("class")) throw VodException("INVALID_RESPONSE", "来源未返回影视目录")
            if (root.has("code") && scalar(root, "code") !in listOf("1", "200"))
                throw VodException("SOURCE_ERROR", "来源返回错误状态")
            root
        } catch (e: VodException) { throw e }
        catch (_: Exception) { throw VodException("INVALID_RESPONSE", "来源未返回有效 JSON") }
    }

    companion object {
        private val CJK_TITLE_SPACES = Regex("""(?<=[㐀-鿿])[\s\p{Zs}]+(?=[㐀-鿿])""")
        internal fun workId(sourceId: String, vodId: String) = "vod:" + digest(sourceId, vodId)
        internal fun playbackId(sourceId: String, vodId: String, lineId: String, episodeKey: String) = "vod:" + digest(sourceId, vodId, lineId, episodeKey)
        private fun digest(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun normalized(value: String): String = Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)
        internal fun scalar(row: JSONObject, key: String): String = when (val value = row.opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> ""
        }
        private fun positive(row: JSONObject, key: String): Int? = scalar(row, key).toIntOrNull()?.takeIf { it > 0 }
        private fun list(root: JSONObject): JSONArray = root.optJSONArray("list") ?: JSONArray()
        private fun plain(value: String, maxLength: Int): String = Jsoup.parse(value).text().take(maxLength)

        internal fun playlists(from: String, urls: String): List<VodLine> {
            if (urls.isBlank()) return emptyList()
            val names = from.split("$$$")
            val groups = urls.split("$$$")
            if (maxOf(names.size, groups.size) > 64) throw VodException("PAYLOAD_LIMIT", "播放线路过多")
            if (names.any { it.length > 1024 }) throw VodException("PAYLOAD_LIMIT", "播放线路名称过长")
            val normalizedNames = names.map(::normalized)
            var episodeCount = 0
            return groups.mapIndexedNotNull { lineIndex, group ->
                val entries = group.split('#')
                episodeCount += entries.size
                if (episodeCount > 2000) throw VodException("PAYLOAD_LIMIT", "剧集条目过多")
                val rawFlag = names.getOrNull(lineIndex).orEmpty()
                val flag = normalized(rawFlag)
                val stableLine = flag.isNotEmpty() && normalizedNames.count { it == flag } == 1
                val lineId = if (stableLine) "line:" + digest(flag) else "line:weak:" + digest(lineIndex.toString(), rawFlag, group)
                val parsed = entries.mapIndexedNotNull { index, entry ->
                    val separator = entry.indexOf('$')
                    val rawLabel = if (separator >= 0) entry.substring(0, separator).trim() else ""
                    if (rawLabel.length > 1024) throw VodException("PAYLOAD_LIMIT", "剧集名称过长")
                    val locator = if (separator >= 0) entry.substring(separator + 1).trim() else entry.trim()
                    if (locator.isEmpty()) null else Triple(rawLabel, locator, index)
                }
                val labels = parsed.map { normalized(it.first) }
                val labelCounts = labels.groupingBy { it }.eachCount()
                val episodes = parsed.map { (rawLabel, locator, index) ->
                    val label = normalized(rawLabel)
                    val stable = stableLine && label.isNotEmpty() && labelCounts[label] == 1
                    val key = if (stable) "episode:" + digest(label) else "episode:weak:" + digest(index.toString(), rawLabel, locator)
                    VodEpisode(key, rawLabel.ifBlank { String.format(Locale.ROOT, "%02d", index + 1) }, stable, locator)
                }
                if (episodes.isEmpty()) null else VodLine(lineId, rawFlag.trim().ifBlank { "线路 ${lineIndex + 1}" }, stableLine, episodes)
            }
        }
    }
}

internal data class VodHttpResult(val url: HttpUrl, val bytes: ByteArray, val mime: String, val elapsedMs: Long = 1)

/** Manual redirects ensure API credentials cannot leak through arbitrary cross-origin headers. */
internal class VodHttp(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun fetch(url: HttpUrl, headers: Map<String, String>, request: VodRequest, maxBytes: Int,
              body: FormBody? = null, prefixOnly: Boolean = false, range: String? = null): VodHttpResult {
        val started = System.nanoTime()
        var target = url
        var currentBody = body
        var currentHeaders = headers
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
            client.callTimeoutMillis.toLong().takeIf { it > 0 } ?: 20_000L)
        repeat(6) { redirect ->
            request.check()
            val builder = Request.Builder().url(target)
            currentHeaders.forEach { (key, value) -> builder.header(key, value) }
            if (range != null) builder.header("Range", range)
            currentBody?.let { builder.post(it) }
            val call = client.newCall(builder.build())
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw VodException("NETWORK_TIMEOUT", "来源请求超时")
            call.timeout().timeout(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)
            request.attach(call)
            try {
                call.execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        if (redirect == 5) throw VodException("REDIRECT_LIMIT", "来源重定向次数过多")
                        val next = response.header("Location")?.let { target.resolve(it) }
                            ?: throw VodException("INVALID_RESPONSE", "来源重定向地址无效")
                        if (next.username.isNotEmpty() || next.password.isNotEmpty() || next.toString().length > 8192)
                            throw VodException("INVALID_RESPONSE", "来源重定向地址无效")
                        if (!sameOrigin(target, next)) {
                            if (currentBody != null && response.code in listOf(307, 308))
                                throw VodException("UNSAFE_REDIRECT", "来源要求向另一站点转发扩展数据，已停止")
                            currentHeaders = currentHeaders.filterKeys { it.equals("User-Agent", true) || it.equals("Referer", true) || it.equals("Origin", true) }
                        }
                        if (response.code in listOf(301, 302, 303)) currentBody = null
                        target = next
                    } else {
                        if (!response.isSuccessful) throw VodException("HTTP_${response.code}", "来源请求失败（HTTP ${response.code}）")
                        val responseBody = response.body
                        if (!prefixOnly && responseBody.contentLength() > maxBytes) throw VodException("PAYLOAD_LIMIT", "来源响应超过容量限制")
                        val bytes = responseBody.byteStream().use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                request.check()
                                val allowance = if (prefixOnly) maxBytes - out.size() else maxBytes + 1 - out.size()
                                if (allowance <= 0) break
                                val n = input.read(buffer, 0, minOf(buffer.size, allowance))
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                if (!prefixOnly && out.size() > maxBytes) throw VodException("PAYLOAD_LIMIT", "来源响应超过容量限制")
                            }
                            out.toByteArray()
                        }
                        request.check()
                        return VodHttpResult(target, bytes, response.header("Content-Type") ?: responseBody.contentType()?.toString().orEmpty(),
                            maxOf(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)))
                    }
                }
            } catch (e: VodException) { throw e }
            catch (_: java.io.InterruptedIOException) {
                request.check()
                throw VodException("NETWORK_TIMEOUT", "来源请求超时")
            }
            catch (_: java.net.SocketException) {
                request.check()
                throw VodException("NETWORK_DISCONNECTED", "来源网络连接中断")
            }
            catch (_: Exception) {
                request.check()
                throw VodException("NETWORK_ERROR", "来源网络请求失败")
            } finally { request.detach(call) }
        }
        throw VodException("REDIRECT_LIMIT", "来源重定向次数过多")
    }

    companion object {
        internal fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}
