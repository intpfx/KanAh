package com.live.tv.music

import android.content.Intent
import android.content.ComponentName
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.live.tv.GlobalSearchOverlay
import com.live.tv.MenuKeyController
import com.live.tv.music.catalog.CatalogTrack
import com.live.tv.music.catalog.MusicRepository
import com.live.tv.music.catalog.musicSearchFailureMessage
import com.live.tv.music.core.SynchronizedLyricLine
import com.live.tv.music.core.SynchronizedLyrics
import com.live.tv.music.playback.PlaybackQueue
import com.live.tv.music.playback.VoicePlaybackRequest
import com.live.tv.music.playback.PlaybackMediaItemFactory
import com.live.tv.music.playback.PlaybackService
import com.live.tv.media.MediaChromeView
import com.live.tv.music.ui.NativeMusicArtwork
import com.live.tv.music.ui.NativeMusicLyricsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The page is a presentation client; MediaSessionService exclusively owns playback. */
class MusicActivity : AppCompatActivity() {
    private lateinit var page: MediaChromeView
    private lateinit var artworkView: ImageView
    private lateinit var lyricsView: NativeMusicLyricsView
    private val repository by lazy { MusicRepository.get(this) }
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var currentTrack: CatalogTrack? = null
    private var lyrics: List<SynchronizedLyricLine> = emptyList()
    private var loadingLyrics = false
    private var artwork: Bitmap? = null
    private var titleColor: Int? = null
    private var cachedIds: Set<String> = emptySet()
    private var cacheTints: Map<String, Int> = emptyMap()

    private var results: List<CatalogTrack> = emptyList()
    private var message = ""
    private var loading = false
    private var visible = false
    private var starterJob: Job? = null
    private var presentedResults = false
    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var cacheJob: Job? = null
    private var selectionGeneration = 0L
    private var cacheGeneration = 0L
    private var cachingTrackId = ""
    private var resumed = false
    private var pendingVoiceQuery: String? = null
    private var voiceJob: Job? = null
    private var nativeBrowserEntry = false
    private lateinit var globalSearch: GlobalSearchOverlay
    private lateinit var menuKey: MenuKeyController
    private var selectedEntryStarted = false
    private var pendingSelectedTrack: CatalogTrack? = null

    private fun parseEntryTrack(value: JSONObject): CatalogTrack? {
        val id = value.optString("id")
        if (id.isBlank()) return null
        val artists = value.optJSONArray("artists") ?: JSONArray()
        return CatalogTrack(id, value.optString("name"),
            (0 until artists.length()).map { artists.optString(it) }, value.optString("album"),
            value.optString("pictureId", id), value.optString("lyricId", id))
    }

    private fun processSelectedEntry() {
        if (!nativeBrowserEntry || selectedEntryStarted || !resumed || controller == null) return
        val selected = pendingSelectedTrack ?: return
        selectedEntryStarted = true
        pendingSelectedTrack = null
        selectTrack(selected)
        render()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let(::restoreNowPlaying)
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playbackState == Player.STATE_READY) loading = false
            render()
        }
        override fun onPlayerError(error: PlaybackException) {
            loading = false
            message = "暂时无法播放，请选择播放按钮重试"
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.live.tv.R.style.MusicTheme)
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pendingVoiceQuery = savedInstanceState?.getString("pendingVoiceQuery")
            ?: if (savedInstanceState == null) VoicePlaybackRequest.query(intent) else null

        nativeBrowserEntry = intent.hasExtra("selected_track")
        selectedEntryStarted = savedInstanceState?.getBoolean("selectedEntryStarted") ?: false

        if (nativeBrowserEntry) {

            pendingSelectedTrack = runCatching {
                parseEntryTrack(JSONObject(intent.getStringExtra("selected_track").orEmpty()))
            }.getOrNull()
            results = runCatching {
                val queue = JSONArray(intent.getStringExtra("selected_queue") ?: "[]")
                (0 until queue.length()).mapNotNull { parseEntryTrack(queue.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        createPage()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (globalSearch.isOpen()) globalSearch.hide()
                else com.live.tv.DesktopMode.returnToHome(this@MusicActivity)
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderLyrics()
                    delay(100)
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::globalSearch.isInitialized && globalSearch.isOpen()) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) globalSearch.hide()
                return true
            }
            return globalSearch.dispatchKeyEvent(event)
        }
        if (::menuKey.isInitialized && menuKey.handle(event)) return true
        val key = event.keyCode
        if (key == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && resumed) {
                if (event.isLongPress) currentTrack?.let(::toggleCache)
                else if (event.repeatCount == 0) controller?.takeIf { currentTrack != null }?.let { player ->
                    if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) player.pause()
                    else {
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }
            }
            return true
        }
        if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && resumed) {
                controller?.takeIf { it.isCurrentMediaItemSeekable && it.duration > 0 }?.let {
                    val position = (it.currentPosition + if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                        .coerceIn(0, it.duration)
                    it.seekTo(position)
                    page.showSeekTime(position, it.duration)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("selected_track")) {
            starterJob?.cancel()
            cancelVoiceSelection()
            nativeBrowserEntry = true
            selectedEntryStarted = false
            pendingSelectedTrack = runCatching {
                parseEntryTrack(JSONObject(intent.getStringExtra("selected_track").orEmpty()))
            }.getOrNull()
            results = emptyList()
            processSelectedEntry()
        }
        VoicePlaybackRequest.query(intent)?.let {
            pendingVoiceQuery = it
            voiceJob?.cancel()
            processVoiceRequest()
        }
    }

    private fun processVoiceRequest() {
        if (!resumed) return
        val player = controller ?: return
        val requested = pendingVoiceQuery ?: return
        starterJob?.cancel()
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch {
            try {
                if (requested.isBlank()) {
                    if (player.currentMediaItem == null) message = "请先选择一首歌曲"
                    else {
                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                } else {

                    message = "正在查找歌曲…"
                    render()
                    val found = repository.search(requested)
                    results = found
                    if (found.isEmpty()) message = "未找到歌曲"
                    else selectTrack(found.first())
                }
                pendingVoiceQuery = null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                pendingVoiceQuery = null
                message = "语音点歌暂不可用，请重试"
            } finally { render() }
        }
    }

    private fun createPage() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(2, 3, 8)) }
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(artworkView, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            setBackgroundColor(0x38000000)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        page = MediaChromeView(this, object : MediaChromeView.Actions {
            override fun select(index: Int) = Unit

        })
        lyricsView = NativeMusicLyricsView(this, ::seekLyric) { Unit }
        page.lyricsView = lyricsView
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        globalSearch = GlobalSearchOverlay(this, lifecycleScope)
        root.addView(globalSearch, FrameLayout.LayoutParams(-1, -1))
        menuKey = MenuKeyController(onSearch = { globalSearch.show() })
        setContentView(root)
        render()
        page.post { if (!globalSearch.isOpen()) page.focusListOrLyrics() }
    }

    private fun seekLyric(seconds: Double) {
        if (!resumed || !seconds.isFinite()) return
        controller?.let { player ->
            if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                player.seekTo((seconds * 1000).toLong().coerceIn(0, player.duration))
                render()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        connectController()
        render()
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("app-session", MODE_PRIVATE).edit().putString("last_screen", "music").apply()
        resumed = true
        com.live.tv.music.playback.MusicForeground.active = true
        processSelectedEntry()
        if (selectionJob?.isActive != true && lyrics.isEmpty()) {
            controller?.currentMediaItem?.let(::restoreNowPlaying)
        }
        processVoiceRequest()
        selectStarterIfEmpty()
    }

    private fun pauseAndCancel() {
        resumed = false
        starterJob?.cancel()
        starterJob = null
        com.live.tv.music.playback.MusicForeground.active = false
        pendingVoiceQuery = null
        voiceJob?.cancel()
        selectionGeneration++
        selectionJob?.cancel()
        controller?.pause()
    }

    override fun onPause() {
        pauseAndCancel()
        super.onPause()
    }

    override fun onStop() {
        pauseAndCancel()
        visible = false
        voiceJob?.cancel()
        selectionGeneration++
        selectionJob?.cancel()
        cacheGeneration++
        cacheJob?.cancel()
        cacheJob = null
        cachingTrackId = ""
        loading = false
        render()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {

        outState.putBoolean("selectedEntryStarted", selectedEntryStarted)
        pendingVoiceQuery?.let { outState.putString("pendingVoiceQuery", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::menuKey.isInitialized) menuKey.cancel()
        super.onDestroy()
    }

    private fun cancelVoiceSelection() {
        pendingVoiceQuery = null
        voiceJob?.cancel()
        voiceJob = null
    }

    private fun connectController() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching { future.get() }.onSuccess { connected ->
                    if (controllerFuture !== future || !visible) {
                        MediaController.releaseFuture(future)
                        return@onSuccess
                    }
                    controller = connected
                    connected.addListener(playerListener)
                    connected.currentMediaItem?.let(::restoreNowPlaying)
                    processSelectedEntry()
                    processVoiceRequest()
                    selectStarterIfEmpty()
                    render()
                }.onFailure {
                    if (controllerFuture === future) {
                        MediaController.releaseFuture(future)
                        controllerFuture = null
                        message = "播放器连接失败，请重新打开"
                        render()
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun selectStarterIfEmpty() {
        val player = controller ?: return
        if (!resumed || player.currentMediaItem != null || pendingSelectedTrack != null ||
            pendingVoiceQuery != null || voiceJob?.isActive == true || starterJob?.isActive == true) return
        starterJob = lifecycleScope.launch {
            try {
                val cached = repository.search("")
                val available = if (cached.isNotEmpty()) cached else repository.search(DEFAULT_SEARCH_SEEDS.random())
                if (!resumed || controller !== player || player.currentMediaItem != null) return@launch
                val track = available.randomOrNull()
                if (track == null) message = "暂无可播放歌曲，请使用全局搜索"
                else selectTrack(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = musicSearchFailureMessage(error)
            } finally {
                if (resumed) render()
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun selectTrack(track: CatalogTrack) {
        if (!resumed) return
        val player = controller ?: return
        // A list cache operation is independent of the selected playback item.
        message = ""
        loading = true
        // Stop the old item immediately. Metadata completion never restarts this new item.
        val queue = PlaybackQueue.selected(results, track)
        player.setMediaItems(queue.mediaItems, queue.startIndex, queue.startPositionMs)
        player.prepare()
        player.play()
    }

    private fun restoreNowPlaying(item: MediaItem) {
        val metadata = item.mediaMetadata
        val track = CatalogTrack(item.mediaId, metadata.title?.toString().orEmpty(),
            metadata.extras?.getStringArrayList(PlaybackMediaItemFactory.METADATA_ARTISTS)
                ?: listOf(metadata.artist?.toString().orEmpty()), metadata.albumTitle?.toString().orEmpty(),
            metadata.extras?.getString(PlaybackMediaItemFactory.METADATA_PICTURE_ID) ?: item.mediaId,
            metadata.extras?.getString(PlaybackMediaItemFactory.METADATA_LYRIC_ID) ?: item.mediaId)
        val trackChanged = currentTrack?.id != track.id || currentTrack?.lyricId != track.lyricId
        currentTrack = track
        if (trackChanged) {
            lyrics = repository.rememberedLyrics(track)
                ?.let { SynchronizedLyrics.parse(it.original, it.translated) }.orEmpty()
            setArtwork(null)
        }
        loadingLyrics = lyrics.isEmpty() && track.lyricId.isNotBlank()
        val generation = ++selectionGeneration
        selectionJob?.cancel()
        selectionJob = lifecycleScope.launch {
            try {
                val resolved = repository.resolve(track)
                if (resolved.selection.artworkUrl == null && track.pictureId.isNotBlank()) {
                    android.util.Log.w("SmartTvMusic", "metadata: artwork unavailable for current selection")
                }
                if (resolved.lyrics == null && track.lyricId.isNotBlank()) {
                    android.util.Log.w("SmartTvMusic", "metadata: lyrics unavailable for current selection")
                }
                if (generation != selectionGeneration || currentTrack?.id != track.id) return@launch
                resolved.lyrics?.let { lyrics = SynchronizedLyrics.parse(it.original, it.translated) }
                loadingLyrics = false
                render()
                if (!globalSearch.isOpen() && (trackChanged || page.hasFocus())) {
                    page.focusListOrLyrics()
                }
                val cached = withContext(Dispatchers.IO) {
                    NativeMusicArtwork.cached(repository.artworkFile(track.id))
                }
                val cover = cached ?: NativeMusicArtwork.remote(resolved.selection.artworkUrl)
                if (generation == selectionGeneration && currentTrack?.id == track.id) setArtwork(cover)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                logFailure("metadata", error)
                if (generation == selectionGeneration) {
                    loadingLyrics = false
                    render()
                }
            }
        }
        render()
    }

    private fun toggleCache(track: CatalogTrack) {
        if (cacheJob?.isActive == true) {
            if (cachingTrackId == track.id) {
                cacheGeneration++
                cacheJob?.cancel()
                cacheJob = null
                cachingTrackId = ""
                android.widget.Toast.makeText(this, "已取消缓存", android.widget.Toast.LENGTH_SHORT).show()
            } else android.widget.Toast.makeText(this, "请等待当前缓存完成", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val generation = ++cacheGeneration
        val removing = repository.contains(track.id)
        cachingTrackId = track.id
        cacheJob = lifecycleScope.launch {
            try {
                if (removing) repository.remove(track.id)
                else repository.cache(track) { }
                if (generation == cacheGeneration) android.widget.Toast.makeText(this@MusicActivity,
                    if (removing) "已移除缓存" else "已缓存：${track.name}", android.widget.Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                logFailure("cache", error)
                if (generation == cacheGeneration) android.widget.Toast.makeText(this@MusicActivity,
                    if (removing) "移除失败，请重试" else "缓存未完成，请重试", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                if (generation == cacheGeneration) {
                    cachingTrackId = ""
                    if (removing && currentTrack?.id == track.id) {
                        controller?.currentMediaItem?.let(::restoreNowPlaying)
                    }
                    render()
                }
            }
        }
    }

    private fun setArtwork(cover: Bitmap?) {
        if (artwork === cover) return
        artwork = cover
        artworkView.setImageBitmap(cover)
        titleColor = cover?.let(com.live.tv.media.PlaybackThemeColor::fromBitmap)
    }

    /** Keep exception types and stack locations without signed URLs/query text in messages. */
    private companion object {
        val DEFAULT_SEARCH_SEEDS = listOf("热歌", "新歌")
    }

    private fun logFailure(operation: String, error: Throwable) {
        val diagnostic = buildString {
            append(operation).append(": ")
            var cause: Throwable? = error
            val visited = HashSet<Throwable>()
            while (cause != null && visited.add(cause)) {
                append(cause.javaClass.name).append('\n')
                cause.stackTrace.take(12).forEach { append("  at ").append(it).append('\n') }
                cause = cause.cause
                if (cause != null) append("caused by ")
            }
        }
        android.util.Log.w("SmartTvMusic", diagnostic)
    }

    private fun renderLyrics() {
        if (!::lyricsView.isInitialized) return
        val player = controller
        val position = player?.currentPosition?.coerceAtLeast(0) ?: 0
        lyricsView.render(currentTrack?.id.orEmpty(), lyrics, position / 1000.0, loadingLyrics)
        page.setTitleProgress(position, player?.duration ?: 0, titleColor)
    }

    private fun render() {
        if (!::page.isInitialized) return
        val track = currentTrack
        renderLyrics()
        page.render(
            title = track?.name.orEmpty(),
            subtitle = track?.artists?.joinToString(" / ").orEmpty(),
            items = emptyList(),
            selected = -1,
            music = true,
            status = message,
        )
    }
}
