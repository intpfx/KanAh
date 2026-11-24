package com.live.tv.music.cache

import com.live.tv.music.catalog.CatalogLyrics
import com.live.tv.music.catalog.CatalogTrack
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMusicCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `artwork upgrade leaves original package unchanged and reopens independently`() = runTest {
        val root = temporary.newFolder("upgrade")
        val cache = OfflineMusicCache(root)
        cache.save(TRACK, LYRICS) { audio, artwork -> audio.writeBytes(AUDIO); artwork.writeBytes(ARTWORK) }
        val original = cache.find(TRACK.id)!!
        val manifest = original.audio.parentFile!!.resolve("manifest.properties")
        val manifestBefore = manifest.readBytes()
        val upgraded = ARTWORK + byteArrayOf(1, 2, 3)
        cache.upgradeArtwork(TRACK.id) { it.writeBytes(upgraded) }
        val reopened = OfflineMusicCache(root).find(TRACK.id)!!
        assertArrayEquals(upgraded, reopened.artwork!!.readBytes())
        assertArrayEquals(ARTWORK, original.artwork!!.readBytes())
        assertArrayEquals(AUDIO, reopened.audio.readBytes())
        assertArrayEquals(manifestBefore, manifest.readBytes())
        assertEquals(LYRICS, reopened.lyrics)
        assertEquals(listOf(TRACK), cache.tracks())
        cache.remove(TRACK.id)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `failed or cancelled artwork upgrade preserves playable package and prior overlay`() = runTest {
        val root = temporary.newFolder("upgrade-failure")
        val cache = OfflineMusicCache(root)
        cache.save(TRACK, LYRICS) { audio, artwork -> audio.writeBytes(AUDIO); artwork.writeBytes(ARTWORK) }
        val upgraded = ARTWORK + byteArrayOf(5)
        cache.upgradeArtwork(TRACK.id) { it.writeBytes(upgraded) }
        expectIOException { cache.upgradeArtwork(TRACK.id) { it.writeText("invalid image") } }
        val started = CompletableDeferred<Unit>()
        val job = async {
            cache.upgradeArtwork(TRACK.id) { target ->
                target.writeBytes(ARTWORK)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertArrayEquals(upgraded, cache.find(TRACK.id)!!.artwork!!.readBytes())
        assertArrayEquals(AUDIO, cache.find(TRACK.id)!!.audio.readBytes())
        assertTrue(root.listFiles()!!.none { it.name.startsWith("artwork-stage-") })
    }

    @Test
    fun `complete package reopens with audio artwork lyrics and original metadata`() = runTest {
        val root = temporary.newFolder("private-music")
        val cache = OfflineMusicCache(root)
        cache.save(TRACK, LYRICS) { audio, artwork ->
            audio.writeBytes(AUDIO)
            artwork.writeBytes(ARTWORK)
        }

        val reopened = OfflineMusicCache(root)
        val entry = reopened.find(TRACK.id)
        assertNotNull(entry)
        assertEquals(TRACK, entry!!.track)
        assertEquals(LYRICS, entry.lyrics)
        assertArrayEquals(AUDIO, entry.audio.readBytes())
        assertArrayEquals(ARTWORK, entry.artwork!!.readBytes())
        assertEquals(listOf(TRACK), reopened.tracks())
    }

    @Test
    fun `cancelling a partial download publishes nothing and keeps existing songs`() = runTest {
        val root = temporary.newFolder("private-music")
        val cache = OfflineMusicCache(root)
        cache.save(TRACK, LYRICS) { audio, artwork ->
            audio.writeBytes(AUDIO)
            artwork.writeBytes(ARTWORK)
        }
        val started = CompletableDeferred<Unit>()
        val cancelledTrack = TRACK.copy(id = "8")
        val job = async {
            cache.save(cancelledTrack, LYRICS) { audio, _ ->
                audio.writeBytes(AUDIO)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertNull(cache.find(cancelledTrack.id))
        job.cancelAndJoin()

        assertEquals(listOf(TRACK), OfflineMusicCache(root).tracks())
        assertTrue(root.listFiles()!!.none { it.name.startsWith("stage-") })
    }

    @Test
    fun `retrying the same song after cancellation cannot publish the old partial attempt`() = runTest {
        val root = temporary.newFolder("private-music")
        val cache = OfflineMusicCache(root)
        val started = CompletableDeferred<Unit>()
        val first = async {
            cache.save(TRACK, LYRICS) { audio, _ ->
                audio.writeBytes(AUDIO)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        val retry = async {
            OfflineMusicCache(root).save(TRACK.copy(name = "第二次完整请求"), LYRICS) { audio, artwork ->
                audio.writeBytes(AUDIO)
                artwork.writeBytes(ARTWORK)
            }
        }
        first.cancelAndJoin()
        retry.await()
        assertEquals("第二次完整请求", cache.find(TRACK.id)!!.track.name)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun `failed cover download cannot leave an audio-only entry for a song that has artwork`() = runTest {
        val root = temporary.newFolder("private-music")
        val cache = OfflineMusicCache(root)
        expectIOException { cache.save(TRACK, LYRICS) { audio, _ -> audio.writeBytes(AUDIO) } }
        assertNull(cache.find(TRACK.id))
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test
    fun `audio-only song with no lyrics is a valid complete package`() = runTest {
        val cache = OfflineMusicCache(temporary.newFolder("private-music"))
        val track = TRACK.copy(pictureId = "", lyricId = "")
        cache.save(track, CatalogLyrics("", "")) { audio, _ -> audio.writeBytes(AUDIO) }
        val entry = cache.find(track.id)!!
        assertNull(entry.artwork)
        assertEquals(CatalogLyrics("", ""), entry.lyrics)
    }

    @Test
    fun `deleting one owned package preserves other songs and user files`() = runTest {
        val root = temporary.newFolder("private-music")
        val outside = temporary.newFile("user-song.mp3").apply { writeBytes(AUDIO) }
        val cache = OfflineMusicCache(root)
        listOf(TRACK, TRACK.copy(id = "8")).forEach { track ->
            cache.save(track, LYRICS) { audio, artwork -> audio.writeBytes(AUDIO); artwork.writeBytes(ARTWORK) }
        }
        cache.remove(TRACK.id)
        assertNull(cache.find(TRACK.id))
        assertEquals(listOf("8"), cache.tracks().map { it.id })
        assertArrayEquals(AUDIO, outside.readBytes())
    }

    @Test
    fun `unexpected files and symlinks cannot turn cache removal into arbitrary deletion`() = runTest {
        val root = temporary.newFolder("private-music")
        val outside = temporary.newFile("user-song.mp3").apply { writeBytes(AUDIO) }
        val cache = OfflineMusicCache(root)
        cache.save(TRACK, LYRICS) { audio, artwork -> audio.writeBytes(AUDIO); artwork.writeBytes(ARTWORK) }
        val entry = cache.find(TRACK.id)!!
        Files.delete(entry.audio.toPath())
        Files.createSymbolicLink(entry.audio.toPath(), outside.toPath())
        expectIOException { cache.remove(TRACK.id) }
        assertArrayEquals(AUDIO, outside.readBytes())
        assertTrue(Files.isSymbolicLink(entry.audio.toPath()))
    }

    @Test
    fun `truncated package is rejected and cannot be overwritten or deleted`() = runTest {
        val cache = OfflineMusicCache(temporary.newFolder("private-music"))
        cache.save(TRACK, LYRICS) { audio, artwork -> audio.writeBytes(AUDIO); artwork.writeBytes(ARTWORK) }
        val audio = cache.find(TRACK.id)!!.audio
        audio.writeBytes(AUDIO.copyOf(5))
        assertThrows(IOException::class.java) { cache.find(TRACK.id) }
        expectIOException { cache.save(TRACK, LYRICS) { _, _ -> error("must not replace damaged original") } }
        assertEquals(5L, audio.length())
    }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected failure keeps the existing package intact.
        }
    }

    companion object {
        val TRACK = CatalogTrack("7", "月亮", listOf("歌手"), "专辑", "cover-7", "lyrics-7")
        val LYRICS = CatalogLyrics("[00:01.00]月光", "[00:01.00]Moonlight")
        val AUDIO = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val ARTWORK = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
    }
}
