package com.live.tv

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import com.live.tv.media.LibraryPosterImages
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.live.tv.media.LibraryReference
import com.live.tv.media.LibraryWork
import com.live.tv.media.PosterMatch
import com.live.tv.media.RecentVodHistory
import com.live.tv.media.VodCacheManager
import com.live.tv.media.VodLibraryActivity
import com.live.tv.media.VodRepository
import com.live.tv.media.VodRequest
import com.live.tv.media.VodSourcePriority
import com.live.tv.media.VodSearchPreview
import com.live.tv.music.MusicActivity
import com.live.tv.music.catalog.MusicRepository
import com.live.tv.music.catalog.musicSearchFailureMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import com.live.tv.music.ui.NativeMusicArtwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Spotlight-like global launcher. Searches films and songs; the pill row changes the page. */
class GlobalSearchOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
) : FrameLayout(context) {
    private enum class Page(
        val label: String,
        val target: Class<*>,
        val icon: Int,
        val hint: String? = null,
        val tabIcon: Int = icon,
    ) {
        SETTINGS("设置", TvSourcesActivity::class.java, R.drawable.ic_search_settings),
        LIVE("直播", MainActivity::class.java, R.drawable.ic_live, tabIcon = R.drawable.ic_search_tab_live),
        VOD("流媒体", VodLibraryActivity::class.java, R.drawable.ic_search_films, "搜索影视", R.drawable.ic_search_tab_vod),
        MUSIC("音乐", MusicActivity::class.java, R.drawable.ic_music, "搜索歌曲或歌手", R.drawable.ic_search_tab_music),
    }

    private val currentPage = when (context) {
        is VodLibraryActivity -> Page.VOD
        is MusicActivity -> Page.MUSIC
        is TvSourcesActivity -> Page.SETTINGS
        else -> Page.LIVE
    }
    private val input = EditText(context)
    private val results = LinearLayout(context)
    private val status = TextView(context)
    private val heading = TextView(context)
    private val tabs = mutableListOf<ImageView>()
    private val surface = LinearLayout(context)
    private val surfaceBackground = glass(0f, 0xe825272c.toInt(), 0x55ffffff)
    private val toolbar = LinearLayout(context)
    private var toolbarFocus: View? = null
    private val panel = LinearLayout(context)
    private val images by lazy { LibraryPosterImages(context) }
    private val videoPreview by lazy { VodSearchPreview(context, scope) }
    private var previewRow: View? = null
    private var searchJob: Job? = null
    private var queryRevision = 0
    private var previousFocus: java.lang.ref.WeakReference<View>? = null
    private var editing = false
    private var open = false
    private var closing = false
    private var visiblePanelHeight = 0
    private var targetPanelHeight = 0
    private var panelAnimator: ValueAnimator? = null
    private val keyboard get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    constructor(context: Context) : this(context, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    init {
        tag = "global-search"
        setBackgroundColor(Color.argb(105, 0, 0, 0))
        visibility = GONE
        isFocusableInTouchMode = true
        setOnClickListener { hide() }
        surface.orientation = LinearLayout.VERTICAL
        surface.background = surfaceBackground
        surface.clipToOutline = true
        surface.setOnClickListener { }
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.addView(input)
        surface.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        Page.entries.forEachIndexed { index, page ->
            val tab = ImageView(context).apply {
                contentDescription = page.label
                isSelected = page == currentPage
                isFocusableInTouchMode = true
                isClickable = true
                defaultFocusHighlightEnabled = false
                setImageResource(page.tabIcon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setOnClickListener { openPage(page) }
                setOnFocusChangeListener { _, focused ->
                    if (focused) toolbarFocus = this
                    paintTab(this, page, focused)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            (tabs.getOrNull(index - 1) ?: input.takeIf { it.isEnabled })?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { tabs.getOrNull(index + 1)?.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { results.getChildAt(0)?.requestFocus(); true }
                        else -> false
                    }
                }
            }
            tabs += tab
            toolbar.addView(tab)
            paintTab(tab, page, false)
        }
        input.apply {
            tag = "global-search-input"
            hint = currentPage.hint
            textSize = 24f
            setSingleLine(true)
            includeFontPadding = false
            isEnabled = currentPage.hint != null
            visibility = if (isEnabled) VISIBLE else GONE
            isFocusableInTouchMode = isEnabled
            isFocusable = isEnabled
            defaultFocusHighlightEnabled = false
            setTextColor(Color.WHITE)
            setHintTextColor(if (isEnabled) 0xffe0e3e8.toInt() else 0xff999da7.toInt())
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(12)
            setPadding(dp(20), 0, dp(20), 0)
            showSoftInputOnFocus = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnFocusChangeListener { _, focused ->
                if (focused) toolbarFocus = this else stopEditing()
                paintInput(focused)
            }
            paintInput(false)
            setOnClickListener { startEditing() }
            setOnEditorActionListener { _, _, _ ->
                stopEditing()
                results.getChildAt(0)?.requestFocus()
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { queueSearch(s?.toString().orEmpty()) }
            })
            setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (event.action == KeyEvent.ACTION_UP) startEditing()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!editing) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN) {
                            tabs.first().requestFocus()
                        }
                        true
                    } else false
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (event.action == KeyEvent.ACTION_DOWN) {
                        results.getChildAt(0)?.requestFocus(); true
                    } else false
                    else -> false
                }
            }
        }
        results.orientation = LinearLayout.VERTICAL
        results.tag = "global-search-results"
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = GONE
        panel.setPadding(dp(20), 0, dp(20), dp(14))
        panel.addView(View(context).apply {
            setBackgroundColor(0x26ffffff)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(1).coerceAtLeast(1)).apply {
            bottomMargin = dp(7)
        })
        heading.apply {
            text = "最近观看"
            textSize = 15f
            setTextColor(0xffc0c4cc.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = GONE
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        panel.addView(heading)
        panel.addView(ScrollView(context).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            addView(results)
        }, LinearLayout.LayoutParams(-1, -2))
        status.textSize = 17f
        status.setTextColor(0xffe0e3e8.toInt())
        status.setPadding(dp(14), dp(10), dp(14), dp(10))
        panel.addView(status)
        surface.addView(panel, LinearLayout.LayoutParams(-1, -2))
        addView(surface, LayoutParams(-2, -2))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val iconSlot = (w * .057f).toInt().coerceAtLeast(1)
        val inset = (w * .024f).toInt()
        val barHeight = (w * .071f).toInt().coerceAtLeast(1)
        toolbar.setPadding(inset, 0, inset, 0)
        toolbar.layoutParams.height = barHeight
        for (tab in tabs) {
            tab.layoutParams = LinearLayout.LayoutParams(iconSlot, iconSlot)
            val iconPadding = (iconSlot * .27f).toInt()
            tab.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        }
        input.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        val surfaceWidth = if (input.isEnabled) (w * .88f).toInt() else iconSlot * tabs.size + inset * 2
        surfaceBackground.cornerRadius = (barHeight * .5f)
        surface.measure(MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((h * .63f).toInt(), MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!closing) {
            val measuredPanelHeight = (surface.measuredHeight - toolbar.measuredHeight).coerceAtLeast(0)
            if (targetPanelHeight != measuredPanelHeight) animatePanelTo(measuredPanelHeight)
        }
        layoutSurface()
    }

    private fun layoutSurface() {
        val x = (width - surface.measuredWidth) / 2
        val center = (height * if (input.isEnabled) .30f else .5f).toInt()
        val y = center - toolbar.measuredHeight / 2
        // Lay out the rows at their measured size. The shared rounded outline clips
        // them during the transition without remeasuring or changing the focused row.
        surface.layout(x, y, x + surface.measuredWidth, y + toolbar.measuredHeight + visiblePanelHeight)
    }

    private fun animatePanelTo(height: Int, onComplete: () -> Unit = {}) {
        panelAnimator?.cancel()
        targetPanelHeight = height
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() || visiblePanelHeight == height) {
            visiblePanelHeight = height
            layoutSurface()
            onComplete()
            return
        }
        panelAnimator = ValueAnimator.ofInt(visiblePanelHeight, height).apply {
            duration = if (height > visiblePanelHeight) 230L else 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                visiblePanelHeight = it.animatedValue as Int
                layoutSurface()
                if (it.animatedFraction == 1f) onComplete()
            }
            start()
        }
    }

    private fun glass(radius: Float, fill: Int, border: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        setStroke(dp(1).coerceAtLeast(1), border)
    }

    private fun paintTab(tab: ImageView, page: Page, focused: Boolean) {
        tab.imageTintList = ColorStateList.valueOf(when {
            focused -> 0xff82baff.toInt()
            page == currentPage -> 0xff599cf2.toInt()
            else -> Color.WHITE
        })
        val scale = if (focused) 1.16f else 1f
        tab.scaleX = scale
        tab.scaleY = scale
    }

    private fun paintInput(focused: Boolean) {
        input.background = null
        val tint = if (focused) 0xff82baff.toInt() else Color.WHITE
        input.compoundDrawableTintList = ColorStateList.valueOf(tint)
        input.setHintTextColor(if (focused) tint else 0xffe0e3e8.toInt())
    }

    private fun startEditing() {
        if (!input.isEnabled) return
        editing = true
        input.requestFocus()
        input.showSoftInputOnFocus = true
        input.isCursorVisible = true
        keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun stopEditing() {
        if (editing) keyboard.hideSoftInputFromWindow(windowToken, 0)
        editing = false
        input.showSoftInputOnFocus = false
        input.isCursorVisible = false
    }

    fun show() {
        if (isOpen()) return
        open = true
        previousFocus = java.lang.ref.WeakReference(rootView.findFocus())
        visibility = VISIBLE
        bringToFront()
        if (input.text.isEmpty()) queueSearch("") else input.setText("")
        stopEditing()
        toolbarFocus = tabs[currentPage.ordinal]
        toolbarFocus?.requestFocus()
    }

    fun hide() = dismiss()

    private fun dismiss(after: () -> Unit = {}) {
        if (!open || closing) return
        closing = true
        stopVideoPreview()
        stopEditing()
        searchJob?.cancel()
        queryRevision++
        images.cancel()
        animatePanelTo(0) {
            open = false
            closing = false
            visibility = GONE
            results.removeAllViews()
            panel.visibility = GONE
            previousFocus?.get()?.takeIf { it.isShown }?.requestFocus()
            previousFocus = null
            after()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopVideoPreview()
    }

    override fun onDetachedFromWindow() {
        stopVideoPreview()
        panelAnimator?.cancel()
        panelAnimator = null
        visiblePanelHeight = 0
        targetPanelHeight = 0
        open = false
        closing = false
        previousFocus = null
        visibility = GONE
        searchJob?.cancel()
        queryRevision++
        images.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (closing) return true
        if (open && !hasFocus() && event.action == KeyEvent.ACTION_DOWN) {
            tabs[currentPage.ordinal].requestFocus()
        }
        val handled = super.dispatchKeyEvent(event)
        return handled || event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
    }

    fun isOpen(): Boolean = open

    private fun openPage(page: Page) {
        if (page == currentPage) { hide(); return }
        dismiss {
            context.startActivity(Intent(context, page.target)
                .putExtra("explicit_live_page", page == Page.LIVE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun stopVideoPreview() {
        if (previewRow == null) return
        previewRow = null
        videoPreview.stop()
    }

    private fun queueSearch(query: String) {
        stopVideoPreview()
        searchJob?.cancel()
        images.cancel()
        val revision = ++queryRevision
        results.removeAllViews()
        val text = query.trim()
        heading.visibility = GONE
        if (!isOpen() || currentPage.hint == null) {
            panel.visibility = GONE
            return
        }
        if (text.isEmpty()) {
            setStatus("")
            if (currentPage == Page.VOD) {
                val recentRows = RecentVodHistory(context).entries().map { row ->
                    val metadata = listOf(row.optString("episodeLabel"), row.optString("sourceName"))
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    val (_, detail) = addResult(row.optString("title"), metadata, Page.VOD.icon,
                        Intent(context, VodLibraryActivity::class.java).putExtra("recent_vod", row.toString()),
                        row.optString("poster"),
                        previewWork = LibraryWork(row.getString("sourceId"), row.getString("vodId"),
                            row.getInt("revision"), row.getInt("epoch"), row.optString("title")),
                        previewEpisode = LibraryReference.read(row))
                    Triple(row, metadata, detail)
                }
                if (recentRows.isNotEmpty()) searchJob = scope.launch {
                    val cachedByWork = attemptSearch {
                        withContext(Dispatchers.IO) {
                            val caches = VodCacheManager.get(context).status().getJSONArray("caches")
                            (0 until caches.length()).mapNotNull { index ->
                                val entry = caches.getJSONObject(index)
                                if (entry.optString("state") == "complete")
                                    LibraryReference.read(entry.optJSONObject("ref")) else null
                            }.groupBy(LibraryReference::workKey)
                        }
                    } ?: return@launch
                    if (!isOpen() || revision != queryRevision) return@launch
                    for ((row, metadata, detail) in recentRows) {
                        val episodes = cachedByWork[LibraryReference.workKey(row)] ?: continue
                        val latestCached = episodes.any { LibraryReference.key(it) == LibraryReference.key(row) }
                        val cacheLabel = "已缓存 ${episodes.size} 集" + if (latestCached) " · 本集已缓存" else ""
                        detail.text = listOf(cacheLabel, metadata).filter { it.isNotBlank() }.joinToString(" · ")
                        detail.visibility = VISIBLE
                    }
                }
            }
            val hasHistory = results.childCount > 0
            heading.visibility = if (hasHistory) VISIBLE else GONE
            panel.visibility = if (hasHistory) VISIBLE else GONE
            return
        }
        panel.visibility = VISIBLE
        setStatus("正在搜索…")
        searchJob = scope.launch {
            delay(250)
            when (currentPage) {
                Page.VOD -> {
                    val request = VodRequest()
                    val response = attemptSearch {
                        try {
                            runInterruptible(Dispatchers.IO) {
                                VodRepository.get(context).search(text, 1, request) { films ->
                                    val batch = films.take(12)
                                    post {
                                        if (!isOpen() || closing || revision != queryRevision) return@post
                                        batch.forEach { addFilmResult(it) }
                                    }
                                }
                            }
                        } finally {
                            request.cancel()
                        }
                    }
                    if (!isOpen() || revision != queryRevision) return@launch
                    val search = response?.optJSONObject("search")
                    val sources = search?.optJSONArray("sources") ?: JSONArray()
                    val failures = (0 until sources.length()).map(sources::getJSONObject)
                        .filter { it.optString("status") == "error" }.map { it.optString("sourceName") }
                    val hasItems = (search?.optJSONArray("items")?.length() ?: 0) > 0
                    setStatus(when {
                        response?.optBoolean("ok") != true -> "影视搜索暂不可用"
                        failures.isNotEmpty() -> "暂不可用：${failures.joinToString("、")}"
                        !hasItems -> "未找到影视"
                        else -> ""
                    })
                }
                Page.MUSIC -> {
                    val repository = MusicRepository.get(context)
                    val tracks = try {
                        repository.search(text, 1).take(12)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (isOpen() && revision == queryRevision) setStatus(musicSearchFailureMessage(error))
                        return@launch
                    }
                    if (!isOpen() || revision != queryRevision) return@launch
                    tracks.forEachIndexed { index, track ->
                        val selected = JSONObject().apply {
                            put("id", track.id); put("name", track.name)
                            put("artists", JSONArray(track.artists))
                            put("album", track.album); put("pictureId", track.pictureId); put("lyricId", track.lyricId)
                        }
                        val (image) = addResult(track.name, track.artists.joinToString(" / "), currentPage.icon,
                            Intent(context, MusicActivity::class.java).putExtra("selected_track", selected.toString()))
                        if (index < 4) launch artworkLoad@{
                            val cached = withContext(Dispatchers.IO) {
                                NativeMusicArtwork.cached(repository.artworkFile(track.id))
                            }
                            if (!isOpen() || revision != queryRevision) return@artworkLoad
                            val bitmap = cached ?: attemptSearch { NativeMusicArtwork.remote(repository.publicArtwork(track)) }
                            if (bitmap != null && isOpen() && revision == queryRevision) {
                                image.scaleType = ImageView.ScaleType.CENTER_CROP
                                image.setImageBitmap(bitmap)
                            }
                        }
                    }
                    setStatus(if (tracks.isEmpty()) "未找到歌曲" else "")
                }
                else -> Unit
            }
        }
    }

    private fun addFilmResult(film: JSONObject) {
        val metadata = listOf("sourceName", "year", "remarks").map { film.optString(it) }
            .filter { it.isNotBlank() }.joinToString(" · ")
        val title = film.optString("title")
        val work = LibraryWork(
            sourceId = film.optString("sourceId"),
            vodId = film.optString("vodId"),
            revision = film.optInt("revision"),
            epoch = film.optInt("epoch"),
            title = title,
            poster = film.optString("poster"),
            year = film.optString("year"),
            type = film.optString("typeName"),
        )
        val placement = FilmPlacement(work, VodSourcePriority.rank(film.optString("sourceName")))
        var insertion = results.childCount
        for (index in 0 until results.childCount) {
            val existing = results.getChildAt(index).tag as? FilmPlacement ?: continue
            if (existing.titleKey != placement.titleKey) continue
            if (existing.rank > placement.rank) {
                insertion = index
                break
            }
            insertion = index + 1
        }
        val (poster) = addResult(title, metadata, Page.VOD.icon,
            Intent(context, VodLibraryActivity::class.java).putExtra("selected_vod", film.toString()),
            placement = placement, insertion = insertion)
        poster.onPosterLoaded = { bitmap -> shareFilmPoster(placement, poster, bitmap) }
        for (index in 0 until results.childCount) {
            val row = results.getChildAt(index) as LinearLayout
            val peer = row.tag as? FilmPlacement ?: continue
            val bitmap = ((row.getChildAt(0) as ImageView).drawable as? BitmapDrawable)?.bitmap ?: continue
            if (placement.matches(peer) && poster.showSharedPoster(bitmap)) break
        }
        images.loadKeepingPlaceholder(poster, work)
    }

    private fun shareFilmPoster(placement: FilmPlacement, poster: ResultImage, bitmap: Bitmap) {
        for (index in 0 until results.childCount) {
            val row = results.getChildAt(index) as LinearLayout
            val peer = row.tag as? FilmPlacement ?: continue
            val image = row.getChildAt(0) as ResultImage
            if (image !== poster && placement.matches(peer) && image.drawable !is BitmapDrawable)
                image.showSharedPoster(bitmap)
        }
    }

    private fun setStatus(value: String) {
        status.text = value
        status.visibility = if (value.isEmpty()) GONE else VISIBLE
    }

    private suspend fun <T> attemptSearch(search: suspend () -> T): T? = try {
        search()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private data class FilmPlacement(val work: LibraryWork, val rank: Int) {
        val titleKey = VodSourcePriority.titleKey(work.title)
        private val year = PosterMatch.year(work.year)
        private val kind = PosterMatch.kind(work.type)

        fun matches(other: FilmPlacement): Boolean =
            work.sourceId != other.work.sourceId && titleKey.isNotBlank() && titleKey == other.titleKey &&
                year != null && year == other.year && kind != null && kind == other.kind
    }

    private class ResultImage(context: Context) : androidx.appcompat.widget.AppCompatImageView(context) {
        var onPosterLoaded: ((Bitmap) -> Unit)? = null

        override fun setImageBitmap(bitmap: Bitmap?) {
            if (bitmap != null && onPosterLoaded != null) scaleType = ScaleType.CENTER_CROP
            super.setImageBitmap(bitmap)
            bitmap?.let { onPosterLoaded?.invoke(it) }
        }

        fun showSharedPoster(bitmap: Bitmap): Boolean {
            if (bitmap.width < 80 || bitmap.height < 100 || bitmap.width.toFloat() / bitmap.height !in .3f..2f) return false
            scaleType = ScaleType.CENTER_CROP
            setImageDrawable(BitmapDrawable(resources, bitmap))
            return true
        }
    }

    private fun addResult(title: String, subtitle: String, iconRes: Int, intent: Intent, artwork: String = "",
        placement: FilmPlacement? = null, insertion: Int = -1,
        previewWork: LibraryWork? = placement?.work, previewEpisode: JSONObject? = null): Pair<ResultImage, TextView> {
        val icon = ResultImage(context).apply {
            background = glass(dp(9).toFloat(), 0xff333943.toInt(), 0x22ffffff)
            clipToOutline = true
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val name = TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val detail = TextView(context).apply {
            text = subtitle
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = if (subtitle.isBlank()) GONE else VISIBLE
        }
        val row = LinearLayout(context).apply {
            tag = placement
            gravity = Gravity.CENTER_VERTICAL
            isFocusableInTouchMode = true
            isClickable = true
            defaultFocusHighlightEnabled = false
            setPadding(dp(10), dp(5), dp(10), dp(5))
            addView(icon, LinearLayout.LayoutParams(dp(if (currentPage == Page.VOD) 104 else 58), dp(58)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(detail)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            setOnClickListener {
                if (previewWork != null) {
                    (icon.drawable as? BitmapDrawable)?.bitmap?.let { VodSearchPreview.rememberPoster(previewWork.key, it) }
                }
                dismiss {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }
            setOnKeyListener { view, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val index = results.indexOfChild(view)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (index == 0) toolbarFocus?.requestFocus() else results.getChildAt(index - 1).requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { results.getChildAt(index + 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { tabs[currentPage.ordinal].requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }
        fun paint(focused: Boolean) {
            row.background = if (focused) glass(dp(12).toFloat(), 0xffeceef1.toInt(), Color.WHITE) else null
            name.setTextColor(if (focused) 0xff171b22.toInt() else Color.WHITE)
            detail.setTextColor(if (focused) 0xff454a55.toInt() else 0xffc0c4cc.toInt())
        }
        row.setOnFocusChangeListener { _, focused ->
            paint(focused)
            if (focused && previewWork != null && isOpen() && !closing) {
                previewRow = row
                videoPreview.focus(previewWork, previewEpisode)
            } else if (!focused && previewRow === row) stopVideoPreview()
        }
        paint(false)
        results.addView(row, insertion, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        if (artwork.isNotBlank()) {
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            images.load(icon, artwork)
        }
        return icon to detail
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
