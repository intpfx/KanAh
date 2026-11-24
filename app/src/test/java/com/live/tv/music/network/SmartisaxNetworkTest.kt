package com.live.tv.music.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartisaxNetworkTest {
    @Test
    fun `stream boundary requires https netease music cdn`() {
        assertTrue(isAllowedMusicCdnUrl("https://m801.music.126.net/song.mp3".toHttpUrl()))
        assertFalse(isAllowedMusicCdnUrl("http://m801.music.126.net/song.mp3".toHttpUrl()))
        assertFalse(isAllowedMusicCdnUrl("https://music.126.net.attacker.example/song.mp3".toHttpUrl()))
        assertFalse(isAllowedMusicCdnUrl("https://example.com/song.mp3".toHttpUrl()))
    }
}
