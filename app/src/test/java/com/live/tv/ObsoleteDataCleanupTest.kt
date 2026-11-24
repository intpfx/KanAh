package com.live.tv

import android.content.Context
import android.os.Looper
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObsoleteDataCleanupTest {
    private fun waitForCleanup() {
        repeat(50) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test fun removedFeatureDataIsDeletedWhileUserSourcesAndProgressRemain() {
        val context: Context = RuntimeEnvironment.getApplication()
        val noBackup = context.noBackupFilesDir
        val stale = listOf(
            File(noBackup, "apple-remote/state"),
            File(noBackup, "lan-https/server.p12"),
            File(noBackup, "proxy-runtime/profile"),
            File(noBackup, "remote-adb/state"),
            File(noBackup, "builtin-proxy.enc.json"),
            File(context.dataDir, "shared_prefs/apple-tv-remote.xml"),
            File(context.dataDir, "shared_prefs/live-volume.xml"),
            File(context.dataDir, "shared_prefs/media-network.xml"),
            File(context.dataDir, "shared_prefs/music-browser.xml"),
            File(context.dataDir, "shared_prefs/media.MediaLibraryActivity.xml"),
            File(context.filesDir, "media-library.json"),
            File(context.filesDir, "media-cache/entry")
        )
        stale.forEach { it.parentFile?.mkdirs(); it.writeText("stale") }
        val kept = listOf(
            File(context.filesDir, "vod-sources.json"),
            File(context.filesDir, "iptv-cache.m3u"),
            File(context.filesDir, "vod-episode-cache/entry"),
            File(context.dataDir, "shared_prefs/media-progress.xml"),
            File(context.dataDir, "shared_prefs/music-playback.xml"),
            File(context.dataDir, "shared_prefs/iptv.xml")
        )
        kept.forEach { it.parentFile?.mkdirs(); it.writeText("user data") }

        ObsoleteDataCleanup.run(context)
        waitForCleanup()

        stale.forEach { assertFalse("removed feature data must be deleted: ${it.path}", it.exists()) }
        kept.forEach { assertTrue("user data must be preserved: ${it.path}", it.isFile) }
    }
}
