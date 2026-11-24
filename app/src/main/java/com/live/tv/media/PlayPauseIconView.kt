package com.live.tv.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator

/**
 * The same solid play/pause pair used by the music player: a rounded triangle and two
 * rounded bars. The two states cross-fade and scale slightly; no custom glyph is drawn.
 */
internal class PlayPauseIconView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(4.5f * density, 0f, 1.5f * density, Color.argb(210, 0, 0, 0))
    }
    private val playPath = Path()
    private val bar = RectF()
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var target = 0f

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setIconColor(color: Int) {
        paint.color = color; invalidate()
    }

    fun setPlaying(playing: Boolean, animate: Boolean = true) {
        val next = if (playing) 1f else 0f
        if (next == target) return
        target = next
        animator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            progress = next; invalidate(); return
        }
        animator = ValueAnimator.ofFloat(progress, next).apply {
            duration = 180
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    internal val progressFraction: Float get() = progress
    internal val targetFraction: Float get() = target

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null && progress != target) { progress = target; invalidate() }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val t = progress.coerceIn(0f, 1f)
        if (t < 1f) drawPlay(canvas, width, height, 1f - t, .94f + .06f * (1f - t))
        if (t > 0f) drawPause(canvas, width, height, t, .94f + .06f * t)
    }

    private fun drawPlay(canvas: Canvas, width: Float, height: Float, alpha: Float, scale: Float) {
        val unit = minOf(width, height) / 64f
        playPath.reset()
        playPath.moveTo(13f * unit, 9f * unit)
        playPath.quadTo(13f * unit, 2f * unit, 20f * unit, 6f * unit)
        playPath.lineTo(56f * unit, 27f * unit)
        playPath.quadTo(64f * unit, 32f * unit, 56f * unit, 37f * unit)
        playPath.lineTo(20f * unit, 58f * unit)
        playPath.quadTo(13f * unit, 62f * unit, 13f * unit, 55f * unit)
        playPath.close()
        drawScaled(canvas, width, height, scale, (alpha * 255).toInt()) { canvas.drawPath(playPath, paint) }
    }

    private fun drawPause(canvas: Canvas, width: Float, height: Float, alpha: Float, scale: Float) {
        val unit = minOf(width, height) / 64f
        drawScaled(canvas, width, height, scale, (alpha * 255).toInt()) {
            bar.set(10f * unit, 5f * unit, 27f * unit, 59f * unit)
            canvas.drawRoundRect(bar, 6f * unit, 6f * unit, paint)
            bar.set(37f * unit, 5f * unit, 54f * unit, 59f * unit)
            canvas.drawRoundRect(bar, 6f * unit, 6f * unit, paint)
        }
    }

    private fun drawScaled(canvas: Canvas, width: Float, height: Float, scale: Float,
        alpha: Int, draw: () -> Unit) {
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-width / 2f, -height / 2f)
        paint.alpha = alpha.coerceIn(0, 255)
        draw()
        paint.alpha = 255
        canvas.restore()
    }
}
