package com.live.tv.music.playback

import androidx.collection.LruCache
import com.live.tv.music.catalog.CatalogLyrics
import com.live.tv.music.catalog.CatalogTrack
import com.live.tv.music.catalog.MusicCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ResolvedOnlineSelection(
    val selection: OnlinePlaybackSelection,
    val lyrics: CatalogLyrics?,
)

/**
 * Resolves presentation-only metadata for a selected online track.
 *
 * The stream URL deliberately does not belong here. PlaybackService resolves the temporary GD URL
 * from the stable track id whenever ExoPlayer opens or retries the media, so background playback
 * never depends on an Activity-owned URL.
 */
class OnlineSelectionResolver(
    private val catalog: MusicCatalog,
) {
    private val lyricsCache = LruCache<String, CatalogLyrics>(8)
    private val artworkCache = LruCache<String, String>(8)

    fun rememberedLyrics(track: CatalogTrack): CatalogLyrics? = lyricsCache[track.lyricId]

    suspend fun resolve(track: CatalogTrack): ResolvedOnlineSelection = coroutineScope {
        val artwork = async {
            track.pictureId.takeIf(String::isNotBlank)?.let { id ->
                artworkCache[id] ?: bestEffort { catalog.resolveArtworkUrl(id).toString() }
                    ?.also { artworkCache.put(id, it) }
            }
        }
        val lyrics = async {
            track.lyricId.takeIf(String::isNotBlank)?.let { id ->
                lyricsCache[id] ?: bestEffort { catalog.getLyrics(id) }
                    ?.takeIf { it.original.isNotBlank() }
                    ?.also { lyricsCache.put(id, it) }
            }
        }

        ResolvedOnlineSelection(
            selection = OnlinePlaybackSelection(
                id = track.id,
                title = track.name,
                artists = track.artists,
                album = track.album,
                pictureId = track.pictureId,
                lyricId = track.lyricId,
                artworkUrl = artwork.await(),
            ),
            lyrics = lyrics.await(),
        )
    }

    private suspend fun <T> bestEffort(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
