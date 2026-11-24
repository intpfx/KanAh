package com.live.tv.media

import android.content.Context
import android.util.AtomicFile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class ImportedVodEpisode(val label: String, val url: HttpUrl)

internal data class VodSource(
    val id: String,
    val name: String,
    val api: HttpUrl,
    val headers: Map<String, String> = emptyMap(),
    val ext: String = "",
    val playUrl: String = "",
    val searchable: Boolean = true,
    val revision: Int = 1,
    val epoch: Int = 1,
    val importedEpisodes: List<ImportedVodEpisode> = emptyList()
) {
    fun publicJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("apiType", 1)
        .put("revision", revision).put("epoch", epoch).put("searchable", searchable)
        .put("requiresParser", playUrl.isNotEmpty())
        .apply {
            if (importedEpisodes.isEmpty()) put("url", api.newBuilder().query(null).fragment(null).build().toString())
            else put("importedEpisodeCount", importedEpisodes.size)
        }

    fun configJson(): JSONObject = JSONObject().put("url", api.toString()).put("header", JSONObject(headers))
        .put("ext", ext).put("playUrl", playUrl).apply {
            if (importedEpisodes.isNotEmpty()) put("importedEpisodes", JSONArray(importedEpisodes.map {
                JSONObject().put("label", it.label).put("url", it.url.toString())
            }))
        }
}

/** Config (including signed API URLs and headers) is encrypted before AtomicFile persistence. */
internal class VodSourceStore(
    file: File,
    private val seal: (String) -> String,
    private val open: (String) -> String
) {
    private val atomicFile = AtomicFile(file)
    private var sources = emptyList<VodSource>()
    private var loadFailed = false

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "vod-sources.json"),
        MediaSecrets()::seal,
        MediaSecrets()::open
    )

    init {
        if (file.exists() || File(file.path + ".bak").exists()) {
            try {
                val bytes = atomicFile.openRead().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 2 * 1024 * 1024) throw VodException("SOURCE_STORE", "点播来源文件过大")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                require(root.getInt("version") == 1)
                val rows = root.getJSONArray("sources")
                require(rows.length() <= 32)
                sources = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val config = JSONObject(open(row.getString("configSealed")))
                    VodSource(row.getString("id"), row.getString("name"), httpUrl(config.getString("url")),
                        headers(config.optJSONObject("header") ?: JSONObject()), config.optString("ext"),
                        config.optString("playUrl"), row.optBoolean("searchable", true), row.getInt("revision"), row.getInt("epoch"),
                        config.optJSONArray("importedEpisodes")?.let { episodes ->
                            (0 until episodes.length()).map { index ->
                                val episode = episodes.getJSONObject(index)
                                importedEpisode(episode.getString("label"), episode.getString("url"))
                            }.also { require(it.size in 1..MAX_IMPORTED_EPISODES) }
                        }.orEmpty())
                }
                require(sources.map { it.id }.distinct().size == sources.size)
                require(sources.all { it.id.isNotBlank() && it.revision > 0 && it.epoch > 0 })
            } catch (_: Exception) { loadFailed = true }
        }
    }

    @Synchronized fun state(): JSONObject = JSONObject().put("sources", JSONArray(sources.sortedBy { VodSourcePriority.rank(it.name) }.map { it.publicJson() }))
        .put("errorCode", if (loadFailed) "SOURCE_STORE" else "")

    @Synchronized fun source(id: String): VodSource {
        if (loadFailed) throw VodException("SOURCE_STORE", "点播来源读取失败，请保留原文件后恢复")
        return sources.find { it.id == id } ?: throw VodException("SOURCE_NOT_FOUND", "点播来源已移除")
    }

    @Synchronized fun add(form: Map<String, String>): VodSource {
        if (loadFailed) throw VodException("SOURCE_STORE", "点播来源读取失败，未覆盖原文件")
        val values = when (form["preset"]) {
            null -> form
            "xigua" -> {
                sources.firstOrNull { it.api.toString().trimEnd('/') == XIGUA_API.trimEnd('/') }?.let { return it }
                mapOf("name" to "西瓜资源站", "url" to XIGUA_API)
            }
            "wsyzy" -> {
                // Retry after a lost response, or another LAN client's click, must not add a duplicate.
                sources.firstOrNull(WsyzyProvider::supports)?.let { return it }
                mapOf("name" to "无损云", "url" to "https://api.wsyzy.net/api.php/provide/vod/")
            }
            else -> throw VodException("UNSUPPORTED_SOURCE", "此预设来源不受支持")
        }
        if (sources.size >= 32) throw VodException("SOURCE_LIMIT", "最多添加 32 个点播来源")
        if (values["apiType"] != null && values["apiType"] != "1") throw VodException("UNSUPPORTED_SOURCE", "当前阶段仅支持 JSON 采集源")
        val name = values["name"].orEmpty().trim()
        if (name.isEmpty() || name.length > 120) throw VodException("INVALID_SOURCE", "请填写 1–120 字的来源名称")
        val api = httpUrl(values["url"].orEmpty())
        val headerText = values["header"].orEmpty().ifBlank { "{}" }
        if (headerText.length > 16_384) throw VodException("INVALID_SOURCE", "请求头配置过大")
        val header = try { headers(JSONObject(headerText)) } catch (e: VodException) { throw e }
        catch (_: Exception) { throw VodException("INVALID_SOURCE", "请求头需要 JSON 对象") }
        val ext = values["ext"].orEmpty()
        val playUrl = values["playUrl"].orEmpty().trim()
        if (ext.length > 32_768 || playUrl.length > 8192) throw VodException("INVALID_SOURCE", "来源扩展配置过大")
        val source = VodSource(UUID.randomUUID().toString(), name, api, header, ext, playUrl,
            values["searchable"] !in listOf("0", "false"))
        save(sources + source)
        return source
    }

    @Synchronized fun importM3u8(title: String, input: String): VodSource {
        if (loadFailed) throw VodException("SOURCE_STORE", "点播来源读取失败，未覆盖原文件")
        val name = title.trim()
        if (name.isEmpty() || name.length > 120 || name.any(Char::isISOControl))
            throw VodException("INVALID_SOURCE", "请填写 1–120 字的影视名称")
        if (input.length > 512 * 1024) throw VodException("INVALID_SOURCE", "选集列表过大")
        val rows = input.lineSequence().map(String::trim).filter(String::isNotBlank).take(MAX_IMPORTED_EPISODES + 1).toList()
        if (rows.size !in 1..MAX_IMPORTED_EPISODES)
            throw VodException("INVALID_SOURCE", "请填写 1–${MAX_IMPORTED_EPISODES} 条 M3U8 地址")
        val episodes = rows.mapIndexed { index, row ->
            val fields = row.split('	', '|', limit = 2)
            val (label, url) = if (row.startsWith("https://", true) || row.startsWith("http://", true) || fields.size == 1)
                ("第 ${index + 1} 集" to row) else fields[0].trim() to fields[1].trim()
            importedEpisode(label, url)
        }
        sources.firstOrNull { it.name == name && it.importedEpisodes == episodes }?.let { return it }
        if (sources.size >= 32) throw VodException("SOURCE_LIMIT", "最多添加 32 个点播来源")
        val source = VodSource(UUID.randomUUID().toString(), name, episodes.first().url, importedEpisodes = episodes)
        save(sources + source)
        return source
    }

    @Synchronized fun remove(id: String) {
        source(id)
        save(sources.filterNot { it.id == id })
    }

    /** Changing the API keeps source/episode identity; outstanding selections must be reloaded. */
    @Synchronized fun updateEndpoint(form: Map<String, String>): VodSource {
        val previous = source(form["id"].orEmpty())
        if (previous.importedEpisodes.isNotEmpty()) throw VodException("INVALID_SOURCE", "导入的选集没有采集接口")
        val api = httpUrl(form["url"].orEmpty())
        if (previous.api == api) return previous
        if (previous.revision == Int.MAX_VALUE) throw VodException("SOURCE_STORE", "来源版本已达上限")
        val next = previous.copy(api = api, revision = previous.revision + 1)
        save(sources.map { if (it.id == next.id) next else it })
        return next
    }

    private fun save(next: List<VodSource>) {
        var stream: java.io.FileOutputStream? = null
        try {
            val rows = JSONArray(next.map { source -> source.publicJson().apply {
                remove("url"); remove("requiresParser"); put("configSealed", seal(source.configJson().toString()))
            } })
            val bytes = JSONObject().put("version", 1).put("sources", rows).toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > 2 * 1024 * 1024) throw VodException("SOURCE_STORE", "点播来源配置总量过大")
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
            sources = next
        } catch (_: Exception) {
            stream?.let { runCatching { atomicFile.failWrite(it) } }
            throw VodException("SOURCE_STORE", "点播来源保存失败，原配置已保留")
        }
    }

    companion object {
        private const val XIGUA_API = "https://xgzy8.tv/api.php/provide/vod/from/xiguam3u8/at/json/"
        private const val MAX_IMPORTED_EPISODES = 300

        private fun importedEpisode(label: String, value: String): ImportedVodEpisode {
            if (label.isBlank() || label.length > 160 || label.any(Char::isISOControl))
                throw VodException("INVALID_SOURCE", "选集名称须为 1–160 字")
            val url = httpUrl(value)
            if (!url.encodedPath.endsWith(".m3u8", ignoreCase = true))
                throw VodException("INVALID_SOURCE", "每行须包含一个 HTTP 或 HTTPS M3U8 地址")
            return ImportedVodEpisode(label, url)
        }


        internal fun httpUrl(value: String): HttpUrl {
            val url = value.takeIf { it.length <= 8192 }?.toHttpUrlOrNull()
                ?: throw VodException("INVALID_SOURCE", "来源地址须为 HTTP 或 HTTPS")
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null || url.toString().length > 8192)
                throw VodException("INVALID_SOURCE", "地址不能包含账号、密码或片段")
            return url
        }

        private fun headers(json: JSONObject): Map<String, String> {
            if (json.length() > 16) throw VodException("INVALID_SOURCE", "最多设置 16 个请求头")
            val result = linkedMapOf<String, String>()
            json.keys().forEach { name ->
                val value = json.opt(name)
                val lower = name.lowercase(java.util.Locale.ROOT)
                if (!name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) || lower in setOf("host", "connection", "content-length", "transfer-encoding", "range") || lower.startsWith("proxy-"))
                    throw VodException("INVALID_SOURCE", "此请求头不受支持")
                if (value !is String || value.length > 4096 || value.any { it.code < 32 || it.code >= 127 })
                    throw VodException("INVALID_SOURCE", "请求头值无效")
                if (result.keys.any { it.equals(name, true) }) throw VodException("INVALID_SOURCE", "请求头名称重复")
                result[name] = value
            }
            return result
        }
    }
}
