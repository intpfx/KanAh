package com.live.tv.music.catalog

import com.live.tv.music.cache.MusicAssetDownloader
import com.live.tv.music.cache.OfflineMusicCache
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicPreviewIsolationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `focus preview resolves only artwork and never lyrics or audio`() = runTest {
        var pictures = 0
        val catalog = object : MusicCatalog {
            override suspend fun search(query: String): List<CatalogTrack> = error("not a search")
            override suspend fun getLyrics(lyricId: String): CatalogLyrics = error("preview must not resolve lyrics")
            override suspend fun resolveAudioUrl(trackId: String): HttpUrl = error("preview must not resolve audio")
            override suspend fun resolveArtworkUrl(pictureId: String): HttpUrl {
                pictures++
                return "https://p1.music.126.net/cover.jpg".toHttpUrl()
            }
        }
        val repository = MusicRepository(catalog, OfflineMusicCache(files.newFolder()), MusicAssetDownloader(OkHttpClient()))
        val track = CatalogTrack("1", "Song", listOf("Artist"), "Album", "picture", "lyric")
        assertEquals("https://p1.music.126.net/cover.jpg", repository.previewArtwork(track))
        assertEquals(1, pictures)
        assertNull(repository.previewArtwork(track.copy(pictureId = "")))
        assertEquals(1, pictures)
    }

    @Test fun `public album covers bypass private cached paths without changing cached assets or TV preview`() = runTest {
        var pictures = 0
        val catalog = object : MusicCatalog {
            override suspend fun search(query: String): List<CatalogTrack> = error("not a search")
            override suspend fun getLyrics(lyricId: String): CatalogLyrics = error("cover must not resolve lyrics")
            override suspend fun resolveAudioUrl(trackId: String): HttpUrl = error("cover must not resolve audio")
            override suspend fun resolveArtworkUrl(pictureId: String): HttpUrl {
                pictures++
                return "https://p1.music.126.net/album.jpg".toHttpUrl()
            }
        }
        val directory = files.newFolder()
        val cache = OfflineMusicCache(directory)
        val track = CatalogTrack("1", "Song", listOf("Artist"), "Album", "picture", "lyric")
        cache.save(track, CatalogLyrics("lyrics", "")) { audio, artwork ->
            audio.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0))
            artwork.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
        }
        fun snapshot() = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes().toList() }
        val before = snapshot()
        val repository = MusicRepository(catalog, cache, MusicAssetDownloader(OkHttpClient.Builder()
            .addInterceptor { error("No asset downloads") }.build()))
        assertTrue(repository.previewArtwork(track)!!.startsWith("file:"))
        assertEquals(0, pictures)
        assertEquals("https://p1.music.126.net/album.jpg", repository.publicArtwork(track))
        assertEquals(1, pictures)
        assertNull(repository.publicArtwork(track.copy(pictureId = "")))
        assertEquals(1, pictures)
        assertEquals(before, snapshot())
        assertTrue(repository.previewArtwork(track)!!.startsWith("file:"))
    }

    @Test fun `public album cover resolution rejects untrusted and credential bearing URLs`() = runTest {
        var address = "https://private:secret@p1.music.126.net/image.jpg"
        val catalog = object : MusicCatalog {
            override suspend fun search(query: String): List<CatalogTrack> = error("not a search")
            override suspend fun getLyrics(lyricId: String): CatalogLyrics = error("not lyrics")
            override suspend fun resolveAudioUrl(trackId: String): HttpUrl = error("not audio")
            override suspend fun resolveArtworkUrl(pictureId: String) = address.toHttpUrl()
        }
        val repository = MusicRepository(catalog, OfflineMusicCache(files.newFolder()), MusicAssetDownloader(OkHttpClient()))
        val track = CatalogTrack("1", "Song", emptyList(), "Album", "picture", "")
        for (url in listOf(address, "https://music.126.net.evil.test/image.jpg", "http://p1.music.126.net/image.jpg")) {
            address = url
            assertNull(repository.publicArtwork(track))
        }
    }
}
