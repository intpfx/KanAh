package com.live.tv.music.playback

import com.live.tv.music.catalog.CatalogLyrics
import com.live.tv.music.catalog.CatalogTrack
import com.live.tv.music.catalog.MusicCatalog
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineSelectionResolverTest {
    @Test
    fun `returning to a track uses its last successful lyrics without another catalog call`() = runTest {
        val catalog = FakeCatalog()
        val resolver = OnlineSelectionResolver(catalog)
        val lyrics = resolver.resolve(TRACK).lyrics
        catalog.lyricsFailure = IOException("catalog unavailable")
        assertEquals(lyrics, resolver.rememberedLyrics(TRACK))
        assertEquals(lyrics, resolver.resolve(TRACK).lyrics)
        assertEquals(1, catalog.lyricsRequests)
        assertNull(resolver.resolve(TRACK.copy(lyricId = "another-song")).lyrics)
    }

    @Test
    fun `optional artwork and lyrics do not prevent online playback`() = runTest {
        val resolver = OnlineSelectionResolver(
            FakeCatalog(
                artworkFailure = IOException("artwork unavailable"),
                lyricsFailure = IOException("lyrics unavailable"),
            ),
        )

        val resolved = resolver.resolve(TRACK)

        assertNull(resolved.selection.artworkUrl)
        assertNull(resolved.lyrics)
    }

    @Test
    fun `activity resolution never consumes a temporary audio url`() = runTest {
        val catalog = FakeCatalog(audioFailure = IOException("must not be called"))
        val resolver = OnlineSelectionResolver(catalog)

        val resolved = resolver.resolve(TRACK)

        assertEquals(0, catalog.audioResolveCount)
        assertEquals(TRACK.id, resolved.selection.id)
    }

    @Test
    fun `resolved metadata keeps track identity`() = runTest {
        val resolved = OnlineSelectionResolver(FakeCatalog()).resolve(TRACK)

        assertEquals(TRACK.id, resolved.selection.id)
        assertEquals(TRACK.name, resolved.selection.title)
        assertEquals(TRACK.artists, resolved.selection.artists)
        assertEquals(ARTWORK_URL.toString(), resolved.selection.artworkUrl)
        assertEquals("[00:01.00]hello", resolved.lyrics?.original)
    }

    private class FakeCatalog(
        private val audioFailure: IOException? = null,
        private val artworkFailure: IOException? = null,
        var lyricsFailure: IOException? = null,
    ) : MusicCatalog {
        var lyricsRequests = 0
            private set
        var audioResolveCount = 0
            private set

        override suspend fun search(query: String): List<CatalogTrack> = emptyList()

        override suspend fun getLyrics(lyricId: String): CatalogLyrics {
            lyricsRequests++
            lyricsFailure?.let { throw it }
            return CatalogLyrics("[00:01.00]hello", "")
        }

        override suspend fun resolveArtworkUrl(pictureId: String): HttpUrl {
            artworkFailure?.let { throw it }
            return ARTWORK_URL
        }

        override suspend fun resolveAudioUrl(trackId: String): HttpUrl {
            audioResolveCount += 1
            audioFailure?.let { throw it }
            return AUDIO_URL
        }
    }

    private companion object {
        val TRACK = CatalogTrack(
            id = "track-1",
            name = "Moonlight",
            artists = listOf("Gift"),
            album = "Night",
            pictureId = "picture-1",
            lyricId = "lyric-1",
        )
        val AUDIO_URL = "https://m701.music.126.net/audio.mp3".toHttpUrl()
        val ARTWORK_URL = "https://p1.music.126.net/artwork.jpg".toHttpUrl()
    }
}
