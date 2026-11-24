package com.live.tv.music

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteMusicTransportTest {
    private fun seekPlayer(events: MutableList<Long>, position: Long = 30_000L,
        duration: Long = 60_000L, playing: Boolean = true, seekable: Boolean = true,
        command: Boolean = true): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "isPlaying" -> playing
                "isCurrentMediaItemSeekable" -> seekable
                "isCommandAvailable" -> command && args!![0] == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
                "getDuration" -> duration
                "getCurrentPosition" -> position
                "getCurrentMediaItem" -> MediaItem.Builder().setMediaId("track-1").build()
                "seekTo" -> { events.add(args!![0] as Long); null }
                else -> throw AssertionError("Unexpected player method ${method.name}")
            }
        } as Player

    @Test fun absoluteSeekMovesWithinBoundTrackAndClampsAtEnd() {
        val events = mutableListOf<Long>()
        for (target in listOf(42_123L, 20_007L, 0L, 99_999L))
            assertTrue(RemoteMusicTransport.seekTo(seekPlayer(events), "track-1", target))
        assertEquals(listOf(42_123L, 20_007L, 0L, 60_000L), events)
    }

    @Test fun absoluteSeekRejectsPausedUnavailableChangedTrackAndInvalidRequests() {
        val events = mutableListOf<Long>()
        for (player in listOf(null, seekPlayer(events, playing = false),
            seekPlayer(events, seekable = false), seekPlayer(events, command = false),
            seekPlayer(events, duration = 0L), seekPlayer(events, duration = androidx.media3.common.C.TIME_UNSET))) {
            assertFalse(RemoteMusicTransport.canSeek(player))
            assertFalse(RemoteMusicTransport.seekTo(player, "track-1", 10_000L))
        }
        assertFalse(RemoteMusicTransport.seekTo(seekPlayer(events), "track-2", 10_000L))
        assertFalse(RemoteMusicTransport.seekTo(seekPlayer(events), "", 10_000L))
        assertFalse(RemoteMusicTransport.seekTo(seekPlayer(events), "track-1", -1L))
        assertTrue(events.isEmpty())
    }

    private fun player(events: MutableList<String>, ready: Boolean = false, hasNext: Boolean = false): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> MediaItem.fromUri("https://example.org/music.mp3")
                "getPlayWhenReady" -> ready
                "getPlaybackState" -> Player.STATE_READY
                "hasNextMediaItem" -> hasNext
                "hasPreviousMediaItem" -> false
                "play", "pause", "seekToNextMediaItem" -> { events.add(method.name); null }
                else -> throw AssertionError("Unexpected player method ${method.name}")
            }
        } as Player

    @Test fun resumeTakesAudioBeforePlaybackAndPauseKeepsOrder() {
        val events = mutableListOf<String>()
        assertTrue(RemoteMusicTransport.execute(player(events), "play_pause", Runnable { events.add("stop-live") }))
        assertEquals(listOf("stop-live", "play"), events)
        events.clear()
        assertTrue(RemoteMusicTransport.execute(player(events, ready = true), "play_pause", Runnable { events.add("stop-live") }))
        assertEquals(listOf("stop-live", "pause"), events)
    }
    @Test fun unavailableTransportDoesNotInterruptLiveButValidQueueChangeTakesAudio() {
        val events = mutableListOf<String>()
        assertFalse(RemoteMusicTransport.execute(player(events), "next", Runnable { events.add("stop-live") }))
        assertTrue(events.isEmpty())
        assertFalse(RemoteMusicTransport.execute(null, "play_pause", Runnable { events.add("stop-live") }))
        assertTrue(events.isEmpty())
        assertTrue(RemoteMusicTransport.execute(player(events, hasNext = true), "next", Runnable { events.add("stop-live") }))
        assertEquals(listOf("stop-live", "seekToNextMediaItem"), events)
    }
}
