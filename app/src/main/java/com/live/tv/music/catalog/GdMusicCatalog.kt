package com.live.tv.music.catalog

import android.util.JsonReader
import java.io.ByteArrayInputStream
import com.live.tv.BuildConfig
import android.util.JsonToken
import com.live.tv.music.network.readCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException

internal const val MAX_GD_RESPONSE_BYTES = 512 * 1024

sealed class CatalogException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {
    class Network(
        cause: IOException,
    ) : CatalogException("GD catalog is unavailable", cause)

    class Http(val statusCode: Int) : CatalogException("GD catalog returned HTTP $statusCode")

    class RejectedAsset(
        val assetUrl: String,
    ) : CatalogException("GD returned an untrusted media asset: $assetUrl")

    class InvalidResponse(
        message: String,
        cause: Throwable? = null,
    ) : CatalogException(message, cause)
}

/** Stable public diagnostics: never expose upstream bodies, URLs or exception text. */
internal fun musicSearchFailureMessage(error: Exception): String = when (error) {
    is CatalogException.Http -> when (error.statusCode) {
        401, 403 -> "音乐搜索访问受限（HTTP ${error.statusCode}），请稍后重试。"
        429 -> "音乐搜索请求过于频繁（HTTP 429），请稍后重试。"
        in 500..599 -> "音乐服务暂不可用（HTTP ${error.statusCode}），请稍后重试。"
        else -> "音乐搜索失败（HTTP ${error.statusCode}），请稍后重试。"
    }
    is CatalogException.Network -> if (error.cause is java.io.InterruptedIOException) {
        "音乐搜索连接超时，请稍后重试。"
    } else {
        "音乐服务连接失败，请检查网络后重试。"
    }
    is CatalogException.InvalidResponse -> "音乐服务返回异常，请稍后重试。"
    else -> "音乐搜索暂不可用，请稍后重试。"
}

data class CatalogTrack(
    val id: String,
    val name: String,
    val artists: List<String>,
    val album: String,
    val pictureId: String,
    val lyricId: String,
)

data class CatalogLyrics(
    val original: String,
    val translated: String,
)

interface MusicCatalog {
    suspend fun search(query: String): List<CatalogTrack>

    /** Page one by default; implementations that cannot paginate may return the same page. */
    suspend fun searchPage(query: String, page: Int): List<CatalogTrack> = search(query)

    suspend fun getLyrics(lyricId: String): CatalogLyrics

    suspend fun resolveArtworkUrl(pictureId: String): HttpUrl

    suspend fun resolveAudioUrl(trackId: String): HttpUrl
}

class GdMusicCatalog(
    private val callFactory: Call.Factory,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val cronet: CronetMusicClient? = null,
) : MusicCatalog {
    override suspend fun search(query: String): List<CatalogTrack> = searchPage(query, 1)

    override suspend fun searchPage(query: String, page: Int): List<CatalogTrack> = request(
        parameters = mapOf(
            "types" to "search",
            "source" to "netease",
            "name" to query.trim(),
            "count" to "8",
            "pages" to page.coerceAtLeast(1).toString(),
        ),
        parse = ::parseSearchResults,
    )

    override suspend fun getLyrics(lyricId: String): CatalogLyrics = request(
        parameters = mapOf(
            "types" to "lyric",
            "source" to "netease",
            "id" to lyricId.trim(),
        ),
        parse = ::parseLyrics,
    )

    override suspend fun resolveArtworkUrl(pictureId: String): HttpUrl {
        val direct = pictureId.trim().toHttpUrlOrNull()
        if (direct != null) {
            val trusted = direct.scheme == "https" &&
                (direct.host == NETEASE_ASSET_HOST || direct.host.endsWith(".$NETEASE_ASSET_HOST"))
            if (trusted) return direct
            throw CatalogException.RejectedAsset(pictureId)
        }
        return try {
            artworkUrl(pictureId, "2000")
    } catch (cancelled: CancellationException) {
        throw cancelled
        } catch (_: IOException) {
            artworkUrl(pictureId, "500")
        }
    }

    private suspend fun artworkUrl(pictureId: String, size: String): HttpUrl = request(
        parameters = mapOf(
            "types" to "pic",
            "source" to "netease",
            "id" to pictureId.trim(),
            "size" to size,
        ),
        parse = ::parseAssetUrl,
    )

    override suspend fun resolveAudioUrl(trackId: String): HttpUrl = request(
        parameters = mapOf(
            "types" to "url",
            "source" to "netease",
            "id" to trackId.trim(),
            "br" to "128",
        ),
        parse = ::parseAssetUrl,
    )

    private suspend fun <T> request(
        parameters: Map<String, String>,
        parse: (JsonReader) -> T,
    ): T {
        val url = baseUrl.newBuilder().apply {
            parameters.forEach(::addQueryParameter)
        }.build()
        val headers = linkedMapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Referer" to "https://music.gdstudio.xyz/",
            "Origin" to "https://music.gdstudio.xyz",
        )
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        return try {
            if (cronet != null) {
                val response = withTimeoutOrNull(20_000) { cronet.get(url.toString(), headers) }
                    ?: throw CatalogException.Network(java.net.SocketTimeoutException("catalog request timed out"))
                if (response.statusCode !in 200..299) throw CatalogException.Http(response.statusCode)
                response.body.openBoundedJsonReader().use(parse)
            } else {
                callFactory.newCall(request).readCancellable { response ->
                    if (!response.isSuccessful) {
                        throw CatalogException.Http(response.code)
                    }
                    try {
                        response.body.openBoundedJsonReader().use(parse)
                    } catch (error: CatalogException) {
                        throw error
                    } catch (error: IOException) {
                        throw error
                    } catch (error: Exception) {
                        throw CatalogException.InvalidResponse("Could not parse the catalog response", error)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CatalogException) {
            throw error
        } catch (error: IOException) {
            throw CatalogException.Network(error)
        } catch (error: Exception) {
            throw CatalogException.InvalidResponse("Could not read the catalog response", error)
        }
    }

    private fun ByteArray.openBoundedJsonReader(): JsonReader {
        if (size > MAX_GD_RESPONSE_BYTES) {
            throw CatalogException.InvalidResponse("GD catalog response exceeds size limit")
        }
        return JsonReader(ByteArrayInputStream(this).reader(Charsets.UTF_8))
    }

    private fun parseSearchResults(reader: JsonReader): List<CatalogTrack> {
        check(reader.peek() == JsonToken.BEGIN_ARRAY) { "Expected a search result array" }
        val tracks = mutableListOf<CatalogTrack>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            parseTrack(reader)?.let { track ->
                if (tracks.size < MAX_SEARCH_RESULTS) tracks += track
            }
        }
        reader.endArray()
        return tracks
    }

    private fun parseTrack(reader: JsonReader): CatalogTrack? {
        var id = ""
        var name = ""
        var artists = emptyList<String>()
        var album = ""
        var pictureId = ""
        var lyricId = ""

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.readBoundedText(MAX_TRACK_ID_LENGTH)
                "name" -> name = reader.readBoundedText(MAX_TRACK_NAME_LENGTH)
                "artist" -> artists = reader.readArtists()
                "album" -> album = reader.readBoundedText(MAX_ALBUM_LENGTH)
                "pic_id" -> pictureId = reader.readBoundedText(MAX_ASSET_ID_LENGTH)
                "lyric_id" -> lyricId = reader.readBoundedText(MAX_ASSET_ID_LENGTH)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (!TRACK_ID_PATTERN.matches(id) || name.isEmpty()) {
            return null
        }
        return CatalogTrack(id, name, artists, album, pictureId, lyricId)
    }

    private fun parseLyrics(reader: JsonReader): CatalogLyrics {
        check(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected a lyric object" }
        var original = ""
        var translated = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "lyric" -> original = reader.readBoundedText(MAX_LYRIC_LENGTH)
                "tlyric" -> translated = reader.readBoundedText(MAX_LYRIC_LENGTH)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CatalogLyrics(original, translated)
    }

    private fun parseAssetUrl(reader: JsonReader): HttpUrl {
        check(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected an asset object" }
        var rawUrl = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "url" -> rawUrl = reader.readBoundedText(MAX_ASSET_URL_LENGTH)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val url = rawUrl.toHttpUrlOrNull()
        val trustedHost = url?.host == NETEASE_ASSET_HOST ||
            url?.host?.endsWith(".$NETEASE_ASSET_HOST") == true
        if (url == null || url.scheme != "https" || !trustedHost) {
            throw CatalogException.RejectedAsset(rawUrl)
        }
        return url
    }

    private fun JsonReader.readArtists(): List<String> {
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return emptyList()
        }
        val artists = mutableListOf<String>()
        beginArray()
        while (hasNext()) {
            val artist = readBoundedText(MAX_ARTIST_LENGTH)
            if (artist.isNotEmpty() && artists.size < MAX_ARTIST_COUNT) artists += artist
        }
        endArray()
        return artists
    }

    private fun JsonReader.readBoundedText(maximumLength: Int): String {
        if (peek() != JsonToken.STRING) {
            skipValue()
            return ""
        }
        return nextString().trim().take(maximumLength)
    }

    private fun ResponseBody.openBoundedJsonReader(): JsonReader {
        val declaredLength = contentLength()
        if (declaredLength > MAX_GD_RESPONSE_BYTES) {
            throw CatalogException.InvalidResponse("GD catalog response exceeds size limit")
        }

        val boundedBuffer = Buffer()
        val responseSource = source()
        var remaining = MAX_GD_RESPONSE_BYTES.toLong() + 1L
        while (remaining > 0L) {
            val read = responseSource.read(boundedBuffer, minOf(8_192L, remaining))
            if (read == -1L) break
            remaining -= read
        }
        if (boundedBuffer.size > MAX_GD_RESPONSE_BYTES) {
            throw CatalogException.InvalidResponse("GD catalog response exceeds size limit")
        }
        val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return JsonReader(boundedBuffer.inputStream().reader(charset))
    }

    private companion object {
        val DEFAULT_BASE_URL = BuildConfig.MUSIC_API_BASE_URL.toHttpUrl()
        const val NETEASE_ASSET_HOST = "music.126.net"
        const val BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.7445.0 Safari/537.36"
        const val MAX_TRACK_ID_LENGTH = 40
        const val MAX_SEARCH_RESULTS = 8
        val TRACK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_TRACK_ID_LENGTH}")
        const val MAX_TRACK_NAME_LENGTH = 160
        const val MAX_ARTIST_LENGTH = 120
        const val MAX_ARTIST_COUNT = 8
        const val MAX_ALBUM_LENGTH = 160
        const val MAX_ASSET_ID_LENGTH = 512
        const val MAX_ASSET_URL_LENGTH = 4_096
        const val MAX_LYRIC_LENGTH = 100_000
    }
}
