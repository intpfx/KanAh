package com.live.tv.media

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import androidx.media3.datasource.DataSpec
import java.io.File
import org.json.JSONObject

/** Debug test APK only. No migration entry point ships in the application APK. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class KHubMigrationRunner : Instrumentation() {
    private lateinit var operation: String

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        operation = arguments.getString("operation").orEmpty()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val context = targetContext
        val root = context.dataDir
        context.filesDir  // Ensure the app-managed files directory exists on a fresh install.
        val stage = File(root, "khub-migration")
        try {
            val secrets = MediaSecrets()
            when (operation) {
                "export" -> {
                    check(context.packageName in setOf("com.siaovon.khub", "com.live.tv"))
                    val backup = File(root, "files/vod-sources.json.bak")
                    val sources = backup.takeIf { it.isFile } ?: File(root, "files/vod-sources.json")
                    if (sources.isFile) {
                        val output = File(stage, "sources.json")
                        check(!output.exists() && stage.mkdir())
                        output.writeText(rewrap(sources, secrets::open))
                    }
                }
                "import" -> {
                    check(context.packageName == "com.siaovon.kanah")
                    val source = File(stage, "sources.json")
                    if (source.isFile) {
                        val sealed = File(stage, "files/vod-sources.json")
                        sealed.parentFile!!.mkdirs()
                        sealed.writeText(rewrap(source, secrets::seal))
                        check(VodSourceStore(sealed, secrets::seal, secrets::open).state().getString("errorCode").isEmpty())
                        check(source.delete())
                    }
                    install(stage, root)
                }
                "verify-cache" -> {
                    check(context.packageName == "com.siaovon.kanah")
                    val manager = VodCacheManager(context)
                    try {
                        val rows = manager.status().getJSONArray("caches")
                        var verified = 0
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            if (row.getString("state") != "complete") continue
                            val spec = checkNotNull(manager.completedSpec(row.getJSONObject("ref")))
                            val media = manager.playbackFactory(spec).createDataSource()
                            try {
                                media.open(DataSpec(Uri.parse(spec.getString("url"))))
                                check(media.read(ByteArray(64), 0, 64) > 0)
                            } finally {
                                media.close()
                            }
                            verified++
                        }
                        result.putInt("offline_entries", verified)
                    } finally {
                        manager.close()
                    }
                }
                "fixture" -> {
                    check(context.packageName == "com.live.tv")
                    check(File(root, "files/vod-sources.json").createNewFile())
                    val dummy = """{"url":"https://fixture.invalid/api","header":{}}"""
                    val row = JSONObject().put("id", "fixture").put("name", "Fixture")
                        .put("revision", 1).put("epoch", 1).put("configSealed", secrets.seal(dummy))
                    File(root, "files/vod-sources.json").writeText(
                        JSONObject().put("version", 1).put("sources", org.json.JSONArray().put(row)).toString())
                    check(context.getSharedPreferences("media-progress", 0).edit().putLong("fixture", 12345).commit())
                    check(context.getSharedPreferences("iptv", 0).edit().putString("fixture", "channel").commit())
                    check(context.getSharedPreferences("music-playback", 0).edit().putString("fixture", "song").commit())
                    val ref = JSONObject().put("sourceId", "fixture").put("vodId", "movie")
                        .put("lineId", "line").put("episodeKey", "one").put("revision", 1).put("epoch", 1)
                    val manager = VodCacheManager(context, freeBytes = 0,
                        resolver = { _, _ -> JSONObject().put("url", "https://fixture.invalid/movie.mp4")
                            .put("mimeType", "video/mp4") },
                        upstream = { androidx.media3.datasource.DataSource.Factory {
                            androidx.media3.datasource.ByteArrayDataSource(ByteArray(1024) { it.toByte() })
                        } })
                    try {
                        manager.enqueue(ref)
                        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                        while (!manager.hasCompleted(ref) && android.os.SystemClock.elapsedRealtime() < deadline) {
                            Thread.sleep(25)
                        }
                        check(manager.hasCompleted(ref))
                    } finally {
                        manager.close()
                    }
                }
                "verify-fixture" -> {
                    check(context.packageName == "com.siaovon.kanah")
                    val info = VodSourceStore(context).state()
                    check(info.getString("errorCode").isEmpty())
                    check(info.getJSONArray("sources").getJSONObject(0).getString("id") == "fixture")
                    check(context.getSharedPreferences("media-progress", 0).getLong("fixture", 0) == 12345L)
                    check(context.getSharedPreferences("iptv", 0).getString("fixture", "") == "channel")
                    check(context.getSharedPreferences("music-playback", 0).getString("fixture", "") == "song")
                }
                else -> error("Unknown operation")
            }
            result.putString("migration", "ok")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("migration_error", error.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun rewrap(file: File, transform: (String) -> String): String {
        check(file.length() in 1..2_097_152)
        val json = JSONObject(file.readText())
        check(json.getInt("version") == 1)
        val rows = json.getJSONArray("sources")
        check(rows.length() <= 32)
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            row.put("configSealed", transform(row.getString("configSealed")))
        }
        return json.toString()
    }

    private fun install(stage: File, root: File) {
        check(stage.isDirectory)
        val paths = listOf(
            "files/vod-sources.json", "files/iptv-cache.m3u", "files/vod-episode-cache", "files/music-cache-v1",
            "cache/vod-frame-cache", "cache/live-last.jpg",
            "databases/exoplayer_internal.db", "databases/exoplayer_internal.db-journal",
            "databases/exoplayer_internal.db-wal",
            "shared_prefs/iptv.xml", "shared_prefs/MainActivity.xml", "shared_prefs/app-session.xml",
            "shared_prefs/media-progress.xml", "shared_prefs/music-playback.xml",
            "shared_prefs/vod-frame-index.xml", "shared_prefs/vod-session.xml", "shared_prefs/vod-recent.xml",
        )
        val planned = paths.filter { File(stage, it).exists() }
        check(planned.isNotEmpty() && paths.none { File(root, it).exists() || File(root, "$it.bak").exists() })
        val moved = mutableListOf<String>()
        try {
            for (path in planned) {
                val destination = File(root, path)
                destination.parentFile!!.mkdirs()
                check(File(stage, path).renameTo(destination))
                moved += path
            }
        } catch (error: Exception) {
            moved.asReversed().forEach { path ->
                check(File(root, path).renameTo(File(stage, path)))
            }
            throw error
        }
    }
}
