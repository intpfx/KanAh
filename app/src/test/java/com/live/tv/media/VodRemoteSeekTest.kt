package com.live.tv.media

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class VodRemoteSeekTest {
    private val loop = shadowOf(Looper.getMainLooper())

    private fun player(seeks: MutableList<Long>, position: Long = 20_000L, end: Long = 100_000L): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getDuration" -> end
                "getCurrentPosition" -> position
                "isCurrentMediaItemSeekable" -> true
                "seekTo" -> { seeks.add(args!!.single() as Long); null }
                else -> null
            }
        } as Player

    @Test fun repeatedTapsUpdateFromThePendingTargetAndCommitOnlyAfterTheBurst() {
        val seeks = mutableListOf<Long>()
        val feedback = mutableListOf<Long>()
        val player = player(seeks)
        val remote = VodRemoteSeek(Handler(Looper.getMainLooper())) { target, _ -> feedback.add(target) }

        repeat(3) {
            remote.step(player, 10_000, isRepeat = false)
            remote.keyUp()
            loop.idleFor(Duration.ofMillis(60))
        }
        assertEquals(listOf(30_000L, 40_000L, 50_000L), feedback)
        assertEquals(listOf(30_000L), seeks)
        assertEquals(50_000L, remote.previewPosition(player))
        loop.idleFor(Duration.ofMillis(121))
        assertEquals(listOf(30_000L, 50_000L), seeks)
        assertNull(remote.previewPosition(player))
    }

    @Test fun aBurstAlreadyInTheInputQueueOnlySeeksOnce() {
        val seeks = mutableListOf<Long>()
        val feedback = mutableListOf<Long>()
        val player = player(seeks)
        val remote = VodRemoteSeek(Handler(Looper.getMainLooper())) { position, _ -> feedback.add(position) }
        repeat(3) { remote.step(player, 10_000, isRepeat = false) }

        assertEquals(listOf(30_000L, 40_000L, 50_000L), feedback)
        assertEquals(emptyList<Long>(), seeks)
        loop.idleFor(Duration.ofMillis(180))
        assertEquals(listOf(50_000L), seeks)
    }

    @Test fun holdingClampsTheTargetAndReleasingCommitsImmediately() {
        val seeks = mutableListOf<Long>()
        val feedback = mutableListOf<Long>()
        val player = player(seeks)
        val remote = VodRemoteSeek(Handler(Looper.getMainLooper())) { target, _ -> feedback.add(target) }

        remote.step(player, -10_000, isRepeat = false)
        loop.idleFor(Duration.ofMillis(500))
        repeat(3) { remote.step(player, -10_000, isRepeat = true) }
        assertEquals(listOf(10_000L, 0L, 0L, 0L), feedback)
        assertEquals(listOf(10_000L), seeks)
        remote.keyUp()
        loop.idle()
        assertEquals(listOf(10_000L, 0L), seeks)
    }

    @Test fun changingPlaybackDropsTheQueuedSeekForTheOldEpisode() {
        val oldSeeks = mutableListOf<Long>()
        val newSeeks = mutableListOf<Long>()
        val old = player(oldSeeks)
        val next = player(newSeeks, position = 99_000)
        val remote = VodRemoteSeek(Handler(Looper.getMainLooper())) { _, _ -> }
        remote.step(old, 10_000, isRepeat = false)
        loop.idleFor(Duration.ofMillis(50))
        remote.step(old, 10_000, isRepeat = false)
        remote.step(next, 10_000, isRepeat = false)
        loop.idleFor(Duration.ofMillis(500))

        assertEquals(listOf(30_000L), oldSeeks)
        assertEquals(listOf(100_000L), newSeeks)
    }
}
