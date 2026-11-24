package com.live.tv.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.sin

/** The title is the progress surface. All paint stays within the text glyphs. */
internal class FluidTitleView(context: Context) : AppCompatTextView(context) {
    internal var progressFraction = 0f
        private set
    internal var themeColor = Color.rgb(205, 213, 221)
        private set
    internal val isFlowAnimating: Boolean get() = flowAnimator?.isStarted == true
    internal var isBuffering = false
        private set
    private var loadingAnimator: ValueAnimator? = null
    private var loadingAngle = 0f
    private var loadingFade: ValueAnimator? = null
    internal var loadingOpacity = 0f
        private set
    private val loadingPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private var loadingRequested = false
    private var loadingDeadline = 0L
    private val revealLoading = Runnable {
        if (isBuffering) { loadingRequested = true; fadeLoading(true) }
    }
    fun setBuffering(value: Boolean) {
        if (isBuffering == value) return
        isBuffering = value
        removeCallbacks(revealLoading)
        if (value) {
            if (loadingOpacity > 0f) { loadingRequested = true; fadeLoading(true) }
            else {
                loadingRequested = false
                loadingDeadline = android.os.SystemClock.uptimeMillis() + 300L
                postDelayed(revealLoading, 300L)
            }
        } else { loadingRequested = false; fadeLoading(false) }
    }
    private fun fadeLoading(value: Boolean) {
        loadingFade?.cancel()
        val target = if (value) 1f else 0f
        if (!isAttachedToWindow || !isShown || !ValueAnimator.areAnimatorsEnabled()) {
            loadingOpacity = target
        } else {
            loadingFade = ValueAnimator.ofFloat(loadingOpacity, target).apply {
                duration = (220 * kotlin.math.abs(target - loadingOpacity)).toLong().coerceAtLeast(1)
                addUpdateListener {
                    if (!ValueAnimator.areAnimatorsEnabled()) { cancel(); loadingOpacity = target }
                    else loadingOpacity = it.animatedValue as Float
                    updateAnimation(); invalidate()
                }
                start()
            }
        }
        updateAnimation(); invalidate()
    }
    private var flowing = false
    internal var flowPhase = 0f
        private set
    private val flowMatrix = Matrix()
    private var shimmerPeriod = 1f
    internal val progressTextWidth: Float
        get() {
            val available = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0).toFloat()
            return (layout?.getLineWidth(0) ?: available).coerceIn(0f, available)
        }
    private var flowAnimator: ValueAnimator? = null
    private val visibilityReady = Runnable { updateAnimation() }
    private val liquidEdge = Path()
    private var glassShader: Shader? = null
    private var liquidShader: Shader? = null
    private var titleAsset: VectorTitleAsset? = null
    internal var vectorBuildCount = 0
        private set

    private fun vectorTitle(): VectorTitleAsset? {
        val textLayout = layout ?: return null
        if (textLayout.lineCount == 0) return null
        val visible = textLayout.text.subSequence(textLayout.getLineStart(0), textLayout.getLineEnd(0)).toString()
        val left = compoundPaddingLeft + textLayout.getLineLeft(0)
        val innerHeight = height - extendedPaddingTop - extendedPaddingBottom
        val vertical = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> (innerHeight - textLayout.height).coerceAtLeast(0)
            Gravity.CENTER_VERTICAL -> ((innerHeight - textLayout.height) / 2).coerceAtLeast(0)
            else -> 0
        }
        val baseline = (extendedPaddingTop + vertical + textLayout.getLineBaseline(0)).toFloat()
        val key = VectorTitleAsset.Key(visible, paint.textSize, paint.typeface, paint.textScaleX,
            paint.textSkewX, paint.letterSpacing, paint.fontFeatureSettings, left, baseline)
        if (titleAsset?.key != key) {
            titleAsset = VectorTitleAsset.create(key, paint)
            vectorBuildCount++
        }
        return titleAsset
    }

    init {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        // No bitmap-backed layer or scaled title image: retain a path display list.
        setLayerType(View.LAYER_TYPE_NONE, null)
        textSize = 24f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        includeFontPadding = false
        // Reserve the accessory slot in every state, including the centred paused title.
        setPadding(0, 0, (32 * resources.displayMetrics.density).toInt(), 0)
        setTextColor(Color.WHITE)
        setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }

    fun setProgress(position: Long, duration: Long) {
        val fraction = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
        if (fraction == progressFraction) return
        progressFraction = fraction
        invalidate()
    }

    fun setThemeColor(color: Int) {
        val opaque = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (themeColor == opaque) return
        themeColor = opaque
        rebuildShaders()
        invalidate()
    }

    fun setFlowing(value: Boolean) {
        flowing = value
        updateAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        val h = height.coerceAtLeast(1).toFloat()
        // A broad translucent highlight gives glass depth without sampling the video.
        glassShader = LinearGradient(0f, 0f, 0f, h,
            if (hasFocus()) intArrayOf(0xEFFFFFFF.toInt(), 0xC9E5EAF0.toInt(), 0xA8D1DBE5.toInt(), 0xD7EAF0F6.toInt())
            else intArrayOf(0xB8FFFFFF.toInt(), 0x88E5EAF0.toInt(), 0x64D1DBE5, 0x9AEAF0F6.toInt()),
            floatArrayOf(0f, .38f, .72f, 1f), Shader.TileMode.CLAMP)
        val highlight = Color.rgb(
            (Color.red(themeColor) * .86f + 255 * .14f).toInt(),
            (Color.green(themeColor) * .86f + 255 * .14f).toInt(),
            (Color.blue(themeColor) * .86f + 255 * .14f).toInt())
        // A broad, low-contrast travelling highlight remains visible even when the
        // fill boundary happens to fall between glyphs. One cached shader/matrix.
        shimmerPeriod = resources.displayMetrics.density * 240f
        liquidShader = LinearGradient(0f, 0f, shimmerPeriod, 0f,
            intArrayOf(themeColor, highlight, themeColor), floatArrayOf(0f, .5f, 1f), Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        if (glassShader == null) rebuildShaders()
        val asset = vectorTitle() ?: return
        val previousShader = paint.shader
        paint.shader = glassShader
        asset.draw(canvas, paint)
        if (progressFraction > 0f) {
            val textLayout = layout
            val available = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(0).toFloat()
            val left = compoundPaddingLeft.toFloat() + (textLayout?.getLineLeft(0) ?: 0f).coerceIn(0f, available)
            val textWidth = progressTextWidth.coerceAtMost((width - compoundPaddingRight - left).coerceAtLeast(0f))
            val boundary = left + textWidth * progressFraction
            val h = height.toFloat()
            // Small, slow opposing curves read as liquid, not a sharp rectangular wipe.
            val amplitude = resources.displayMetrics.density * 2.3f * sin(Math.PI * progressFraction).toFloat()
            val drift = sin(flowPhase * Math.PI * 2).toFloat() * amplitude
            liquidEdge.reset()
            liquidEdge.moveTo(0f, 0f)
            liquidEdge.lineTo(boundary + drift, 0f)
            liquidEdge.cubicTo(boundary - amplitude, h * .32f, boundary + amplitude, h * .68f, boundary - drift, h)
            liquidEdge.lineTo(0f, h)
            liquidEdge.close()
            val save = canvas.save()
            if (progressFraction < 1f) canvas.clipPath(liquidEdge)
            flowMatrix.setTranslate(flowPhase * shimmerPeriod, 0f)
            liquidShader?.setLocalMatrix(flowMatrix)
            paint.shader = liquidShader
            asset.draw(canvas, paint)
            canvas.restoreToCount(save)
        }
        paint.shader = previousShader
        if (loadingOpacity > 0f) {
            val density = resources.displayMetrics.density
            val size = 16f * density
            val left = compoundPaddingLeft + progressTextWidth + 10f * density
            val top = (height - size) / 2f
            loadingPaint.strokeWidth = 1.8f * density
            loadingPaint.color = themeColor
            loadingPaint.alpha = (55 * loadingOpacity).toInt()
            canvas.drawOval(left, top, left + size, top + size, loadingPaint)
            loadingPaint.color = themeColor
            loadingPaint.alpha = (255 * loadingOpacity).toInt()
            canvas.drawArc(left, top, left + size, top + size, loadingAngle, 100f, false, loadingPaint)
        }
    }

    private fun updateAnimation() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) {
            loadingFade?.cancel(); loadingOpacity = if (loadingRequested) 1f else 0f
        }
        val loading = (loadingRequested || loadingOpacity > 0f) && isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (!loading) loadingAnimator?.cancel()
        else if (loadingAnimator?.isStarted != true) {
            loadingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { if (!ValueAnimator.areAnimatorsEnabled()) cancel() else { loadingAngle = it.animatedValue as Float; invalidate() } }
                start()
            }
        }
        val animate = flowing && isAttachedToWindow && isShown && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()
        if (!animate) {
            flowAnimator?.cancel()
            return
        }
        if (isFlowAnimating) return
        val animator = flowAnimator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    cancel()
                } else {
                    flowPhase = it.animatedValue as Float
                    invalidate()
                }
            }
        }.also { flowAnimator = it }
        animator.start()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // Focus brightens only the glyph fill; edges stay sharp in both states.
        rebuildShaders()
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (isClickable) info.className = "android.widget.Button"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isBuffering && !loadingRequested) {
            removeCallbacks(revealLoading)
            postDelayed(revealLoading, (loadingDeadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
        }
        updateAnimation()
        // The parent/window visibility may settle only after its child attachment
        // callback returns. Reconcile once after that traversal, never poll.
        post(visibilityReady)
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(visibilityReady); removeCallbacks(revealLoading)
        flowAnimator?.cancel(); loadingAnimator?.cancel(); loadingFade?.cancel(); loadingOpacity = if (loadingRequested) 1f else 0f
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateAnimation() else { flowAnimator?.cancel(); loadingAnimator?.cancel(); loadingFade?.cancel(); loadingOpacity = if (loadingRequested) 1f else 0f }
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

}
