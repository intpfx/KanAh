package com.live.tv.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class OnlinePlaybackSelection(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val pictureId: String,
    val lyricId: String,
    val artworkUrl: String?,
)

object GdPlaybackUri {
    private const val SCHEME = "smartisax-gd"
    private const val AUTHORITY = "netease"
    private const val MAX_TRACK_ID_LENGTH = 40
    private val TRACK_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,$MAX_TRACK_ID_LENGTH}")

    fun forTrack(trackId: String): Uri {
        val normalized = trackId.trim()
        require(TRACK_ID_PATTERN.matches(normalized)) { "Invalid GD track id" }
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(AUTHORITY)
            .appendPath(normalized)
            .build()
    }

    fun trackId(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY || uri.query != null || uri.fragment != null) {
            return null
        }
        val trackId = uri.pathSegments.singleOrNull() ?: return null
        if (!TRACK_ID_PATTERN.matches(trackId) || uri.encodedPath != "/${Uri.encode(trackId)}") return null
        return trackId
    }
}

object PlaybackMediaItemFactory {
    fun create(selection: OnlinePlaybackSelection): MediaItem {
        val extras = Bundle().apply {
            putString(METADATA_PICTURE_ID, selection.pictureId)
            putString(METADATA_LYRIC_ID, selection.lyricId)
            putStringArrayList(METADATA_ARTISTS, ArrayList(selection.artists))
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(selection.title)
            .setArtist(selection.artists.joinToString(" / "))
            .setAlbumTitle(selection.album?.takeIf(String::isNotBlank))
            .setExtras(extras)
            .apply {
                selection.artworkUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let(Uri::parse)
                    ?.let(::setArtworkUri)
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(selection.id)
            .setUri(GdPlaybackUri.forTrack(selection.id))
            .setMediaMetadata(metadata)
            .build()
    }

    const val METADATA_PICTURE_ID = "com.live.tv.music.picture_id"
    const val METADATA_LYRIC_ID = "com.live.tv.music.lyric_id"
    const val METADATA_ARTISTS = "com.live.tv.music.artists"
}
