package com.live.tv.media

import android.content.Context
import android.graphics.Bitmap
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.preload.PreCacheHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** One focused result per overlay. Media3 downloads a time range without an ExoPlayer. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VodSearchPreview(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private var job: Job? = null
    private var request: VodRequest? = null
    private var helper: PreCacheHelper? = null

    fun focus(work: LibraryWork, episode: JSONObject?) {
        stop()
        val pending = VodRequest().bounded(20_000)
        request = pending
        job = scope.launch {
            delay(500)
            val prepared = runCatching {
                runInterruptible(Dispatchers.IO) {
                    val repo = VodRepository.get(app)
                    val ref = episode ?: LibraryDetail.online(work, repo.detail(work.sourceId, work.vodId, pending))
                        .preferredEpisode(app)?.reference ?: return@runInterruptible null
                    if (VodCacheManager.get(app).hasCompleted(ref)) return@runInterruptible null
                    val spec = repo.resolve(ref, pending)
                    val start = app.getSharedPreferences("media-progress", Context.MODE_PRIVATE)
                        .getLong(spec.getString("id"), 0L).coerceAtLeast(0L)
                    val cache = temporaryCache(app)
                    Triple(ref, spec, start) to cache
                }
            }.getOrNull() ?: return@launch
            if (request !== pending || pending.isCancelled()) return@launch
            val (media, cache) = prepared
            val (ref, spec, start) = media
            runCatching {
                val uri = spec.getString("url")
                val item = MediaItem.Builder().setUri(uri).setMimeType(spec.getString("mimeType")).build()
                val upstream = VodMediaHttp.factory(app, spec, pending)
                val bytes = AtomicLong()
                val boundedUpstream = DataSource.Factory {
                    upstream.createDataSource().apply {
                        addTransferListener(object : TransferListener {
                            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                                if (bytes.addAndGet(bytesTransferred.toLong()) > 16L * 1024 * 1024) {
                                    pending.cancel()
                                    throw IOException("Search preview exceeded its byte budget")
                                }
                            }
                        })
                    }
                }
                helper = PreCacheHelper.Factory(cache, boundedUpstream, VodRenderers.create(app), preCacheLooper.value)
                    .setListener(object : PreCacheHelper.Listener {
                        override fun onPreCacheCompleted(mediaItem: MediaItem) {
                            android.util.Log.d("KHubSearchPreview", "Prefetch finished at ${start}ms")
                        }
                        override fun onPrepareError(mediaItem: MediaItem, error: IOException) {
                            if (!pending.isCancelled()) android.util.Log.d("KHubSearchPreview", "Prepare failed: ${error.javaClass.simpleName}")
                        }
                        override fun onDownloadError(mediaItem: MediaItem, error: IOException) {
                            if (!pending.isCancelled()) android.util.Log.d("KHubSearchPreview", "Download failed: ${error.javaClass.simpleName}")
                        }
                    })
                    .create(item)
                warmed = Warmed(LibraryReference.key(ref), spec, SystemClock.elapsedRealtime())
                helper?.preCache(start, 5_000)
            }
        }
    }

    fun stop() {
        request?.cancel()
        job?.cancel()
        helper?.release(false)
        request = null
        job = null
        helper = null
    }

    companion object {
        private data class Warmed(val key: String, val spec: JSONObject, val createdAt: Long)
        @Volatile private var warmed: Warmed? = null
        @Volatile private var cache: SimpleCache? = null
        private var poster: Pair<String, Bitmap>? = null

        private val preCacheLooper = lazy {
            HandlerThread("vod-search-precache").apply { start() }.looper
        }

        @Synchronized private fun temporaryCache(context: Context): SimpleCache {
            cache?.let { return it }
            val directory = File(context.cacheDir, "vod-search-preview")
            val database = StandaloneDatabaseProvider(context)
            if (directory.exists()) SimpleCache.delete(directory, database)
            return SimpleCache(directory, LeastRecentlyUsedCacheEvictor(48L * 1024 * 1024), database)
                .also { cache = it }
        }

        fun preparedSpec(ref: JSONObject): JSONObject? = warmed?.takeIf {
            it.key == LibraryReference.key(ref) && SystemClock.elapsedRealtime() - it.createdAt < 90_000
        }?.spec

        fun playbackFactory(upstream: DataSource.Factory): DataSource.Factory =
            cache?.let { CacheDataSource.Factory().setCache(it).setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(object : CacheDataSource.EventListener {
                    override fun onCacheIgnored(reason: Int) = Unit
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        android.util.Log.d("KHubSearchPreview", "Playback reused ${cachedBytesRead} cached bytes")
                    }
                }) } ?: upstream

        fun rememberPoster(key: String, bitmap: Bitmap) { poster = key to bitmap }
        fun takePoster(key: String): Bitmap? = poster.also { poster = null }?.takeIf { it.first == key }?.second
    }
}

/** The preview and the player choose the same history entry, ordered by actual watch time. */
internal fun LibraryDetail.preferredEpisode(context: Context, fallbackLineId: String = ""): LibraryEpisode? {
    val progress = context.getSharedPreferences("media-progress", Context.MODE_PRIVATE)
    var latest: LibraryEpisode? = null
    var latestTime = 0L
    for (line in lines) for (episode in line.episodes) {
        val ref = episode.reference
        val id = MacCmsProvider.playbackId(ref.optString("sourceId"), ref.optString("vodId"),
            ref.optString("lineId"), ref.optString("episodeKey"))
        val updatedAt = progress.getLong("$id:updatedAt", 0L)
        if (updatedAt > latestTime && progress.getLong(id, 0L) > 0L) {
            latest = episode
            latestTime = updatedAt
        }
    }
    return latest ?: (lines.firstOrNull { it.id == fallbackLineId } ?: lines.firstOrNull())?.episodes?.firstOrNull()
}
