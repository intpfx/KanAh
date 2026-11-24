package com.live.tv.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

/** Shared television playback presentation. Activities own playback and media data. */
internal class MediaChromeView(context: Context, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun select(index: Int)
        fun longSelect(index: Int) = Unit
    }

    data class Item(
        val label: String,
        val subtitle: String = "",
        val cached: Boolean = false,
        val themeColor: Int? = null,
        val cache: EpisodeDecoration = EpisodeDecoration(),
        val watched: Float = 0f,
    )

    private val list = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToOutline = true
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val titleGroup = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }
    private var titleProgress = 0f
    private var titleProgressColor = Color.WHITE
    internal val titleView: TextView = object : AppCompatTextView(context) {
        private val fill = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val lines = layout ?: return
            if (titleProgress <= 0f) return
            fun lineWidth(index: Int) = (lines.getLineRight(index) - lines.getLineLeft(index)).coerceAtLeast(0f)
            var remaining = (0 until lines.lineCount).sumOf { lineWidth(it).toDouble() }.toFloat() * titleProgress
            fill.rewind()
            for (index in 0 until lines.lineCount) {
                val width = lineWidth(index)
                val left = compoundPaddingLeft + lines.getLineLeft(index)
                val top = totalPaddingTop + lines.getLineTop(index).toFloat()
                val bottom = totalPaddingTop + lines.getLineBottom(index).toFloat()
                if (remaining > 0f && width > 0f) {
                    fill.addRect(left, top, left + minOf(width, remaining), bottom, Path.Direction.CW)
                }
                remaining -= width
            }
            val saved = canvas.save()
            canvas.clipPath(fill)
            paint.shader = LinearGradient(0f, 0f, 1f, 0f, titleProgressColor, titleProgressColor, Shader.TileMode.CLAMP)
            try {
                super.onDraw(canvas)
            } finally {
                paint.shader = null
                canvas.restoreToCount(saved)
            }
        }
    }.apply {
        gravity = Gravity.END
        setTextColor(Color.WHITE)
        includeFontPadding = false
        typeface = BOLD
        isSingleLine = false
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setHorizontallyScrolling(false)
    }

    fun setTitleProgress(position: Long, duration: Long, themeColor: Int?) {
        val fraction = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        val color = themeColor ?: Color.RED
        if (fraction == titleProgress && color == titleProgressColor) return
        titleProgress = fraction
        titleProgressColor = color
        titleView.invalidate()
    }
    private val subtitleView = label(0xffd8d4d9.toInt(), Gravity.END)
    private val statusView = label(0xffe3dadd.toInt(), Gravity.START)
    private val seekTime = SeekTimeView(context)
    private val hideSeekTime = Runnable { seekTime.update("") }

    fun showSeekTime(position: Long, duration: Long) {
        seekTime.update("${formatTime(position)} / ${formatTime(duration)}")
        removeCallbacks(hideSeekTime)
        postDelayed(hideSeekTime, 1500)
    }

    fun hideSeekTime() {
        removeCallbacks(hideSeekTime)
        seekTime.update("")
    }

    /** This HUD invalidates pixels only. The episode list need not measure for every key. */
    private class SeekTimeView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = BOLD
        }
        private var time = ""

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun update(value: String) {
            if (time == value) return
            time = value
            invalidate()
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            paint.textSize = width * .03f
            paint.setShadowLayer(width * .006f, 0f, width * .001f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            if (time.isNotEmpty()) canvas.drawText(time, width * .5f,
                (height - paint.ascent() - paint.descent()) * .5f, paint)
        }
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000L
        val hours = seconds / 3600
        val minutes = (seconds / 60 % 60).toString().padStart(2, '0')
        val remainder = (seconds % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:$minutes:$remainder" else "$minutes:$remainder"
    }

    private var items = emptyList<Item>()
    private var music = false
    private var browsing = false
    private var themeColor = DEFAULT_THEME
    private var manualIndex: Int? = null
    private var rowKey: Pair<Int, Int>? = null
    private var rowLongPress = false
    private val longPress = Runnable {
        val index = rowKey?.second ?: return@Runnable
        if (rowLongPress) return@Runnable
        rowLongPress = true
        actions.longSelect(index)
    }

    internal var selectedIndex: Int = -1
        private set
    internal val candidateIndex: Int get() = manualIndex ?: selectedIndex
    internal var lyricsView: View? = null
        set(value) {
            field?.let(::removeView)
            field = value
            value?.let { addView(it, 0) }
            requestLayout()
        }

    init {
        tag = "shared-media-chrome"
        layoutDirection = LAYOUT_DIRECTION_LTR
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        list.addView(rows)
        addView(list)
        titleGroup.addView(titleView)
        titleGroup.addView(subtitleView)
        addView(titleGroup)
        addView(statusView)
        isFocusableInTouchMode = true
        addView(seekTime)
    }

    fun render(
        title: String,
        subtitle: String,
        items: List<Item>,
        selected: Int,
        music: Boolean = false,
        browsing: Boolean = false,
        status: String = "",
        themeColor: Int = DEFAULT_THEME,
    ) {
        val showList = !music || browsing
        val modeChanged = this.music != music || this.browsing != browsing
        val index = selected.takeIf { it in items.indices } ?: if (items.isEmpty()) -1 else 0
        val selectionChanged = index != selectedIndex
        val rebuild = modeChanged || this.items.size != items.size ||
            this.items.indices.any { this.items[it].copy(cache = items[it].cache, watched = items[it].watched) != items[it] }
        val decorationsChanged = this.items != items || this.themeColor != themeColor
        val listWasFocused = list.hasFocus()
        this.music = music
        this.browsing = browsing
        this.themeColor = themeColor
        this.items = items
        selectedIndex = index
        if (modeChanged || selectionChanged || manualIndex?.let { it !in items.indices } == true) manualIndex = null

        titleView.text = title
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isEmpty() && !music) GONE else VISIBLE
        setStatus(status)
        updateControlsVisibility()
        lyricsView?.visibility = if (!showList) VISIBLE else GONE

        if (rebuild) {
            cancelRowKey()
            rows.removeAllViews()
            items.forEachIndexed { rowIndex, item -> rows.addView(ResultRow(rowIndex, item)) }
        }
        if (!rebuild && decorationsChanged) {
            items.forEachIndexed { index, item -> (rows.getChildAt(index) as ResultRow).updateProgress(item) }
        }
        if (controlsVisible && showList && (selectionChanged || rebuild)) {
            if (listWasFocused || modeChanged && hasFocus()) focusListOrLyrics()
            requestLayout()
        }
    }

    fun setStatus(status: String) {
        if (statusView.text.toString() == status) return
        statusView.text = status
        statusView.visibility = if (status.isEmpty()) GONE else VISIBLE
    }

    private var controlsVisible = true

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        updateControlsVisibility()
    }

    private fun updateControlsVisibility() {
        list.visibility = if (controlsVisible && (!music || browsing)) VISIBLE else GONE
        titleGroup.visibility = if (controlsVisible && (!music || titleView.text.isNotEmpty())) VISIBLE else INVISIBLE
    }

    fun resetFocus() {
        manualIndex = null
        focusListOrLyrics()
        requestLayout()
    }

    fun focusListOrLyrics(): Boolean {
        if (list.visibility == VISIBLE && candidateIndex >= 0) {
            return rows.getChildAt(candidateIndex)?.requestFocus() ?: false
        }
        return lyricsView?.takeIf { it.visibility == VISIBLE }?.requestFocus() == true
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean =
        focusListOrLyrics() || super.onRequestFocusInDescendants(direction, previouslyFocusedRect)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelRowKey()
    }

    override fun onDetachedFromWindow() {
        cancelRowKey()
        removeCallbacks(hideSeekTime)
        seekTime.update("")
        super.onDetachedFromWindow()
    }

    private fun cancelRowKey() {
        removeCallbacks(longPress)
        rowKey = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val row = findFocus() as? ResultRow
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER ||
            key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val target = row?.index
            if (target != null || rowKey != null) {
                if (down && event.repeatCount == 0 && target != null) {
                    rowKey = key to target
                    rowLongPress = false
                    postDelayed(longPress, 650)
                } else if (down && event.isLongPress && rowKey?.second == target &&
                    (row != null)) {
                    removeCallbacks(longPress)
                    longPress.run()
                } else if (!down && rowKey?.first == key) {
                    val index = rowKey?.second
                    cancelRowKey()
                    if (!rowLongPress && !event.isCanceled && index != null && target == index) {
                        actions.select(index)
                    }
                }
                return true
            }
        }
        if (down && row != null) {
            val index = row.index
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    cancelRowKey()
                    val next = index + if (key == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next in items.indices) {
                        manualIndex = next
                        rows.getChildAt(next).requestFocus()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val listWidth = (w * .43f).roundToInt()
        val listHeight = if (music) (h * .85f).roundToInt() else h
        val rowHeight = (h * .125f).roundToInt()
        val before = if (music) (h * .01f).roundToInt() else (listHeight - rowHeight).coerceAtLeast(0) / 2
        val after = if (music) (h * .04f).roundToInt() else listHeight - rowHeight - before
        rows.setPadding(0, before, 0, after.coerceAtLeast(0))
        for (i in items.indices) (rows.getChildAt(i) as ResultRow).size(w, h, rowHeight)
        exact(list, listWidth, listHeight)
        lyricsView?.let { exact(it, listWidth, h) }

        var titleSize = w * .042f
        exact(seekTime, w, (w * .08f).roundToInt())
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, w * .014f)
        titleView.setShadowLayer(w * .00625f, 0f, w * .00104f, 0xaa000000.toInt())
        subtitleView.setShadowLayer(w * .00625f, 0f, w * .00104f, 0xaa000000.toInt())
        (subtitleView.layoutParams as LinearLayout.LayoutParams).topMargin = (h * .0045f).roundToInt()
        val titleWidth = MeasureSpec.makeMeasureSpec((w * .43f).roundToInt(), MeasureSpec.EXACTLY)
        val naturalHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        titleGroup.measure(titleWidth, naturalHeight)
        while (h > 0 && titleGroup.measuredHeight > h * .65f && titleSize > 1f) {
            titleSize *= .85f
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
            titleGroup.measure(titleWidth, naturalHeight)
        }
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_PX, w * .014f)
        statusView.measure(MeasureSpec.makeMeasureSpec((w * .40f).roundToInt(), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))

    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width * .06f).roundToInt()
        val end = width - (width * .054f).roundToInt()
        place(list, x, if (music) height - list.measuredHeight else 0)
        lyricsView?.let { place(it, x, 0) }
        place(titleGroup, end - titleGroup.measuredWidth,
            height - (height * .085f).roundToInt() - titleGroup.measuredHeight)
        place(statusView, x, if (browsing) height - (height * .01f).roundToInt() - statusView.measuredHeight
            else (height * .11f).roundToInt())
        place(seekTime, (width - seekTime.measuredWidth) / 2, (height - seekTime.measuredHeight) / 2)
        if (list.visibility == VISIBLE) scrollToCandidate()
    }

    private fun scrollToCandidate() {
        val row = rows.getChildAt(candidateIndex) ?: return
        if (music) {
            if (row.top < list.scrollY) list.scrollTo(0, row.top)
            if (row.bottom > list.scrollY + list.height) list.scrollTo(0, row.bottom - list.height)
        } else list.scrollTo(0, row.top + (row.height - list.height) / 2)
    }

    private inner class ResultRow(val index: Int, private val item: Item) : LinearLayout(context) {
        private var watched = item.watched
        private val name = object : androidx.appcompat.widget.AppCompatTextView(context) {
            override fun onDraw(canvas: Canvas) {
                val line = layout
                if (watched > 0f && line != null && line.lineCount > 0) {
                    val start = line.getLineLeft(0) + compoundPaddingLeft
                    val end = line.getLineRight(0) + compoundPaddingLeft
                    if (end > start) paint.shader = LinearGradient(start, 0f, end, 0f,
                        intArrayOf(themeColor, themeColor, currentTextColor, currentTextColor),
                        floatArrayOf(0f, watched, watched, 1f), Shader.TileMode.CLAMP)
                }
                super.onDraw(canvas)
                paint.shader = null
            }
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        private val subtitle = label(SECONDARY, Gravity.START)
        private val tint = if (music && item.cached) item.themeColor ?: themeColor else null
        private var viewportWidth = 0
        private val cachePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var decoration = item.cache

        fun updateProgress(value: Item) {
            decoration = value.cache
            watched = value.watched
            name.invalidate()
            updateCacheLayout()
            invalidate()
        }

        private fun updateCacheLayout() {
            val hasCacheMark = decoration.active || decoration.complete
            setPadding(0, 0, if (hasCacheMark) (viewportWidth * .024f).roundToInt() else 0, 0)
            val cacheStatus = when {
                decoration.complete -> "已缓存"
                decoration.active -> "缓存中 ${(decoration.fraction * 100).roundToInt()}%"
                else -> null
            }
            val watchStatus = if (watched > 0f) "已观看 ${(watched * 100).roundToInt()}%" else null
            contentDescription = if (cacheStatus == null && watchStatus == null) null
                else listOfNotNull(item.label, watchStatus, cacheStatus).joinToString("，")
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusableInTouchMode = true
            isClickable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            name.text = item.label
            subtitle.text = item.subtitle
            subtitle.visibility = if (item.subtitle.isBlank()) GONE else VISIBLE
            addView(name)
            addView(subtitle)
            setOnClickListener { actions.select(index) }
            setOnLongClickListener { actions.longSelect(index); true }
            updateFocusStyle()
        }

        fun size(width: Int, height: Int, rowHeight: Int) {
            viewportWidth = width
            layoutParams.height = rowHeight
            updateCacheLayout()
            (subtitle.layoutParams as LayoutParams).topMargin = (height * .0055f).roundToInt()
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, width * .0135f)
            if (music) {
                name.setShadowLayer(width * .0052f, 0f, width * .00104f, 0x88000000.toInt())
                subtitle.setShadowLayer(width * .0052f, 0f, width * .00104f, 0x88000000.toInt())
            }
            updateFocusStyle()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!decoration.complete && !decoration.active) return
            val r = viewportWidth * .0065f
            val line = name.layout
            val labelEnd = if (line != null && line.lineCount > 0) name.left + line.getLineRight(0) else name.right.toFloat()
            val x = (labelEnd + viewportWidth * .009f + r).coerceAtMost(width - r - 2f)
            val y = height / 2f
            cachePaint.strokeWidth = r * .22f
            cachePaint.strokeCap = Paint.Cap.ROUND
            if (decoration.complete) {
                cachePaint.style = Paint.Style.FILL
                cachePaint.color = 0xff36bf72.toInt()
                canvas.drawCircle(x, y, r, cachePaint)
                cachePaint.color = Color.WHITE
                canvas.drawLine(x - .48f*r, y, x - .12f*r, y + .35f*r, cachePaint)
                canvas.drawLine(x - .12f*r, y + .35f*r, x + .49f*r, y - .38f*r, cachePaint)
            } else {
                cachePaint.style = Paint.Style.STROKE
                cachePaint.color = 0x55ffffff
                canvas.drawCircle(x, y, r, cachePaint)
                cachePaint.color = themeColor
                canvas.drawArc(x-r, y-r, x+r, y+r, -90f, 360f * decoration.fraction, false, cachePaint)
            }
        }

        private fun updateFocusStyle() {
            name.setTextSize(TypedValue.COMPLEX_UNIT_PX, viewportWidth * if (isFocused) .037f else .023f)
            name.typeface = if (isFocused) BOLD else Typeface.DEFAULT
            name.setTextColor(tint ?: if (isFocused) Color.WHITE else SECONDARY)
            subtitle.setTextColor(if (isFocused) Color.WHITE else SECONDARY)
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, rect: android.graphics.Rect?) {
            super.onFocusChanged(gainFocus, direction, rect)
            updateFocusStyle()
            if (gainFocus) {
                scrollToCandidate()
                post { if (isFocused) scrollToCandidate() }
            } else if (rowKey?.second == index) cancelRowKey()
        }
    }

    private fun label(color: Int, gravity: Int) = TextView(context).apply {
        setTextColor(color)
        this.gravity = gravity or Gravity.CENTER_VERTICAL
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun exact(view: View, width: Int, height: Int) =
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))

    private fun place(view: View, x: Int, y: Int) =
        view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)

    private companion object {
        val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        const val SECONDARY = 0xffb7b4bb.toInt()
        const val DEFAULT_THEME = 0xffc6ac9f.toInt()
    }
}
