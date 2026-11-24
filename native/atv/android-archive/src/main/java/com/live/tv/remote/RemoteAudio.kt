package com.live.tv.remote

import android.media.AudioManager
import kotlin.math.roundToInt

/** Actual TV audio state for the iPhone HUD; absolute volume never toggles twice. */
internal class RemoteAudio(
    private val manager: AudioManager,
    private val volumeFixed: () -> Boolean = { manager.isVolumeFixed },
) {
    fun volume(): Double = runCatching {
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        (manager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / maximum).coerceIn(0.0, 1.0)
    }.getOrDefault(0.5)

    fun muted(): Boolean = runCatching { manager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)

    fun setVolume(volume: Double, muted: Boolean): Boolean {
        if (!volume.isFinite()) return false
        return runCatching {
            if (volumeFixed()) return false
            val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val level = (volume.coerceIn(0.0, 1.0) * maximum).roundToInt()
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            true
        }.getOrDefault(false)
    }
}
