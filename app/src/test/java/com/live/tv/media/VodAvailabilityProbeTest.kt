package com.live.tv.media

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodAvailabilityProbeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val requests = CopyOnWriteArrayList<Request>()
    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\npart.ts\n#EXT-X-ENDLIST\n"
    private fun ts() = ByteArray(4096).apply { this[0] = 0x47; this[188] = 0x47 }
    private fun answer(request: Request, data: ByteArray, mime: String = "application/octet-stream", code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(data.toResponseBody(mime.toMediaType())).build()
    private fun manifest(request: Request, value: String = media) = answer(request, value.toByteArray(), "application/vnd.apple.mpegurl")
    private fun repository(respond: (okhttp3.Interceptor.Chain) -> Response): Pair<VodRepository, JSONObject> {
        val repository = VodRepository(VodSourceStore(File(temporary.newFolder(), "vod.json"), { it }, { it }),
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); requests.add(request)
                if (request.url.host == "api.example") {
                    val json = JSONObject().put("list", JSONArray().put(JSONObject().put("vod_id", "film")
                        .put("vod_name", "示例").put("vod_play_from", "主线")
                        .put("vod_play_url", "正片\$https://cdn.example/root.m3u8")))
                    answer(request, json.toString().toByteArray(), "application/json")
                } else respond(chain)
            }.build())
        val id = repository.command("vod_add_source", mapOf("name" to "测试", "url" to "https://api.example/api",
            "header" to "{\"User-Agent\":\"Fixture\",\"Authorization\":\"synthetic-secret\"}")).getString("id")
        val detail = repository.detail(id, "film", VodRequest())
        val line = detail.getJSONArray("lines").getJSONObject(0)
        val ref = JSONObject().put("sourceId", id).put("vodId", "film").put("revision", 1).put("epoch", 1)
            .put("lineId", line.getString("id")).put("episodeKey", line.getJSONArray("episodes").getJSONObject(0).getString("key"))
        return repository to ref
    }
    private fun expect(code: String, operation: () -> Any?) {
        try { operation(); fail("Expected $code") } catch (error: VodException) {
            assertEquals(code, error.code); assertFalse(error.message.orEmpty().contains("synthetic-secret"))
        }
    }
    private fun mediaRequests() = requests.filter { it.url.host != "api.example" }

    @Test fun validManifestWithForbiddenFirstSegmentIsRejectedButPlaybackHeaderProbeStaysLight() {
        val (repository, ref) = repository { chain ->
            if (chain.request().url.encodedPath == "/part.ts") answer(chain.request(), byteArrayOf(), code = 403)
            else manifest(chain.request())
        }
        repository.resolve(ref, VodRequest())
        assertEquals(listOf("/root.m3u8"), mediaRequests().map { it.url.encodedPath })
        expect("HTTP_403") { repository.resolve(ref, VodRequest(), true) }
        assertEquals("/part.ts", mediaRequests().last().url.encodedPath)
    }

    @Test fun masterPlaylistFollowsFirstVariantAndChecksBoundedSegmentWithoutPrivateApiHeaders() {
        val (repository, ref) = repository { chain -> when (chain.request().url.encodedPath) {
            "/root.m3u8" -> manifest(chain.request(), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nquality/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000\nother.m3u8\n")
            "/quality/index.m3u8" -> manifest(chain.request())
            else -> answer(chain.request(), ts(), "video/mp2t")
        } }
        repository.resolve(ref, VodRequest(), true)
        val fetched = mediaRequests()
        assertEquals(listOf("/root.m3u8", "/quality/index.m3u8", "/quality/part.ts"), fetched.map { it.url.encodedPath })
        assertEquals("bytes=0-4095", fetched.last().header("Range"))
        fetched.forEach { assertNull(it.header("Authorization")); assertEquals("Fixture", it.header("User-Agent")) }
    }

    @Test fun aes128KeyAndInitializationMapAreCheckedBeforeFirstSegment() {
        val (repository, ref) = repository { chain -> when (chain.request().url.encodedPath) {
            "/root.m3u8" -> manifest(chain.request(), "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000001\n#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"50@100\"\n#EXTINF:5,\npart.ts\n")
            "/key.bin" -> answer(chain.request(), ByteArray(16) { 7 })
            "/init.mp4" -> answer(chain.request(), ByteArray(50).apply { "ftyp".toByteArray().copyInto(this, 4) })
            else -> answer(chain.request(), ByteArray(4096) { 7 })
        } }
        repository.resolve(ref, VodRequest(), true)
        val fetched = mediaRequests()
        assertEquals(listOf("/root.m3u8", "/key.bin", "/init.mp4", "/part.ts"), fetched.map { it.url.encodedPath })
        assertEquals("bytes=100-149", fetched[2].header("Range"))
        assertEquals("bytes=0-4095", fetched.last().header("Range"))
    }

    @Test fun invalidKeyAndDrmRejectBeforeFetchingSegment() {
        var method = "AES-128"
        val (repository, ref) = repository { chain ->
            if (chain.request().url.encodedPath == "/root.m3u8") manifest(chain.request(),
                "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-KEY:METHOD=$method,URI=\"key.bin\"\n#EXTINF:5,\npart.ts\n")
            else answer(chain.request(), ByteArray(17))
        }
        expect("PAYLOAD_LIMIT") { repository.resolve(ref, VodRequest(), true) }
        assertFalse(mediaRequests().any { it.url.encodedPath.endsWith(".ts") })
        method = "SAMPLE-AES"
        val count = mediaRequests().size
        expect("UNSUPPORTED_MEDIA") { repository.resolve(ref, VodRequest(), true) }
        assertEquals(count + 1, mediaRequests().size)
    }

    @Test fun nestedPlaylistsAndInjectedCredentialsCannotCauseUnboundedOrUnsafeRequests() {
        var unsafe = false
        val (repository, ref) = repository { chain ->
            val path = chain.request().url.encodedPath
            val next = if (unsafe) "https://user:synthetic-secret@other.example/index.m3u8" else "next$path"
            manifest(chain.request(), "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n$next\n")
        }
        expect("PAYLOAD_LIMIT") { repository.resolve(ref, VodRequest(), true) }
        assertEquals(4, mediaRequests().size)
        unsafe = true
        expect("UNSUPPORTED_MEDIA") { repository.resolve(ref, VodRequest(), true) }
        assertEquals(5, mediaRequests().size)
        assertTrue(mediaRequests().all { it.url.host == "cdn.example" })
    }

    @Test fun segmentIsCancelledByParentAndDeadlineIsSharedAcrossAllProbeRequests() {
        val started = CountDownLatch(1)
        var segmentCall: Call? = null
        var deadlineTest = false
        val (repository, ref) = repository { chain ->
            if (chain.request().url.encodedPath == "/root.m3u8") manifest(chain.request()) else {
                if (deadlineTest) Thread.sleep(750) else {
                    segmentCall = chain.call(); started.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(5)
                }
                answer(chain.request(), ts(), "video/mp2t")
            }
        }
        val parent = VodRequest()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                try { repository.resolve(ref, parent, true); "unexpected" } catch (error: VodException) { error.code }
            }
            assertTrue(started.await(3, TimeUnit.SECONDS)); parent.cancel()
            assertEquals("CANCELLED", future.get(2, TimeUnit.SECONDS)); assertTrue(segmentCall!!.isCanceled())
            deadlineTest = true
            expect("SEARCH_TIMEOUT") { repository.resolve(ref, VodRequest().bounded(500), true) }
        } finally { parent.cancel(); executor.shutdownNow() }
    }

    @Test fun largeSuccessfulSegmentOnlyReadsFourKiBAndRejectsHtmlPayloads() {
        var html = false
        val (repository, ref) = repository { chain ->
            if (chain.request().url.encodedPath == "/root.m3u8") manifest(chain.request())
            else if (html) answer(chain.request(), "<html>synthetic-secret</html>".toByteArray(), "text/html")
            else answer(chain.request(), ByteArray(1_000_000).apply { ts().copyInto(this) }, "video/mp2t")
        }
        repository.resolve(ref, VodRequest(), true)
        assertEquals("bytes=0-4095", mediaRequests().last().header("Range"))
        html = true
        expect("UNSUPPORTED_MEDIA") { repository.resolve(ref, VodRequest(), true) }
    }

    @Test fun measuredProbeReportsActualBoundedSegmentBytesAndLatencyWithoutPrivateLocators() {
        val (repository, ref) = repository { chain ->
            if (chain.request().url.encodedPath == "/root.m3u8") manifest(chain.request()) else {
                Thread.sleep(35)
                answer(chain.request(), ByteArray(1_000_000).apply { ts().copyInto(this) }, "video/mp2t")
            }
        }
        val probe = repository.resolve(ref, VodRequest(), true).getJSONObject("probe")
        assertEquals(4096, probe.getInt("sampleBytes"))
        assertTrue(probe.getLong("sampleElapsedMs") >= 25)
        assertTrue(probe.getLong("elapsedMs") >= probe.getLong("sampleElapsedMs"))
        assertEquals("bounded-startup-sample", probe.getString("method"))
        assertFalse(probe.toString().contains("cdn.example"))
        assertFalse(probe.toString().contains("synthetic-secret"))
        assertFalse(repository.resolve(ref, VodRequest()).has("probe"))
    }

    @Test fun aSourceChangedDuringSampleCannotPublishSuccessfulMetrics() {
        lateinit var subject: VodRepository
        lateinit var ref: JSONObject
        val pair = repository { chain ->
            if (chain.request().url.encodedPath == "/root.m3u8") manifest(chain.request()) else {
                subject.command("vod_update_source", mapOf("id" to ref.getString("sourceId"), "url" to "https://replacement.example/api"))
                answer(chain.request(), ts(), "video/mp2t")
            }
        }
        subject = pair.first; ref = pair.second
        expect("STALE_SELECTION") { subject.resolve(ref, VodRequest(), true) }
    }

    @Test fun speedSampleReadsAtMostSixtyFourKiBAndRespectsSmallerByteRanges() {
        var byteRange = false
        val (repository, ref) = repository { chain ->
            if (chain.request().url.encodedPath == "/root.m3u8") manifest(chain.request(),
                "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5,\n" +
                    (if (byteRange) "#EXT-X-BYTERANGE:376@100\n" else "") + "part.ts\n")
            else answer(chain.request(), ByteArray(1_000_000).apply { ts().copyInto(this) }, "video/mp2t")
        }
        val probe = repository.resolve(ref, VodRequest(), true, 65_536).getJSONObject("probe")
        assertEquals(65_536, probe.getInt("sampleBytes"))
        assertEquals("bytes=0-65535", mediaRequests().last().header("Range"))
        byteRange = true
        val shorter = repository.resolve(ref, VodRequest(), true, 65_536).getJSONObject("probe")
        assertEquals(376, shorter.getInt("sampleBytes"))
        assertEquals("bytes=100-475", mediaRequests().last().header("Range"))
    }

    @Test fun mp4SpeedProbeMeasuresTheRealHeaderSampleWithoutExtraDownloads() {
        val (repository, ref) = repository { chain ->
            Thread.sleep(25)
            answer(chain.request(), ByteArray(1_000_000).apply {
                this[3] = 24
                "ftyp".toByteArray().copyInto(this, 4)
            }, "video/mp4")
        }
        val probe = repository.resolve(ref, VodRequest(), true, 65_536).getJSONObject("probe")
        assertEquals(65_536, probe.getInt("sampleBytes"))
        assertTrue(probe.getLong("sampleElapsedMs") >= 20)
        assertEquals(1, mediaRequests().size)
        assertEquals("bytes=0-65535", mediaRequests().single().header("Range"))
    }
}
