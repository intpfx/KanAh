package com.live.tv.media

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackThemeSamplerTest {
    private val sampler = PlaybackThemeSampler(Handler(Looper.getMainLooper()))
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun longBlackIntroRetriesUntilValidFrameThenStops() {
        var reads = 0
        val applied = mutableListOf<Int>()
        sampler.start({ true }, { complete -> complete(if (++reads <= 15) null else 123) }, applied::add)
        advance(14)
        assertEquals(15, reads); assertTrue(applied.isEmpty())
        advance(1)
        assertEquals(listOf(123), applied)
        advance(10)
        assertEquals(16, reads)
    }

    @Test fun stopCancelsRetryAndLateCopyCannotColorNewResource() {
        var complete: ((Int?) -> Unit)? = null
        val applied = mutableListOf<Int>()
        sampler.start({ true }, { complete = it }, applied::add)
        sampler.stop()
        complete!!(123)
        advance(10)
        assertTrue(applied.isEmpty())
        var reads = 0
        sampler.start({ true }, { reads++; it(null) }, applied::add)
        sampler.stop(); advance(10)
        assertEquals(1, reads)
    }

    @Test fun replacementWaitsForOldCopyAndIgnoresItsResult() {
        var oldComplete: ((Int?) -> Unit)? = null
        val applied = mutableListOf<Int>()
        var reads = 0
        sampler.start({ true }, { oldComplete = it }, applied::add)
        sampler.start({ true }, { reads++; it(456) }, applied::add)
        advance(3)
        assertEquals(0, reads)
        oldComplete!!(123)
        assertTrue(applied.isEmpty())
        advance(1)
        assertEquals(1, reads); assertEquals(listOf(456), applied)
    }

    @Test fun invalidSessionStopsPollingAndRejectsInFlightResult() {
        var current = true
        var complete: ((Int?) -> Unit)? = null
        val applied = mutableListOf<Int>()
        sampler.start({ current }, { complete = it }, applied::add)
        current = false; complete!!(123); advance(10)
        assertTrue(applied.isEmpty())
    }
}
