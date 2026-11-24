package com.live.tv.media

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player

/** Keep remote feedback ahead of decoding, without restarting HLS loads for every repeat. */
internal class VodRemoteSeek(
    private val handler: Handler,
    private val showTarget: (Long, Long) -> Unit,
) {
    private var owner: Player? = null
    private var target = 0L
    private var applied = 0L
    private var lastInputAt = 0L
    private var repeating = false
    private val settle = Runnable { commit() }

    fun step(player: Player, deltaMs: Long, isRepeat: Boolean, eventTime: Long = SystemClock.uptimeMillis()) {
        val duration = player.duration
        if (!player.isCurrentMediaItemSeekable || duration <= 0L) {
            reset()
            return
        }
        val continuing = owner === player &&
            (isRepeat || target != applied || eventTime - lastInputAt in 0..SETTLE_MS)
        if (!continuing) {
            reset()
            owner = player
            applied = player.currentPosition.coerceIn(0L, duration)
            target = applied
        }
        lastInputAt = eventTime
        repeating = isRepeat
        target = (target + deltaMs).coerceIn(0L, duration)
        showTarget(target, duration)

        handler.removeCallbacks(settle)
        if (target != applied) {
            val delayMs = when {
                isRepeat -> RELEASE_GRACE_MS
                continuing -> SETTLE_MS
                else -> FIRST_STEP_MS
            }
            handler.postDelayed(settle, delayMs)
        }
    }

    fun keyUp() {
        if (repeating && owner != null && target != applied) {
            handler.removeCallbacks(settle)
            handler.post(settle)
        }
        repeating = false
    }

    fun previewPosition(player: Player): Long? = target.takeIf { owner === player && target != applied }

    fun commit() {
        handler.removeCallbacks(settle)
        val player = owner ?: return
        val duration = player.duration
        if (!player.isCurrentMediaItemSeekable || duration <= 0L || target == applied) return
        val position = target.coerceIn(0L, duration)
        applied = position
        target = position
        player.seekTo(position)
    }

    fun reset() {
        handler.removeCallbacks(settle)
        owner = null
        repeating = false
    }

    private companion object {
        const val FIRST_STEP_MS = 45L
        const val SETTLE_MS = 180L
        const val RELEASE_GRACE_MS = 350L
    }
}
