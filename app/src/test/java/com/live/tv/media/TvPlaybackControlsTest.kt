package com.live.tv.media

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvPlaybackControlsTest {
    private class Playback {
        var position = 25_000L
        var duration = 90_000L
        var playing = true
        var seekable = true
        var state = Player.STATE_READY
        var speed = 1f
        var canSpeed = true
        var canSelectTracks = true
        var tracks = Tracks.EMPTY
        var parameters = TrackSelectionParameters.Builder(ApplicationProvider.getApplicationContext()).build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentPosition", "getBufferedPosition" -> position
                "getDuration" -> duration
                "getPlayWhenReady", "isPlaying" -> playing
                "getPlaybackState" -> state
                "isCurrentMediaItemSeekable" -> seekable
                "isCommandAvailable" -> when (args?.first()) { Player.COMMAND_SET_SPEED_AND_PITCH -> canSpeed; Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS -> canSelectTracks; else -> true }
                "getCurrentTracks" -> tracks
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> { parameters = args!!.first() as TrackSelectionParameters; null }
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "setPlaybackSpeed" -> { speed = args!!.first() as Float; null }
                "seekTo" -> { position = args!!.last() as Long; null }
                "seekToDefaultPosition" -> { position = 0; null }
                "pause" -> { playing = false; null }
                "play" -> { playing = true; null }
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null }
            }
        } as Player
    }
    private fun withControls(test: (TvPlaybackControls, Playback) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val controls = TvPlaybackControls(controller.get()); val playback = Playback()
        controller.get().setContentView(controls); controls.bind(playback.player)
        controller.visible().windowFocusChanged(true)
        try { test(controls, playback) } finally { controls.bind(null); controller.pause().stop().destroy() }
    }
    @Test fun buttonsPauseResumeAndClampSeekToMediaBounds() = withControls { controls, p ->
        controls.show(); assertTrue(controls.playPause.hasFocus())
        controls.playPause.performClick(); assertFalse(p.playing); assertTrue(controls.playPause.contentDescription.toString().endsWith("播放"))
        controls.playPause.performClick(); assertTrue(p.playing)
        controls.seekBy(-10_000); assertEquals(15_000L, p.position)
        controls.seekBy(-100_000); assertEquals(0L, p.position)
        controls.seekBy(200_000); assertEquals(90_000L, p.position)
    }
    @Test fun titleArrowKeysSeekOnRepeatClampAndHideTimeOnRelease() = withControls { controls, p ->
        controls.show()
        fun key(action: Int, code: Int, repeat: Int = 0) = controls.dispatchKeyEvent(KeyEvent(0L, 0L, action, code, repeat))
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(35_000L, p.position)
        assertEquals("0:35 / 1:30", controls.seekTime.text.toString())
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1))
        assertEquals(45_000L, p.position)
        assertTrue(controls.heading.hasFocus())
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(View.GONE, controls.seekTime.visibility)
        p.position = 89_000L
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals(90_000L, p.position)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
        p.position = 5_000L
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(0L, p.position)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)
        p.seekable = false
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(0L, p.position)
        assertTrue(controls.heading.hasFocus())
        assertEquals(View.GONE, controls.seekTime.visibility)
    }

    @Test fun scrubberUsesRemoteTenSecondStepAndCommit() = withControls { controls, p ->
        controls.show(); controls.timeBar.requestFocus()
        assertTrue(controls.timeBar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertEquals(25_000L, p.position)
        assertTrue(controls.timeBar.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)))
        assertEquals(35_000L, p.position)
    }
    @Test fun hidingControlsCancelsPendingRemoteSeek() = withControls { controls, p ->
        controls.show(); controls.timeBar.requestFocus()
        controls.timeBar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        controls.hide(); assertEquals(25_000L, p.position); assertFalse(controls.isShowingControls)
        animation(controls, "visibilityAnimator")?.end(); assertEquals(View.GONE, controls.visibility)
    }
    @Test fun remotePreviewFollowsPositionWithoutSeekingAndSurvivesRefresh() = withControls { controls, p ->
        controls.previewRemoteSeek(60_000)
        assertEquals(25_000L, p.position)
        assertEquals(View.VISIBLE, controls.seekTime.visibility)
        assertEquals("1:00 / 1:30", controls.seekTime.text.toString())
        assertEquals(2f / 3f, controls.heading.progressFraction, .001f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7))
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals("1:00 / 1:30", controls.seekTime.text.toString())
        controls.previewRemoteSeek(40_000)
        assertEquals("0:40 / 1:30", controls.seekTime.text.toString())
        p.position = 40_000; controls.endRemoteSeek()
        assertEquals(View.GONE, controls.seekTime.visibility)
        assertEquals(40f / 90f, controls.heading.progressFraction, .001f)
    }
    @Test fun previewClampsAndClearsOnHideRebindAndLocalScrub() = withControls { controls, p ->
        controls.previewRemoteSeek(200_000)
        assertEquals("1:30 / 1:30", controls.seekTime.text.toString())
        controls.hide(); assertEquals(View.GONE, controls.seekTime.visibility)
        controls.previewRemoteSeek(-10_000)
        assertEquals("0:00 / 1:30", controls.seekTime.text.toString())
        controls.bind(p.player); assertEquals(View.GONE, controls.seekTime.visibility)
        controls.show(); controls.timeBar.requestFocus()
        controls.timeBar.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals("0:35 / 1:30", controls.seekTime.text.toString())
        controls.previewRemoteSeek(60_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(25_000L, p.position)
        assertEquals("1:00 / 1:30", controls.seekTime.text.toString())
        controls.endRemoteSeek(); assertEquals(View.GONE, controls.seekTime.visibility)
    }
    @Test fun completedPlaybackRestartsFromBeginning() = withControls { controls, p ->
        p.position = p.duration; p.state = Player.STATE_ENDED; controls.bind(p.player)
        assertTrue(controls.playPause.contentDescription.toString().endsWith("重播"))
        controls.playPause.performClick(); assertEquals(0L, p.position); assertTrue(p.playing)
        p.position = p.duration; controls.play(); assertEquals(0L, p.position); assertTrue(p.playing)
    }
    @Test fun physicalMediaSeekKeysUseControlsAndIgnoreKeyRepeat() {
        val controller = Robolectric.buildActivity(MediaPlaybackActivity::class.java).create().visible()
        val activity = controller.get()
        val controls = MediaPlaybackActivity::class.java.getDeclaredField("controls").apply { isAccessible = true }.get(activity) as TvPlaybackControls
        val p = Playback(); controls.bind(p.player)
        fun press(key: Int) { activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)) }
        try {
            press(KeyEvent.KEYCODE_MEDIA_REWIND); assertEquals(15_000L, p.position)
            press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); assertEquals(25_000L, p.position)
            activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 1)); assertEquals(25_000L, p.position)
            p.state = Player.STATE_ENDED; p.position = p.duration
            press(KeyEvent.KEYCODE_MEDIA_PLAY); assertEquals(0L, p.position)
        } finally { controls.bind(null); controller.destroy() }
    }
    @Test fun unseekableAndAbsentTracksRemainNavigableWithExplicitLabels() = withControls { controls, p ->
        p.seekable = false; controls.bind(p.player)
        assertFalse(controls.timeBar.isEnabled)
        assertTrue(controls.audio.isEnabled); assertTrue(controls.subtitles.isEnabled)
        assertTrue(controls.subtitles.contentDescription.contains("当前资源未提供"))
        controls.seekBy(10_000); assertEquals(25_000L, p.position)
    }
    @Test fun autoHideDoesNotDismissPausedControlsOrSettings() = withControls { controls, p ->
        controls.show(); controls.settings(true); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7)); assertEquals(View.VISIBLE, controls.visibility)
        controls.settings(false); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7)); assertEquals(View.GONE, controls.visibility)
        p.playing = false; controls.show(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(7)); assertEquals(View.VISIBLE, controls.visibility)
    }
    private fun trackGroup(id: String, mime: String, label: String, selected: Boolean = false, supported: Boolean = true): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(mime).setLabel(label).build()
        return Tracks.Group(TrackGroup(id, format), false, intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(selected))
    }
    @Test fun audioCyclesSupportedTracksAndShowsSelectionImmediately() = withControls { controls, p ->
        controls.show()
        val chinese = trackGroup("zh", "audio/aac", "普通话", selected = true)
        val english = trackGroup("en", "audio/aac", "英语")
        val unsupported = trackGroup("bad", "audio/aac", "不可播放", supported = false)
        p.tracks = Tracks(listOf(chinese, english, unsupported))
        p.parameters = p.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        controls.bind(p.player)
        assertEquals("普通话", controls.audio.text.toString())
        controls.audio.performClick()
        assertEquals("英语", controls.audio.text.toString())
        assertEquals(listOf(0), p.parameters.overrides[english.mediaTrackGroup]!!.trackIndices)
        // The next click must use the new override while currentTracks still reports the old group.
        controls.audio.performClick(); assertEquals("普通话", controls.audio.text.toString())
        assertTrue(C.TRACK_TYPE_VIDEO in p.parameters.disabledTrackTypes)
        assertEquals(1, p.parameters.overrides.size)
        p.canSelectTracks = false; controls.bind(p.player)
        val before = p.parameters
        assertTrue(controls.audio.isEnabled); controls.audio.performClick(); assertSame(before, p.parameters)
    }
    @Test fun subtitlesCycleOffAndTracksWithoutASelectionDialog() = withControls { controls, p ->
        controls.show()
        val chinese = trackGroup("sub-zh", "text/vtt", "中文字幕", selected = true)
        val english = trackGroup("sub-en", "text/vtt", "英语字幕")
        val audio = trackGroup("audio", "audio/aac", "普通话", selected = true)
        p.tracks = Tracks(listOf(chinese, english, audio))
        p.parameters = p.parameters.buildUpon().setOverrideForType(TrackSelectionOverride(audio.mediaTrackGroup, 0)).build()
        controls.bind(p.player)
        assertEquals("中文字幕", controls.subtitles.text.toString())
        controls.subtitles.performClick(); assertEquals("英语字幕", controls.subtitles.text.toString())
        controls.subtitles.performClick(); assertEquals("字幕关闭", controls.subtitles.text.toString())
        assertTrue(C.TRACK_TYPE_TEXT in p.parameters.disabledTrackTypes)
        assertNotNull(p.parameters.overrides[audio.mediaTrackGroup])
        controls.subtitles.performClick(); assertEquals("中文字幕", controls.subtitles.text.toString())
        assertFalse(C.TRACK_TYPE_TEXT in p.parameters.disabledTrackTypes)
        assertEquals(listOf(0), p.parameters.overrides[chinese.mediaTrackGroup]!!.trackIndices)
    }
    @Test fun adaptiveVariantsStayOneAudioChoiceAndAbsentSubtitlesAreDisabled() = withControls { controls, p ->
        controls.show()
        val format = Format.Builder().setSampleMimeType("audio/aac").setLanguage("zh").build()
        val adaptive = Tracks.Group(TrackGroup("adaptive", format, format.buildUpon().setAverageBitrate(256000).build()), true,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, false))
        val other = trackGroup("en", "audio/aac", "英语")
        p.tracks = Tracks(listOf(adaptive, other)); controls.bind(p.player)
        assertEquals("中文", controls.audio.text.toString())
        assertEquals("无字幕", controls.subtitles.text.toString()); assertTrue(controls.subtitles.isEnabled)
        controls.audio.performClick(); assertEquals("英语", controls.audio.text.toString())
        controls.audio.performClick(); assertEquals("中文", controls.audio.text.toString())
        assertEquals(listOf(0, 1), p.parameters.overrides[adaptive.mediaTrackGroup]!!.trackIndices)
        p.tracks = Tracks(listOf(adaptive)); controls.bind(p.player)
        assertTrue(controls.audio.isEnabled); assertEquals("中文", controls.audio.text.toString())
        val before = p.parameters
        controls.audio.performClick(); assertSame(before, p.parameters)
    }
    @Test fun focusedActionsHighlightTextWithoutAddingBackgrounds() = withControls { controls, p ->
        p.tracks = Tracks(listOf(
            trackGroup("zh", "audio/aac", "普通话", selected = true),
            trackGroup("en", "audio/aac", "英语"),
            trackGroup("sub", "text/vtt", "中文字幕", selected = true)
        ))
        controls.bind(p.player); controls.show()
        for (button in listOf(controls.speed, controls.audio, controls.subtitles)) {
            val restingColor = button.currentTextColor
            assertNull(button.background)
            assertTrue(button.requestFocus())
            assertEquals(android.graphics.Color.WHITE, button.currentTextColor)
            assertNotEquals(restingColor, button.currentTextColor)
            assertNull(button.background)
            controls.heading.requestFocus()
            assertEquals(restingColor, button.currentTextColor)
            assertNull(button.background)
        }
    }
    @Test fun speedCyclesDirectlyAndRespectsPlayerCommand() = withControls { controls, p ->
        controls.show()
        for (expected in listOf(1.25f, 1.5f, 2f, .5f, .75f, 1f)) {
            controls.speed.performClick()
            assertEquals(expected, p.speed)
            assertEquals("${expected.toString().removeSuffix(".0")}×", controls.speed.text.toString())
        }
        p.canSpeed = false; controls.bind(p.player)
        assertTrue(controls.speed.isEnabled)
        controls.speed.performClick(); assertEquals(1f, p.speed)
    }
    @Test fun bufferingUsesTitleIndicatorAndClearsWhenReadyOrUnbound() = withControls { controls, p ->
        controls.show(); p.state = Player.STATE_BUFFERING; controls.bind(p.player)
        assertTrue(controls.heading.isBuffering)
        assertTrue(controls.heading.paddingRight > 0)
        p.state = Player.STATE_READY; controls.bind(p.player)
        assertFalse(controls.heading.isBuffering); assertTrue(controls.heading.paddingRight > 0)
        p.state = Player.STATE_BUFFERING; controls.bind(p.player); controls.bind(null)
        assertFalse(controls.heading.isBuffering)
    }
    @Test fun episodeScrollRetargetsFromCurrentOffsetAndOnlyConfirmationCommits() = withControls { controls, _ ->
        val picker = controls.episodePicker
        val selected = mutableListOf<Int>()
        controls.onEpisodeSelected = { selected.add(it) }
        controls.episodes((1..20).map { "第${it}集" }, 4); controls.show()
        picker.layout(0, 0, 480, 48); picker.requestFocus()
        fun animator(): android.animation.ValueAnimator? = org.robolectric.util.ReflectionHelpers.getField(picker, "scrollAnimator")
        animator()?.end()
        val initial = picker.scrollOffsetPx
        picker.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(initial, picker.scrollOffsetPx, .01f)
        val first = requireNotNull(animator()); first.currentPlayTime = 100
        val midway = picker.scrollOffsetPx
        assertTrue(midway > initial)
        picker.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(midway, picker.scrollOffsetPx, .01f)
        animator()?.end(); assertTrue(picker.scrollOffsetPx > midway)
        assertEquals(6, picker.candidateIndex); assertEquals(4, picker.selectedIndex); assertTrue(selected.isEmpty())
        picker.performClick(); assertEquals(listOf(6), selected)
        controls.heading.requestFocus(); animator()?.end(); assertEquals(initial, picker.scrollOffsetPx, .01f)
        controls.episodes(listOf("第1集", "第2集"), 0)
        assertEquals(0f, picker.scrollOffsetPx, .01f)
    }
    @Test fun episodeWheelOnlyCommitsOnConfirmAndDiscardsCandidateOnBlur() = withControls { controls, _ ->
        val selected = mutableListOf<Int>()
        controls.onEpisodeSelected = { selected.add(it) }
        controls.episodes(listOf("第01集", "第02集", "第03集"), 1)
        controls.show(); controls.episodePicker.requestFocus()
        controls.episodePicker.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(2, controls.episodePicker.candidateIndex); assertTrue(selected.isEmpty())
        controls.speed.requestFocus(); assertEquals(1, controls.episodePicker.candidateIndex)
        controls.episodePicker.requestFocus()
        controls.episodePicker.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        controls.episodePicker.performClick(); assertEquals(listOf(0), selected)
        controls.episodes(emptyList(), -1)
        assertEquals(View.GONE, controls.episodePicker.visibility); assertTrue(controls.heading.hasFocus())
    }
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun fluidTitleMovesBetweenPlaybackAndPauseAndPreviewKeepsCenterClear() = withControls { controls, p ->
        controls.title("三体·周年纪念版")
        controls.themeColor(android.graphics.Color.rgb(90, 190, 205))
        p.tracks = Tracks(listOf(trackGroup("zh", "audio/aac", "普通话", selected = true), trackGroup("en", "text/vtt", "英语字幕", selected = true)))
        controls.bind(p.player); controls.episodes(listOf("第01集", "第02集", "第03集", "第04集", "第05集"), 1); controls.show()
        fun layout() {
            controls.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            controls.layout(0, 0, 1920, 1080)
        }
        fun titleAnimation(): android.animation.ValueAnimator? =
            org.robolectric.util.ReflectionHelpers.getField(controls, "titleAnimator")
        // Native API 35's looper can jump to a future window timer during traversal.
        // Drive the real animator deterministically; auto-hide has its own clock-based test.
        fun settle() { titleAnimation()?.end(); animation(controls, "visibilityAnimator")?.end(); animation(controls, "groupAnimator")?.end(); layout() }
        layout(); assertTrue(controls.heading.requestFocusFromTouch()); settle()
        assertTrue(controls.heading.hasFocus())
        assertEquals(80f, controls.heading.translationX, 1f)
        assertTrue(controls.heading.translationY > 600)
        assertTrue(controls.heading.translationY + controls.heading.height <= controls.controlGroup.top)
        assertEquals(controls.controlGroup.left.toFloat(), controls.heading.translationX, 1f)
        assertEquals(48, controls.height - controls.controlGroup.bottom)
        assertEquals(controls.heading.translationY + controls.heading.height, controls.controlGroup.top.toFloat(), 1f)
        assertEquals(0, controls.episodePicker.left)
        assertEquals(960, controls.episodePicker.width)
        assertEquals(controls.episodePicker.bottom, controls.actionRow.top)
        assertEquals(0, controls.actionRow.left)
        assertEquals(32f, controls.speed.textSize, .1f)
        assertEquals(0, controls.speed.paddingLeft)
        assertEquals(android.view.Gravity.LEFT, controls.speed.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(View.GONE, controls.seekTime.visibility)
        assertNull(controls.background)
        val capture = System.getenv("VOD_CONTROLS_CAPTURE")
        fun snapshot(suffix: String) {
            capture?.let { path ->
                val bitmap = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.rgb(15, 28, 36)); controls.draw(canvas)
                java.io.File(path.removeSuffix(".png") + suffix + ".png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        assertEquals(25f / 90f, controls.heading.progressFraction, .001f)
        snapshot("")
        val titleWidthBeforeLoading = controls.heading.width
        val titleXBeforeLoading = controls.heading.translationX
        p.state = Player.STATE_BUFFERING; controls.bind(p.player); settle()
        assertEquals(titleWidthBeforeLoading, controls.heading.width)
        assertEquals(titleXBeforeLoading, controls.heading.translationX, .01f)
        assertTrue(controls.heading.isBuffering)
        // Delay timing is verified by FluidTitleViewTest; keep this native capture on its fixed layout.
        org.robolectric.util.ReflectionHelpers.getField<Runnable>(controls.heading, "revealLoading").run()
        val loading: android.animation.ValueAnimator = org.robolectric.util.ReflectionHelpers.getField(controls.heading, "loadingAnimator")
        assertTrue(loading.isStarted); loading.currentPlayTime = 300
        val loadingFade: android.animation.ValueAnimator = org.robolectric.util.ReflectionHelpers.getField(controls.heading, "loadingFade")
        loadingFade.end()
        snapshot("-buffering")
        p.state = Player.STATE_READY; controls.bind(p.player); settle()
        val hideLoading: android.animation.ValueAnimator = org.robolectric.util.ReflectionHelpers.getField(controls.heading, "loadingFade")
        hideLoading.end(); assertFalse(loading.isStarted)
        controls.heading.performClick(); settle()
        assertFalse(p.playing)
        assertTrue(controls.heading.scaleX > 1.5f)
        assertEquals(960f, controls.heading.translationX + controls.heading.width * controls.heading.scaleX / 2, 2f)
        assertEquals(540f, controls.heading.translationY + controls.heading.height * controls.heading.scaleY / 2, 2f)
        snapshot("-paused")
        controls.heading.performClick()
        titleAnimation()?.setCurrentFraction(.3f)
        val intermediateScale = controls.heading.scaleX
        controls.heading.performClick()
        assertEquals(intermediateScale, controls.heading.scaleX, .01f)
        settle()
        assertEquals(960f, controls.heading.translationX + controls.heading.width * controls.heading.scaleX / 2, 2f)
        controls.previewRemoteSeek(60_000); settle()
        assertEquals(1f, controls.heading.scaleX, .01f)
        assertEquals("1:00 / 1:30", controls.seekTime.text.toString())
        snapshot("-scrubbing")
        controls.endRemoteSeek(); settle()
        assertEquals(View.GONE, controls.seekTime.visibility); assertTrue(controls.heading.scaleX > 1.5f)
        controls.heading.performClick(); settle()
        controls.episodePicker.requestFocus()
        controls.episodePicker.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        settle(); snapshot("-episodes")
        assertEquals(2, controls.episodePicker.candidateIndex)
        assertEquals(1, controls.episodePicker.selectedIndex)
        controls.episodes(emptyList(), -1); settle()
        assertEquals(0, controls.speed.left)
        assertEquals(controls.heading.translationX, controls.controlGroup.left + controls.speed.left.toFloat(), 1f)
    }
    private fun animation(controls: TvPlaybackControls, name: String): android.animation.ValueAnimator? =
        org.robolectric.util.ReflectionHelpers.getField(controls, name)

    private fun layoutControls(controls: TvPlaybackControls) {
        controls.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        controls.layout(0, 0, 1920, 1080)
    }

    @Test fun pauseHidesGroupCancelsEpisodePreviewAndRestoresOnPlay() = withControls { controls, p ->
        controls.episodes(listOf("第01集", "第02集"), 0)
        controls.show(); layoutControls(controls)
        controls.episodePicker.requestFocus()
        controls.episodePicker.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, controls.episodePicker.candidateIndex)
        controls.togglePlayback()
        assertFalse(p.playing)
        assertTrue(controls.heading.hasFocus())
        assertFalse(controls.speed.requestFocus())
        assertEquals(0, controls.episodePicker.candidateIndex)
        animation(controls, "groupAnimator")?.setCurrentFraction(.4f)
        val intermediate = controls.controlGroup.alpha
        assertTrue(intermediate > 0f && intermediate < 1f)
        // Hidden group is inert even while its visual is fading.
        controls.speed.performClick(); assertEquals(1f, p.speed)
        controls.togglePlayback()
        assertEquals(intermediate, controls.controlGroup.alpha, .001f)
        animation(controls, "groupAnimator")?.end()
        assertEquals(1f, controls.controlGroup.alpha, .001f)
        assertTrue(controls.speed.requestFocus())
        controls.togglePlayback(); animation(controls, "groupAnimator")?.end()
        assertEquals(View.INVISIBLE, controls.controlGroup.visibility)
        controls.previewRemoteSeek(40_000)
        assertEquals(View.INVISIBLE, controls.controlGroup.visibility)
        assertEquals(View.VISIBLE, controls.seekTime.visibility)
    }

    @Test fun hideFadesAndShowReversesWithoutAlphaJump() = withControls { controls, _ ->
        controls.show(); layoutControls(controls)
        animation(controls, "visibilityAnimator")?.end()
        controls.hide()
        assertFalse(controls.isShowingControls)
        assertFalse(controls.heading.requestFocus())
        assertEquals(View.VISIBLE, controls.visibility)
        animation(controls, "visibilityAnimator")?.setCurrentFraction(.4f)
        val intermediate = controls.alpha
        assertTrue(intermediate > 0f && intermediate < 1f)
        controls.show()
        assertEquals(intermediate, controls.alpha, .001f)
        assertTrue(controls.isShowingControls)
        animation(controls, "visibilityAnimator")?.end()
        assertEquals(1f, controls.alpha, .001f)
        controls.hide(); animation(controls, "visibilityAnimator")?.end()
        assertEquals(View.GONE, controls.visibility)
        controls.show(); controls.bind(null)
        assertEquals(View.GONE, controls.visibility)
        assertEquals(0f, controls.alpha, .001f)
    }

    @Test fun disabledAnimationsApplyVisibilityImmediately() = withControls { controls, _ ->
        val setter = android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        val getter = android.animation.ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
        val previous = getter.invoke(null) as Float
        try {
            setter.invoke(null, 0f)
            controls.show(); layoutControls(controls)
            controls.togglePlayback()
            assertEquals(View.INVISIBLE, controls.controlGroup.visibility)
            assertEquals(0f, controls.controlGroup.alpha, .001f)
            controls.hide()
            assertEquals(View.GONE, controls.visibility)
            controls.show()
            assertEquals(View.VISIBLE, controls.visibility)
            assertEquals(1f, controls.alpha, .001f)
        } finally { setter.invoke(null, previous) }
    }

    @Test fun formatTimeSupportsLongMoviesAndUnknownPosition() {
        assertEquals("0:00", TvPlaybackControls.formatTime(C.TIME_UNSET))
        assertEquals("2:05", TvPlaybackControls.formatTime(125_000))
        assertEquals("2:00:05", TvPlaybackControls.formatTime(7_205_000))
    }
}
