package com.live.tv.music.catalog

import android.content.Context
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull
import com.live.tv.music.cache.CachedMusicTrack
import com.live.tv.music.cache.MusicAssetDownloader
import com.live.tv.music.cache.OfflineMusicCache
import com.live.tv.music.network.SmartisaxNetwork
import org.chromium.net.CronetEngine
import com.live.tv.music.playback.OnlinePlaybackSelection
import com.live.tv.music.playback.OnlineSelectionResolver
import com.live.tv.music.playback.ResolvedOnlineSelection
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-wide data owner; neither a WebView nor an Activity owns audio or cached files. */
class MusicRepository(
    private val catalog: MusicCatalog,
    private val cacheStore: OfflineMusicCache,
    private val downloader: MusicAssetDownloader,
) {
    private val artworkAttempts = ConcurrentHashMap.newKeySet<String>()
    private val onlineResolver = OnlineSelectionResolver(catalog)
    private val recentSearches = LruCache<Pair<String, Int>, List<CatalogTrack>>(8)

    fun rememberedLyrics(track: CatalogTrack): CatalogLyrics? = onlineResolver.rememberedLyrics(track)

    suspend fun search(query: String, page: Int = 1): List<CatalogTrack> = withContext(Dispatchers.IO) {
        val normalized = query.trim().take(160)
        val local = if (page <= 1) cachedTracks().filter { track ->
            normalized.isBlank() || (listOf(track.name, track.album) + track.artists)
                .any { it.contains(normalized, ignoreCase = true) }
        } else emptyList()
        if (normalized.isBlank()) return@withContext local
        val searchKey = normalized to page
        val online = try {
            catalog.searchPage(normalized, page).also { recentSearches.put(searchKey, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recentSearches[searchKey] ?: if (local.isNotEmpty()) emptyList() else throw error
        }
        (local + online).distinctBy { it.id }.take(24)
    }

    suspend fun resolve(track: CatalogTrack): ResolvedOnlineSelection = withContext(Dispatchers.IO) {
        cacheStore.find(track.id)?.let { cached ->
            refreshCachedArtwork(cached)
            val refreshed = cacheStore.find(track.id) ?: cached
            return@withContext ResolvedOnlineSelection(refreshed.selection(), refreshed.lyrics)
        }
        onlineResolver.resolve(track)
    }

    /** Browsing is artwork-only: no audio URL, lyrics, player or playback session is touched. */
    suspend fun previewArtwork(track: CatalogTrack): String? = withContext(Dispatchers.IO) {
        cacheStore.find(track.id)?.artwork?.let { return@withContext it.toURI().toString() }
        if (track.pictureId.isBlank()) null else catalog.resolveArtworkUrl(track.pictureId).toString()
    }

    /** LAN album covers need a public URL even when the TV has a private cached image. */
    suspend fun publicArtwork(track: CatalogTrack): String? = withContext(Dispatchers.IO) {
        if (track.pictureId.isBlank()) return@withContext null
        val url = catalog.resolveArtworkUrl(track.pictureId)
        val publicHost = url.host == "music.126.net" || url.host.endsWith(".music.126.net")
        if (url.scheme == "https" && url.port == 443 && publicHost && url.username.isEmpty() && url.password.isEmpty())
            url.toString() else null
    }

    private suspend fun refreshCachedArtwork(cached: CachedMusicTrack) {
        val track = cached.track
        if (track.pictureId.isBlank() || !artworkAttempts.add(track.id)) return
        val oldEdge = artworkEdge(cached.artwork)
        if (oldEdge >= 1500) return
        try {
            withTimeoutOrNull(5_000) {
                val url = catalog.resolveArtworkUrl(track.pictureId) ?: return@withTimeoutOrNull
                cacheStore.upgradeArtwork(track.id) { target ->
                    downloader.download(url, target, true, OfflineMusicCache.ARTWORK_LIMIT) {}
                    if (artworkEdge(target) <= oldEdge) throw IOException("Artwork does not improve resolution")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional online improvement must never prevent offline playback.
        }
    }

    private fun artworkEdge(file: File?): Int {
        if (file == null) return 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(0)
    }

    suspend fun cache(track: CatalogTrack, onProgress: (Double) -> Unit = {}) {
        if (contains(track.id)) { onProgress(1.0); return }
        // Metadata failures are optional for streaming, but cannot publish an incomplete cache.
        val lyrics = if (track.lyricId.isBlank()) CatalogLyrics("", "") else catalog.getLyrics(track.lyricId)
        val audio = catalog.resolveAudioUrl(track.id)
        val artwork = track.pictureId.takeIf { it.isNotBlank() }?.let { catalog.resolveArtworkUrl(it) }
        cacheStore.save(track, lyrics) { audioFile, artworkFile ->
            downloader.download(audio, audioFile, false, OfflineMusicCache.TRACK_LIMIT) { onProgress(it * 0.90) }
            if (artwork != null) {
                downloader.download(artwork, artworkFile, true, OfflineMusicCache.ARTWORK_LIMIT) { onProgress(0.90 + it * 0.09) }
            }
        }
        onProgress(1.0)
    }

    suspend fun remove(id: String) = cacheStore.remove(id)
    fun contains(id: String): Boolean = cacheStore.contains(id)
    fun cachedTracks(): List<CatalogTrack> = cacheStore.tracks()
    fun artworkFile(id: String): File? = cacheStore.find(id)?.artwork
    fun audioFile(id: String): File? = cacheStore.find(id)?.audio

    private fun CachedMusicTrack.selection(): OnlinePlaybackSelection = OnlinePlaybackSelection(
        track.id, track.name, track.artists, track.album, track.pictureId, track.lyricId,
        artwork?.toURI()?.toString(),
    )

    companion object {
        @Volatile private var instance: MusicRepository? = null
        fun get(context: Context): MusicRepository = instance ?: synchronized(this) {
            instance ?: MusicRepository(
                GdMusicCatalog(
                    SmartisaxNetwork.catalogClient,
                    cronet = runCatching {
                        CronetMusicClient(CronetEngine.Builder(context.applicationContext).enableHttp2(true).build())
                    }.getOrNull(),
                ),
                OfflineMusicCache(File(context.applicationContext.filesDir.canonicalFile, "music-cache-v1")),
                MusicAssetDownloader(SmartisaxNetwork.streamingClient),
            ).also { instance = it }
        }
    }
}
