package com.live.tv.media

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.live.tv.AppSidebar
import java.util.concurrent.Executors

/** Native TV playback, with Media3 track/subtitle controls and local resume positions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPlaybackActivity : Activity() {
    private val sidebar by lazy { AppSidebar(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var message: TextView
    private lateinit var controls: TvPlaybackControls
    private var remoteScrubGesture: String? = null
    private var remoteScrubSequence = 0L
    private val closedScrubs = LinkedHashSet<String>()
    private val expireRemoteScrub = Runnable { closeRemoteScrub() }
    private fun rememberClosedScrub(gesture: String) {
        closedScrubs.add(gesture)
        while (closedScrubs.size > 128) closedScrubs.remove(closedScrubs.first())
    }
    private fun closeRemoteScrub() {
        progressHandler.removeCallbacks(expireRemoteScrub)
        remoteScrubGesture?.let(::rememberClosedScrub)
        remoteScrubGesture = null; remoteScrubSequence = 0
        if (::controls.isInitialized) controls.endRemoteSeek()
    }
    private fun applyRemoteScrub(expectedSession: String, gesture: String, seq: Long, phase: String, positionMs: Long): Boolean {
        if (activeOwner() !== this || sessionId != expectedSession || gesture.isEmpty() || gesture.length > 80 || seq <= 0 || positionMs < 0 || phase !in listOf("preview", "commit", "cancel")) return false
        if (gesture in closedScrubs) return false
        if (remoteScrubGesture == gesture && seq <= remoteScrubSequence) return false
        if (phase == "cancel") {
            if (remoteScrubGesture == gesture) closeRemoteScrub() else rememberClosedScrub(gesture)
            return true
        }
        if (!canRemoteSeek(player)) {
            if (remoteScrubGesture == gesture) closeRemoteScrub() else rememberClosedScrub(gesture)
            return false
        }
        if (remoteScrubGesture != gesture) { closeRemoteScrub(); remoteScrubGesture = gesture }
        remoteScrubSequence = seq
        val p = player ?: return false
        val target = positionMs.coerceIn(0, p.duration)
        if (phase == "commit") { p.seekTo(target); closeRemoteScrub() }
        else {
            controls.previewRemoteSeek(target)
            progressHandler.removeCallbacks(expireRemoteScrub)
            progressHandler.postDelayed(expireRemoteScrub, 2500)
        }
        return true
    }
    private var consumedRevealKey = 0
    private var id = ""
    private var sessionId = ""
    private var generation = 0
    private var playbackError = ""
    private val progressHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var observedPlayingMs=0L
    private var playbackLastTime=0L
    private var playbackLastPosition=0L
    private var playbackVerified=false
    private var vodRef: org.json.JSONObject? = null
    private var previousEpisode: org.json.JSONObject? = null
    private var nextEpisode: org.json.JSONObject? = null
    private var episodeItems: List<PlaybackEpisodeCatalog.Item> = emptyList()
    private var selectedEpisodeIndex = -1
    private var themeSampledGeneration = -1
    private val themeSampler = PlaybackThemeSampler(progressHandler)
    private val surfaceCopyHealth = PlaybackSurfaceCopyHealth()
    private var themePosterUrl = ""
    private var posterThemeStarted = false
    private var themeResolved = false
    private var posterThemeRequest: VodRequest? = null
    private var vodRequest: VodRequest? = null
    private var resumeAllowed = true
    private var renderedFirstFrame = false
    private var progressLastSaved = 0L
    internal var vodResolver: (org.json.JSONObject, VodRequest) -> org.json.JSONObject = { ref, request -> VodRepository.get(this).resolve(ref, request) }
    private val recordProgress=object:Runnable {
        override fun run() {
            val p=player ?: return
            val now=android.os.SystemClock.elapsedRealtime()
            val delta=p.currentPosition-playbackLastPosition
            val elapsed=now-playbackLastTime
            if(p.isPlaying && delta in 1..4000 && elapsed in 1..4000) observedPlayingMs+=minOf(delta,elapsed)
            playbackLastTime=now;playbackLastPosition=p.currentPosition
            if(observedPlayingMs>=10_000) playbackVerified=true
            if(now - progressLastSaved >= 10_000 || p.playbackState == Player.STATE_ENDED) { saveProgress(p); progressLastSaved = now }
            progressHandler.postDelayed(this,1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        intent.getStringExtra("vod_ref")?.let { raw ->
            vodRef = try { org.json.JSONObject(raw) } catch (_: Exception) { org.json.JSONObject() }
        }
        view = PlayerView(this).apply { useController = false; isFocusable = true }
        controls = TvPlaybackControls(this).apply {
            title(intent.getStringExtra("title").orEmpty())
            onEpisodeSelected = { index ->
                if (index != selectedEpisodeIndex) episodeItems.getOrNull(index)?.let { item ->
                    val safe = vodRef?.let { adjacentReference(item.reference, it) }
                    if (safe != null) changeEpisode(safe)
                }
            }
        }
        message = TextView(this).apply {
            setTextColor(Color.rgb(245,245,247)); textSize = 20f; text = "正在读取媒体…"
            gravity = Gravity.CENTER; includeFontPadding = false; setPadding(dp(28), dp(20), dp(28), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEB080A0E.toInt()); cornerRadius = dp(22).toFloat() }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); addView(view, ViewGroup.LayoutParams(-1,-1))
            addView(message, FrameLayout.LayoutParams(-2,-2,Gravity.CENTER).apply { leftMargin = dp(48); rightMargin = dp(48) })
            addView(controls, ViewGroup.LayoutParams(-1,-1))
        })
        view.setOnClickListener { controls.show() }
        view.requestFocus()
    }
    override fun onResume() {
        super.onResume()
        com.live.tv.MainActivity.releaseLiveForPlayback()
        foreground = true; current = java.lang.ref.WeakReference(this)
        startPlayback()
    }
    private fun startPlayback() {
        releasePlayback()
        sessionId = java.util.UUID.randomUUID().toString()
        id = ""; observedPlayingMs = 0; playbackVerified = false
        controls.themeColor(PlaybackThemeColor.FALLBACK)
        val request = ++generation
        val cancellation = VodRequest().also { vodRequest = it }
        val selectedRef = vodRef?.let { org.json.JSONObject(it.toString()) }
        playbackError = ""; renderedFirstFrame = false; message.visibility = android.view.View.VISIBLE
        message.text = "正在读取媒体…"
        worker.execute {
            try {
                val ref = selectedRef ?: throw VodException("INVALID_REFERENCE", "请选择在线影视条目")
                val downloads = VodCacheManager.get(this)
                val cached = downloads.completedSpec(ref)
                if (cached != null) VodRepository.get(this).validateReference(ref, cancellation)
                val spec = cached ?: vodResolver(ref, cancellation)
                val uri = Uri.parse(spec.getString("url"))
                require(uri.scheme in listOf("http", "https")) { "Only online HTTP media is supported" }
                val http=downloads.playbackFactory(spec)
                runOnUiThread {
                    if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                    id = spec.getString("id")
                    themePosterUrl = spec.optString("poster")
                    resumeAllowed = spec.optBoolean("stable", false)
                    val source = DefaultDataSource.Factory(this, http)
                    val instance = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
                    player = instance; view.player = instance; controls.bind(instance)
                    val catalog = PlaybackEpisodeCatalog.read(spec, ref)
                    episodeItems = catalog.items; selectedEpisodeIndex = catalog.selectedIndex
                    controls.title(catalog.title.ifBlank { intent.getStringExtra("title").orEmpty() })
                    previousEpisode = adjacentReference(spec.optJSONObject("previousEpisode"), ref)
                    nextEpisode = adjacentReference(spec.optJSONObject("nextEpisode"), ref)
                    controls.episodes(episodeItems.map { it.label }, selectedEpisodeIndex)
                    instance.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    instance.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (request != generation || player !== instance) return
                            message.visibility = android.view.View.GONE
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            if (request != generation || player !== instance) return
                            playbackError=error.errorCodeName; progressHandler.removeCallbacks(recordProgress); message.visibility = android.view.View.VISIBLE; message.text = "播放失败，请检查来源连接或切换资源版本"
                        }
                        override fun onRenderedFirstFrame() { onSessionFirstFrame(request, instance) }
                    })
                    val item = MediaItem.Builder().setUri(uri).setMediaId(id).setMimeType(spec.getString("mimeType"))
                    instance.setMediaItem(item.build())
                    if(resumeAllowed) instance.seekTo(getSharedPreferences("media-progress", MODE_PRIVATE).getLong(id, 0))
                    instance.prepare(); instance.playWhenReady = true
                    observedPlayingMs=0;playbackVerified=false
                    playbackLastTime=android.os.SystemClock.elapsedRealtime();playbackLastPosition=instance.currentPosition
                    progressLastSaved=playbackLastTime
                    progressHandler.postDelayed(recordProgress,1000)
                    message.visibility = android.view.View.GONE
                }
            } catch (error: Exception) { runOnUiThread {
                if (request != generation || cancellation.isCancelled() || isFinishing || isDestroyed) return@runOnUiThread
                playbackError = if(error is VodException) error.code else "RESOLVE_FAILED"
                message.text = if(error is VodException) error.message else "无法读取资源，请检查媒体来源"
                message.visibility = android.view.View.VISIBLE
            } }
        }
    }
    override fun onPause() {
        sidebar.dismiss()
        if (current?.get() === this) {
            foreground = false; current = null
        }
        releasePlayback()
        super.onPause()
    }
    private fun releasePlayback() {
        closeRemoteScrub()
        themeSampler.stop()
        posterThemeRequest?.cancel(); posterThemeRequest = null
        surfaceCopyHealth.reset()
        themePosterUrl = ""; posterThemeStarted = false; themeResolved = false
        generation++
        controls.bind(null); controls.hide()
        previousEpisode = null; nextEpisode = null; episodeItems = emptyList(); selectedEpisodeIndex = -1; controls.episodes(emptyList(), -1)
        vodRequest?.cancel(); vodRequest = null
        progressHandler.removeCallbacks(recordProgress)
        player?.let {
            saveProgress(it)
            it.pause(); it.release()
        }
        player = null; view.player = null
    }
    private fun changeEpisode(reference: org.json.JSONObject?) {
        val ref = reference ?: return
        vodRef = org.json.JSONObject(ref.toString())
        intent.putExtra("vod_ref", vodRef.toString())
        startPlayback()
        controls.show()
    }
    private fun adjacentReference(candidate: org.json.JSONObject?, current: org.json.JSONObject): org.json.JSONObject? {
        val safe = publicReference(candidate) ?: return null
        if (listOf("sourceId", "vodId", "lineId", "revision", "epoch").any { safe.opt(it) != current.opt(it) } ||
            safe.optString("episodeKey") == current.optString("episodeKey")) return null
        return safe
    }
    override fun onDestroy() {
        sidebar.dispose()
        // Normally onPause releases the lease first. Also cover early destruction and ensure
        // an abandoned weak reference can never divert the LAN remote away from live TV.
        if (current?.get() === this) { foreground = false; current = null }
        generation++
        themeSampler.stop()
        posterThemeRequest?.cancel(); posterThemeRequest = null
        surfaceCopyHealth.reset()
        themePosterUrl = ""; posterThemeStarted = false; themeResolved = false
        vodRequest?.cancel(); vodRequest = null
        progressHandler.removeCallbacks(recordProgress)
        closeRemoteScrub()
        worker.shutdownNow(); super.onDestroy()
    }
    private fun onSessionFirstFrame(request: Int, owner: Player) {
        // A released player's late callback must not mark a newer attempt as visible.
        if (request == generation && player === owner) {
            renderedFirstFrame = true
            if (themeSampledGeneration != request) {
                themeSampledGeneration = request
                themeSampler.start(
                    isCurrent = { request == generation && player === owner && !isFinishing && !isDestroyed },
                    readFrame = { complete -> sampleTheme(request, owner, complete) },
                    applyColor = { color ->
                        themeResolved = true
                        posterThemeRequest?.cancel(); posterThemeRequest = null
                        controls.themeColor(color)
                    }
                )
            }
        }
    }
    private fun sampleTheme(request: Int, owner: Player, complete: (Int?) -> Unit) {
        if (!isThemeSessionCurrent(request, owner)) { complete(null); return }
        val surface = view.videoSurfaceView as? android.view.SurfaceView
        if (surface?.holder?.surface?.isValid != true) { surfaceCopyHealth.reset(); complete(null); return }
        val bitmap = android.graphics.Bitmap.createBitmap(48, 27, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(surface, bitmap, { result ->
                var color: Int? = null
                try {
                    if (isThemeSessionCurrent(request, owner)) {
                        val success = result == android.view.PixelCopy.SUCCESS
                        if (surfaceCopyHealth.needsPosterFallback(success, bitmap)) startPosterTheme(request, owner)
                        if (success) color = PlaybackThemeColor.fromBitmap(bitmap)
                    }
                } finally { bitmap.recycle() }
                complete(color)
            }, progressHandler)
        } catch (_: IllegalArgumentException) { bitmap.recycle(); surfaceCopyHealth.reset(); complete(null) }
    }
    private fun isThemeSessionCurrent(request: Int, owner: Player) =
        request == generation && player === owner && !isFinishing && !isDestroyed

    private fun startPosterTheme(request: Int, owner: Player) {
        if (posterThemeStarted || themeResolved || themePosterUrl.isBlank() || !isThemeSessionCurrent(request, owner)) return
        posterThemeStarted = true
        val url = themePosterUrl
        val cancellation = VodRequest().also { posterThemeRequest = it }
        worker.execute {
            val color = PlaybackPosterTheme.load(url, VodRepository.get(this).client, cancellation)
            runOnUiThread {
                if (isThemeSessionCurrent(request, owner) && !cancellation.isCancelled() && !themeResolved && color != null) {
                    themeResolved = true
                    themeSampler.stop()
                    controls.themeColor(color)
                }
                if (posterThemeRequest === cancellation) posterThemeRequest = null
            }
        }
    }
    private fun saveProgress(p: Player) {
        if (!resumeAllowed || id.isEmpty()) return
        val position = if (p.playbackState == Player.STATE_ENDED) 0 else p.currentPosition.coerceAtLeast(0)
        val duration = p.duration.takeIf { it > 0 } ?: 0
        getSharedPreferences("media-progress", MODE_PRIVATE).edit().putLong(id, position)
            .putLong("$id:duration", duration).putLong("$id:updatedAt", System.currentTimeMillis()).apply()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    /** LAN keys bypass ViewRootImpl, which normally performs unhandled D-pad focus traversal. */
    internal fun dispatchRemoteKey(key: Int) {
        val handled = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        if (!handled) {
            val direction = when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> null
            }
            if (direction != null) {
                val focused = currentFocus ?: controls.findFocus()
                val next = focused?.focusSearch(direction)
                if (next != null && next.isEnabled && next.isFocusable) next.requestFocusFromTouch()
            }
        }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (sidebar.handleKey(event)) return true
        val key = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && consumedRevealKey == key) { consumedRevealKey = 0; return true }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_BACK && controls.isShowingControls) {
                controls.hide(); consumedRevealKey = key; return true
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (event.repeatCount == 0) when (key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> controls.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); controls.show() }
                    else -> controls.togglePlayback()
                }
                consumedRevealKey = key; return true
            }
            if (key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (event.repeatCount == 0) controls.seekBy(if (key == KeyEvent.KEYCODE_MEDIA_REWIND) -10_000 else 10_000)
                consumedRevealKey = key; return true
            }
            if (!controls.isShowingControls && key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                controls.show(); consumedRevealKey = key; return true
            }
            controls.interacted()
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Legacy Activity back callback is required for the existing TV target")
    override fun onBackPressed() {
        if (sidebar.isShowing()) sidebar.close()
        else if (controls.isShowingControls) controls.hide()
        else super.onBackPressed()
    }
    companion object {
        @Volatile private var foreground = false
        @Volatile private var current: java.lang.ref.WeakReference<MediaPlaybackActivity>? = null
        private fun activeOwner(): MediaPlaybackActivity? = current?.get()?.takeIf {
            foreground && !it.isFinishing && !it.isDestroyed
        }
        @JvmStatic fun currentActivity(): Activity? = activeOwner()
        @JvmStatic fun playbackState(): org.json.JSONObject {
            val a=activeOwner() ?: return org.json.JSONObject().put("active",false)
                .put("sessionId", "").put("vodRef", org.json.JSONObject.NULL)
            val p=a.player
            return org.json.JSONObject().put("active",true).put("sessionId",a.sessionId)
                .put("vodRef", publicReference(a.vodRef) ?: org.json.JSONObject.NULL)
                .put("id",a.id).put("positionMs",p?.currentPosition ?: 0)
                .put("seekable", canRemoteSeek(p)).put("durationMs",p?.duration ?: 0).put("playing",p?.isPlaying ?: false).put("state",p?.playbackState ?: 0).put("error",a.playbackError)
                .put("vod",a.vodRef != null).put("renderedFirstFrame",a.renderedFirstFrame).put("verifiedPlayback",a.playbackVerified)
        }
        /** Explicit primitive allowlist: never publish a resolver spec, locator or request headers. */
        private fun publicReference(ref: org.json.JSONObject?): org.json.JSONObject? {
            if (ref == null) return null
            val safe = org.json.JSONObject()
            for ((key, limit) in listOf("sourceId" to 128, "vodId" to 256, "lineId" to 128, "episodeKey" to 128)) {
                val value = ref.opt(key) as? String ?: return null
                if (value.isBlank() || value.length > limit || value.any { it.code < 32 || it.code == 127 }) return null
                safe.put(key, value)
            }
            for (key in listOf("revision", "epoch")) {
                val value = ref.opt(key) as? Number ?: return null
                val number = value.toInt()
                if (number < 1 || value.toDouble() != number.toDouble()) return null
                safe.put(key, number)
            }
            return safe
        }
        private fun canRemoteSeek(p: Player?): Boolean = p != null && p.isPlaying &&
            p.isCurrentMediaItemSeekable && p.duration > 0 &&
            p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

        @JvmStatic fun remoteScrub(expectedSession: String, gesture: String, seq: Long, phase: String, positionMs: Long): Boolean {
            val activity = activeOwner() ?: return false
            // LAN dispatch already runs on the main thread, so state and UI change atomically.
            return activity.applyRemoteScrub(expectedSession, gesture, seq, phase, positionMs)
        }
        @JvmStatic fun remoteSeekTo(expectedSession: String, positionMs: Long): Boolean {
            if (positionMs < 0) return false
            val activity = activeOwner() ?: return false
            if (activity.sessionId != expectedSession || !canRemoteSeek(activity.player)) return false
            activity.runOnUiThread {
                if (activeOwner() === activity && activity.sessionId == expectedSession && canRemoteSeek(activity.player)) {
                    val p = activity.player ?: return@runOnUiThread
                    p.seekTo(positionMs.coerceIn(0, p.duration))
                }
            }
            return true
        }
        @JvmStatic fun remoteKey(value: String): Boolean {
            val activity = activeOwner() ?: return false
            val key = when (value) { "up" -> 19; "down" -> 20; "left" -> 21; "right" -> 22; "ok" -> 23; "back" -> 4; "menu" -> 82; else -> 0 }
            if (key == 0 && value !in listOf("play_pause", "previous", "next")) return false
            activity.runOnUiThread {
                when(value) {
                    "play_pause" -> activity.controls.togglePlayback()
                    "previous" -> activity.controls.seekBy(-10_000)
                    "next" -> activity.controls.seekBy(10_000)
                    else -> {
                        activity.dispatchRemoteKey(key)
                    }
                }
            }
            return true
        }
        @JvmStatic fun isForeground() = activeOwner() != null
    }
}
