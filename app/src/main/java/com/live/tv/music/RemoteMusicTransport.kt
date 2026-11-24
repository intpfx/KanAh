package com.live.tv.music

import androidx.media3.common.Player

/** Validate first, then give music sole audio ownership before changing the player. */
internal object RemoteMusicTransport {
    fun canSeek(player: Player?): Boolean = player != null && player.isPlaying &&
        player.isCurrentMediaItemSeekable &&
        player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.duration > 0

    fun seekTo(player: Player?, expectedId: String, positionMs: Long): Boolean {
        if (!canSeek(player) || positionMs < 0 || expectedId.isEmpty() || player!!.currentMediaItem?.mediaId != expectedId) return false
        player.seekTo(positionMs.coerceIn(0L, player.duration))
        return true
    }

    fun execute(player: Player?, action: String, takeAudio: Runnable): Boolean {
        if (player?.currentMediaItem == null) return false
        when (action) {
            "play_pause" -> Unit
            "previous" -> if (!player.hasPreviousMediaItem()) return false
            "next" -> if (!player.hasNextMediaItem()) return false
            else -> return false
        }
        takeAudio.run()
        when (action) {
            "play_pause" -> {
                if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
                else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }
            "previous" -> player.seekToPreviousMediaItem()
            "next" -> player.seekToNextMediaItem()
        }
        return true
    }
}
