package com.live.tv.media

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

internal data class EpisodeDecoration(val complete: Boolean = false, val active: Boolean = false, val fraction: Float = 0f)

/** Focus previews the horizontal episode strip; only confirmation changes the playing episode. */
internal class EpisodePickerView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.LEFT }
    private var items = emptyList<String>()
    private var scrollAnimator: ValueAnimator? = null
    private var themeColor = Color.rgb(90, 190, 205)
    private var progress = FloatArray(0)
    private var decorations = emptyList<EpisodeDecoration>()
    private var centerCandidate = -1
    private var centerDownAt = 0L
    private var longFired = false
    private val longPress = Runnable {
        if (candidateIndex in items.indices) {
            longFired = true
            onLongSelected?.invoke(candidateIndex)
            onInteraction?.invoke()
        }
    }
    fun setThemeColor(color: Int) { themeColor = color or (0xFF shl 24); invalidate() }
    internal var scrollOffsetPx = 0f
        private set
    private var vertical = false
    fun setVertical(value: Boolean) { vertical = value; requestLayout(); positionStrip(false); invalidate() }
    private val step get() = (if (vertical) 64f else 96f) * density
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    private fun targetOffset(): Float {
        val extent = if (vertical) height else width
        return ((candidateIndex + .5f) * step - extent / 2f)
            .coerceIn(0f, (items.size * step - extent).coerceAtLeast(0f))
    }
    private fun positionStrip(animate: Boolean) {
        val target = targetOffset()
        scrollAnimator?.cancel()
        if (!animate || !isLaidOut || !ValueAnimator.areAnimatorsEnabled() || kotlin.math.abs(target - scrollOffsetPx) < .5f) {
            scrollOffsetPx = target; invalidate(); return
        }
        scrollAnimator = ValueAnimator.ofFloat(scrollOffsetPx, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { scrollOffsetPx = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); positionStrip(false)
    }
    override fun onDetachedFromWindow() {
        scrollAnimator?.cancel(); scrollOffsetPx = targetOffset(); super.onDetachedFromWindow()
    }
    internal var selectedIndex = -1
        private set
    internal var candidateIndex = -1
        private set
    var onSelected: ((Int) -> Unit)? = null
    var onLongSelected: ((Int) -> Unit)? = null
    var onInteraction: (() -> Unit)? = null
    init {
        id = generateViewId(); isFocusable = true; isClickable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setOnClickListener {
            if (candidateIndex in items.indices) onSelected?.invoke(candidateIndex)
            onInteraction?.invoke()
        }
    }
    fun setEpisodes(values: List<String>, selected: Int, progress: FloatArray = FloatArray(0),
        decorations: List<EpisodeDecoration> = emptyList()) {
        if (items == values && selectedIndex == selected && this.progress.contentEquals(progress) &&
            this.decorations == decorations) return
        items = values.toList(); this.progress = progress.copyOf(); this.decorations = decorations
        selectedIndex = if (selected in items.indices) selected else -1
        candidateIndex = selectedIndex
        visibility = if (items.isEmpty()) GONE else VISIBLE
        isEnabled = items.size > 1
        positionStrip(false); describe(); invalidate()
    }
    internal fun progressAt(index: Int): Float = progress.getOrElse(index) { 0f }

    fun cancelPreview() { candidateIndex = selectedIndex; positionStrip(true); describe(); invalidate() }
    private fun move(delta: Int): Boolean {
        if (!isEnabled || items.isEmpty()) return false
        candidateIndex = (candidateIndex + delta).coerceIn(items.indices)
        positionStrip(true); describe(); invalidate(); onInteraction?.invoke(); return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            if (event.repeatCount == 0) {
                centerCandidate = candidateIndex
                centerDownAt = android.os.SystemClock.elapsedRealtime()
                longFired = false
                removeCallbacks(longPress)
                postDelayed(longPress, 1000)
            }
            true
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> if (vertical) super.onKeyDown(keyCode, event) else move(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (vertical) super.onKeyDown(keyCode, event) else move(1)
        KeyEvent.KEYCODE_DPAD_UP -> if (vertical) move(-1) else super.onKeyDown(keyCode, event)
        KeyEvent.KEYCODE_DPAD_DOWN -> if (vertical) move(1) else super.onKeyDown(keyCode, event)
        else -> super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            removeCallbacks(longPress)
            // Only a key-up paired with a key-down may commit. The key that reveals the
            // hidden controls is consumed before focus moves here, so its key-up must not
            // restart the current episode.
            val target = centerCandidate
            if (!longFired && isEnabled && target in items.indices) {
                onSelected?.invoke(target); onInteraction?.invoke()
            }
            centerCandidate = -1
            longFired = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        cancelPreview()
        if (!gainFocus) { removeCallbacks(longPress); longFired = false; centerCandidate = -1 }
        onInteraction?.invoke()
    }
    private fun describe() { contentDescription = "当前播放：${items.getOrNull(selectedIndex).orEmpty()}，候选：${items.getOrNull(candidateIndex).orEmpty()}，${if (vertical) "上下" else "左右"}选择，确定切换" }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.NumberPicker"
        if (items.isNotEmpty()) info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, items.lastIndex.toFloat(), candidateIndex.coerceAtLeast(0).toFloat())
        if (isEnabled) { info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD) }
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> move(1)
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> move(-1)
        else -> super.performAccessibilityAction(action, arguments)
    }
    private fun decorate(canvas: Canvas, x: Float, centerY: Float, decoration: EpisodeDecoration) {
        val radius = 5.2f * density
        if (decoration.complete) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(46, 160, 90)
            canvas.drawCircle(x, centerY, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            canvas.drawLine(x - 2.6f * density, centerY, x - .6f * density, centerY + 2f * density, paint)
            canvas.drawLine(x - .6f * density, centerY + 2f * density, x + 3f * density, centerY - 2.4f * density, paint)
        } else if (decoration.active) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.7f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.argb(70, 245, 245, 247)
            canvas.drawCircle(x, centerY, radius, paint)
            paint.color = themeColor
            val fraction = if (decoration.fraction > 0f) decoration.fraction.coerceIn(0f, 1f) else .12f
            canvas.drawArc(x - radius, centerY - radius, x + radius, centerY + radius, -90f, 360f * fraction, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawVertical(canvas: Canvas) {
        if (height <= 0 || items.isEmpty()) return
        val focused = hasFocus()
        val first = (scrollOffsetPx / step).toInt().coerceAtLeast(0)
        val last = kotlin.math.ceil((scrollOffsetPx + height) / step).toInt().coerceAtMost(items.lastIndex)
        val x = 4f * density
        for (index in first..last) {
            val y = index * step - scrollOffsetPx
            val candidate = index == candidateIndex
            paint.textSize = (if (candidate && focused) 34f else 26f) * resources.displayMetrics.scaledDensity
            paint.color = when {
                index == selectedIndex -> themeColor
                candidate && focused -> Color.WHITE
                else -> Color.argb(145, 245, 245, 247)
            }
            paint.typeface = Typeface.create("sans-serif-medium", if (candidate && focused) Typeface.BOLD else Typeface.NORMAL)
            paint.maskFilter = null
            val text = TextUtils.ellipsize(items[index], paint, width - 24f * density, TextUtils.TruncateAt.END).toString()
            val baseline = y + step / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(text, x, baseline, paint)
            val decoration = decorations.getOrNull(index)
            if (decoration != null) {
                val cx = width - 10f * density
                val cy = y + step / 2f
                val radius = 4.6f * density
                if (decoration.complete) {
                    paint.style = Paint.Style.FILL; paint.color = Color.rgb(46, 160, 90)
                    canvas.drawCircle(cx, cy, radius, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f * density; paint.color = Color.WHITE
                    canvas.drawLine(cx - 2.4f * density, cy, cx - .6f * density, cy + 1.8f * density, paint)
                    canvas.drawLine(cx - .6f * density, cy + 1.8f * density, cx + 2.8f * density, cy - 2.2f * density, paint)
                } else if (decoration.active) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * density; paint.color = Color.argb(70, 245, 245, 247)
                    canvas.drawCircle(cx, cy, radius, paint)
                    paint.color = themeColor
                    canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, 360f * decoration.fraction.coerceIn(0f, 1f), false, paint)
                }
                paint.style = Paint.Style.FILL
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (vertical) { drawVertical(canvas); return }
        if (width <= 0 || items.isEmpty()) return
        val center = height / 2f
        val focused = hasFocus()
        val hasProgress = progress.any { it > 0f }
        val edgeWidth = minOf(72f * density, width / 4f)
        val remaining = (items.size * step - width - scrollOffsetPx).coerceAtLeast(0f)
        val leftStrength = (scrollOffsetPx / edgeWidth).coerceIn(0f, 1f)
        val rightStrength = (remaining / edgeWidth).coerceIn(0f, 1f)
        fun smooth(value: Float): Float { val t = value.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.clipRect(0, 0, width, height)
        val first = (scrollOffsetPx / step).toInt().coerceAtLeast(0)
        val last = kotlin.math.ceil((scrollOffsetPx + width) / step).toInt().coerceAtMost(items.lastIndex)
        for (index in first..last) {
            val x = index * step - scrollOffsetPx
            val candidate = index == candidateIndex
            val fraction = progress.getOrElse(index) { 0f }.coerceIn(0f, 1f)
            paint.textSize = (if (candidate && focused) 20f else 16f) * resources.displayMetrics.scaledDensity
            paint.color = when {
                index == selectedIndex -> {
                    // Without any progress keep the playing item's solid theme colour
                    // (also used by the live channel strip); with progress, mute the base
                    // so the watched part painted by the glyph clip stands out.
                    val alpha = if (hasProgress) 160 else 255
                    Color.argb(alpha, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))
                }
                candidate && focused -> Color.WHITE
                else -> Color.argb(145, 245, 245, 247)
            }
            val decoration = decorations.getOrNull(index)
            val iconSpace = if (decoration != null && (decoration.complete || decoration.active)) 15f * density else 0f
            val text = TextUtils.ellipsize(items[index], paint, step - 12f * density - iconSpace, TextUtils.TruncateAt.END).toString()
            val textWidth = paint.measureText(text)
            val baseline = center - (paint.ascent() + paint.descent()) / 2
            val blur = maxOf((1f - smooth(x / edgeWidth)) * leftStrength,
                (1f - smooth((width - x - textWidth) / edgeWidth)) * rightStrength)
            val blurFilter = if (blur > .01f) BlurMaskFilter(blur * 2.8f * density, BlurMaskFilter.Blur.NORMAL) else null
            paint.maskFilter = blurFilter
            canvas.drawText(text, x + iconSpace, baseline, paint)
            paint.maskFilter = null
            decoration?.let { decorate(canvas, x + 4f * density, center, it) }
            if (fraction > 0f) {
                // No separate progress bar: clip the same glyphs to the watched fraction.
                val save = canvas.save()
                canvas.clipRect(x + iconSpace, 0f, x + iconSpace + textWidth * fraction, height.toFloat())
                paint.maskFilter = blurFilter
                paint.color = themeColor
                canvas.drawText(text, x + iconSpace, baseline, paint)
                paint.maskFilter = null
                canvas.restoreToCount(save)
            }
        }
        paint.maskFilter = null
        if (focused && candidateIndex in items.indices) {
            paint.color = Color.WHITE
            paint.textSize = 20f * resources.displayMetrics.scaledDensity
            val label = TextUtils.ellipsize(items[candidateIndex], paint, step - 12f * density, TextUtils.TruncateAt.END).toString()
            canvas.drawCircle(candidateIndex * step - scrollOffsetPx + paint.measureText(label) / 2f, center + 17f * density, 1.3f * density, paint)
        }
        // Sample a smooth curve across the edge, rather than a hard linear fade.
        val stops = FloatArray(33) { it / 32f }
        val colors = IntArray(stops.size) { index ->
            val x = stops[index] * width
            val alpha = (1f - leftStrength * (1f - smooth(x / edgeWidth))) *
                (1f - rightStrength * (1f - smooth((width - x) / edgeWidth)))
            Color.argb((alpha * 255).toInt(), 0, 0, 0)
        }
        edgePaint.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
            colors, stops, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgePaint)
        canvas.restore()
    }
}
