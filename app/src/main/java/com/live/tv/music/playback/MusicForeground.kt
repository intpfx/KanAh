package com.live.tv.music.playback

/** Activity visibility gate also guards delayed MediaSession commands. */
object MusicForeground {
    @Volatile var active = false
}
