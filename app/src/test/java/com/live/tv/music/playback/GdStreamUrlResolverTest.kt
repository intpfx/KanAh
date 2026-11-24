package com.live.tv.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.live.tv.music.catalog.CatalogLyrics
import com.live.tv.music.catalog.CatalogTrack
import com.live.tv.music.catalog.MusicCatalog
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GdStreamUrlResolverTest {
    @Test
    fun `offline reopen resolves stable identity to private audio without any catalog request`() {
        val catalog = FakeCatalog(runtimeFailure = true)
        val local = Uri.parse("file:///data/user/0/com.live.tv.music/files/music-cache-v1/test/audio")
        val resolver = GdStreamUrlResolver(catalog, offlineAudio = { id -> if (id == "7") local else null })
        val stable = GdPlaybackUri.forTrack("7")
        val resolved = resolver.resolveDataSpec(DataSpec.Builder().setUri(stable).setPosition(321L).build())
        assertEquals(local, resolved.uri)
        assertEquals(321L, resolved.position)
        assertEquals(stable, resolver.resolveReportedUri(local))
        assertEquals(0, catalog.audioResolveCount)
    }

    @Test
    fun `resolution preserves range headers and hides temporary reported uri`() {
        val catalog = FakeCatalog()
        val resolver = GdStreamUrlResolver(catalog, elapsedRealtimeMillis = { 100L })
        val stableUri = GdPlaybackUri.forTrack("212412")
        val original = DataSpec.Builder()
            .setUri(stableUri)
            .setPosition(321L)
            .setLength(654L)
            .setKey("stable-key")
            .setHttpRequestHeaders(mapOf("X-Test" to "value"))
            .build()

        val first = resolver.resolveDataSpec(original)
        val second = resolver.resolveDataSpec(original)

        assertEquals(STREAM_URL.toString(), first.uri.toString())
        assertEquals(321L, first.position)
        assertEquals(654L, first.length)
        assertEquals("stable-key", first.key)
        assertEquals("value", first.httpRequestHeaders["X-Test"])
        assertEquals(stableUri, resolver.resolveReportedUri(first.uri))
        assertEquals(first.uri, second.uri)
        assertEquals(1, catalog.audioResolveCount)
    }

    @Test
    fun `invalidating a temporary url forces one fresh resolution`() {
        val catalog = FakeCatalog()
        val resolver = GdStreamUrlResolver(catalog)
        val spec = DataSpec(GdPlaybackUri.forTrack("212412"))

        resolver.resolveDataSpec(spec)
        resolver.invalidateTemporaryUrls()
        resolver.resolveDataSpec(spec)

        assertEquals(2, catalog.audioResolveCount)
    }

    @Test
    fun `resolver rejects malformed stable uri before catalog access`() {
        val catalog = FakeCatalog()
        val resolver = GdStreamUrlResolver(catalog)

        assertThrows(IOException::class.java) {
            resolver.resolveDataSpec(DataSpec(Uri.parse("smartisax-gd://attacker@netease/212412")))
        }
        assertEquals(0, catalog.audioResolveCount)
    }

    @Test
    fun `catalog runtime failures become loader io failures`() {
        val resolver = GdStreamUrlResolver(FakeCatalog(runtimeFailure = true))

        assertThrows(IOException::class.java) {
            resolver.resolveDataSpec(DataSpec(GdPlaybackUri.forTrack("212412")))
        }
    }

    @Test
    fun `retry policy finds temporary url failures through wrapper causes`() {
        val responseFailure = invalidResponse(403)

        assertEquals(
            403,
            GdTemporaryUrlRetryPolicy.responseCodeInCauseChain(IOException("wrapped", responseFailure)),
        )
    }

    @Test
    fun `first expiry per load task refreshes even after an earlier different error`() {
        val catalog = FakeCatalog()
        val resolver = GdStreamUrlResolver(catalog)
        val policy = GdTemporaryUrlRetryPolicy(resolver)
        val stableSpec = DataSpec(GdPlaybackUri.forTrack("212412"))
        resolver.resolveDataSpec(stableSpec)
        val loadTaskId = 42L
        val firstExpiry = loadErrorInfo(loadTaskId, invalidResponse(403), errorCount = 2)
        val secondExpiry = loadErrorInfo(loadTaskId, invalidResponse(410), errorCount = 3)

        assertEquals(0L, policy.getRetryDelayMsFor(firstExpiry))
        resolver.resolveDataSpec(stableSpec)
        assertEquals(2, catalog.audioResolveCount)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(secondExpiry))
    }

    private fun invalidResponse(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "response",
            null,
            emptyMap(),
            DataSpec(Uri.parse(STREAM_URL.toString())),
            byteArrayOf(),
        )

    private fun loadErrorInfo(
        loadTaskId: Long,
        error: IOException,
        errorCount: Int,
    ): LoadErrorHandlingPolicy.LoadErrorInfo = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo.Builder(loadTaskId, DataSpec(GdPlaybackUri.forTrack("212412")), 0L).build(),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        error,
        errorCount,
    )

    private class FakeCatalog(
        private val runtimeFailure: Boolean = false,
    ) : MusicCatalog {
        var audioResolveCount = 0
            private set

        override suspend fun search(query: String): List<CatalogTrack> = emptyList()

        override suspend fun getLyrics(lyricId: String): CatalogLyrics = CatalogLyrics("", "")

        override suspend fun resolveArtworkUrl(pictureId: String): HttpUrl = STREAM_URL

        override suspend fun resolveAudioUrl(trackId: String): HttpUrl {
            audioResolveCount += 1
            if (runtimeFailure) error("bad GD response")
            return STREAM_URL
        }
    }

    private companion object {
        val STREAM_URL = "https://m801.music.126.net/song.mp3".toHttpUrl()
    }
}
