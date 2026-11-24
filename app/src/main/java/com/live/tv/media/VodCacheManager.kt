package com.live.tv.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Opt-in episode downloads only. Background workers own disk writes; playback never writes cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodCacheManager internal constructor(
    private val context: Context,
    private val directory: File = File(context.filesDir, "vod-episode-cache"),
    private val maxBytes: Long = Long.MAX_VALUE,
    private val freeBytes: Long = -1,
    private val resolver: (JSONObject, VodRequest) -> JSONObject = { ref, request -> VodRepository.get(context).resolve(ref, request) },
    private val upstream: (JSONObject) -> DataSource.Factory = { spec -> VodMediaHttp.factory(context, spec) },
    private val manifestTimeoutMillis: Long = 12_000,
) {
    private data class Job(val id: String, val ref: JSONObject, var state: String = "queued", var title: String = "",
        var bytes: Long = 0, var totalBytes: Long = -1, var percent: Float = -1f, var error: String = "",
        var spec: JSONObject? = null, var resources: JSONArray = JSONArray(), var cancelled: Boolean = false,
        var request: VodRequest? = null, var downloader: Downloader? = null, var thread: Thread? = null,
        var errorCode: String = "", var errorDetail: String = "", var preflightSource: DataSource? = null,
        var finished: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1)) {
        fun publicJson() = JSONObject().put("id", id).put("ref", JSONObject(ref.toString())).put("state", state)
            .put("title", title)
            .put("resourceTitle", spec?.optString("resourceTitle").orEmpty())
            .put("episodeLabel", spec?.optString("episodeLabel").orEmpty())
            .put("poster", spec?.optString("poster").orEmpty())
            .put("bytes", bytes).put("totalBytes", totalBytes).put("percent", percent)
            .apply { if (error.isNotEmpty()) put("error", error); if (errorCode.isNotEmpty()) put("errorCode", errorCode)
                if (errorDetail.isNotEmpty()) put("errorDetail", errorDetail) }
    }
    private val jobs = linkedMapOf<String, Job>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vod-episode-download").apply { isDaemon = true } }
    private val removalWorker = Executors.newSingleThreadExecutor { Thread(it, "vod-cache-removal").apply { isDaemon = true } }
    private val manifestTimer = Executors.newSingleThreadScheduledExecutor { Thread(it, "vod-manifest-deadline").apply { isDaemon = true } }
    private val pendingBytes = AtomicLong()
    @Volatile private var loaded = false
    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null
    private val metadata = AtomicFile(File(directory, "episodes.json"))

    private fun metadataExists() = metadata.baseFile.exists() || File(metadata.baseFile.path + ".bak").exists()
    private fun offMain() { check(Looper.myLooper() != Looper.getMainLooper()) { "Episode cache must be accessed off the UI thread" } }
    @Synchronized private fun load() {
        offMain()
        if (loaded) return
        directory.mkdirs()
        database = StandaloneDatabaseProvider(context)
        cache = SimpleCache(File(directory, "media"), NoOpCacheEvictor(), database!!)
        if (metadataExists()) {
            val rows = JSONObject(metadata.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).getJSONArray("episodes")
            for (i in 0 until minOf(rows.length(), 16)) {
                val row = rows.getJSONObject(i); val ref = row.getJSONObject("ref"); val id = identity(ref)
                if (id != row.optString("id")) continue
                val job = Job(id, ref, row.optString("state"), row.optString("title"), row.optLong("bytes"),
                    row.optLong("totalBytes", -1), row.optDouble("percent", -1.0).toFloat(), row.optString("error"),
                    row.optJSONObject("spec"), row.optJSONArray("resources") ?: JSONArray())
                if (job.state in listOf("queued", "resolving", "downloading", "removing")) {
                    job.state = "failed"; job.error = "缓存已中断，可重新缓存以继续"
                }
                job.errorCode = row.optString("errorCode")
                job.errorDetail = row.optString("errorDetail")
                jobs[id] = job
            }
        }
        loaded = true
    }
    @Synchronized private fun persist() {
        val rows = JSONArray(jobs.values.map { it.publicJson().put("spec", it.spec).put("resources", it.resources) })
        val out = metadata.startWrite()
        try { out.write(JSONObject().put("episodes", rows).toString().toByteArray()); metadata.finishWrite(out) }
        catch (error: Exception) { metadata.failWrite(out); throw error }
    }
    private fun storageVolume(): File {
        var volume = directory
        while (!volume.exists() && volume.parentFile != null) volume = volume.parentFile!!
        return volume
    }
    private fun reservedSpace(): Long = if (freeBytes >= 0) freeBytes else
        maxOf(1024L * 1024 * 1024, storageVolume().totalSpace / 10)
    private fun capacity(used: Long): JSONObject {
        val available = storageVolume().usableSpace.coerceAtLeast(0)
        val reserve = reservedSpace()
        val remaining = minOf((maxBytes - used).coerceAtLeast(0), (available - reserve).coerceAtLeast(0))
        return JSONObject().put("maxBytes", if (maxBytes == Long.MAX_VALUE) used + remaining else maxBytes).put("usedBytes", used)
            .put("budgetMode", if (maxBytes == Long.MAX_VALUE) "automatic" else "fixed")
            .put("availableBytes", available).put("reservedBytes", reserve).put("remainingBytes", remaining)
    }
    @Synchronized fun status(): JSONObject {
        offMain()
        if (!loaded && !metadataExists()) return capacity(0).put("ok", true).put("caches", JSONArray())
        load()
        jobs.values.filter { it.state == "complete" && !complete(it) }.forEach {
            it.state = "failed"; it.error = "缓存文件不完整，请重新缓存"
        }
        return capacity(synchronized(pendingBytes) {
            val committed = cache!!.cacheSpace
            val pending = pendingBytes.get()
            if (pending > Long.MAX_VALUE - committed) Long.MAX_VALUE else committed + pending
        })
            .put("ok", true).put("caches", JSONArray(jobs.values.map { job ->
                val progressId = job.spec?.optString("id").orEmpty()
                val progress = context.getSharedPreferences("media-progress", Context.MODE_PRIVATE)
                job.publicJson().put("positionMs", if (progressId.isEmpty()) 0 else progress.getLong(progressId, 0).coerceAtLeast(0))
                    .put("lastPlayedAt", if (progressId.isEmpty()) 0 else progress.getLong("$progressId:updatedAt", 0).coerceAtLeast(0))
            }))
    }
    @Synchronized fun enqueue(ref: JSONObject): JSONObject {
        load(); val copied = JSONObject(ref.toString()); val id = identity(copied)
        val old = jobs[id]
        if (old != null && (old.state in listOf("queued", "resolving", "downloading", "removing") || old.thread != null || complete(old)))
            return JSONObject().put("ok", true).put("cache", old.publicJson())
        if (old == null && jobs.size >= 16) throw VodException("CACHE_LIMIT", "缓存列表已满，请先移除不需要的剧集")
        val job = old ?: Job(id, copied)
        job.state = "queued"; job.error = ""; job.errorCode = ""; job.errorDetail = ""; job.cancelled = false; job.finished = java.util.concurrent.CountDownLatch(1); jobs[id] = job
        worker.execute { download(job) }
        return JSONObject().put("ok", true).put("cache", job.publicJson())
    }
    /** Remove the episodes present when clearing starts, including unfinished downloads. */
    fun clearEpisodes(): Int {
        val completion = synchronized(this) {
            offMain()
            if (!loaded && !metadataExists()) return 0
            load()
            val ids = jobs.keys.toList()
            ids.forEach { cancel(it) }
            removalWorker.submit<Int> {
                synchronized(this) { ids.count { jobs[it]?.state == "failed" } }
            }
        }
        return completion.get()
    }

    @Synchronized fun cancel(id: String): JSONObject {
        load()
        val job = jobs[id] ?: throw VodException("CACHE_NOT_FOUND", "此缓存已不存在")
        if (job.state == "removing") return JSONObject().put("ok", true).put("cache", job.publicJson())
        job.cancelled = true; job.state = "removing"; job.request?.cancel(); job.downloader?.cancel(); job.thread?.interrupt()
        val active = job.thread != null
        val finished = job.finished
        val preflight = job.preflightSource
        removalWorker.execute {
            try {
                runCatching { preflight?.close() }
                if (active) finished.await()
                removeResources(id)
                synchronized(this) {
                    jobs.remove(id)
                    try {
                        persist()
                    } catch (error: IOException) {
                        jobs[id] = job
                        throw error
                    }
                }
            } catch (_: Exception) {
                synchronized(this) {
                    job.state = "failed"; job.error = "缓存移除失败，请重试"
                    runCatching { persist() }
                }
            }
        }
        return JSONObject().put("ok", true).put("cache", job.publicJson())
    }
    @Synchronized fun hasCompleted(ref: JSONObject): Boolean {
        offMain()
        val id = runCatching { identity(ref) }.getOrNull() ?: return false
        if (!loaded && !metadataExists()) return false
        load(); return jobs[id]?.let { complete(it) } == true
    }
    @Synchronized fun completedSpec(ref: JSONObject): JSONObject? {
        offMain()
        val id = runCatching { identity(ref) }.getOrNull() ?: return null
        if (!loaded && !metadataExists()) return null
        load(); val job = jobs[id] ?: return null
        return if (complete(job)) job.spec?.let { JSONObject(it.toString()).put("cacheId", job.id) } else null
    }
    private fun complete(job: Job): Boolean = job.state == "complete" && job.spec != null && job.resources.length() > 0 &&
        (0 until job.resources.length()).all { index ->
            val span = job.resources.getJSONObject(index)
            val key = span.getString("key"); val position = span.getLong("position"); val length = span.getLong("length")
            cache!!.isCached(key, position, length) && cache!!.getCachedSpans(key).filter { it.position < position + length && it.position + it.length > position }.all {
                it.file?.isFile == true && it.file?.length() == it.length
            }
        }

    /** Cache-only playback guarantees completed downloads work without DNS/probes or signed URL refresh. */
    fun playbackFactory(spec: JSONObject): DataSource.Factory {
        val id = spec.optString("cacheId")
        if (id.isEmpty()) return upstream(spec)
        load()
        return factory(id, null, false)
    }
    private fun factory(id: String, network: DataSource.Factory?, downloading: Boolean): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache!!).setCacheKeyFactory { data -> "$id|${data.key ?: data.uri}" }
            .setUpstreamDataSourceFactory(network).setCacheWriteDataSinkFactory(if (downloading) DataSink.Factory {
                BudgetSink(CacheDataSink.Factory().setCache(cache!!).setFragmentSize(Long.MAX_VALUE).createDataSink(),
                    { cache!!.cacheSpace }, pendingBytes, maxBytes, reservedSpace(), { directory.usableSpace })
            } else null)

    private fun download(job: Job) {
        Thread.interrupted()
        var resolvedSpec: JSONObject? = null
        var manifestValidated = false
        try {
            for (attempt in 0..MAX_NETWORK_RETRIES) {
                val request = VodRequest()
                try {
                    synchronized(this) {
                        if (job.cancelled) return
                        job.thread = Thread.currentThread()
                        job.request = request
                        if (resolvedSpec == null) { job.state = "resolving"; persist() }
                    }
                    val spec = resolvedSpec ?: resolver(JSONObject(job.ref.toString()), request).also { resolvedSpec = it }
                    val mime = spec.getString("mimeType")
                    if (mime !in listOf("application/x-mpegURL", "application/vnd.apple.mpegurl", "video/mp4"))
                        throw VodException("CACHE_UNSUPPORTED", "暂只支持缓存 HLS 与 MP4 剧集")
                    synchronized(this) {
                        request.check(); if (job.cancelled) throw InterruptedException()
                        if (job.spec?.optString("url")?.let { it != spec.optString("url") } == true) removeResources(job.id)
                        job.spec = spec; job.title = spec.optString("title"); job.state = "downloading"; persist()
                    }
                    val item = MediaItem.Builder().setUri(Uri.parse(spec.getString("url"))).setMimeType(mime).build()
                    val network = upstream(spec)
                    if (mime != "video/mp4" && !manifestValidated) {
                        validateFiniteHls(job, network, Uri.parse(spec.getString("url")), request)
                        manifestValidated = true
                    }
                    val source = factory(job.id, network, true)
                    val downloader = if (mime == "video/mp4") ProgressiveDownloader(item, source, Executor { it.run() })
                        else HlsDownloader.Factory(source).setExecutor(Executor { it.run() }).create(item)
                    synchronized(this) { job.downloader = downloader; if (job.cancelled) throw InterruptedException() }
                    var lastSaved = 0L
                    downloader.download { total, bytes, percent -> synchronized(this) {
                        job.totalBytes = total; job.bytes = bytes; job.percent = percent
                        val now = System.nanoTime()
                        if (now - lastSaved > 1_000_000_000) { persist(); lastSaved = now }
                    } }
                    synchronized(this) {
                        if (job.cancelled || request.isCancelled() || Thread.currentThread().isInterrupted) throw InterruptedException()
                        job.resources = JSONArray()
                        cache!!.keys.filter { it.startsWith("${job.id}|") }.forEach { key -> cache!!.getCachedSpans(key).forEach { span ->
                            job.resources.put(JSONObject().put("key", key).put("position", span.position).put("length", span.length))
                        } }
                        if (job.resources.length() == 0) throw IOException("Empty download")
                        job.state = "complete"; job.percent = 100f; persist()
                    }
                    return
                } catch (error: Exception) {
                    synchronized(this) {
                        if (!job.cancelled) {
                            val causes = generateSequence(error as Throwable) { it.cause }.take(8).toList()
                            val budget = causes.filterIsInstance<CacheBudgetException>().firstOrNull()
                            val vod = causes.filterIsInstance<VodException>().firstOrNull()
                            val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                            val upstream = causes.filterIsInstance<DataSourceException>().firstOrNull()
                            val errno = causes.filterIsInstance<android.system.ErrnoException>().firstOrNull()
                            val diskFull = errno?.errno == android.system.OsConstants.ENOSPC
                            val networkFailure = when (vod?.code) {
                                "NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "CACHE_MANIFEST_TIMEOUT" -> true
                                null -> causes.none { it is javax.net.ssl.SSLException } &&
                                    (upstream?.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                        upstream?.reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                        causes.any { it is java.net.SocketTimeoutException || it is java.net.SocketException })
                                else -> false
                            }
                            val retryable = budget == null && http == null && !diskFull && networkFailure
                            if (retryable && attempt < MAX_NETWORK_RETRIES) {
                                android.util.Log.w("MaciVodCache", "Retrying episode cache after network failure (${attempt + 1}/$MAX_NETWORK_RETRIES)")
                                return@synchronized
                            }
                            if (vod?.code in setOf("CACHE_UNSUPPORTED", "CACHE_MANIFEST_LIMIT"))
                                runCatching { removeResources(job.id) }
                            job.errorDetail = buildString {
                                append(job.state)
                                append(": ")
                                append(causes.joinToString(" -> ") { it.javaClass.simpleName })
                                http?.let { append(" HTTP ").append(it.responseCode) }
                                upstream?.let { append(" Media3=").append(it.reason) }
                                errno?.let { append(" errno=").append(it.errno) }
                            }
                            job.errorCode = budget?.reason ?: vod?.code ?: when {
                                http != null -> "CACHE_HTTP_${http.responseCode}"
                                errno != null -> "CACHE_ERRNO_${errno.errno}"
                                causes.any { it is java.net.SocketTimeoutException } -> "CACHE_TIMEOUT"
                                causes.any { it is java.net.UnknownHostException } -> "CACHE_DNS"
                                causes.any { it is javax.net.ssl.SSLException } -> "CACHE_TLS"
                                causes.any { it is java.net.SocketException } -> "CACHE_SOCKET"
                                causes.any { it is java.io.EOFException } -> "CACHE_UNEXPECTED_EOF"
                                upstream != null -> "CACHE_MEDIA3_${upstream.reason}"
                                error is IOException -> "CACHE_IO"
                                else -> "CACHE_INTERNAL"
                            }
                            android.util.Log.w("MaciVodCache", "${job.errorCode} bytes=${job.bytes} ${job.errorDetail}")
                            job.state = "failed"; job.error = when {
                                budget != null -> budget.publicMessage()
                                diskFull -> "电视可用存储不足，无法继续缓存本集"
                                vod != null -> vod.message.orEmpty()
                                retryable -> "网络连接中断或超时，自动重试后仍未完成，可重新缓存以继续"
                                else -> "缓存未完成，可重新缓存以继续"
                            }
                            runCatching { persist() }
                        }
                        return
                    }
                } finally {
                    request.cancel()
                    synchronized(this) {
                        job.request = null
                        job.downloader = null
                        job.preflightSource = null
                        Thread.interrupted()
                    }
                }
                try {
                    Thread.sleep(500L shl attempt)
                } catch (_: InterruptedException) {
                    return
                }
            }
        } finally {
            synchronized(this) {
                job.thread = null
                Thread.interrupted()
                job.finished.countDown()
            }
        }
    }
    private fun validateFiniteHls(job: Job, source: DataSource.Factory, uri: Uri, request: VodRequest) {
        val scope = request.bounded(manifestTimeoutMillis)
        val total = AtomicLong()
        val active = java.util.concurrent.atomic.AtomicReference<DataSource?>()
        val loadingThread = Thread.currentThread()
        // Interrupt cancels OkHttpDataSource's pending open call; close releases a blocked body read.
        val timeout = manifestTimer.schedule({
            scope.cancel(); loadingThread.interrupt(); runCatching { active.get()?.close() }
        }, manifestTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        fun read(url: Uri, parser: HlsPlaylistParser): androidx.media3.exoplayer.hls.playlist.HlsPlaylist {
            scope.check()
            val bounded = ManifestDataSource(source.createDataSource(), scope, total)
            synchronized(this) { request.check(); job.preflightSource = bounded; active.set(bounded) }
            return try {
                ParsingLoadable.load(bounded, parser, url, C.DATA_TYPE_MANIFEST).also { scope.check() }
            } finally {
                bounded.close()
                synchronized(this) { if (job.preflightSource === bounded) job.preflightSource = null; active.compareAndSet(bounded, null) }
            }
        }
        fun finite(media: HlsMediaPlaylist) {
            if (!media.hasEndTag || media.segments.isEmpty() || media.protectionSchemes != null)
                throw VodException("CACHE_UNSUPPORTED", "暂不支持缓存直播或受保护的媒体")
        }
        try {
            val manifest = read(uri, HlsPlaylistParser())
            when (manifest) {
                is HlsMediaPlaylist -> finite(manifest)
                is HlsMultivariantPlaylist -> {
                    if (manifest.mediaPlaylistUrls.size !in 1..32 || manifest.sessionKeyDrmInitData.isNotEmpty())
                        throw VodException("CACHE_UNSUPPORTED", "此播放清单暂不支持缓存")
                    manifest.mediaPlaylistUrls.forEach { child ->
                        val media = read(child, HlsPlaylistParser(manifest, null))
                        finite(media as? HlsMediaPlaylist ?: throw VodException("CACHE_UNSUPPORTED", "此播放清单暂不支持缓存"))
                    }
                }
            }
            scope.check()
        } catch (error: Exception) {
            if (scope.expired() && !job.cancelled)
                throw VodException("CACHE_MANIFEST_TIMEOUT", "播放清单读取超时，请重试")
            throw error
        } finally {
            timeout.cancel(false); scope.cancel(); runCatching { active.getAndSet(null)?.close() }
            synchronized(this) { job.preflightSource = null }
        }
    }
    private fun removeResources(id: String) { cache!!.keys.filter { it.startsWith("$id|") }.forEach { cache!!.removeResource(it) } }
    internal fun close() {
        val sources = synchronized(this) {
            jobs.values.forEach { it.cancelled = true; it.request?.cancel(); it.downloader?.cancel(); it.thread?.interrupt() }
            jobs.values.mapNotNull { it.preflightSource }
        }
        sources.forEach { runCatching { it.close() } }
        worker.shutdownNow(); removalWorker.shutdownNow(); manifestTimer.shutdownNow()
        worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        removalWorker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        cache?.release(); database?.close()
    }
    companion object {
        private const val MAX_NETWORK_RETRIES = 3
        @Volatile private var instance: VodCacheManager? = null
        @JvmStatic fun get(context: Context): VodCacheManager = instance ?: synchronized(this) {
            instance ?: VodCacheManager(context.applicationContext).also { instance = it }
        }
        internal fun identity(ref: JSONObject): String {
            val fields = listOf("sourceId", "vodId", "lineId", "episodeKey", "revision", "epoch")
            val exact = JSONArray(fields.map { ref.get(it).toString() }).toString()
            return MessageDigest.getInstance("SHA-256").digest(exact.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

internal class CacheBudgetException(val reason: String = "CACHE_QUOTA_LIMIT", private val limit: Long = 0) : IOException("Episode cache budget exceeded") {
    fun publicMessage(): String {
        val size = when { limit >= 1024L * 1024 * 1024 && limit % (1024L * 1024 * 1024) == 0L -> "${limit / (1024L * 1024 * 1024)} GiB"
            limit >= 1024L * 1024 -> "${limit / (1024L * 1024)} MiB"; else -> "$limit B" }
        return if (reason == "CACHE_STORAGE_LOW") "电视可用存储不足，需保留 $size 系统余量"
            else "已达到应用缓存配额（$size），当前配额无法继续缓存本集"
    }
}
/** Reserve uncommitted fragments before each write. No eviction can silently invalidate completed episodes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class BudgetSink(private val delegate: DataSink, private val committed: () -> Long, private val pending: AtomicLong,
    private val maxBytes: Long, private val freeFloor: Long, private val free: () -> Long) : DataSink {
    private var written = 0L
    override fun open(dataSpec: DataSpec) { delegate.open(dataSpec) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(pending) {
            // Subtract from the remaining allowance: adding large cache/pending counters can
            // overflow when automatic allocation uses Long.MAX_VALUE as its quota sentinel.
            val quotaLeft = (maxBytes - committed().coerceAtLeast(0)).coerceAtLeast(0)
            val reserved = pending.get().coerceAtLeast(0)
            if (reserved > quotaLeft || length.toLong() > quotaLeft - reserved)
                throw CacheBudgetException("CACHE_QUOTA_LIMIT", maxBytes)
            val storageLeft = (free().coerceAtLeast(0) - freeFloor).coerceAtLeast(0)
            if (length.toLong() > storageLeft) throw CacheBudgetException("CACHE_STORAGE_LOW", freeFloor)
            pending.addAndGet(length.toLong()); written += length
        }
        delegate.write(buffer, offset, length)
    }
    override fun close() {
        synchronized(pending) { try { delegate.close() } finally { pending.addAndGet(-written); written = 0 } }
    }
}

/** Bounded streaming parser input, including an unknown-length/chunked response. Close is idempotent. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ManifestDataSource(private val delegate: DataSource, private val request: VodRequest,
    private val aggregate: AtomicLong, private val limit: Long = 512L * 1024,
    private val aggregateLimit: Long = 2L * 1024 * 1024) : DataSource {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private var bytes = 0L
    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) { delegate.addTransferListener(listener) }
    override fun open(spec: DataSpec): Long {
        request.check()
        val length = delegate.open(spec)
        request.check()
        if (length > limit || length > aggregateLimit - aggregate.get()) throw tooLarge()
        return length
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        request.check()
        if (length == 0) return 0
        val remaining = minOf(limit - bytes, aggregateLimit - aggregate.get())
        // One extra byte distinguishes exact-limit EOF from a larger unknown-length body.
        val count = delegate.read(buffer, offset, minOf(length.toLong(), maxOf(1L, remaining)).toInt())
        request.check()
        if (count > 0) {
            if (count > remaining) throw tooLarge()
            bytes += count; aggregate.addAndGet(count.toLong())
        }
        return count
    }
    override fun getUri(): Uri? = delegate.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
    override fun close() { if (closed.compareAndSet(false, true)) delegate.close() }
    private fun tooLarge() = VodException("CACHE_MANIFEST_LIMIT", "播放清单过大，暂不支持缓存")
}
