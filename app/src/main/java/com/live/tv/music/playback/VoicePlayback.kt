package com.live.tv.music.playback

import android.app.SearchManager
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.live.tv.music.catalog.CatalogTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object VoicePlaybackRequest {
    const val ACTION = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    fun query(intent: Intent): String? {
        if (intent.action != ACTION) return null
        val query = intent.getStringExtra(SearchManager.QUERY).orEmpty().trim()
        if (query.isNotEmpty()) return query.take(160)
        return listOf("android.intent.extra.title", "android.intent.extra.artist")
            .mapNotNull { intent.getStringExtra(it)?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ").take(160)
    }
}

object PlaybackQueue {
    fun item(track: CatalogTrack): MediaItem = PlaybackMediaItemFactory.create(
        OnlinePlaybackSelection(track.id, track.name, track.artists, track.album,
            track.pictureId, track.lyricId, null),
    )
    @androidx.annotation.OptIn(UnstableApi::class)
    fun selected(tracks: List<CatalogTrack>, track: CatalogTrack): MediaSession.MediaItemsWithStartPosition {
        val queue = tracks.distinctBy { it.id }.let { if (it.any { row -> row.id == track.id }) it else listOf(track) }
        return MediaSession.MediaItemsWithStartPosition(queue.map(::item), queue.indexOfFirst { it.id == track.id }, 0L)
    }
}

/** Resolves only stable catalog identities. External URLs never bypass the streaming resolver. */
class VoiceMediaResolver(private val search: suspend (String) -> List<CatalogTrack>) {
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun resolve(items: List<MediaItem>, current: List<MediaItem>, index: Int, position: Long,
        startIndex: Int, startPosition: Long): MediaSession.MediaItemsWithStartPosition {
        val query = items.singleOrNull()?.requestMetadata?.searchQuery
        if (query != null) {
            if (query.isBlank()) {
                require(current.isNotEmpty()) { "No selected song to resume" }
                return MediaSession.MediaItemsWithStartPosition(current, index, position)
            }
            val found = search(query.trim().take(160))
            require(found.isNotEmpty()) { "No matching songs" }
            return PlaybackQueue.selected(found, found.first())
        }
        require(items.all { item ->
            val uri = item.localConfiguration?.uri
            uri != null && GdPlaybackUri.trackId(uri) == item.mediaId
        }) { "Only stable catalog media items are supported" }
        return MediaSession.MediaItemsWithStartPosition(items, startIndex, startPosition)
    }
}

class VoiceSessionCallback(private val scope: CoroutineScope, private val resolver: VoiceMediaResolver) : MediaSession.Callback {
    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val player = session.player
        val current = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        return future { resolver.resolve(mediaItems, current, index, position, startIndex, startPositionMs) }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        val player = session.player
        val current = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        return future { resolver.resolve(mediaItems, current, index, position, C.INDEX_UNSET, C.TIME_UNSET).mediaItems }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try { result.set(block()) }
            catch (error: Exception) { result.setException(error) }
        }
        job.invokeOnCompletion { if (job.isCancelled) result.cancel(false) }
        result.addListener({ if (result.isCancelled) job.cancel() }, { it.run() })
        return result
    }
}
