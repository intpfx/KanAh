package com.live.tv.music.playback

import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackSnapshot(val selection: OnlinePlaybackSelection, val positionMillis: Long)

/** Only stable song identity and presentation metadata are persisted; temporary URLs never are. */
class PlaybackSessionStore(private val preferences: SharedPreferences) {
    private var lastSavedPayload: String? = null

    fun save(item: MediaItem?, positionMillis: Long) {
        item ?: return
        if (runCatching { GdPlaybackUri.forTrack(item.mediaId) }.isFailure) return
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        val payload = JSONObject()
            .put("version", 1)
            .put("id", item.mediaId)
            .put("title", metadata.title?.toString().orEmpty())
            .put("artists", JSONArray(extras?.getStringArrayList(PlaybackMediaItemFactory.METADATA_ARTISTS).orEmpty()))
            .put("album", metadata.albumTitle?.toString().orEmpty())
            .put("pictureId", extras?.getString(PlaybackMediaItemFactory.METADATA_PICTURE_ID).orEmpty())
            .put("lyricId", extras?.getString(PlaybackMediaItemFactory.METADATA_LYRIC_ID).orEmpty())
            .put("positionMillis", positionMillis.coerceIn(0, MAX_POSITION_MILLIS))
        val encoded = payload.toString()
        if (encoded == lastSavedPayload) return
        preferences.edit().putString(KEY, encoded).apply()
        lastSavedPayload = encoded
    }

    fun restore(): PlaybackSnapshot? = runCatching {
        val raw = preferences.getString(KEY, null) ?: return null
        if (raw.length > 16_384) return null
        val value = JSONObject(raw)
        if (value.getInt("version") != 1) return null
        val id = value.getString("id")
        GdPlaybackUri.forTrack(id)
        val title = value.getString("title")
        if (title.isBlank() || title.length > 160) return null
        val artists = value.getJSONArray("artists")
        if (artists.length() > 8) return null
        val position = value.getLong("positionMillis")
        if (position !in 0..MAX_POSITION_MILLIS) return null
        PlaybackSnapshot(
            OnlinePlaybackSelection(
                id, title, (0 until artists.length()).map { artists.getString(it).take(120) },
                value.optString("album").take(160), value.optString("pictureId").take(80),
                value.optString("lyricId").take(80), null,
            ),
            position,
        )
    }.getOrNull()

    private companion object {
        const val KEY = "music-session-v1"
        const val MAX_POSITION_MILLIS = 24L * 60 * 60 * 1_000
    }
}
