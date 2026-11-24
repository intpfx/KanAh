package com.live.tv.remote

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteAudioTest {
    private val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
    private val audio = RemoteAudio(manager)

    @Test fun readingActualVolumeDoesNotApplyRelativeButtonTwice() {
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, maximum / 2, 0)
        manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
        val levelAfterOneButton = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        assertEquals(levelAfterOneButton.toDouble() / maximum, audio.volume(), 0.001)
        assertEquals(levelAfterOneButton, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun absoluteVolumeIsClampedAndMuteIsIdempotent() {
        assertTrue(audio.setVolume(1.2, true))
        assertEquals(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(audio.muted())
        assertTrue(audio.setVolume(1.0, true))
        assertTrue(audio.muted())
        assertTrue(audio.setVolume(0.5, false))
        assertFalse(audio.muted())
        val expected = (manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.5).roundToInt()
        assertEquals(expected, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(audio.setVolume(Double.NaN, false))
    }

    @Test fun fixedVolumeTvIsHandledWithoutChangingVolume() {
        val old = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Robolectric 4.16.1 has no fixed-volume shadow API; inject the TV capability.
        val fixedAudio = RemoteAudio(manager, volumeFixed = { true })
        assertFalse(fixedAudio.setVolume(0.0, true))
        assertEquals(old, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
}
