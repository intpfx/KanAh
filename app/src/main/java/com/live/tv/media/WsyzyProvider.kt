package com.live.tv.media

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Wsyzy disables MacCMS search; its website offers a bounded, non-paginated suggest API. */
internal class WsyzyProvider(private val source: VodSource, client: OkHttpClient) : VodProvider {
    private val macCms = MacCmsProvider(source, client)
    private val http = VodHttp(client)

    override fun catalog(categoryId: String, query: String, page: Int, request: VodRequest): JSONObject {
        request.check()
        if (query.isEmpty()) return macCms.catalog(categoryId, query, page, request)
        if (page !in 1..10_000 || categoryId.length > 256 || query.length > 200)
            throw VodException("INVALID_QUERY", "查询参数无效")
        if (!source.searchable) throw VodException("SEARCH_DISABLED", "此来源已关闭搜索")
        // The upstream ignores both pg and page. Never fetch the same result window a second time.
        if (page > 1) return result(JSONArray(), page, request, false)

        val url = SEARCH_URL.toHttpUrl().newBuilder().addQueryParameter("mid", "1")
            .addQueryParameter("wd", query).addQueryParameter("limit", (SEARCH_LIMIT + 1).toString()).build()
        // API secrets and extension parameters belong to api.wsyzy.net, not to the website.
        val headers = source.headers.filterKeys { it.equals("User-Agent", true) }
        val response = http.fetch(url, headers, request, 2 * 1024 * 1024)
        val root = try { JSONObject(String(response.bytes, Charsets.UTF_8).removePrefix("\uFEFF")) }
        catch (_: Exception) { throw VodException("INVALID_RESPONSE", "来源未返回有效 JSON") }
        if (root.has("code") && MacCmsProvider.scalar(root, "code") !in setOf("1", "200"))
            throw VodException("SOURCE_ERROR", "来源返回错误状态")
        val rows = root.optJSONArray("list") ?: throw VodException("INVALID_RESPONSE", "来源未返回影视目录")
        if (rows.length() > 200) throw VodException("PAYLOAD_LIMIT", "目录条目过多")

        val converted = JSONArray()
        val seen = mutableSetOf<String>()
        for (index in 0 until rows.length()) {
            request.check()
            val row = rows.optJSONObject(index) ?: continue
            val id = MacCmsProvider.scalar(row, "id")
            if (id.isBlank() || id.length > 256 || !seen.add(id)) continue
            if (converted.length() == SEARCH_LIMIT) continue
            val picture = MacCmsProvider.scalar(row, "pic").takeIf { it.isNotBlank() && it.length <= 8192 }
                ?.let { response.url.resolve(it)?.toString() }.orEmpty()
            converted.put(JSONObject().put("vod_id", id).put("vod_name", MacCmsProvider.scalar(row, "name"))
                .put("vod_pic", picture))
        }
        val total = MacCmsProvider.scalar(root, "total").toLongOrNull()?.takeIf { it >= 0 }
        val truncated = seen.size > SEARCH_LIMIT || (total != null && total > converted.length()) ||
            (total == null && rows.length() >= SEARCH_LIMIT)
        return result(converted, page, request, truncated)
    }

    private fun result(rows: JSONArray, page: Int, request: VodRequest, truncated: Boolean): JSONObject {
        val normalized = JSONObject().put("list", rows).put("page", page).put("pagecount", 1).put("limit", SEARCH_LIMIT)
        // Suggest omits year/type even when it supplies a poster; enrich for existing cross-source grouping.
        return macCms.catalogResult(normalized, page, request, enrichMetadata = true)
            .put("hasMore", false).put("searchLimit", SEARCH_LIMIT).put("truncated", truncated)
    }

    override fun detail(vodId: String, request: VodRequest): VodParsedDetail = macCms.detail(vodId, request)
    override fun probe(locator: String, request: VodRequest): JSONObject = macCms.probe(locator, request)

    companion object {
        internal const val SEARCH_LIMIT = 50
        private const val SEARCH_URL = "https://wsyzy.cc/index.php/ajax/suggest"

        internal fun supports(source: VodSource): Boolean = source.api.scheme == "https" && source.api.port == 443 &&
            source.api.host == "api.wsyzy.net" && source.api.encodedPath.removeSuffix("/") == "/api.php/provide/vod"
    }
}
