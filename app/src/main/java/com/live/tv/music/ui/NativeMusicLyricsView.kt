package com.live.tv.music.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import com.live.tv.music.core.SynchronizedLyricLine
import com.live.tv.music.core.SynchronizedLyrics
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native content for the shared player's left column. Playback remains owned by MediaSession. */
internal class NativeMusicLyricsView(
    context: Context,
    private val seek: (Double) -> Unit,
    private val focusTransport: () -> Unit,
) : View(context) {
    private var track = ""
    private var lyrics = emptyList<SynchronizedLyricLine>()
    private var emptyLabel = "暂无歌词"
    private var active = -1
    private var selected = 0
    private var manual = false
    private var offset = 0f
    private var animation: ValueAnimator? = null
    private val motion = PathInterpolator(.22f, .68f, .2f, 1f)
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val layoutCache = android.util.LruCache<Triple<String, Int, Int>, StaticLayout>(64)

    init {
        isFocusableInTouchMode = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "歌词"
    }

    fun render(trackId: String, lines: List<SynchronizedLyricLine>, seconds: Double, loading: Boolean) {
        val rebuilt = track != trackId || lyrics != lines
        if (rebuilt) {
            track = trackId
            lyrics = lines
            manual = false
            isFocusable = lines.isNotEmpty()
            isClickable = lines.isNotEmpty()
            layoutCache.evictAll()
        }
        val newEmptyLabel = if (loading) "正在载入…" else "暂无歌词"
        if (emptyLabel != newEmptyLabel) {
            emptyLabel = newEmptyLabel
            if (lines.isEmpty()) invalidate()
        }
        val index = SynchronizedLyrics.activeIndexAt(lines, seconds)
        val next = if (manual) selected else index.coerceAtLeast(0)
        if (rebuilt || active != index || next != selected) {
            active = index
            select(next, !rebuilt)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && selected == lyrics.lastIndex) {
                focusTransport()
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || selected > 0) {
                manual = true
                select(selected + if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1, true)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> true
        KeyEvent.KEYCODE_DPAD_RIGHT -> { focusTransport(); true }
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            if (event.repeatCount == 0) performClick()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun performClick(): Boolean {
        val line = lyrics.getOrNull(selected) ?: return super.performClick()
        super.performClick()
        manual = false
        seek(line.timeSeconds)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (lyrics.isEmpty() || height == 0) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val index = kotlin.math.floor((event.y - height / 2f) / rowHeight + offset + .5f).toInt()
            if (index in lyrics.indices) {
                requestFocus()
                manual = true
                select(index, true)
            }
        }
        return true
    }

    private fun select(index: Int, animated: Boolean) {
        val next = index.coerceIn(0, lyrics.lastIndex.coerceAtLeast(0))
        val changed = next != selected
        if (!changed && animated) {
            invalidate()
            return
        }
        selected = next
        contentDescription = lyrics.getOrNull(selected)?.let { "歌词，${it.text}" } ?: "歌词"
        animation?.cancel()
        if (changed && animated && isShown && ValueAnimator.areAnimatorsEnabled()) {
            animation = ValueAnimator.ofFloat(offset, next.toFloat()).apply {
                duration = 460L
                interpolator = motion
                addUpdateListener {
                    offset = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            offset = next.toFloat()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        offset = selected.toFloat()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutCache.evictAll()
        if (h > 0) mask.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, .12f, .86f, 1f), Shader.TileMode.CLAMP)
    }

    private val rowHeight: Float get() = height * .17f

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val viewportWidth = width / .43f
        if (lyrics.isEmpty()) {
            drawLine(canvas, emptyLabel, .024f * viewportWidth, Color.rgb(221, 221, 221),
                false, 0f, height * .42f, width)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        val left = viewportWidth * .017f
        val availableWidth = (width - left).roundToInt().coerceAtLeast(1)
        val first = (offset.toInt() - 4).coerceAtLeast(0)
        val last = (offset.toInt() + 4).coerceAtMost(lyrics.lastIndex)
        for (index in first..last) {
            val line = lyrics[index]
            val highlighted = index == selected
            val color = when {
                highlighted -> Color.WHITE
                abs(index - selected) >= 2 -> 0x7ae1dfe1
                else -> 0xcce1dfe1.toInt()
            }
            val size = viewportWidth * if (highlighted) .035f else .0245f
            val rowMiddle = height / 2f + (index - offset) * rowHeight
            val primary = layout(line.text, size, color, highlighted, availableWidth)
            val translation = line.translation?.takeIf { highlighted }?.let {
                layout(it, viewportWidth * .0155f, Color.rgb(227, 223, 227), false, availableWidth)
            }
            val gap = if (translation != null) height * .005f else 0f
            val top = rowMiddle - (primary.height + gap + (translation?.height ?: 0)) / 2f
            drawLayout(canvas, primary, left, top)
            translation?.let { drawLayout(canvas, it, left, top + primary.height + gap) }
            if (highlighted && isFocused && index != active) {
                ring.strokeWidth = viewportWidth / 480f
                val radius = viewportWidth * .00375f
                canvas.drawCircle(radius + ring.strokeWidth / 2, rowMiddle, radius, ring)
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        canvas.restoreToCount(layer)
    }

    private fun drawLine(canvas: Canvas, label: String, size: Float, color: Int, bold: Boolean,
                         left: Float, top: Float, width: Int) =
        drawLayout(canvas, layout(label, size, color, bold, width), left, top)

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, left: Float, top: Float) {
        val save = canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun layout(label: String, size: Float, color: Int, bold: Boolean, width: Int): StaticLayout {
        val key = Triple(label, size.toBits(), color)
        return layoutCache[key] ?: run {
            val paint = TextPaint(text).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                setShadowLayer(this@NativeMusicLyricsView.width / 34.4f, 0f,
                    this@NativeMusicLyricsView.width / 206.4f, 0x88000000.toInt())
            }
            StaticLayout.Builder.obtain(label, 0, label.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.18f)
                .build()
                .also { layoutCache.put(key, it) }
        }
    }
}
