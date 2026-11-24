package com.live.tv.music.cache

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicAssetDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `download copies a complete audio response and reports completion only after eof`() = runTest {
        val target = temporary.newFile("audio")
        val progress = mutableListOf<Double>()
        MusicAssetDownloader(client(200, "audio/mpeg")).download(URL, target, false, 1_024, progress::add)
        assertArrayEquals(OfflineMusicCacheTest.AUDIO, target.readBytes())
        assertEquals(1.0, progress.last(), 0.0)
    }

    @Test
    fun `partial content and html responses cannot masquerade as a complete audio download`() = runTest {
        for ((status, mime) in listOf(206 to "audio/mpeg", 200 to "text/html")) {
            val target = temporary.newFolder().resolve("audio")
            expectIOException { MusicAssetDownloader(client(status, mime)).download(URL, target, false, 1_024) {} }
            assertFalse(target.exists())
        }
    }

    @Test
    fun `declared oversized audio is rejected before opening a cache file`() = runTest {
        val target = temporary.newFolder().resolve("audio")
        expectIOException { MusicAssetDownloader(client(200, "audio/mpeg")).download(URL, target, false, 4) {} }
        assertFalse(target.exists())
    }

    private fun client(code: Int, mime: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test response")
                .body(OfflineMusicCacheTest.AUDIO.toResponseBody(mime.toMediaType())).build()
        }.build()

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected IOException") } catch (_: IOException) { /* expected */ }
    }

    private companion object { val URL = "https://m701.music.126.net/song.mp3".toHttpUrl() }
}
