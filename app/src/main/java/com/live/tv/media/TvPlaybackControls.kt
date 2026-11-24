package com.live.tv.media

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.Util
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import java.util.Locale

/** TV focus and presentation around Media3's native, accessible scrubber. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvPlaybackControls(context: Context) : FrameLayout(context) {
    private val ink = Color.rgb(245, 245, 247)
    private val secondary = Color.rgb(184, 187, 194)
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var remotePosition: Long? = null
    private var scrubbing = false
    private var keySeekPosition: Long? = null
    private var cancelPendingSeek = false
    private var settingsOpen = false
    internal var isShowingControls = false
        private set
    private var visibilityAnimator: ValueAnimator? = null
    private var groupAnimator: ValueAnimator? = null
    private var groupVisible = false
    internal val controlGroup = object : LinearLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            if (groupVisible && isShowingControls) super.dispatchTouchEvent(event) else false
    }.apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.LEFT; clipChildren = false; clipToPadding = false }
    internal val actionRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    private var titleAnimator: ValueAnimator? = null
    private var titleExpansion = 0f
    private var titleTarget = 0f
    internal val heading = FluidTitleView(context).apply {
        id = View.generateViewId(); text = "原生播放"; textSize = 24f
        isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { togglePlayback() }
        setOnFocusChangeListener { _, focused -> if (!focused) endKeySeek(); interacted() }
    }
    // Retain the semantic transport target without adding a separate icon.
    internal val playPause get() = heading
    internal val seekTime = label(52f, ink).apply { gravity = Gravity.CENTER; visibility = GONE }
    internal val timeBar = DefaultTimeBar(context).apply {
        id = View.generateViewId(); isFocusable = true
        contentDescription = "播放进度，左右调整，确定应用"
        setPlayedColor(Color.TRANSPARENT); setScrubberColor(Color.TRANSPARENT); setBufferedColor(Color.TRANSPARENT); setUnplayedColor(Color.TRANSPARENT)
        setKeyTimeIncrement(10_000)
        setPadding(0, 0, 0, 0)
    }
    internal val episodePicker = EpisodePickerView(context).apply {
        visibility = GONE
        onSelected = { if (groupVisible && isShowingControls) onEpisodeSelected?.invoke(it) }
        onInteraction = { interacted() }
    }
    internal val audio = action("无音轨") { cycleTrack(C.TRACK_TYPE_AUDIO) }
    internal val subtitles = action("字幕关闭") { cycleTrack(C.TRACK_TYPE_TEXT) }
    internal val speed = action("1×") { cycleSpeed() }
    var onEpisodeSelected: ((Int) -> Unit)? = null
    private val hideLater = Runnable { if (player?.isPlaying == true && !scrubbing && remotePosition == null && !settingsOpen) hide() }
    private val refresh = object : Runnable {
        override fun run() { update(); if (isShowingControls) handler.postDelayed(this, 500) }
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update(); if (player.playbackState == Player.STATE_BUFFERING) show(false); if ((!player.playWhenReady && player.playbackState != Player.STATE_IDLE) || player.playbackState == Player.STATE_ENDED) show(false) }
    }

    init {
        visibility = GONE; alpha = 0f; clipChildren = false; clipToPadding = false
        val bottom = controlGroup
        addView(bottom, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { setMargins(dp(40), 0, dp(40), dp(24)) })
        bottom.addView(episodePicker, LinearLayout.LayoutParams(dp(480), dp(44)))
        bottom.addView(actionRow, LinearLayout.LayoutParams(-2, dp(48)))
        listOf(speed, audio, subtitles).forEach { button ->
            actionRow.addView(button, LinearLayout.LayoutParams(dp(if (button === speed) 56 else 104), dp(48)).apply { leftMargin = if (button === speed) 0 else dp(8) })
        }
        addView(heading, LayoutParams(-2, dp(64), Gravity.TOP or Gravity.LEFT))
        // Media3 still supplies accessible and keyboard scrubbing, with the title as its visual.
        addView(timeBar, LayoutParams(-1, dp(28), Gravity.BOTTOM).apply { leftMargin = dp(40); rightMargin = dp(40) })
        addView(seekTime, LayoutParams(-1, -2, Gravity.CENTER))
        listOf(audio, subtitles, speed).forEach { button ->
            button.nextFocusUpId = heading.id; button.nextFocusDownId = button.id
        }
        heading.nextFocusUpId = heading.id
        heading.nextFocusLeftId = heading.id; heading.nextFocusRightId = heading.id
        episodePicker.nextFocusUpId = heading.id
        episodePicker.nextFocusDownId = speed.id
        episodePicker.nextFocusLeftId = episodePicker.id
        episodePicker.nextFocusRightId = episodePicker.id
        speed.nextFocusLeftId = speed.id
        speed.nextFocusRightId = audio.id
        audio.nextFocusLeftId = speed.id; audio.nextFocusRightId = subtitles.id
        subtitles.nextFocusLeftId = audio.id; subtitles.nextFocusRightId = subtitles.id
        timeBar.nextFocusDownId = timeBar.id; timeBar.nextFocusUpId = heading.id
        timeBar.setOnFocusChangeListener { _, _ -> interacted() }
        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) { remotePosition = null; scrubbing = true; showTime(position); interacted() }
            override fun onScrubMove(timeBar: TimeBar, position: Long) { showTime(position); interacted() }
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                scrubbing = false
                if (!canceled && !cancelPendingSeek) player?.takeIf { it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }?.seekTo(position)
                seekTime.visibility = GONE
                update(); interacted()
            }
        })
        update()
    }

    fun bind(value: Player?) {
        clearSeekPreview()
        player?.removeListener(listener); player = value; value?.addListener(listener); update()
        if (value == null) hide()
        if (isShowingControls && findFocus() == null && playPause.isEnabled) playPause.requestFocus()
    }

    fun episodes(items: List<String>, selectedIndex: Int) {
        val hadFocus = episodePicker.hasFocus()
        episodePicker.setEpisodes(items, selectedIndex)
        if (hadFocus && !episodePicker.isEnabled) heading.requestFocus()
        heading.nextFocusDownId = if (episodePicker.isEnabled) episodePicker.id else speed.id
        listOf(speed, audio, subtitles).forEach { it.nextFocusUpId = if (episodePicker.isEnabled) episodePicker.id else heading.id }
        requestLayout()
    }

    fun themeColor(color: Int) { heading.setThemeColor(color); episodePicker.setThemeColor(color) }

    fun title(value: String) { heading.text = value.ifBlank { "原生播放" }; requestLayout() }

    fun show(focus: Boolean = true) {
        val wasHidden = !isShowingControls
        isShowingControls = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        fadeVisibility(true)
        updateGroupVisibility()
        if (wasHidden) { handler.removeCallbacks(refresh); handler.post(refresh) }
        if (focus) playPause.requestFocus()
        interacted()
    }

    fun hide() {
        // View.setEnabled may lose focus before Media3 reports cancellation. Guard that focus-loss callback too.
        if (scrubbing) {
            cancelPendingSeek = true
            try { timeBar.isEnabled = false } finally { cancelPendingSeek = false }
            update()
        }
        clearSeekPreview()
        episodePicker.cancelPreview()
        isShowingControls = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clearFocus()
        handler.removeCallbacks(refresh); handler.removeCallbacks(hideLater)
        fadeVisibility(false)
        (parent as? View)?.requestFocus()
    }

    /** Preview only: the remote protocol commits its seek separately on release. */
    fun previewRemoteSeek(positionMs: Long) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable || !p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || p.duration <= 0) return
        if (scrubbing) clearSeekPreview()
        endKeySeek()
        remotePosition = positionMs.coerceIn(0, p.duration)
        show(false)
        timeBar.setDuration(p.duration)
        timeBar.setPosition(remotePosition!!)
        showTime(remotePosition!!)
        updateTitleState()
    }
    fun endRemoteSeek() {
        remotePosition = null
        if (!scrubbing) seekTime.visibility = GONE
        update(); interacted()
    }
    private fun clearSeekPreview() {
        remotePosition = null
        endKeySeek()
        if (scrubbing) {
            cancelPendingSeek = true
            try { timeBar.isEnabled = false } finally { cancelPendingSeek = false }
        }
        scrubbing = false
        seekTime.visibility = GONE
        updateTitleState()
    }

    fun settings(open: Boolean) { settingsOpen = open; if (!open) interacted() else handler.removeCallbacks(hideLater) }
    fun interacted() { handler.removeCallbacks(hideLater); if (isShowingControls && !settingsOpen) handler.postDelayed(hideLater, 6000) }
    fun play() {
        val wasHidden = !isShowingControls
        player?.let { Util.handlePlayButtonAction(it) }
        show(wasHidden); update()
    }
    fun togglePlayback() {
        val wasHidden = !isShowingControls
        player?.let { Util.handlePlayPauseButtonAction(it) }
        show(wasHidden); update()
    }
    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !p.isCurrentMediaItemSeekable) return
        val duration = p.duration
        val wasHidden = !isShowingControls
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it })
        show(wasHidden); update()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (isShowingControls) super.dispatchTouchEvent(event) else false
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isShowingControls) return false
        if (event.action == KeyEvent.ACTION_DOWN) interacted()
        if (heading.hasFocus()) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val p = player
                    if (p != null && p.isCurrentMediaItemSeekable && p.duration > 0 && p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                        val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                        val position = ((keySeekPosition ?: p.currentPosition) + delta).coerceIn(0, p.duration)
                        keySeekPosition = position
                        p.seekTo(position)
                        showTime(position)
                    }
                } else if (event.action == KeyEvent.ACTION_UP) endKeySeek()
                // Consume even for live/unseekable items; Web must not fall back to horizontal focus movement.
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN && groupVisible) {
                    listOf(episodePicker, speed, audio, subtitles).firstOrNull { it.visibility == VISIBLE && it.isEnabled }?.requestFocus()
                }
                return true
            }
        }
        val actions = listOf(speed, audio, subtitles)
        val focusedAction = actions.indexOfFirst { it.hasFocus() }
        if (groupVisible && focusedAction >= 0) {
            val target = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> actions[(focusedAction - 1).coerceAtLeast(0)]
                KeyEvent.KEYCODE_DPAD_RIGHT -> actions[(focusedAction + 1).coerceAtMost(actions.lastIndex)]
                KeyEvent.KEYCODE_DPAD_UP -> if (episodePicker.isEnabled) episodePicker else heading
                KeyEvent.KEYCODE_DPAD_DOWN -> actions[focusedAction]
                else -> null
            }
            if (target != null) {
                if (event.action == KeyEvent.ACTION_DOWN) target.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    private fun endKeySeek() {
        if (keySeekPosition == null) return
        keySeekPosition = null
        if (!scrubbing && remotePosition == null) seekTime.visibility = GONE
        update(); interacted()
    }
    override fun onDetachedFromWindow() { clearSeekPreview(); titleAnimator?.cancel(); visibilityAnimator?.cancel(); groupAnimator?.cancel(); handler.removeCallbacksAndMessages(null); player?.removeListener(listener); super.onDetachedFromWindow() }

    private fun update() {
        val p = player
        heading.setBuffering(p?.playbackState == Player.STATE_BUFFERING)
        val canSeek = p != null && p.isCurrentMediaItemSeekable && p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && p.duration > 0
        timeBar.isEnabled = canSeek
        playPause.isEnabled = p?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true; playPause.alpha = if (playPause.isEnabled) 1f else .35f
        playPause.contentDescription = "${heading.text}，" + when { p?.playbackState == Player.STATE_ENDED -> "重播"; Util.shouldShowPlayButton(p) -> "播放"; else -> "暂停" }
        heading.setFlowing(p?.isPlaying == true && remotePosition == null && !scrubbing && keySeekPosition == null)
        updateTitleState()
        updateGroupVisibility()
        updateTrack(audio, C.TRACK_TYPE_AUDIO)
        updateTrack(subtitles, C.TRACK_TYPE_TEXT)
        speed.isEnabled = p != null
        listOf(audio, subtitles, speed).forEach { it.alpha = if (it.isEnabled) 1f else .35f }
        speed.text = String.format(Locale.ROOT, "%.2f", p?.playbackParameters?.speed ?: 1f).trimEnd('0').trimEnd('.') + "×"
        speed.contentDescription = "播放速度 ${speed.text}，" + if (p?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true) "单击切换" else "当前资源不支持调整"
        if (!scrubbing && remotePosition == null && keySeekPosition == null) { timeBar.setDuration(p?.duration ?: C.TIME_UNSET); timeBar.setPosition(p?.currentPosition ?: 0); timeBar.setBufferedPosition(p?.bufferedPosition ?: 0); heading.setProgress(p?.currentPosition ?: 0, p?.duration ?: 0) }
    }
    // One choice per logical track group; alternate bitrates stay adaptive, not extra languages.
    private data class TrackChoice(val group: Tracks.Group, val indices: List<Int>, val label: String)
    private fun trackChoices(type: Int): List<TrackChoice> {
        val choices = player?.currentTracks?.groups.orEmpty().filter { it.type == type }.mapNotNull { group ->
            val indices = (0 until group.length).filter { group.isTrackSupported(it) }
            if (indices.isEmpty()) return@mapNotNull null
            val format = group.getTrackFormat(indices.firstOrNull { group.isTrackSelected(it) } ?: indices.first())
            val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
                Locale.forLanguageTag(it).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE).ifBlank { it }
            }
            val label = format.label?.takeIf { it.isNotBlank() } ?: listOfNotNull(language,
                if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) "${format.channelCount} 声道" else null)
                .joinToString(" · ").ifBlank { if (type == C.TRACK_TYPE_TEXT) "字幕" else "音轨" }
            TrackChoice(group, indices, label)
        }
        val duplicates = choices.groupingBy { it.label }.eachCount()
        return choices.mapIndexed { index, choice ->
            if (duplicates.getValue(choice.label) > 1) choice.copy(label = "${choice.label} · ${index + 1}") else choice
        }
    }
    private fun selectedTrack(type: Int, choices: List<TrackChoice>): Int {
        if (choices.isEmpty()) return -1
        val p = player ?: return -1
        val parameters = p.trackSelectionParameters
        if (type in parameters.disabledTrackTypes) return -1
        val override = choices.indexOfFirst { choice ->
            parameters.overrides[choice.group.mediaTrackGroup]?.trackIndices?.any { it in choice.indices } == true
        }
        if (override >= 0) return override
        return choices.indexOfFirst { choice -> choice.indices.any { choice.group.isTrackSelected(it) } }
    }
    private fun updateTrack(button: Button, type: Int) {
        val choices = trackChoices(type)
        val selected = selectedTrack(type, choices)
        button.text = choices.getOrNull(selected)?.label ?: if (type == C.TRACK_TYPE_TEXT) {
            if (choices.isEmpty()) "无字幕" else "字幕关闭"
        } else if (choices.isEmpty()) "无音轨" else "自动音轨"
        val availability = when {
            choices.isEmpty() -> "当前资源未提供"
            player?.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS) != true -> "当前资源不支持切换"
            type == C.TRACK_TYPE_AUDIO && choices.size == 1 && selected >= 0 -> "仅此音轨"
            else -> "单击切换"
        }
        button.contentDescription = "${if (type == C.TRACK_TYPE_TEXT) "字幕" else "音轨"}：${button.text}，$availability"
        // A fixed three-item row remains navigable even when a resource has no alternative track.
        button.isEnabled = player != null
    }
    private fun cycleTrack(type: Int) {
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val choices = trackChoices(type)
        if (choices.isEmpty()) return
        val selected = selectedTrack(type, choices)
        if (type == C.TRACK_TYPE_AUDIO && choices.size == 1 && selected >= 0) return
        val next = if (selected + 1 < choices.size) selected + 1 else if (type == C.TRACK_TYPE_TEXT) -1 else 0
        val parameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, next < 0)
        choices.getOrNull(next)?.let { parameters.setOverrideForType(TrackSelectionOverride(it.group.mediaTrackGroup, it.indices)) }
        p.trackSelectionParameters = parameters.build()
        update()
    }
    private fun cycleSpeed() {
        val p = player ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        val values = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        val current = p.playbackParameters.speed
        p.setPlaybackSpeed(values.firstOrNull { it > current + .001f } ?: values.first())
        update()
    }
    private fun showTime(position: Long) {
        val duration = player?.duration ?: 0
        heading.setFlowing(false)
        seekTime.text = "${formatTime(position)} / ${if (duration > 0) formatTime(duration) else "--:--"}"
        seekTime.visibility = VISIBLE
        heading.setProgress(position, duration)
        updateTitleState()
    }
    /** Alpha lives on containers so disabled glyph opacity stays independent of transitions. */
    private fun fadeVisibility(show: Boolean) {
        val target = if (show) 1f else 0f
        visibilityAnimator?.cancel()
        if (show) visibility = VISIBLE
        if (alpha == target || !ValueAnimator.areAnimatorsEnabled() || player == null) {
            alpha = target
            visibility = if (show) VISIBLE else GONE
            return
        }
        visibilityAnimator = ValueAnimator.ofFloat(alpha, target).apply {
            duration = (220 * kotlin.math.abs(target - alpha)).toLong().coerceAtLeast(1)
            addUpdateListener {
                if (!ValueAnimator.areAnimatorsEnabled()) { cancel(); alpha = target } else alpha = it.animatedValue as Float
                if (alpha == 0f && !isShowingControls) visibility = GONE
            }
            start()
        }
    }

    private fun updateGroupVisibility() {
        val show = player != null && !Util.shouldShowPlayButton(player)
        if (groupVisible == show && (groupAnimator?.isRunning == true || controlGroup.alpha == if (show) 1f else 0f)) return
        groupVisible = show
        groupAnimator?.cancel()
        val hadFocus = controlGroup.hasFocus()
        controlGroup.descendantFocusability = if (show) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        if (!show) {
            episodePicker.cancelPreview()
            controlGroup.clearFocus()
            if (hadFocus && isShowingControls) heading.requestFocus()
        }
        val target = if (show) 1f else 0f
        if (show) controlGroup.visibility = VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled() || !isLaidOut || visibility != VISIBLE || player == null) {
            controlGroup.alpha = target
            controlGroup.visibility = if (show) VISIBLE else INVISIBLE
            return
        }
        groupAnimator = ValueAnimator.ofFloat(controlGroup.alpha, target).apply {
            duration = (220 * kotlin.math.abs(target - controlGroup.alpha)).toLong().coerceAtLeast(1)
            addUpdateListener {
                if (!ValueAnimator.areAnimatorsEnabled()) { cancel(); controlGroup.alpha = target } else controlGroup.alpha = it.animatedValue as Float
                if (controlGroup.alpha == 0f && !groupVisible) controlGroup.visibility = INVISIBLE
            }
            start()
        }
    }

    private fun updateTitleState() {
        val target = if (player != null && Util.shouldShowPlayButton(player) && remotePosition == null && !scrubbing && keySeekPosition == null) 1f else 0f
        if (target == titleTarget && (titleAnimator?.isRunning == true || titleExpansion == target)) return
        titleTarget = target
        titleAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || !isLaidOut || visibility != VISIBLE) {
            titleExpansion = target; positionTitle(); return
        }
        titleAnimator = ValueAnimator.ofFloat(titleExpansion, target).apply {
            duration = (420 * kotlin.math.abs(target - titleExpansion)).toLong().coerceAtLeast(100)
            interpolator = android.view.animation.PathInterpolator(.22f, .7f, .2f, 1f)
            addUpdateListener {
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    cancel(); titleExpansion = titleTarget
                } else titleExpansion = it.animatedValue as Float
                positionTitle()
            }
            start()
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = View.MeasureSpec.getSize(widthMeasureSpec)
        episodePicker.layoutParams.width = minOf(dp(480), (available - dp(80)).coerceAtLeast(0))
        val maximumTitleWidth = (available - dp(80)).coerceAtLeast(dp(160))
        if (heading.maxWidth != maximumTitleWidth) heading.maxWidth = maximumTitleWidth
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionTitle()
    }
    private fun positionTitle() {
        if (width <= 0 || heading.width <= 0) return
        val expandedScale = minOf(2.35f, (width - dp(96)).toFloat() / heading.width)
        val scale = 1f + (expandedScale - 1f) * titleExpansion
        val startX = dp(40).toFloat()
        val startY = controlGroup.top - heading.height.toFloat()
        val endX = (width - heading.width * expandedScale) / 2f
        val endY = (height - heading.height * expandedScale) / 2f
        heading.pivotX = 0f; heading.pivotY = 0f
        heading.scaleX = scale; heading.scaleY = scale
        heading.translationX = startX + (endX - startX) * titleExpansion
        heading.translationY = startY + (endY - startY) * titleExpansion
    }
    private fun label(size: Float, color: Int) = TextView(context).apply { textSize = size; setTextColor(color); includeFontPadding = false; typeface = Typeface.create("sans-serif", Typeface.NORMAL); fontFeatureSettings = "tnum"; setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK) }
    private fun action(text: String, onClick: () -> Unit) = Button(context).apply {
        textSize = 16f; setTextColor(secondary); includeFontPadding = false; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); stateListAnimator = null
        minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0; setPadding(0, 0, dp(6), 0); pivotX = 0f
        id = View.generateViewId(); this.text = text; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
        background = null
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        setOnClickListener { if (groupVisible && isShowingControls) { onClick(); interacted() } }
        setOnFocusChangeListener { view, focused ->
            // Focus belongs to the glyphs, matching the title and episode wheel.
            setTextColor(if (focused) Color.WHITE else secondary)
            setShadowLayer(dp(3).toFloat(), 0f, if (focused) 0f else dp(1).toFloat(), if (focused) 0x70FFFFFF else Color.BLACK)
            view.animate().cancel()
            val scale = if (focused) 1.055f else 1f
            if (ValueAnimator.areAnimatorsEnabled()) view.animate().scaleX(scale).scaleY(scale).setDuration(160).start() else { view.scaleX = scale; view.scaleY = scale }
            interacted()
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    companion object {
        internal fun formatTime(value: Long): String {
            val seconds = value.coerceAtLeast(0) / 1000
            return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}
