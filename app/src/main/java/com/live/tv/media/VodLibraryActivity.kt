package com.live.tv.media

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.util.AtomicFile
import androidx.media3.inspector.frame.FrameExtractor
import androidx.media3.exoplayer.source.MediaSource
import android.text.InputFilter
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.live.tv.R
import com.live.tv.DesktopMode
import com.live.tv.GlobalSearchOverlay
import com.live.tv.MenuKeyController
import com.live.tv.TvSourcesActivity
import com.live.tv.music.MusicActivity
import com.live.tv.TvDialogs
import com.live.tv.music.catalog.CatalogTrack
import com.live.tv.music.catalog.MusicRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Remote-first library. Public metadata is disposable; playback always uses a current opaque VOD reference. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodLibraryActivity : Activity() {
    internal var libraryData: LibraryData? = null
    internal var libraryImages: LibraryImages? = null
    internal val libraryModel = VodLibraryModel()
    private val worker = Executors.newFixedThreadPool(3) { Thread(it, "vod-library").apply { isDaemon = true } }
    private var resourceTheme: Int? = null
    private val recordWatchProgress = object : Runnable {
        override fun run() {
            if (!active || player?.isPlaying != true) return
            savePlayerProgress()
            renderChrome()
            main.postDelayed(this, 5000)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val remoteSeek = VodRemoteSeek(main) { position, duration ->
        chrome.setTitleProgress(position, duration, resourceTheme)
        chrome.showSeekTime(position, duration)
    }
    private val searchScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val data get() = libraryData!!
    private val images get() = libraryImages!!
    private var active = false
    private var catalogGeneration = 0
    private var catalogSourcesGeneration = 0
    private var detailGeneration = 0
    private var actionGeneration = 0
    private var localGeneration = 0
    private var localRequest: VodRequest? = null
    private var catalogRequest: VodRequest? = null
    private var detailRequest: VodRequest? = null
    private var actionRequest: VodRequest? = null
    private var query = ""
    private var cachedOnly = false
    private var spotlightWork: LibraryWork? = null
    private var selectedWorkKey = ""
    private var selectedWorkPosition = 0
    private var selectedLine = ""
    private var selectedEpisode = ""
    private var catalogLoading = false
    private var catalogError = ""
    private var retryPage = 1
    private var tailDownHeld = false
    private var detailLoading = false
    private var detailInvalidated = false
    private var actionLoading = false
    private var work: LibraryWork? = null
    private var detail: LibraryDetail? = null
    private var onlineDetailRequested = true
    private var cacheMode = false
    private var playDetail: LibraryDetail? = null
    private val markedEpisodes = linkedMapOf<String, LibraryEpisode>()
    private var detailNotice = ""
    private var detailError = ""
    private var cachePollGeneration = 0
    private var cacheMutationGeneration = 0
    private var cachePollInFlight = false
    private val cachePoll = Runnable { pollCaches() }
    private var dialog: AlertDialog? = null
    private lateinit var root: FrameLayout
    private lateinit var wall: FrameLayout
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var playerRef: org.json.JSONObject? = null
    private var playerId = ""
    private var playerRequest: VodRequest? = null
    private var playerGeneration = 0
    private var preparedKey = ""
    private var pendingAutoPlay = false
    private var pendingUserPlay = false
    private var showingFrame = false
    private var searchArtwork: Bitmap? = null
    private var heroFading = false
    private var artworkRequestedFor: String? = null
    private var renderedFrame = false
    private var frameSource: MediaSource.Factory? = null
    private var preloadFrameSource: MediaSource.Factory? = null
    private var frameRequest = 0
    private var uiHidden = false
    private var revealConfirmKey: Int? = null
    private val hideUiRunnable = Runnable {
        if (player?.isPlaying == true && dialog?.isShowing != true &&
            !globalSearch.isOpen() && searchOverlay.visibility != View.VISIBLE) hideChrome()
    }
    private val startupTimeout = Runnable {
        val instance = player ?: return@Runnable
        if (!active || renderedFrame || instance.playbackState != Player.STATE_BUFFERING) return@Runnable
        val tryingToPlay = instance.playWhenReady
        instance.pause()
        instance.stop()
        showChrome()
        showDetailMessage(if (tryingToPlay) "起播超时，按确认重试或切换来源" else "预览加载超时，按确认播放")
    }
    private var playerWorkKey = ""
    private var lastFrameWorkKey = ""
    private var restoredWorkKey = ""
    private var lastPlayedEpisodeKey = ""
    private var resumeEpisodeKey = ""
    private var resumePlayer: ExoPlayer? = null
    private var sessionWorkKey = ""
    private var sessionSourceId = ""
    private var sessionVodId = ""
    private val detailCache = android.util.LruCache<String, LibraryDetail>(8)
    private val lineCache = mutableMapOf<String, String>()
    private val episodeCache = mutableMapOf<String, String>()
    private var preloadPlayer: ExoPlayer? = null
    private var preloadWorkKey = ""
    private var preloadRef: org.json.JSONObject? = null
    private var preloadId = ""
    private var preloadResume = 0L
    private var preloadGeneration = 0
    private lateinit var searchOverlay: FrameLayout
    private lateinit var searchOverlayInput: android.widget.EditText
    private lateinit var searchResults: LinearLayout
    private var searchGeneration = 0
    private var playRunnable: Runnable? = null
    private lateinit var hero: LibraryPosterView
    private var selectedCardIndex = 0
    private var searchActive = false
    private var searchRunnable: Runnable? = null
    private lateinit var searchInput: android.widget.EditText
    private lateinit var searchAction: TextView
    private lateinit var clearSearchButton: TextView
    private lateinit var posters: LibraryPosterAdapter
    private lateinit var emptyMessage: TextView
    private lateinit var retry: TextView
    private lateinit var detailScreen: FrameLayout
    private var playerTitle = ""
    private var detailStatus = ""
    private lateinit var episodeAdapter: LibraryEpisodeAdapter
    private lateinit var detailRetry: TextView
    private var musicResults: List<CatalogTrack> = emptyList()
    private var musicSearchGeneration = 0
    private lateinit var globalSearch: GlobalSearchOverlay
    private lateinit var chrome: MediaChromeView
    private lateinit var menuKey: MenuKeyController
    private var previewPosition = -1
    private val preview = Runnable {
        if (!active) return@Runnable
        val position = previewPosition
        if (position >= 0) posters.items.getOrNull(position)?.let(::previewItem)
    }
    /** Test seam so catalog/layout tests never wait on the online music catalogue. */
    internal var musicSearchOverride: (suspend (String) -> List<CatalogTrack>)? = null
    private val musicArtworkCache = android.util.LruCache<String, Bitmap>(32)
    private val musicArtworkExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "library-music-art").apply { isDaemon = true }
    }
    private val musicHttp by lazy {
        MediaNetwork.get(this).client(OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).build())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libraryData = libraryData ?: NativeLibraryData(this)
        libraryImages = libraryImages ?: LibraryPosterImages(this)
        selectedWorkKey = savedInstanceState?.getString("workKey").orEmpty()
        selectedWorkPosition = savedInstanceState?.getInt("workPosition") ?: 0
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        root = FrameLayout(this).apply { setBackgroundColor(LibraryStyle.BASE); tag = "vod-library" }
        buildWall(); buildDetail()
        root.addView(wall, FrameLayout.LayoutParams(-1, -1))
        root.addView(detailScreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        buildSearchOverlay()
        root.addView(searchOverlay, FrameLayout.LayoutParams(-1, -1))
        globalSearch = GlobalSearchOverlay(this, searchScope)
        root.addView(globalSearch, FrameLayout.LayoutParams(-1, -1))
        menuKey = MenuKeyController(onSearch = { globalSearch.show() })
        data.landing()?.let { ready ->
            libraryModel.updateSources(ready.sources)
            libraryModel.updateCaches(ready.caches)
            libraryModel.applyPage(ready.page, "", 1)
        }
        setContentView(root)
        showLastFrame()
        restoreSessionSnapshot()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        root.post { hideKeyboard() }
        renderWall()
        root.post { if (active && dialog?.isShowing != true && !chrome.hasFocus()) chrome.resetFocus() }
    }

    override fun onResume() {
        super.onResume(); active = true
        if (player?.isPlaying == true) main.postDelayed(recordWatchProgress, 5000)
        getSharedPreferences("app-session", MODE_PRIVATE).edit().putString("last_screen", "vod").apply()
        refreshLocal(false)
        scheduleCachePoll()
        val suspendedPlayer = resumePlayer
        resumePlayer = null
        if (openSearchResult()) return
        if (suspendedPlayer != null && player === suspendedPlayer) suspendedPlayer.play()
        if (work?.music == null && work != null && detail == null) loadDetail(onlineDetailRequested)
        else if (detail != null) renderDetail(false)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (active) openSearchResult()
    }

    private fun openSearchResult(): Boolean {
        val recent = intent.getStringExtra("recent_vod")
        val result = recent ?: intent.getStringExtra("selected_vod") ?: return false
        intent.removeExtra("recent_vod")
        intent.removeExtra("selected_vod")
        val row = runCatching { JSONObject(result) }.getOrNull() ?: return false
        val reference = if (recent != null) LibraryReference.read(row) ?: return false else null
        libraryModel.updateSources(data.sources())
        if (!libraryModel.isCurrent(row)) {
            showDetailMessage("影视来源已变更，请重新搜索")
            return false
        }
        val id = row.optString("vodId")
        if (id.isBlank() || id.length > 256) return false
        val item = LibraryWork(row.optString("sourceId"), id, row.optInt("revision"), row.optInt("epoch"),
            row.optString("title").take(512).ifBlank { "未命名影片" },
            poster = row.optString("poster").take(8192), sourceName = row.optString("sourceName").take(512))
        main.removeCallbacks(preview)
        restoredWorkKey = ""
        resumeEpisodeKey = ""
        spotlightWork = item
        searchArtwork = VodSearchPreview.takePoster(item.key)
        if (artworkRequestedFor != null) images.load(hero.image, "")
        artworkRequestedFor = null
        cachedOnly = false
        selectedWorkKey = item.key
        pendingAutoPlay = reference == null
        pendingUserPlay = false
        showChrome()
        renderWall()
        if (reference != null) {
            val key = LibraryReference.key(reference)
            val lineId = reference.optString("lineId")
            val lineIndex = detail?.lines?.indexOfFirst { it.id == lineId } ?: -1
            if (lineIndex >= 0) selectLine(lineIndex, false) else selectedLine = lineId
            val episode = episodeAdapter.items.firstOrNull { LibraryReference.key(it.reference) == key }
                ?: LibraryEpisode(row.optString("episodeLabel"), reference)
            if (player != null && playerRef?.let(LibraryReference::key) == key) {
                player?.play()
            } else {
                play(episode)
            }
        } else if (pendingAutoPlay && detail?.work?.key == item.key) {
            renderDetail(false)
        }
        chrome.resetFocus()
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && active && ::hero.isInitialized && dialog?.isShowing != true &&
            !retry.hasFocus() && !chrome.hasFocus() &&
            !hero.hasFocus()) focusPosters()
    }

    override fun onPause() {
        remoteSeek.commit()
        remoteSeek.reset()
        main.removeCallbacks(recordWatchProgress)
        main.removeCallbacks(startupTimeout)
        val instance = player
        val wasPlaying = instance?.playWhenReady == true
        val state = instance?.playbackState
        val shouldResume = wasPlaying && (state == Player.STATE_BUFFERING || state == Player.STATE_READY)
        resumePlayer = instance.takeIf { shouldResume }
        savePlayerProgress()
        val episodeKey = playerRef?.let(LibraryReference::key)
        if (episodeKey != null && episodeKey == lastPlayedEpisodeKey) {
            resumeEpisodeKey = if (shouldResume) episodeKey else ""
            getSharedPreferences("vod-session", MODE_PRIVATE).edit()
                .putString("resumeEpisodeKey", resumeEpisodeKey).apply()
        }
        if (wasPlaying) {
            instance?.pause()
            captureFrame()
        }
        if (instance == null && playerRequest != null) {
            playerGeneration++
            playerRequest?.cancel()
            playerRequest = null
            preparedKey = ""
        }
        releasePreload()
        main.removeCallbacks(cachePoll); cachePollGeneration++; cachePollInFlight = false
        active = false; catalogGeneration++; detailGeneration++; actionGeneration++; localGeneration++
        main.removeCallbacks(preview)
        searchRunnable?.let { main.removeCallbacks(it) }
        localRequest?.cancel(); localRequest = null
        catalogRequest?.cancel(); detailRequest?.cancel(); actionRequest?.cancel(); images.cancel()
        catalogLoading = false; detailLoading = false; actionLoading = false; tailDownHeld = false
        super.onPause()
    }

    override fun onDestroy() {
        if (::menuKey.isInitialized) menuKey.cancel()
        searchScope.cancel()
        releasePlayer(false); releasePreload()
        dialog?.dismiss(); images.close(); worker.shutdownNow()
        musicArtworkExecutor.shutdownNow(); musicArtworkCache.evictAll()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("workKey", selectedWorkKey); outState.putInt("workPosition", selectedWorkPosition)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Back dismisses overlays without leaving the player")
    override fun onBackPressed() {
        when {
            ::globalSearch.isInitialized && globalSearch.isOpen() -> globalSearch.hide()
            ::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE -> hideSearchOverlay()
            dialog?.isShowing == true -> dialog?.dismiss()
            ::chrome.isInitialized && !uiHidden -> hideChrome()
            else -> DesktopMode.returnToHome(this)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::globalSearch.isInitialized && globalSearch.isOpen()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    globalSearch.hide()
                    scheduleHideChrome()
                }
                return true
            }
            return globalSearch.dispatchKeyEvent(event)
        }
        if (::menuKey.isInitialized && menuKey.handle(event)) return true
        val key = event.keyCode
        if (searchOverlay.visibility != View.VISIBLE) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val delta = if (key == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                    player?.let { remoteSeek.step(it, delta, event.repeatCount > 0, event.eventTime) }
                } else if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    remoteSeek.keyUp()
                }
                return true
            }
            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) togglePlayback()
                return true
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN ||
                key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER ||
                key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (uiHidden) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        showChrome()
                        chrome.resetFocus()
                        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)
                            return super.dispatchKeyEvent(event)
                        revealConfirmKey = key
                    }
                    return true
                }
                if (revealConfirmKey == key) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        revealConfirmKey = null
                        if (!event.isCanceled && player != null) togglePlayback()
                    }
                    return true
                }
                if ((key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) &&
                    !chrome.hasFocus()) chrome.resetFocus()
            }
        }
        val handled = super.dispatchKeyEvent(event)
        // Any remote interaction restarts the idle timer while controls remain visible.
        if (event.action == KeyEvent.ACTION_DOWN && player?.isPlaying == true && !uiHidden) scheduleHideChrome()
        return handled
    }

    private fun focusDetail() {
        val music = posters.items.getOrNull(selectedCardIndex)?.music
        if (music != null) { playMusic(music); return }
        if (detail != null && episodeAdapter.items.isNotEmpty()) {
            chrome.resetFocus(); return
        }
        if (detailRetry.visibility == View.VISIBLE) { detailRetry.requestFocus(); return }
    }

    private fun requestSearch() {
        searchInput.showSoftInputOnFocus = true
        val ok = searchInput.requestFocus()
        android.util.Log.d("MaciNav", "requestSearch focus=" + ok + " has=" + searchInput.hasFocus() + " vis=" + searchInput.visibility + " window=" + searchInput.isAttachedToWindow)
        searchInput.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        if (::root.isInitialized) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun saveSessionSnapshot(current: LibraryWork, reference: JSONObject, episodeLabel: String) {
        if (LibraryReference.workKey(reference) != current.key) return
        val lineId = reference.getString("lineId")
        val line = detail?.takeIf { it.work.key == current.key }?.lines?.firstOrNull { it.id == lineId }
        val actual = LibraryEpisode(episodeLabel, reference)
        val entries = line?.episodes?.takeIf { list ->
            list.any { LibraryReference.key(it.reference) == LibraryReference.key(reference) }
        } ?: listOf(actual)
        val episodes = JSONArray(entries.map { episode ->
            JSONObject().put("label", episode.label).put("reference", JSONObject(episode.reference.toString()))
                .put("cached", episode.cached).put("cacheState", episode.cacheState).put("percent", episode.percent)
        })
        val snapshot = JSONObject().put("played", true).put("workKey", current.key).put("title", current.title)
            .put("sourceId", current.sourceId).put("vodId", current.vodId)
            .put("sourceName", current.sourceName).put("poster", current.poster)
            .put("lineId", lineId).put("lineLabel", line?.label.orEmpty())
            .put("selectedEpisode", reference.getString("episodeKey")).put("episodes", episodes)
        lastPlayedEpisodeKey = LibraryReference.key(reference)
        getSharedPreferences("vod-session", MODE_PRIVATE).edit()
            .putString("snapshot", snapshot.toString())
            .putString("resumeEpisodeKey", lastPlayedEpisodeKey).apply()
    }

    private fun restoreSessionSnapshot() {
        val prefs = getSharedPreferences("vod-session", MODE_PRIVATE)
        val raw = prefs.getString("snapshot", null)
        val snapshot = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val rows = snapshot.optJSONArray("episodes") ?: JSONArray()
        val savedEpisodes = (0 until rows.length()).mapNotNull { index ->
            val entry = rows.optJSONObject(index) ?: return@mapNotNull null
            val ref = LibraryReference.read(entry.optJSONObject("reference")) ?: return@mapNotNull null
            if (LibraryReference.workKey(ref) != snapshot.optString("workKey") ||
                ref.optString("lineId") != snapshot.optString("lineId")) return@mapNotNull null
            LibraryEpisode(entry.optString("label"), ref, entry.optBoolean("cached"),
                entry.optString("cacheState"), entry.optInt("percent", -1))
        }
        val saved = savedEpisodes.firstOrNull { it.reference.optString("episodeKey") == snapshot.optString("selectedEpisode") }
            ?: savedEpisodes.firstOrNull()
        val recent = if (snapshot.optBoolean("played") && saved != null) null else RecentVodHistory(this).entries().firstOrNull()
        val reference = LibraryReference.read(recent) ?: saved?.reference ?: return
        val title = (recent ?: snapshot).optString("title").ifBlank { "未命名影片" }
        val item = LibraryWork(reference.getString("sourceId"), reference.getString("vodId"),
            reference.getInt("revision"), reference.getInt("epoch"), title,
            poster = (recent ?: snapshot).optString("poster"), sourceName = (recent ?: snapshot).optString("sourceName"))
        val list = savedEpisodes.takeIf { entries ->
            entries.any { LibraryReference.key(it.reference) == LibraryReference.key(reference) }
        } ?: listOf(LibraryEpisode(recent?.optString("episodeLabel").orEmpty(), reference))
        sessionWorkKey = item.key
        sessionSourceId = item.sourceId
        sessionVodId = item.vodId
        restoredWorkKey = item.key
        lastPlayedEpisodeKey = LibraryReference.key(reference)
        resumeEpisodeKey = prefs.getString("resumeEpisodeKey", "").orEmpty()
            .takeIf { recent == null && snapshot.optBoolean("played") && it == lastPlayedEpisodeKey }.orEmpty()
        selectedWorkKey = item.key
        spotlightWork = item
        work = item
        playerTitle = item.title
        selectedLine = reference.getString("lineId")
        selectedEpisode = reference.getString("episodeKey")
        setEpisodeItems(list)
    }

    private fun showLastFrame() {
        if (!::hero.isInitialized) return
        val prefs = getSharedPreferences("vod-frame-index", MODE_PRIVATE)
        val id = prefs.getString("__last_frame", null) ?: return
        val frame = loadCachedFrame(id) ?: return
        hero.animate().cancel(); hero.alpha = 1f
        hero.image.setImageBitmap(frame)
        hero.visibility = View.VISIBLE
        showingFrame = true
        emptyMessage.visibility = View.GONE
        lastFrameWorkKey = prefs.getString("__last_work_key", "").orEmpty()
        playerTitle = prefs.getString("__last_title", "").orEmpty()
        renderChrome()
    }

    private fun buildSearchOverlay() {
        searchOverlay = FrameLayout(this).apply {
            tag = "library-search-overlay"; visibility = View.GONE
            setBackgroundColor(Color.argb(248, 8, 10, 14)); isFocusable = true
        }
        val column = vertical().apply { setPadding(dp(40), dp(28), dp(40), dp(24)) }
        searchOverlayInput = android.widget.EditText(this).apply {
            tag = "library-search-overlay-input"; hint = "搜索影视、歌曲或歌手"; isSingleLine = true
            textSize = 20f; setTextColor(LibraryStyle.INK); setHintTextColor(LibraryStyle.SECONDARY)
            setPadding(dp(18), 0, dp(18), 0)
            background = LibraryStyle.round(this@VodLibraryActivity, Color.argb(255, 30, 34, 43), 12)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { performOverlaySearch(text.toString()); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    performOverlaySearch(text.toString()); true
                } else false
            }
        }
        column.addView(searchOverlayInput, LinearLayout.LayoutParams(-1, dp(54)))
        searchResults = vertical().apply { setPadding(0, dp(18), 0, 0) }
        column.addView(android.widget.ScrollView(this).apply { addView(searchResults) }, LinearLayout.LayoutParams(-1, 0, 1f))
        searchOverlay.addView(column, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showSearchOverlay() {
        if (!::searchOverlay.isInitialized) return
        showChrome()
        if (player?.isPlaying == true) { player?.pause(); savePlayerProgress(); updatePlaybackControls() }
        searchOverlay.visibility = View.VISIBLE; searchOverlayInput.setText(query)
        searchOverlayInput.requestFocus(); hideKeyboard()
        searchOverlayInput.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(searchOverlayInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearchOverlay() {
        if (!::searchOverlay.isInitialized) return
        searchOverlay.visibility = View.GONE; searchGeneration++; hideKeyboard(); chrome.resetFocus()
    }

    private fun performOverlaySearch(value: String) {
        val term = value.trim().take(200)
        if (term.isEmpty()) return
        val generation = ++searchGeneration; searchResults.removeAllViews()
        searchResults.addView(text("正在搜索…", 16f, LibraryStyle.SECONDARY), LinearLayout.LayoutParams(-1, dp(44)))
        worker.execute {
            val result = runCatching { data.catalog(term, 1, VodRequest()) }.getOrNull()
            val songs = runCatching {
                kotlinx.coroutines.runBlocking { MusicRepository.get(applicationContext).search(term) }
            }.getOrDefault(emptyList())
            main.post {
                if (!active || generation != searchGeneration) return@post
                searchResults.removeAllViews()
                val model = VodLibraryModel()
                if (result != null) runCatching { model.updateSources(data.sources()); model.applyPage(result, term, 1) }
                val works = if (result == null) emptyList() else model.works(term, false)
                if (works.isEmpty() && songs.isEmpty()) {
                    searchResults.addView(text(if (result == null) "搜索失败，请重试" else "未找到“$term”", 16f, LibraryStyle.SECONDARY))
                    return@post
                }
                fun addRow(label: String, action: () -> Unit) {
                    searchResults.addView(text(label, 18f, LibraryStyle.INK).apply {
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        background = LibraryStyle.round(this@VodLibraryActivity, Color.argb(120, 30, 34, 43), 10)
                        isFocusable = true; isClickable = true
                        setOnClickListener { hideSearchOverlay(); action() }
                    }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
                }
                songs.take(12).forEach { track ->
                    addRow("音乐 · ${track.name} · ${track.artists.joinToString(" / ")}") {
                        musicResults = songs; playMusic(track)
                    }
                }
                works.take(24).forEach { work ->
                    addRow(work.title) {
                        selectedWorkKey = work.key; selectedWorkPosition = 0
                        previewItem(work); pendingAutoPlay = true
                    }
                }
                searchResults.getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun buildWall() {
        wall = FrameLayout(this).apply { tag = "library-wall"; clipChildren = true }
        playerView = (android.view.LayoutInflater.from(this)
            .inflate(R.layout.view_library_player, wall, false) as PlayerView).apply {
            id = View.generateViewId(); tag = "library-video"; useController = false
            isFocusable = true; isFocusableInTouchMode = true
            visibility = View.GONE; setShutterBackgroundColor(Color.BLACK)
            setOnClickListener { togglePlayback() }
        }
        wall.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        hero = LibraryPosterView(this).apply {
            id = View.generateViewId(); tag = "library-hero"
            hideTitle()
            isFocusable = true; isFocusableInTouchMode = true
            setOnClickListener {
                val item = posters.items.getOrNull(selectedCardIndex)
                if (item?.music != null) playMusic(item.music) else togglePlayback()
            }
        }
        wall.addView(hero, FrameLayout.LayoutParams(-1, -1))
        posters = LibraryPosterAdapter(this, images, ::loadMusicArtwork)
        emptyMessage = text("", 20f, LibraryStyle.SECONDARY).apply {
            gravity = Gravity.CENTER; tag = "library-empty"; visibility = View.GONE
        }
        wall.addView(emptyMessage, FrameLayout.LayoutParams(-1, -1))
        retry = button("重试", "library-retry") {
            focusPosters(); if (libraryModel.page == 0) refreshLocal(false) else loadPage(retryPage)
        }.apply {
            setOnKeyListener { _, key, event ->
                if (key !in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT) || posters.isEmpty) false
                else { if (event.action == KeyEvent.ACTION_DOWN) focusPosters(); true }
            }
        }
        wall.addView(retry, FrameLayout.LayoutParams(-2, dp(42), Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(dp(16), 0, dp(16), dp(16)) })
    }

    /** One full-screen cover: only the focused resource's poster is rendered. */
    private fun renderHero() {
        // Catalogue/cache refreshes must not replace an already decoded episode's picture.
        player?.takeIf { renderedFrame }?.let {
            revealVideo(it)
            return
        }
        val item = posters.items.getOrNull(selectedCardIndex) ?: return
        hero.animate().cancel(); hero.alpha = 1f
        hero.bindTitle(item.title)
        hero.contentDescription = item.title
        val music = item.music
        val framePrefs = getSharedPreferences("vod-frame-index", MODE_PRIVATE)
        val frameId = framePrefs.getString(item.key, null)
            ?: framePrefs.getString("__last_frame", null)?.takeIf { item.key == lastFrameWorkKey }
        val cachedFrame = frameId?.let(::loadCachedFrame)
        if (cachedFrame != null) {
            hero.image.scaleType = ImageView.ScaleType.CENTER_CROP
            hero.image.setImageBitmap(cachedFrame)
            hero.visibility = View.VISIBLE
            showingFrame = true
            PlaybackThemeColor.fromBitmap(cachedFrame)?.let { resourceTheme = it }
        } else if (music != null) {
            loadMusicArtwork(hero.image, music)
        } else if (spotlightWork?.key == item.key) {
            hero.visibility = View.VISIBLE
            hero.image.scaleType = ImageView.ScaleType.FIT_CENTER
            val bitmap = searchArtwork
            if (bitmap != null) hero.image.setImageBitmap(bitmap)
            else if (artworkRequestedFor != item.key) {
                artworkRequestedFor = item.key
                images.load(hero.image, item)
            }
        } else {
            showingFrame = false
            if (artworkRequestedFor != null) images.load(hero.image, "")
            artworkRequestedFor = null
            hero.image.setImageDrawable(null)
        }
    }

    private fun selectGalleryIndex(index: Int) {
        val next = index.coerceIn(0, maxOf(0, posters.count - 1))
        if (next == selectedCardIndex && posters.count > 0) return
        pendingAutoPlay = false
        selectedCardIndex = next; selectedWorkPosition = next
        selectedWorkKey = posters.items.getOrNull(next)?.key.orEmpty()
        renderHero()
        schedulePreview(next)
        maybeLoadMore(next)
    }

    /** Full-height posters, selected one pinned to the left edge; neighbours fill the rest. */
    private fun maybeLoadMore(position: Int) {
        if (!active || cachedOnly || catalogLoading || !libraryModel.hasMore) return
        if (position >= posters.count - 2) loadPage(libraryModel.page + 1)
    }

    private fun schedulePreview(position: Int) {
        previewPosition = position
        main.removeCallbacks(preview)
        main.postDelayed(preview, 240)
    }

    private fun previewItem(item: LibraryWork) {
        selectedWorkKey = item.key
        // Cache polling can redraw the catalogue while the same work is playing.
        if (work?.key == item.key) return
        work?.let { previous ->
            if (selectedLine.isNotEmpty()) lineCache[previous.key] = selectedLine
            if (selectedEpisode.isNotEmpty()) episodeCache[previous.key] = selectedEpisode
        }
        val promote = preloadPlayer != null && preloadWorkKey == item.key
        if (promote) {
            releasePlayer(true)
            val pp = preloadPlayer
            preloadPlayer = null
            if (pp != null) {
                frameSource = preloadFrameSource
                player = pp; playerRef = preloadRef; playerId = preloadId; playerWorkKey = item.key
                playerView.player = pp; playerView.visibility = View.VISIBLE
                if (preloadResume > 0) pp.seekTo(preloadResume)
                preparedKey = item.key; renderedFrame = false
                updatePlaybackControls()
            }
        } else {
            releasePlayer(true)
            releasePreload()
            preparedKey = ""; renderedFrame = false; playerWorkKey = item.key
            playerView.visibility = View.GONE; hero.visibility = View.VISIBLE
        }
        // Keep the previous frame/poster visible until the next image is ready; clearing here caused a blank flash.
        resourceTheme = null
        work = item; detail = null; playDetail = null; detailNotice = ""; detailError = ""; markedEpisodes.clear()
        cacheMode = false; detailInvalidated = false; onlineDetailRequested = true
        playerTitle = item.title
        val sameSession = item.key == sessionWorkKey ||
            (sessionVodId.isNotEmpty() && item.vodId == sessionVodId && item.sourceId == sessionSourceId)
        if (!sameSession) {
            selectedLine = ""
            selectedEpisode = ""
            sessionWorkKey = ""; sessionSourceId = ""; sessionVodId = ""
            episodeAdapter.items = emptyList(); renderEpisodes()
        } else {
            sessionWorkKey = item.key; sessionSourceId = item.sourceId; sessionVodId = item.vodId
        }
        if (item.music != null) {
            showDetailMessage("按确认键播放歌曲")
            renderActions()
            return
        }
        val cachedDetail = detailCache.get(item.key)
        if (cachedDetail != null && item.current) {
            detail = cachedDetail.copy(work = item); playDetail = cachedDetail
            selectedLine = lineCache[item.key] ?: cachedDetail.lines.firstOrNull()?.id.orEmpty()
            selectedEpisode = episodeCache[item.key].orEmpty()
            renderDetail(false)
            // Refresh in the background; the cached strip stays interactive instead of being rebuilt empty.
            loadDetail(true)
            return
        }
        if (item.current) loadDetail(true) else invalidateDetail()
    }

    private fun submitSearch(value: String, settleFocus: Boolean = true) {
        val term = value.trim().take(200)
        changeFilter(term, false)
        musicSearchGeneration++
        musicResults = emptyList()
        setMusicResults(emptyList())
        if (term.isEmpty()) return
        if (settleFocus) {
            hideKeyboard(); searchInput.clearFocus()
            main.post { focusPosters() }
        }
        val generation = musicSearchGeneration
        worker.execute {
            val found = try {
                val search = musicSearchOverride
                kotlinx.coroutines.runBlocking {
                    search?.invoke(term) ?: MusicRepository.get(applicationContext).search(term)
                }
            } catch (_: Exception) { emptyList<CatalogTrack>() }
            main.post {
                if (!active || generation != musicSearchGeneration || query != term) return@post
                setMusicResults(found)
            }
        }
    }

    private fun setMusicResults(items: List<CatalogTrack>) {
        musicResults = items
        if (::posters.isInitialized && active) renderWall()
    }

    private fun musicWork(track: CatalogTrack): LibraryWork = LibraryWork(
        sourceId = "music", vodId = track.id, revision = 1, epoch = 1,
        title = track.name, type = "音乐", sourceName = track.artists.joinToString(" / "),
        music = track)

    /** Album covers are loaded from the per-track cache or the public artwork URL. */
    private fun loadMusicArtwork(view: ImageView, track: CatalogTrack) {
        val expected = "music-art:" + track.id
        view.tag = expected
        view.setImageDrawable(null)
        musicArtworkCache.get(track.id)?.let { view.setImageBitmap(it); return }
        musicArtworkExecutor.execute {
            val source = try {
                kotlinx.coroutines.runBlocking { MusicRepository.get(applicationContext).previewArtwork(track) }
            } catch (_: Exception) { null }
            val bitmap = decodeMusicArtwork(source) ?: return@execute
            main.post {
                if (view.tag == expected) {
                    view.setImageBitmap(bitmap); musicArtworkCache.put(track.id, bitmap)
                }
            }
        }
    }

    private fun decodeMusicArtwork(source: String?): Bitmap? = runCatching {
        if (source.isNullOrBlank()) return@runCatching null
        val bytes = when {
            source.startsWith("file:") || source.startsWith("/") -> {
                val file = if (source.startsWith("file:")) {
                    android.net.Uri.parse(source).path?.let { java.io.File(it) }
                } else java.io.File(source)
                if (file == null || !file.isFile) return@runCatching null
                file.readBytes()
            }
            source.startsWith("https://") || source.startsWith("http://") ->
                musicHttp.newCall(Request.Builder().url(source).build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.bytes() ?: return@runCatching null
                }
            else -> return@runCatching null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 320 && bounds.outHeight / (sample * 2) >= 320) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    private fun releasePreload() {
        preloadGeneration++
        preloadPlayer?.release(); preloadPlayer = null
        preloadFrameSource = null
        preloadWorkKey = ""; preloadRef = null; preloadId = ""; preloadResume = 0L
    }

    /** Warm the next catalogue resource's first episode so a right switch shows video immediately. */
    private fun preloadNextResource() {
        if (!active) return
        if (!VodRenderers.supportsParallelPlayback) { releasePreload(); return }
        val next = posters.items.getOrNull(selectedCardIndex + 1)
        if (next == null || next.music != null) { releasePreload(); return }
        if (preloadWorkKey == next.key && preloadPlayer != null) return
        releasePreload()
        val generation = preloadGeneration
        val ownerKey = selectedWorkKey
        val downloads = VodCacheManager.get(this)
        val request = VodRequest()
        worker.execute {
            try {
                val raw = data.detail(next, request)
                val value = LibraryDetail.online(next, raw)
                val prefs = getSharedPreferences("media-progress", MODE_PRIVATE)
                var bestLine = 0; var bestEpisode: LibraryEpisode? = null; var bestAt = 0L
                value.lines.forEachIndexed { lineIndex, line ->
                    line.episodes.forEach { episode ->
                        val ref = episode.reference
                        val id = MacCmsProvider.playbackId(ref.optString("sourceId"), ref.optString("vodId"),
                            ref.optString("lineId"), ref.optString("episodeKey"))
                        val at = prefs.getLong("$id:updatedAt", 0L)
                        if (prefs.getLong(id, 0L) > 0 && at > bestAt) { bestAt = at; bestLine = lineIndex; bestEpisode = episode }
                    }
                }
                val line = value.lines.getOrNull(bestLine) ?: return@execute
                val episode = bestEpisode ?: line.episodes.firstOrNull() ?: return@execute
                val ref = JSONObject(episode.reference.toString())
                val cached = downloads.completedSpec(ref)
                val spec = cached ?: VodRepository.get(this).resolve(ref, request)
                request.check()
                val uri = Uri.parse(spec.getString("url"))
                require(uri.scheme in listOf("http", "https"))
                val http = downloads.playbackFactory(spec)
                val id = spec.optString("id")
                val resume = prefs.getLong(id, 0L)
                main.post {
                    if (!active || generation != preloadGeneration || selectedWorkKey != ownerKey) return@post
                    val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
                    val instance = ExoPlayer.Builder(this, VodRenderers.create(this)).setMediaSourceFactory(source).build()
                    instance.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    instance.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(id)
                        .setMimeType(spec.optString("mimeType", "video/mp4")).build())
                    instance.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            if (player === instance) { renderedFrame = true; sampleEpisodeTheme(instance); revealVideo(instance) }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (player !== instance) return
                            main.removeCallbacks(recordWatchProgress)
                            if (isPlaying) {
                                recordRecentPlayback(value.work, ref, episode.label)
                                main.postDelayed(recordWatchProgress, 5000)
                                revealVideo(instance); scheduleHideChrome(); renderChrome()
                            }
                        }
                    })
                    instance.prepare(); instance.playWhenReady = false; instance.pause()
                    preloadFrameSource = source
                    preloadPlayer = instance; preloadWorkKey = value.work.key; preloadRef = ref
                    preloadId = id; preloadResume = resume
                }
            } catch (_: Exception) { }
        }
    }

    private fun frameFile(id: String): File {
        val dir = File(cacheDir, "vod-frame-cache").apply { mkdirs() }
        return File(dir, id.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120) + ".jpg")
    }

    private fun loadCachedFrame(id: String): Bitmap? {
        val file = frameFile(id)
        if (!file.isFile || file.length() == 0L) return null
        val frame = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        if (hasVisiblePixels(frame)) return frame
        frame.recycle()
        return null
    }

    private fun captureFrame() {
        val owner = player ?: return
        if (!renderedFrame || playerId.isEmpty()) return
        val id = playerId
        val workKey = playerWorkKey
        val position = owner.currentPosition
        val request = ++frameRequest
        val source = frameSource
        val item = owner.currentMediaItem
        val surface = playerView.videoSurfaceView as? SurfaceView
        fun publish(frame: Bitmap) {
            if (request != frameRequest || !hasVisiblePixels(frame)) {
                frame.recycle()
                return
            }
            saveFrame(frame, id, workKey)
            if (player === owner && !owner.playWhenReady &&
                kotlin.math.abs(owner.currentPosition - position) <= 250) {
                hero.animate().cancel()
                hero.alpha = 1f
                hero.image.setImageBitmap(frame)
                hero.visibility = View.VISIBLE
                showingFrame = true
            } else {
                frame.recycle()
            }
        }
        fun decode() {
            if (source == null || item == null || request != frameRequest) return
            val extractor = FrameExtractor.Builder(applicationContext, item)
                .setMediaSourceFactory(source).build()
            val future = extractor.getFrame(position)
            val timeout = Runnable { future.cancel(true) }
            main.postDelayed(timeout, 12_000)
            future.addListener({
                main.removeCallbacks(timeout)
                try {
                    if (!future.isCancelled) publish(future.get().bitmap)
                } catch (error: Exception) {
                    android.util.Log.w("MaciPlay", "preview decode failed: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
                } finally {
                    extractor.close()
                }
            }, { main.post(it) })
        }
        if (surface == null || !surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) {
            decode()
            return
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS && hasVisiblePixels(bitmap)) publish(bitmap)
                else {
                    bitmap.recycle()
                    decode()
                }
            }, main)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            decode()
        }
    }

    private fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        for (y in 1..8) for (x in 1..8) {
            val pixel = bitmap.getPixel(bitmap.width * x / 9, bitmap.height * y / 9)
            if (maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) > 20) return true
        }
        return false
    }

    private fun saveFrame(frame: Bitmap, id: String, workKey: String) {
        if (id.isEmpty()) { frame.recycle(); return }
        try {
            val file = AtomicFile(frameFile(id))
            val stream = file.startWrite()
            try {
                check(frame.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            val prefs = getSharedPreferences("vod-frame-index", MODE_PRIVATE).edit()
            if (workKey.isNotEmpty()) prefs.putString(workKey, id)
            prefs.putString("__last_frame", id)
            prefs.putString("__last_work_key", workKey)
            prefs.putString("__last_title", work?.title.orEmpty())
            prefs.apply()
        } catch (_: Exception) { }
    }

    private fun scheduleHideChrome() {
        main.removeCallbacks(hideUiRunnable)
        if (player?.isPlaying == true && !uiHidden && dialog?.isShowing != true &&
            !(::globalSearch.isInitialized && globalSearch.isOpen()) &&
            !(::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE)) {
            main.postDelayed(hideUiRunnable, 4000)
        }
    }

    private fun hideChrome() {
        if (uiHidden) return
        uiHidden = true
        main.removeCallbacks(hideUiRunnable)
        detailScreen.visibility = View.GONE
        chrome.setControlsVisible(false)
        playerView.requestFocusFromTouch()
    }

    private fun showChrome() {
        uiHidden = false
        detailScreen.visibility = View.VISIBLE
        chrome.setControlsVisible(true)
        scheduleHideChrome()
    }

    private fun togglePlayback() {
        remoteSeek.commit()
        val p = player
        if (p == null) {
            val episode = episodeAdapter.items.getOrNull(episodeAdapter.items.indexOfFirst {
                it.reference.optString("episodeKey") == selectedEpisode
            }) ?: episodeAdapter.items.firstOrNull()
            if (episode != null) startEpisode(episode, true) else pendingUserPlay = true
            return
        }
        if (p.playWhenReady) {
            p.pause()
            if (p.playbackState == Player.STATE_BUFFERING) showDetailMessage("已暂停")
            savePlayerProgress()
            captureFrame()
            renderEpisodes()
            showChrome()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
            if (p.playbackState == Player.STATE_BUFFERING) showDetailMessage("正在缓冲…")
            revealVideo(p)
            scheduleHideChrome()
        }
        updatePlaybackControls()
    }

    private fun savePlayerProgress() {
        val p = player ?: return
        if (playerId.isEmpty()) return
        val prefs = getSharedPreferences("media-progress", MODE_PRIVATE)
        val duration = p.duration.takeIf { it > 0 } ?: return
        val completed = p.playbackState == Player.STATE_ENDED
        val position = p.currentPosition.coerceIn(0L, duration)
        if (!completed && position == 0L && !p.playWhenReady) return
        prefs.edit().putLong(playerId, if (completed) 0L else position)
            .putLong("$playerId:watched", if (completed) duration else position)
            .putLong("$playerId:duration", duration).putLong("$playerId:updatedAt", System.currentTimeMillis()).apply()

    }

    private fun releasePlayer(save: Boolean = true) {
        remoteSeek.reset()
        if (::chrome.isInitialized) chrome.hideSeekTime()
        main.removeCallbacks(recordWatchProgress)
        main.removeCallbacks(startupTimeout)
        playerGeneration++; playerRequest?.cancel(); playerRequest = null
        if (save) savePlayerProgress()
        player?.release(); player = null
        playerView.player = null; playerView.visibility = View.GONE
        if (::hero.isInitialized) {
            hero.animate().cancel(); heroFading = false; hero.alpha = 1f; hero.visibility = View.VISIBLE
        }
        playerRef = null; playerId = ""; renderedFrame = false; frameSource = null
        updatePlaybackControls()
    }

    /** Once a frame is ready, play intent owns the surface even during short buffering gaps. */
    private fun revealVideo(instance: ExoPlayer) {
        if (player !== instance || !renderedFrame || !instance.playWhenReady) return
        if (hero.visibility != View.VISIBLE || heroFading) return
        if (spotlightWork?.key != work?.key || spotlightWork == null || hero.image.drawable == null) {
            hero.visibility = View.GONE
            showingFrame = false
            return
        }
        heroFading = true
        hero.animate().alpha(0f).setDuration(160).withEndAction {
            heroFading = false
            if (player === instance) {
                hero.visibility = View.GONE
                hero.alpha = 1f
                showingFrame = false
            }
        }.start()
    }

    private fun recordRecentPlayback(current: LibraryWork?, reference: JSONObject, label: String) {
        if (current == null || LibraryReference.workKey(reference) != current.key) return
        RecentVodHistory(this).record(current, reference, label)
        saveSessionSnapshot(current, reference, label)
    }

    private fun startEpisode(episode: LibraryEpisode, autoplay: Boolean) {
        android.util.Log.d("MaciPlay", "start label=" + episode.label + " auto=" + autoplay)
        com.live.tv.MainActivity.releaseLiveForPlayback()
        main.removeCallbacks(preview)
        releasePlayer(true)
        showChrome()
        val generation = ++playerGeneration
        val request = VodRequest(); playerRequest = request
        val downloads = VodCacheManager.get(this)
        val ref = JSONObject(episode.reference.toString())
        val playingWork = work
        val progress = getSharedPreferences("media-progress", MODE_PRIVATE)
        val refId = MacCmsProvider.playbackId(ref.optString("sourceId"), ref.optString("vodId"),
            ref.optString("lineId"), ref.optString("episodeKey"))
        val resume = progress.getLong(refId, 0L)
        showDetailMessage(if (autoplay) "正在开始播放…" else "正在准备画面…")
        worker.execute {
            try {
                request.check()
                val cached = downloads.completedSpec(ref)
                if (cached != null) VodRepository.get(this).validateReference(ref, request)
                val prefetched = if (cached == null) VodSearchPreview.preparedSpec(ref) else null
                if (prefetched != null) VodRepository.get(this).validateReference(ref, request)
                val spec = cached ?: prefetched ?: VodRepository.get(this).resolve(ref, request)
                request.check()
                val uri = Uri.parse(spec.getString("url"))
                require(uri.scheme in listOf("http", "https")) { "Only online HTTP media is supported" }
                val network = downloads.playbackFactory(spec)
                val http = if (prefetched != null) VodSearchPreview.playbackFactory(network) else network
                main.post {
                    if (generation != playerGeneration) return@post
                    playerRequest = null
                    if (!active) return@post
                    val item = MediaItem.Builder().setUri(uri).setMediaId(spec.optString("id"))
                        .setMimeType(spec.optString("mimeType", "video/mp4")).build()
                    val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
                    val instance = ExoPlayer.Builder(this, VodRenderers.create(this)).setMediaSourceFactory(source).build()
                    frameSource = source
                    player = instance; playerRef = ref; playerId = refId
                    android.util.Log.d("MaciPlay", "player created id=" + refId)
                    playerView.player = instance; playerView.visibility = View.VISIBLE
                    instance.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    instance.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            android.util.Log.d("MaciPlay", "state=" + state + " ready=" + instance.playWhenReady +
                                " playing=" + instance.isPlaying + " video=" + instance.videoSize + " error=" + instance.playerError)
                            if (player !== instance) return
                            main.removeCallbacks(startupTimeout)
                            if (state == Player.STATE_BUFFERING) {
                                if (!renderedFrame) main.postDelayed(startupTimeout, 20_000)
                                if (instance.playWhenReady) showDetailMessage("正在缓冲…")
                            } else if (state == Player.STATE_READY) {
                                showDetailMessage("")
                                updatePlaybackControls()
                            }
                            if (state == Player.STATE_ENDED) { savePlayerProgress(); renderEpisodes() }
                        }
                        override fun onRenderedFirstFrame() {
                            if (player !== instance) return
                            android.util.Log.d("MaciPlay", "firstFrame ready=" + instance.playWhenReady +
                                " playing=" + instance.isPlaying + " hero=" + hero.visibility +
                                " playerView=" + playerView.visibility + " size=" + instance.videoSize)
                            renderedFrame = true
                            sampleEpisodeTheme(instance)
                            revealVideo(instance)
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            android.util.Log.d("MaciPlay", "isPlaying=" + isPlaying + " ready=" + instance.playWhenReady +
                                " state=" + instance.playbackState + " video=" + instance.videoSize)
                            if (player !== instance) return
                            main.removeCallbacks(recordWatchProgress)
                            if (isPlaying) {
                                recordRecentPlayback(playingWork, ref, episode.label)
                                main.postDelayed(recordWatchProgress, 5000)
                            }
                            renderChrome()
                            if (isPlaying) { revealVideo(instance); scheduleHideChrome() }
                            else if (!instance.playWhenReady) showChrome()
                        }
                        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                            android.util.Log.d("MaciPlay", "playWhenReady=" + playWhenReady + " reason=" + reason)
                            if (playWhenReady) revealVideo(instance)
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.d("MaciPlay", "error " + error.errorCodeName + " " + error.message)
                            if (player !== instance) return
                            main.removeCallbacks(startupTimeout)
                            showChrome()
                            showDetailMessage("播放失败，请检查来源连接或切换线路")
                            hero.visibility = View.VISIBLE
                            updatePlaybackControls()
                        }
                    })
                    instance.setMediaItem(item, resume)
                    instance.prepare()
                    instance.playWhenReady = autoplay
                    android.util.Log.d("MaciPlay", "after playWhenReady=" + instance.playWhenReady + " auto=" + autoplay)
                    if (!autoplay) { instance.pause(); showChrome() } else scheduleHideChrome()
                    updatePlaybackControls()
                    preloadNextResource()
                }
            } catch (error: Exception) {
                main.post {
                    if (generation != playerGeneration) return@post
                    playerRequest = null
                    if (active) showDetailMessage(message(error))
                }
            }
        }
    }

    private fun prepareLatestProgress(value: LibraryDetail) {
        if (player != null || playerRequest != null || preparedKey == value.work.key) return
        val restored = if (value.work.key == restoredWorkKey) {
            value.lines.firstOrNull { it.id == selectedLine }?.episodes
                ?.firstOrNull { it.reference.optString("episodeKey") == selectedEpisode }
        } else null
        val episode = restored ?: value.preferredEpisode(this, selectedLine) ?: return
        val targetLine = value.lines.indexOfFirst { it.id == episode.reference.optString("lineId") }
        preparedKey = value.work.key
        selectLine(targetLine, false)
        selectedEpisode = episode.reference.optString("episodeKey")
        val ref = episode.reference
        val frameId = MacCmsProvider.playbackId(ref.optString("sourceId"), ref.optString("vodId"),
            ref.optString("lineId"), ref.optString("episodeKey"))
        loadCachedFrame(frameId)?.let { frame ->
            images.cancel()
            hero.bindTitle(value.work.title)
            hero.image.setImageBitmap(frame)
            hero.visibility = View.VISIBLE
            showingFrame = true
        }
        startEpisode(episode, restored != null && LibraryReference.key(ref) == resumeEpisodeKey)
    }

    private fun updatePlaybackControls() {
        renderChrome()
    }

    private fun trackChoices(type: Int): List<Pair<Tracks.Group, List<Int>>> =
        player?.currentTracks?.groups.orEmpty().filter { it.type == type }.mapNotNull { group ->
            val indices = (0 until group.length).filter { group.isTrackSupported(it) }
            if (indices.isEmpty()) null else group to indices
        }

    private fun updateTrackLabel(view: TextView, type: Int) {
        val p = player ?: run { view.text = if (type == C.TRACK_TYPE_AUDIO) "无音轨" else "字幕关闭"; return }
        val choices = trackChoices(type)
        val selected = p.trackSelectionParameters.overrides.keys.any { it.type == type }
        view.text = when {
            choices.isEmpty() -> if (type == C.TRACK_TYPE_AUDIO) "无音轨" else "字幕关闭"
            type == C.TRACK_TYPE_AUDIO -> if (selected) "音轨" else "默认音轨"
            else -> if (selected) "字幕开" else "字幕关闭"
        }
    }

    private fun cycleSpeed() {
        val p = player ?: return
        val values = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        val next = values.firstOrNull { it > p.playbackParameters.speed + .001f } ?: values.first()
        p.setPlaybackSpeed(next); updatePlaybackControls()
    }

    private fun cycleTrack(type: Int) {
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val choices = trackChoices(type)
        if (choices.isEmpty()) return
        val parameters = p.trackSelectionParameters
        val disabled = type in parameters.disabledTrackTypes
        val currentGroup = parameters.overrides.keys.firstOrNull { it.type == type }
        val builder = parameters.buildUpon().clearOverridesOfType(type)
        if (disabled) {
            val choice = choices.first()
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(choice.first.mediaTrackGroup, choice.second))
        } else if (type == C.TRACK_TYPE_TEXT) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            val index = choices.indexOfFirst { it.first.mediaTrackGroup == currentGroup }
            val next = if (index + 1 < choices.size) index + 1 else 0
            val choice = choices[next]
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(choice.first.mediaTrackGroup, choice.second))
        }
        p.trackSelectionParameters = builder.build(); updatePlaybackControls()
    }

    private fun playMusic(track: CatalogTrack) {
        val queue = musicResults.ifEmpty { listOf(track) }
        startActivity(Intent(this, MusicActivity::class.java)
            .putExtra("selected_track", trackJson(track).toString())
            .putExtra("selected_queue", JSONArray(queue.map(::trackJson)).toString())
            .putExtra("music_query", query))
    }

    private fun trackJson(track: CatalogTrack) = JSONObject()
        .put("id", track.id).put("name", track.name)
        .put("artists", JSONArray().apply { track.artists.forEach { put(it) } })
        .put("album", track.album).put("pictureId", track.pictureId).put("lyricId", track.lyricId)

    private fun prefetchPosters() {
        // Posters are intentionally not fetched; the opening video frame is the preview.
    }

    private fun focusPosters() {
        if (posters.isEmpty || globalSearch.isOpen()) return
        val position = selectedWorkPosition.coerceIn(0, posters.count - 1)
        selectedCardIndex = position; selectedWorkKey = posters.items[position].key
        renderHero()
        chrome.resetFocus()
        hideKeyboard()
        schedulePreview(position)
    }

    private fun buildDetail() {
        detailScreen = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 3, 5, 9))
            tag = "library-detail"
        }
        episodeAdapter = LibraryEpisodeAdapter(this)
        chrome = MediaChromeView(this, object : MediaChromeView.Actions {
            override fun select(index: Int) {
                if (actionLoading || detailInvalidated) return
                val episode = episodeAdapter.items.getOrNull(index) ?: return
                val playing = playerRef
                if (player != null && playing != null &&
                    LibraryReference.key(playing) == LibraryReference.key(episode.reference)) {
                    val current = player
                    if (current?.playWhenReady == true && current.playbackState == Player.STATE_BUFFERING) {
                        showDetailMessage("正在缓冲…")
                    } else togglePlayback()
                } else {
                    play(episode)
                }
            }
            override fun longSelect(index: Int) {
                if (!actionLoading) episodeAdapter.items.getOrNull(index)?.let(::cacheOrRemoveEpisode)
            }
        }).apply {
            tag = "library-episodes"
            titleView.tag = "library-detail-title"
            titleView.id = View.generateViewId()
        }
        detailRetry = button("重试", "library-detail-retry") { loadDetail(true) }.apply { visibility = View.GONE }
        detailScreen.addView(detailRetry, FrameLayout.LayoutParams(dp(70), dp(38), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(22)
            bottomMargin = dp(8)
        })
    }

    private fun changeFilter(value: String, onlyCached: Boolean) {
        spotlightWork = null
        query = value.trim().take(200); cachedOnly = onlyCached; selectedWorkKey = ""; selectedWorkPosition = 0
        refreshLocal(true)
    }

    private fun refreshLocal(reset: Boolean) {
        if (!active) return
        localRequest?.cancel(); val request = VodRequest(); localRequest = request
        val token = ++localGeneration
        if (reset) libraryModel.resetOnline()
        val blocking = libraryModel.page == 0
        if (blocking) {
            catalogRequest?.cancel(); catalogGeneration++
            catalogLoading = true; catalogError = ""; renderWall()
        }
        val catalogToken = catalogGeneration
        val sourceToken = catalogSourcesGeneration
        val mutationToken = cacheMutationGeneration
        worker.execute {
            try {
                request.check(); val caches = data.caches(); val sources = data.sources(); request.check()
                main.post {
                    if (!localCurrent(token, request)) return@post
                    localRequest = null
                    // Only a completed reconciliation supersedes this source read. A failed
                    // later page still needs local refresh to remove revised/deleted sources.
                    if (sourceToken == catalogSourcesGeneration && libraryModel.updateSources(sources)) {
                        catalogRequest?.cancel(); catalogGeneration++; catalogLoading = false
                    } else if (blocking && catalogToken == catalogGeneration) catalogLoading = false
                    if (!actionLoading && mutationToken == cacheMutationGeneration) applyCacheStatus(caches)
                    renderWall()
                    if (work != null && !libraryModel.isCurrent(work!!.identity())) invalidateDetail()
                    if (!cachedOnly && libraryModel.page == 0 && libraryModel.sources.any { it.searchable }) loadPage(1)
                }
            } catch (error: Exception) {
                main.post {
                    if (!localCurrent(token, request)) return@post
                    localRequest = null
                    // A ready directory stays usable while local file validation is retried.
                    if (blocking && catalogToken == catalogGeneration) {
                        catalogLoading = false; catalogError = message(error); renderWall()
                    }
                }
            }
        }
    }

    private fun localCurrent(token: Int, request: VodRequest) = active && token == localGeneration &&
        localRequest === request && !request.isCancelled()

    private fun loadPage(page: Int) {
        if (!active || cachedOnly || catalogLoading) return
        catalogRequest?.cancel(); val request = VodRequest(); catalogRequest = request
        val token = ++catalogGeneration; val search = query
        catalogLoading = true; catalogError = ""; retryPage = page; renderWall()
        worker.execute {
            try {
                request.check(); val result = data.catalog(search, page, request); val sources = data.sources(); request.check()
                main.post {
                    if (!catalogCurrent(token, request)) return@post
                    catalogLoading = false
                    catalogSourcesGeneration++
                    if (libraryModel.updateSources(sources)) {
                        if (work != null && !libraryModel.isCurrent(work!!.identity())) invalidateDetail()
                        renderWall(); refreshLocal(false); return@post
                    }
                    try { libraryModel.applyPage(result, search, page) } catch (error: Exception) { catalogError = message(error) }
                    renderWall()
                }
            } catch (error: Exception) {
                main.post { if (catalogCurrent(token, request)) { catalogLoading = false; catalogError = message(error); renderWall() } }
            }
        }
    }

    private fun catalogCurrent(token: Int, request: VodRequest) = active && token == catalogGeneration && catalogRequest === request && !request.isCancelled()

    private fun renderWall() {
        if (!::hero.isInitialized) return
        player?.let(::revealVideo)
        val key = selectedWorkKey; val previous = selectedWorkPosition
        val films = libraryModel.works(query, cachedOnly)
        val pinned = spotlightWork?.takeIf {
            (it.key == restoredWorkKey || libraryModel.isCurrent(it.identity())) && films.none { film -> film.key == it.key }
        }
        posters.items = if (cachedOnly) films else listOfNotNull(pinned) + musicResults.map(::musicWork) + films
        if (posters.isEmpty) {
            if (!showingFrame) { hero.image.setImageDrawable(null); hero.bindTitle("") }
        } else {
            val position = posters.items.indexOfFirst { it.key == key }.takeIf { it >= 0 }
                ?: previous.coerceIn(0, maxOf(0, posters.count - 1))
            selectedCardIndex = position; selectedWorkPosition = position; selectedWorkKey = posters.items[position].key
            if (work?.key != selectedWorkKey || player == null || !renderedFrame) renderHero()
            // Reflect a new result set in the bottom panel immediately; focus changes stay debounced.
            previewPosition = position
            main.removeCallbacks(preview)
            preview.run()
        }
        main.post { prefetchPosters() }
        emptyMessage.visibility = if (posters.count == 0 && !showingFrame && !catalogLoading) View.VISIBLE else View.GONE
        emptyMessage.text = when {
            catalogError.isNotEmpty() -> "暂时无法加载，请重试"
            cachedOnly -> "暂无完整缓存"
            libraryModel.sourceError.isNotEmpty() -> libraryModel.sourceError
            libraryModel.sources.none { it.searchable } -> "暂无可用来源"
            query.isNotEmpty() -> "未找到“$query”"
            else -> "暂无影片"
        }
        val canRetry = !cachedOnly && (catalogError.isNotEmpty() || libraryModel.failures.isNotEmpty())
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE; retry.isEnabled = !catalogLoading
        val focusInPanel = chrome.hasFocus()
        if (dialog?.isShowing != true && !focusInPanel &&
            !retry.hasFocus() && !hero.hasFocus()) {
            if (posters.count > 0) focusPosters()
            else if (canRetry && !catalogLoading) retry.requestFocus()
        }
    }

    private fun loadDetail(online: Boolean) {
        val chosen = work ?: return
        if (chosen.music != null || !active || detailInvalidated) return
        detailRequest?.cancel(); actionRequest?.cancel(); actionGeneration++; actionLoading = false
        val request = VodRequest(); detailRequest = request; val token = ++detailGeneration
        detailLoading = true; onlineDetailRequested = online; detailNotice = ""; detailError = ""
        showDetailMessage("正在读取详情与选集…")
        detailRetry.visibility = View.GONE; renderActions()
        worker.execute {
            try {
                request.check(); data.validate(chosen.identity(), request)
                val verified = chosen.completed.filter { data.completed(it.reference, request) != null }
                val verifiedKeys = verified.map { LibraryReference.key(it.reference) }.toSet()
                val checked = chosen.copy(caches = chosen.caches.map {
                    if (it.complete && LibraryReference.key(it.reference) !in verifiedKeys) it.copy(state = "failed") else it
                })
                val result = try { LibraryDetail.online(checked, data.detail(chosen, request)) }
                    catch (error: Exception) {
                        request.check()
                        if (invalidIdentity(error) || verified.isEmpty()) throw error
                        LibraryDetail.offline(checked, verified)
                    }
                data.validate(chosen.identity(), request); request.check()
                main.post {
                    if (!detailCurrent(token, request, chosen)) return@post
                    detailLoading = false; playDetail = result; work = result.work
                    detail = if (cacheMode) LibraryDetail.forCacheSelection(result.work, result) else result
                    detailCache.put(result.work.key, result)
                    renderDetail(false)
                }
            } catch (error: Exception) {
                main.post { if (detailCurrent(token, request, chosen)) {
                    detailLoading = false
                    if (invalidIdentity(error)) invalidateDetail() else detailFailure(message(error))
                } }
            }
        }
    }

    private fun detailCurrent(token: Int, request: VodRequest, chosen: LibraryWork) =
        active && token == detailGeneration && detailRequest === request && work?.key == chosen.key && !request.isCancelled()

    private fun detailFailure(error: String) {
        detailLoading = false; showDetailMessage(error); detailRetry.visibility = View.VISIBLE
        detailError = error
        if (cacheMode) {
            detail = work?.let { LibraryDetail.forCacheSelection(it, playDetail) }
            renderDetail(false)
        } else if (detail == null) {
            episodeAdapter.items = emptyList(); renderEpisodes()
        }
        detailRetry.visibility = View.VISIBLE
        renderActions(); if (detail == null && dialog?.isShowing != true) detailRetry.requestFocus()
    }

    private fun invalidateDetail() {
        detailGeneration++; actionGeneration++; detailRequest?.cancel(); actionRequest?.cancel()
        detailLoading = false; actionLoading = false; detailInvalidated = true; playDetail = null
        cacheMode = true; detailNotice = "来源已改变；仍可删除本地缓存"; detailError = ""
        work = work?.copy(current = false)
        detail = work?.let { LibraryDetail.forCacheSelection(it, null) }
        detailRetry.visibility = View.GONE
        renderDetail(false)
    }

    private fun renderDetail(focus: Boolean) {
        val value = detail ?: return
        if (!showingFrame && spotlightWork?.key != value.work.key) hero.visibility = View.GONE
        if (cacheMode) {
            val available = value.lines.flatMap { it.episodes }.map { LibraryReference.key(it.reference) }.toSet()
            markedEpisodes.keys.retainAll(available)
        }
        playerTitle = value.work.title
        showDetailMessage(when {
            detailLoading -> "正在读取详情与选集…"
            detailInvalidated -> "来源已改变；仍可删除本地缓存"
            detailError.isNotEmpty() -> detailError
            detailNotice.isNotEmpty() -> detailNotice
            !cacheMode && value.offline -> "网络暂时不可用，显示本地完整缓存"
            else -> ""
        })
        episodeAdapter.cacheMode = cacheMode
        episodeAdapter.marked = markedEpisodes.keys.toSet()
        renderEpisodes()
        val index = value.lines.indexOfFirst { it.id == selectedLine }.coerceAtLeast(0)
        if (value.lines.isEmpty()) { setEpisodeItems(emptyList()); renderActions() }
        else selectLine(index, focus)
        if (pendingAutoPlay || pendingUserPlay) {
            val preferred = if (pendingAutoPlay && spotlightWork?.key == value.work.key) value.preferredEpisode(this) else null
            pendingAutoPlay = false; pendingUserPlay = false
            if (preferred != null) {
                selectLine(value.lines.indexOfFirst { it.id == preferred.reference.optString("lineId") }, false)
                play(preferred)
            } else {
                (episodeAdapter.items.firstOrNull { it.reference.optString("episodeKey") == selectedEpisode }
                    ?: episodeAdapter.items.firstOrNull())?.let { startEpisode(it, true) }
            }
        } else if (!detailLoading && player == null) prepareLatestProgress(value)
        updatePlaybackControls()
    }

    private fun selectLine(index: Int, focus: Boolean) {
        val line = detail?.lines?.getOrNull(index) ?: return
        if (selectedLine != line.id) selectedEpisode = ""
        selectedLine = line.id; setEpisodeItems(line.episodes)
        val position = line.episodes.indexOfFirst { it.reference.optString("episodeKey") == selectedEpisode }.coerceAtLeast(0)
        selectedEpisode = line.episodes.getOrNull(position)?.reference?.optString("episodeKey").orEmpty()
        renderEpisodes()
        renderActions()
        work?.let { lineCache[it.key] = line.id }
        if (selectedEpisode.isNotEmpty()) work?.let { episodeCache[it.key] = selectedEpisode }
        detail?.let { detailCache.put(it.work.key, it) }
        if (focus && dialog?.isShowing != true) chrome.resetFocus()
    }

    private fun showLineChooser() {
        showChrome()
        val value = detail
        val lineLabels = value?.lines?.map { it.label }.orEmpty()
        val labels = lineLabels + "影视来源…"
        val index = value?.lines?.indexOfFirst { it.id == selectedLine }?.coerceAtLeast(0) ?: lineLabels.size
        val popup = TvDialogs.builder(this).selectedIndex(index).setTitle("播放线路与来源")
            .setItems(labels.toTypedArray()) { owner, selected ->
                if (selected >= lineLabels.size) {
                    startActivity(Intent(this@VodLibraryActivity, TvSourcesActivity::class.java))
                } else {
                    val id = value?.lines?.getOrNull(selected)?.id
                    val current = detail
                    if (active && value != null && current?.work?.key == value.work.key && (!detailInvalidated || cacheMode)) {
                        val position = current?.lines?.indexOfFirst { it.id == id } ?: -1
                        if (position >= 0) selectLine(position, false)
                    }
                }
                owner.dismiss()
            }.show()
        dialog = popup; popup.setOnDismissListener {
            if (dialog === popup) {
                dialog = null
                scheduleHideChrome()
            }
        }
        popup.listView.tag = "library-line-dialog"
        popup.listView.post {
            if (popup.isShowing) { popup.listView.requestFocusFromTouch(); popup.listView.setSelection(index) }
        }
    }

    private fun setEpisodeItems(items: List<LibraryEpisode>) {
        episodeAdapter.items = items
        renderEpisodes()
    }

    private fun renderEpisodes() {
        renderChrome()
    }

    private fun cacheOrRemoveEpisode(episode: LibraryEpisode) {
        showDetailMessage("长按缓存：${episode.label}")
        val chosen = work ?: run { showDetailMessage("详情尚未就绪，请稍候"); return }
        if (actionLoading) { showDetailMessage("正在处理上一项缓存操作，请稍候"); return }
        if (detailLoading) { showDetailMessage("正在读取详情，请稍候再试"); return }
        if (detailInvalidated) { showDetailMessage("来源已改变，请退出后重新进入"); return }
        if (episode.cacheState == "removing") return
        val cached = episode.cached || episode.cacheState in setOf("complete", "queued", "resolving", "downloading")
        if (cached) confirmRemoveEpisode(chosen, episode) else cacheEpisode(chosen, episode)
    }

    private fun cacheEpisode(chosen: LibraryWork, episode: LibraryEpisode) {
        if (work?.key != chosen.key || actionLoading) return
        runCacheBatch(chosen, listOf(JSONObject(episode.reference.toString())), false)
    }

    private fun confirmRemoveEpisode(chosen: LibraryWork, episode: LibraryEpisode) {
        if (work?.key != chosen.key || actionLoading) return
        val popup = TvDialogs.builder(this).setTitle("删除本集缓存")
            .setMessage("${chosen.title} · ${episode.label}\n将删除这集的本地缓存，下载中的任务也会停止。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (active && work?.key == chosen.key && !actionLoading)
                    runCacheBatch(chosen, listOf(JSONObject(episode.reference.toString())), true)
            }.show()
        dialog = popup
        popup.setOnDismissListener {
            if (dialog === popup) {
                dialog = null
                scheduleHideChrome()
            }
        }
        popup.window?.decorView?.tag = "library-delete-dialog"
        popup.getButton(AlertDialog.BUTTON_NEGATIVE).tag = "library-delete-cancel"
        popup.getButton(AlertDialog.BUTTON_POSITIVE).tag = "library-delete-confirm"
        popup.getButton(AlertDialog.BUTTON_NEGATIVE).post {
            if (popup.isShowing) popup.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocusFromTouch()
        }
    }

    private fun cacheFor(episode: LibraryEpisode) = work?.caches?.firstOrNull { LibraryReference.key(it.reference) == LibraryReference.key(episode.reference) }
    private fun selectedDownloads() = markedEpisodes.values.filter {
        cacheFor(it)?.state !in listOf("complete", "queued", "resolving", "downloading", "removing")
    }
    private fun selectedCaches() = work?.caches.orEmpty().filter { LibraryReference.key(it.reference) in markedEpisodes && it.state != "removing" }

    private fun renderActions() {
        if (!::chrome.isInitialized) return
        detailRetry.visibility = if (!detailLoading && !detailInvalidated &&
            (detailError.isNotEmpty() || playDetail?.offline == true)) View.VISIBLE else View.GONE
    }

    private fun sampleEpisodeTheme(owner: ExoPlayer) {
        if (player !== owner || !active) return
        val surface = playerView.videoSurfaceView as? SurfaceView ?: return
        if (!surface.holder.surface.isValid) return
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { result ->
                val color = if (result == PixelCopy.SUCCESS && player === owner && active)
                    PlaybackThemeColor.fromBitmap(bitmap) else null
                bitmap.recycle()
                if (color != null) {
                    resourceTheme = color
                    renderChrome()
                }
            }, main)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
        }
    }

    private fun renderChrome() {
        if (!::chrome.isInitialized) return
        val progress = getSharedPreferences("media-progress", MODE_PRIVATE)
        val items = episodeAdapter.items.map {
            val ref = it.reference
            val id = MacCmsProvider.playbackId(ref.optString("sourceId"), ref.optString("vodId"),
                ref.optString("lineId"), ref.optString("episodeKey"))
            val duration = progress.getLong("$id:duration", 0L)
            val position = progress.getLong("$id:watched", progress.getLong(id, 0L))
            val watched = if (duration > 0L) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
            MediaChromeView.Item(it.label, cache = EpisodeDecoration(
                complete = it.cached || it.cacheState == "complete",
                active = it.cacheState in setOf("queued", "resolving", "downloading"),
                fraction = it.percent.coerceIn(0, 100) / 100f,
            ), watched = watched)
        }
        val selected = episodeAdapter.items.indexOfFirst {
            it.reference.optString("episodeKey") == selectedEpisode
        }.coerceAtLeast(0)
        chrome.render(playerTitle, "", items, selected, status = detailStatus, themeColor = resourceTheme ?: PlaybackThemeColor.FALLBACK)
        val current = player
        val duration = current?.duration?.takeIf { it > 0 }
        if (duration != null) {
            chrome.setTitleProgress(remoteSeek.previewPosition(current) ?: current.currentPosition, duration, resourceTheme)
        } else {
            val reference = episodeAdapter.items.getOrNull(selected)?.reference
            val id = reference?.let {
                MacCmsProvider.playbackId(it.optString("sourceId"), it.optString("vodId"),
                    it.optString("lineId"), it.optString("episodeKey"))
            }
            chrome.setTitleProgress(id?.let { progress.getLong(it, 0L) } ?: 0L,
                id?.let { progress.getLong("$it:duration", 0L) } ?: 0L, resourceTheme)
        }
    }

    private fun play(episode: LibraryEpisode) {
        // A cached/session episode list stays interactive while its detail refresh is in flight.
        if (!active) return
        if (actionLoading) { showDetailMessage("正在处理缓存操作，请稍候"); return }
        if (detailInvalidated) { showDetailMessage("来源已改变，请退出后重新进入"); return }
        if (episode.cached && detail?.work != null) {
            // Cached media is resolved by the same playback path; no separate player Activity.
        }
        selectedEpisode = episode.reference.optString("episodeKey")
        work?.let { episodeCache[it.key] = selectedEpisode }
        startEpisode(episode, true)
    }

    private fun runCacheBatch(chosen: LibraryWork, references: List<JSONObject>, removing: Boolean) {
        cacheMutationGeneration++
        actionRequest?.cancel(); val request = VodRequest(); actionRequest = request; val token = ++actionGeneration
        actionLoading = true; showDetailMessage(if (removing) "正在删除缓存…" else "正在加入缓存队列…"); renderActions()
        worker.execute {
            val succeeded = mutableSetOf<String>(); val failures = mutableListOf<String>()
            var invalid = false
            var status: JSONObject? = null
            try {
                for (reference in references.distinctBy { LibraryReference.key(it) }) {
                    request.check()
                    try {
                        val result = if (removing) data.removeCache(reference, request) else data.cache(reference, request)
                        if (!result.optBoolean("ok")) throw VodException(result.optString("code", "CACHE_FAILED"), result.optString("error", "操作失败，请重试"))
                        succeeded.add(LibraryReference.key(reference))
                    } catch (error: Exception) {
                        request.check(); failures.add(message(error))
                        if (!removing && invalidIdentity(error)) { invalid = true; break }
                        if (!removing && error is VodException && error.code in listOf("CACHE_LIMIT", "CACHE_STORAGE_LOW", "CACHE_QUOTA_LIMIT")) break
                    }
                }
                request.check(); status = data.caches(); request.check()
            } catch (_: Exception) { if (!request.isCancelled()) failures.add("缓存状态暂时无法刷新") }
            main.post {
                // Discard any status read in the middle of this batch, even after it has finished.
                cacheMutationGeneration++
                if (!actionCurrent(token, request, chosen)) return@post
                actionLoading = false
                markedEpisodes.keys.removeAll(succeeded)
                status?.let { applyCacheStatus(it) }
                if (invalid) { invalidateDetail(); return@post }
                detailNotice = (if (removing) "已提交删除 ${succeeded.size} 集" else "已加入 ${succeeded.size} 集缓存") +
                    if (failures.isEmpty()) "" else "；${references.distinctBy { LibraryReference.key(it) }.size - succeeded.size} 项未完成：${failures.first()}"
                renderDetail(false); renderActions(); scheduleCachePoll()
                // A disabled action must not strand the remote focus after the batch completes.
                if (dialog?.isShowing != true && currentFocus?.isEnabled != true) chrome.resetFocus()
            }
        }
    }

    private fun scheduleCachePoll() {
        main.removeCallbacks(cachePoll)
        if (active) main.postDelayed(cachePoll, 1500)
    }

    private fun pollCaches() {
        if (!active || cachePollInFlight) {
            return
        }
        if (localRequest != null || actionLoading) { scheduleCachePoll(); return }
        if (work == null && libraryModel.caches.none { it.state in listOf("queued", "resolving", "downloading", "removing") }) {
            scheduleCachePoll(); return
        }
        val token = cachePollGeneration; val mutationToken = cacheMutationGeneration; cachePollInFlight = true
        worker.execute {
            val status = runCatching { data.caches() }.getOrNull()
            main.post {
                if (!active || token != cachePollGeneration) return@post
                cachePollInFlight = false
                if (!actionLoading && mutationToken == cacheMutationGeneration && status != null) applyCacheStatus(status)
                scheduleCachePoll()
            }
        }
    }

    private fun applyCacheStatus(status: JSONObject) {
        val previousFailures = libraryModel.caches.filter { it.state == "failed" }
            .associate { LibraryReference.key(it.reference) to it.error }
        val before = libraryModel.caches.map { listOf(LibraryReference.key(it.reference), it.state, it.percent, it.bytes, it.error) }
        libraryModel.updateCaches(status)
        val after = libraryModel.caches.map { listOf(LibraryReference.key(it.reference), it.state, it.percent, it.bytes, it.error) }
        libraryModel.caches.firstOrNull {
            it.state == "failed" && it.error.isNotBlank() && previousFailures[LibraryReference.key(it.reference)] != it.error
        }?.let { failed ->
            val episode = listOf(failed.title, failed.label).filter { it.isNotBlank() }.joinToString(" · ")
            val notice = if (episode.isEmpty()) failed.error else "$episode：${failed.error}"
            Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
        }
        val currentWork = work
        val workStatus = currentWork?.caches?.map { listOf(LibraryReference.key(it.reference), it.state, it.percent, it.bytes, it.error) }
        val latestWorkStatus = libraryModel.caches.filter { it.workKey == currentWork?.key }
            .map { listOf(LibraryReference.key(it.reference), it.state, it.percent, it.bytes, it.error) }
        if (before == after && (currentWork == null || workStatus == latestWorkStatus)) return
        work?.let { chosen ->
            val caches = libraryModel.caches.filter { it.workKey == chosen.key }
            work = chosen.copy(caches = caches)
            playDetail = playDetail?.let { value ->
                if (!value.offline) value.withCaches(caches)
                else LibraryDetail.forCacheSelection(work!!, null).let { local -> local.copy(offline = true,
                    lines = local.lines.map { line -> line.copy(episodes = line.episodes.filter { it.cached }) }.filter { it.episodes.isNotEmpty() }) }
            }
            detail = if (cacheMode) LibraryDetail.forCacheSelection(work!!, playDetail) else playDetail
            if (detail != null && !detailLoading) renderDetail(false)
        }
        renderWall()
    }

    private fun actionCurrent(token: Int, request: VodRequest, chosen: LibraryWork) = active && token == actionGeneration &&
        actionRequest === request && work?.key == chosen.key && !request.isCancelled()

    private fun showSearch() {
        val input = input("输入影片、歌曲或歌手", 200).apply { setText(query); selectAll(); imeOptions = EditorInfo.IME_ACTION_SEARCH }
        val content = vertical().apply { setPadding(dp(24), dp(18), dp(24), dp(8)); addView(input) }
        val builder = TvDialogs.builder(this).setTitle("搜索影视与歌曲").setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索") { _, _ -> submitSearch(input.text.toString()) }
        if (query.isNotEmpty()) builder.setNeutralButton("清除") { _, _ -> submitSearch("") }
        dialog = builder.show().also { popup ->
                        popup.setOnDismissListener { if (dialog === popup) dialog = null }
                input.setOnEditorActionListener { _, action, event ->
                    if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                        popup.dismiss(); submitSearch(input.text.toString()); true
                    } else false
                }
                input.nextFocusDownId = popup.getButton(AlertDialog.BUTTON_POSITIVE).id
                popup.getButton(AlertDialog.BUTTON_POSITIVE).nextFocusUpId = input.id
                // A single-line EditText otherwise consumes DOWN to move its cursor to the end.
                // On a TV, DOWN should reach the visible search action in one remote press.
                input.setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                        popup.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus(); true
                    } else false
                }
                input.requestFocus()
            }
    }

    private fun message(error: Exception) = if (error is VodException) error.message.orEmpty().take(240) else "暂时无法读取，请重试"
    private fun invalidIdentity(error: Exception) = error is VodException && error.code in listOf("STALE_SELECTION", "SOURCE_NOT_FOUND", "SOURCE_STORE")
    private fun showDetailMessage(value: String) {
        if (detailStatus == value) return
        detailStatus = value
        if (::chrome.isInitialized) chrome.setStatus(value)
        if (value.isNotBlank() && ::root.isInitialized) root.announceForAccessibility(value)
    }

    private fun input(hint: String, limit: Int) = TvDialogs.searchInput(this, hint).apply {
        id = View.generateViewId()
        setSingleLine(true); filters = arrayOf(InputFilter.LengthFilter(limit)); textSize = 17f
    }
    private fun dp(value: Int) = LibraryStyle.dp(this, value)
    private fun text(value: String, size: Float, color: Int = LibraryStyle.INK) = LibraryStyle.text(this, value, size, color)
    private fun button(value: String, tag: String, action: () -> Unit) = LibraryStyle.button(this, value, tag, action)
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        clipChildren = false; clipToPadding = false
    }
}
