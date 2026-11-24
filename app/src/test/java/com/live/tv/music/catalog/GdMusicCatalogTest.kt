package com.live.tv.music.catalog

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GdMusicCatalogTest {
    @Test
    fun searchPinsNeteaseRequestAndKeepsPlayableTracksWithoutPresentationMetadata() = runTest {
        val requests = mutableListOf<Request>()
        val catalog = GdMusicCatalog(
            callFactory = respondingClient(requests) { request ->
                jsonResponse(
                    request,
                    """
                    [
                      {
                        "id": " 100 ",
                        "name": " 月亮 ",
                        "artist": [" Someone ", "", 42, " Another "],
                        "album": " Night ",
                        "pic_id": " 200 ",
                        "lyric_id": " 300 ",
                        "ignored": "upstream-only"
                      },
                      {
                        "id": "invalid-without-picture",
                        "name": "Audio-only presentation",
                        "artist": [],
                        "album": "",
                        "lyric_id": "301"
                      },
                      {
                        "id": "../../unsafe",
                        "name": "Rejected id",
                        "artist": [],
                        "album": ""
                      }
                    ]
                    """.trimIndent(),
                )
            },
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )

        val tracks = catalog.search("  月亮  ")

        assertEquals(
            listOf(
                CatalogTrack(
                    id = "100",
                    name = "月亮",
                    artists = listOf("Someone", "Another"),
                    album = "Night",
                    pictureId = "200",
                    lyricId = "300",
                ),
                CatalogTrack(
                    id = "invalid-without-picture",
                    name = "Audio-only presentation",
                    artists = emptyList(),
                    album = "",
                    pictureId = "",
                    lyricId = "301",
                ),
            ),
            tracks,
        )
        val request = requests.single()
        assertEquals("search", request.url.queryParameter("types"))
        assertEquals("netease", request.url.queryParameter("source"))
        assertEquals("8", request.url.queryParameter("count"))
        assertEquals("1", request.url.queryParameter("pages"))
        assertEquals("月亮", request.url.queryParameter("name"))
    }

    @Test
    fun getLyricsParsesOriginalAndTranslatedLrc() = runTest {
        val requests = mutableListOf<Request>()
        val catalog = GdMusicCatalog(
            callFactory = respondingClient(requests) { request ->
                jsonResponse(
                    request,
                    """
                    {
                      "lyric": "  [00:01.00]月亮  ",
                      "tlyric": "  [00:01.00]Moon  ",
                      "ignored": "upstream-only"
                    }
                    """.trimIndent(),
                )
            },
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )

        val lyrics = catalog.getLyrics(" 300 ")

        assertEquals(
            CatalogLyrics(
                original = "[00:01.00]月亮",
                translated = "[00:01.00]Moon",
            ),
            lyrics,
        )
        val request = requests.single()
        assertEquals("lyric", request.url.queryParameter("types"))
        assertEquals("netease", request.url.queryParameter("source"))
        assertEquals("300", request.url.queryParameter("id"))
    }

    @Test
    fun resolvesArtworkAndAudioAddressesFromTheirPinnedEndpoints() = runTest {
        val requests = mutableListOf<Request>()
        val catalog = GdMusicCatalog(
            callFactory = respondingClient(requests) { request ->
                val assetUrl = when (request.url.queryParameter("types")) {
                    "pic" -> "https://p1.music.126.net/cover.jpg"
                    "url" -> "https://m701.music.126.net/song.mp3"
                    else -> error("Unexpected request: ${request.url}")
                }
                jsonResponse(request, "{\"url\":\"$assetUrl\"}")
            },
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )

        val artwork = catalog.resolveArtworkUrl(" 200 ")
        val audio = catalog.resolveAudioUrl(" 100 ")

        assertEquals("https://p1.music.126.net/cover.jpg".toHttpUrl(), artwork)
        assertEquals("https://m701.music.126.net/song.mp3".toHttpUrl(), audio)
        val artworkRequest = requests[0]
        assertEquals("pic", artworkRequest.url.queryParameter("types"))
        assertEquals("netease", artworkRequest.url.queryParameter("source"))
        assertEquals("200", artworkRequest.url.queryParameter("id"))
        assertEquals("2000", artworkRequest.url.queryParameter("size"))
        val audioRequest = requests[1]
        assertEquals("url", audioRequest.url.queryParameter("types"))
        assertEquals("netease", audioRequest.url.queryParameter("source"))
        assertEquals("100", audioRequest.url.queryParameter("id"))
        assertEquals("128", audioRequest.url.queryParameter("br"))
    }

    @Test
    fun artworkFallsBackWhenHighResolutionLookupFails() = runTest {
        val requests = mutableListOf<Request>()
        val catalog = GdMusicCatalog(
            callFactory = respondingClient(requests) { request ->
                jsonResponse(request, if (request.url.queryParameter("size") == "2000") "{}"
                    else "{\"url\":\"https://p1.music.126.net/cover.jpg\"}")
            },
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )
        assertEquals("https://p1.music.126.net/cover.jpg".toHttpUrl(), catalog.resolveArtworkUrl("200"))
        assertEquals(listOf("2000", "500"), requests.map { it.url.queryParameter("size") })
    }

    @Test
    fun assetPolicyAcceptsOnlyHttpsNeteaseRootAndSubdomains() {
        listOf(
            "https://music.126.net/root.mp3",
            "https://m701.music.126.net/subdomain.mp3",
        ).forEach { safeUrl ->
            val catalog = assetCatalog(safeUrl)
            runTest {
                assertEquals(safeUrl.toHttpUrl(), catalog.resolveAudioUrl("100"))
            }
        }

        listOf(
            "http://m701.music.126.net/insecure.mp3",
            "https://evilmusic.126.net/lookalike.mp3",
            "https://music.126.net.attacker.example/suffix.mp3",
            "https://attacker.example/external.mp3",
        ).forEach { unsafeUrl ->
            val catalog = assetCatalog(unsafeUrl)
            val error = assertThrows(CatalogException.RejectedAsset::class.java) {
                runTest { catalog.resolveAudioUrl("100") }
            }
            assertEquals(unsafeUrl, error.assetUrl)
        }
    }

    @Test
    fun networkFailuresAreMappedToAnExplicitCatalogException() {
        val callFactory = OkHttpClient.Builder()
            .addInterceptor { throw IOException("offline") }
            .build()
        val catalog = GdMusicCatalog(
            callFactory = callFactory,
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )

        val error = assertThrows(CatalogException.Network::class.java) {
            runTest { catalog.search("月亮") }
        }

        assertEquals("offline", error.cause?.message)
    }

    @Test
    fun searchNeverAcceptsMoreThanEightTracks() = runTest {
        val rows = (1..12).joinToString(",") { index ->
            """{"id":"$index","name":"Track $index","artist":[],"album":"","pic_id":"","lyric_id":""}"""
        }
        val catalog = GdMusicCatalog(
            callFactory = respondingClient(mutableListOf()) { request ->
                jsonResponse(request, "[$rows]")
            },
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )

        assertEquals(8, catalog.search("tracks").size)
    }

    @Test
    fun oversizedCatalogResponsesFailBeforeJsonParsing() {
        val oversizedJson = "[" + " ".repeat(MAX_GD_RESPONSE_BYTES + 1) + "]"
        val catalog = GdMusicCatalog(
            callFactory = respondingClient(mutableListOf()) { request ->
                jsonResponse(request, oversizedJson)
            },
            baseUrl = "https://catalog.test/api.php".toHttpUrl(),
        )

        assertThrows(CatalogException.InvalidResponse::class.java) {
            runTest { catalog.search("oversized") }
        }
    }

    @Test
    fun httpFailuresRetainStatusWithoutParsingUpstreamHtml() {
        for (status in listOf(403, 429, 503)) {
            val catalog = GdMusicCatalog(
                respondingClient(mutableListOf()) { request ->
                    jsonResponse(request, "<html>private upstream detail</html>").newBuilder()
                        .code(status).build()
                },
                "https://catalog.test/api.php".toHttpUrl(),
            )
            val error = assertThrows(CatalogException.Http::class.java) {
                runTest { catalog.search("三体") }
            }
            assertEquals(status, error.statusCode)
        }
    }

    @Test
    fun userDiagnosticsDistinguishRestrictionRateLimitOutageAndTimeout() {
        assertEquals("音乐搜索访问受限（HTTP 403），请稍后重试。", musicSearchFailureMessage(CatalogException.Http(403)))
        assertEquals("音乐搜索请求过于频繁（HTTP 429），请稍后重试。", musicSearchFailureMessage(CatalogException.Http(429)))
        assertEquals("音乐服务暂不可用（HTTP 503），请稍后重试。", musicSearchFailureMessage(CatalogException.Http(503)))
        assertEquals("音乐搜索连接超时，请稍后重试。", musicSearchFailureMessage(CatalogException.Network(java.net.SocketTimeoutException("private URL"))))
        assertEquals("音乐服务连接失败，请检查网络后重试。", musicSearchFailureMessage(CatalogException.Network(IOException("private URL"))))
        assertEquals("音乐服务返回异常，请稍后重试。", musicSearchFailureMessage(CatalogException.InvalidResponse("private response")))
        assertEquals("音乐搜索暂不可用，请稍后重试。", musicSearchFailureMessage(IllegalStateException("private detail")))
    }

    private fun assetCatalog(assetUrl: String): GdMusicCatalog = GdMusicCatalog(
        callFactory = respondingClient(mutableListOf()) { request ->
            jsonResponse(request, "{\"url\":\"$assetUrl\"}")
        },
        baseUrl = "https://catalog.test/api.php".toHttpUrl(),
    )

    private fun respondingClient(
        requests: MutableList<Request>,
        response: (Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.request().let { request ->
                requests += request
                response(request)
            }
        }
        .build()

    private fun jsonResponse(request: Request, json: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(json.toResponseBody("application/json".toMediaType()))
        .build()
}
