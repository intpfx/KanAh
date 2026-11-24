package com.live.tv.music.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.core.net.toUri
import com.live.tv.music.catalog.MusicCatalog
import java.io.IOException
import java.util.LinkedHashMap
import kotlinx.coroutines.runBlocking

/** Resolves stable Smartisax media ids to short-lived GD URLs on ExoPlayer's loader thread. */
@androidx.annotation.OptIn(UnstableApi::class)
class GdStreamUrlResolver(
    private val catalog: MusicCatalog,
    private val cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val offlineAudio: (String) -> Uri? = { null },
) : ResolvingDataSource.Resolver {
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val reportedUris = object : LinkedHashMap<String, Uri>(MAX_REPORTED_URIS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Uri>?): Boolean =
            size > MAX_REPORTED_URIS
    }

    init {
        require(cacheTtlMillis > 0L) { "cacheTtlMillis must be positive" }
    }

    @Throws(IOException::class)
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val stableUri = dataSpec.uri
        val trackId = GdPlaybackUri.trackId(stableUri)
            ?: throw IOException("Rejected invalid Smartisax GD media URI")
        offlineAudio(trackId)?.let { local ->
            synchronized(lock) { reportedUris[local.toString()] = stableUri }
            return dataSpec.withUri(local)
        }
        val resolvedUri = synchronized(lock) {
            val now = elapsedRealtimeMillis()
            cache[trackId]
                ?.takeIf { now < it.expiresAtMillis }
                ?.resolvedUri
                ?: resolveAndCacheLocked(trackId, stableUri, now)
        }
        return dataSpec.withUri(resolvedUri)
    }

    override fun resolveReportedUri(uri: Uri): Uri = synchronized(lock) {
        reportedUris[uri.toString()] ?: if (isTrustedCdnUri(uri)) REDACTED_REPORTED_URI else uri
    }

    fun invalidateTemporaryUrls() {
        synchronized(lock) { cache.clear() }
    }

    @Throws(IOException::class)
    private fun resolveAndCacheLocked(trackId: String, stableUri: Uri, now: Long): Uri {
        val resolvedUri = try {
            runBlocking { catalog.resolveAudioUrl(trackId) }.toString().toUri()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("GD stream URL resolution failed", error)
        }
        cache[trackId] = CacheEntry(resolvedUri, now + cacheTtlMillis)
        reportedUris[resolvedUri.toString()] = stableUri
        return resolvedUri
    }

    private data class CacheEntry(
        val resolvedUri: Uri,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val DEFAULT_CACHE_TTL_MILLIS = 60_000L
        const val MAX_CACHE_ENTRIES = 16
        const val MAX_REPORTED_URIS = 32
        const val MUSIC_CDN_HOST = "music.126.net"
        val REDACTED_REPORTED_URI: Uri = GdPlaybackUri.forTrack("redacted")

        fun isTrustedCdnUri(uri: Uri): Boolean {
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" &&
                (host == MUSIC_CDN_HOST || host.endsWith(".$MUSIC_CDN_HOST"))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class GdTemporaryUrlRetryPolicy(
    private val streamUrlResolver: GdStreamUrlResolver,
) : DefaultLoadErrorHandlingPolicy() {
    private val refreshedLoadTasks = HashSet<Long>()

    override fun getRetryDelayMsFor(
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): Long {
        val responseCode = responseCodeInCauseChain(loadErrorInfo.exception)
        if (responseCode == HTTP_FORBIDDEN || responseCode == HTTP_GONE) {
            val loadTaskId = loadErrorInfo.loadEventInfo.loadTaskId
            val shouldRefresh = synchronized(refreshedLoadTasks) { refreshedLoadTasks.add(loadTaskId) }
            if (shouldRefresh) {
                streamUrlResolver.invalidateTemporaryUrls()
                return 0L
            }
            return C.TIME_UNSET
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun onLoadTaskConcluded(loadTaskId: Long) {
        synchronized(refreshedLoadTasks) { refreshedLoadTasks.remove(loadTaskId) }
        super.onLoadTaskConcluded(loadTaskId)
    }

    internal companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_GONE = 410
        fun responseCodeInCauseChain(error: Throwable): Int? {
            val visited = HashSet<Throwable>()
            var current: Throwable? = error
            while (current != null && visited.add(current)) {
                if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
                current = current.cause
            }
            return null
        }
    }
}
