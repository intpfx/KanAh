package com.live.tv

import android.content.Context
import android.util.Log
import java.io.File

/** One-time, idempotent removal of data owned by features that no longer exist. */
internal object ObsoleteDataCleanup {
    private const val TAG = "Maci/cleanup"

    // Only files that were read exclusively by removed features. User sources, caches,
    // playback progress, IPTV data, desktop mode and music playback state are preserved.
    private val noBackupEntries = listOf("apple-remote", "lan-https", "proxy-runtime", "remote-adb")
    private val noBackupFiles = listOf("builtin-proxy.enc.json")
    private val sharedPreferences = listOf("apple-tv-remote", "live-volume", "media-network", "music-browser", "media.MediaLibraryActivity")
    private val filesEntries = listOf("media-cache")
    private val files = listOf("media-library.json")

    fun run(context: Context) {
        val app = context.applicationContext
        Thread({
            val removed = mutableListOf<String>()
            try {
                val noBackup = app.noBackupFilesDir
                noBackupEntries.forEach { name ->
                    if (File(noBackup, name).deleteRecursively()) removed += name
                }
                noBackupFiles.forEach { name ->
                    if (File(noBackup, name).delete()) removed += name
                }
                sharedPreferences.forEach { name ->
                    if (File(app.dataDir, "shared_prefs/$name.xml").delete()) removed += "$name.xml"
                }
                filesEntries.forEach { name ->
                    if (File(app.filesDir, name).deleteRecursively()) removed += name
                }
                files.forEach { name ->
                    if (File(app.filesDir, name).delete()) removed += name
                }
                if (removed.isNotEmpty()) Log.i(TAG, "removed obsolete data: " + removed.joinToString())
            } catch (error: Exception) {
                Log.w(TAG, "obsolete data cleanup skipped: " + error.javaClass.simpleName)
            }
        }, "maci-obsolete-data").start()
    }
}
