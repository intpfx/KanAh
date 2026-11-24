package com.live.tv.music.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackSessionStoreTest {
    @Test
    fun `reopening preserves position and identity without persisting any signed asset url`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("resume-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val selection = OnlinePlaybackSelection("7", "月亮", listOf("歌手"), "专辑", "cover", "lyric", "https://p1.music.126.net/a.jpg?secret=value")
        PlaybackSessionStore(preferences).save(PlaybackMediaItemFactory.create(selection), 54_930L)

        val reopened = PlaybackSessionStore(preferences).restore()!!
        assertEquals(54_930L, reopened.positionMillis)
        assertEquals(selection.copy(artworkUrl = null), reopened.selection)
        assertFalse(preferences.all.toString().contains("secret=value"))
    }

    @Test
    fun `unrecognized persisted schema does not create a playback session`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("invalid-test", Context.MODE_PRIVATE)
        preferences.edit().putString("music-session-v1", "{\"version\":99}").commit()
        assertNull(PlaybackSessionStore(preferences).restore())
    }
}
