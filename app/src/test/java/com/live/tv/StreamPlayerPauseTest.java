package com.live.tv;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Platform-player behavior with synthetic media; no data source opens a network connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class StreamPlayerPauseTest {
    private StreamPlayer stream;
    private SurfaceTexture texture;
    private final List<MediaPlayer> decoders = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int readyCount;

    @Before public void setUp() {
        // Preparation is completed explicitly, so the tests can exercise the pre-frame boundary.
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(900_000, -1));
        ShadowMediaPlayer.setCreateListener((player, shadow) -> decoders.add(player));
        stream = new StreamPlayer(RuntimeEnvironment.getApplication(), new StreamPlayer.Listener() {
            @Override public void onStreamReady() { readyCount++; }
            @Override public void onStreamError(String reason) { errors.add(reason); }
        });
        texture = new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(texture, 1920, 1080);
    }

    @After public void tearDown() {
        if (stream != null) stream.release();
        if (texture != null) texture.release();
        ShadowMediaPlayer.resetStaticState();
    }

    private MediaPlayer current() { return ReflectionHelpers.getField(stream, "player"); }
    private MediaPlayer startReady() {
        stream.play(Arrays.asList("https://first.example/live.m3u8", "https://mirror.example/live.m3u8"), Collections.emptyMap());
        MediaPlayer player = current();
        assertNotNull(player);
        Shadows.shadowOf(player).invokePreparedListener();
        Shadows.shadowOf(player).setCurrentPosition(4_000);
        Shadows.shadowOf(player).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(stream.isReady()); assertTrue(stream.isPlaying());
        return player;
    }

    @Test public void pauseAndResumeKeepTheDecoderSurfaceAndLastFrameForMinutes() {
        MediaPlayer player = startReady();
        View view = stream.view();
        Surface surface = ReflectionHelpers.getField(stream, "videoSurface");
        int visibility = view.getVisibility();
        long position = stream.positionMs();

        assertTrue(stream.setPaused(true));
        assertTrue(stream.isReady()); assertFalse(stream.isPlaying());
        assertEquals(ShadowMediaPlayer.State.PAUSED, Shadows.shadowOf(player).getState());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        assertSame(player, current()); assertSame(view, stream.view());
        assertSame(surface, ReflectionHelpers.getField(stream, "videoSurface"));
        assertEquals(visibility, view.getVisibility()); assertTrue(surface.isValid());
        assertEquals(position, stream.positionMs());
        assertEquals(1, decoders.size()); assertEquals(1, readyCount); assertTrue(errors.isEmpty());

        assertTrue(stream.setPaused(false));
        assertSame(player, current()); assertTrue(stream.isPlaying()); assertTrue(stream.isReady());
        assertEquals(ShadowMediaPlayer.State.STARTED, Shadows.shadowOf(player).getState());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(stream.positionMs() > position);
        assertEquals(1, decoders.size()); assertEquals(1, readyCount); assertTrue(errors.isEmpty());
    }

    @Test public void pauseRequiresPreparationAndAVisibleFrameAndRepeatedRequestsAreIdempotent() {
        assertFalse(stream.setPaused(true));
        stream.play(Collections.singletonList("https://first.example/live.m3u8"), Collections.emptyMap());
        MediaPlayer player = current();
        assertFalse(stream.setPaused(true));
        assertEquals(ShadowMediaPlayer.State.PREPARING, Shadows.shadowOf(player).getState());
        Shadows.shadowOf(player).invokePreparedListener();
        assertFalse("A prepared decoder has not necessarily rendered", stream.setPaused(true));
        assertTrue(stream.isPlaying());
        Shadows.shadowOf(player).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(stream.setPaused(true)); assertTrue(stream.setPaused(true));
        assertEquals(ShadowMediaPlayer.State.PAUSED, Shadows.shadowOf(player).getState());
        assertTrue(stream.setPaused(false)); assertTrue(stream.setPaused(false));
        assertEquals(ShadowMediaPlayer.State.STARTED, Shadows.shadowOf(player).getState());
        assertSame(player, current()); assertEquals(1, decoders.size()); assertEquals(1, readyCount);
    }

    @Test public void anErrorWhilePausedCannotSilentlyStartAnotherMirror() {
        MediaPlayer player = startReady();
        assertTrue(stream.setPaused(true));
        Shadows.shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45));
        assertEquals("A user pause must survive stream errors without automatic decoding", 1, decoders.size());
        assertFalse(stream.isPlaying());
    }

    @Test public void anErrorDuringOrdinaryPlaybackStillAdvancesToTheNextMirror() {
        MediaPlayer first = startReady();
        Shadows.shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(2, decoders.size());
        MediaPlayer second = current();
        assertNotSame(first, second);
        assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(first).getState());
        Shadows.shadowOf(second).invokePreparedListener();
        Shadows.shadowOf(second).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(stream.isReady()); assertTrue(stream.isPlaying()); assertEquals(2, readyCount);
        assertTrue(errors.isEmpty());
    }

    @Test public void stopAndNewPlaybackClearThePauseStateWithoutRevivingTheOldDecoder() {
        MediaPlayer original = startReady();
        assertTrue(stream.setPaused(true));
        stream.stop();
        assertNull(current()); assertFalse(stream.isReady()); assertFalse(stream.isPlaying());
        assertFalse(stream.setPaused(false));
        assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(original).getState());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(1, decoders.size()); assertTrue(errors.isEmpty());

        MediaPlayer replacement = startReady();
        assertNotSame(original, replacement); assertTrue(stream.isPlaying());
        assertTrue(stream.setPaused(true));
        stream.play(Collections.singletonList("https://third.example/live.m3u8"), Collections.emptyMap());
        MediaPlayer third = current();
        assertNotSame(replacement, third);
        Shadows.shadowOf(third).invokePreparedListener();
        Shadows.shadowOf(third).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(stream.isPlaying()); assertTrue(stream.isReady());
        assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(replacement).getState());
    }

    @Test public void replacingTheSurfaceWhilePausedKeepsTheNewDecoderIdleUntilExplicitResume() {
        MediaPlayer original = startReady();
        assertTrue(stream.setPaused(true));
        assertTrue(stream.onSurfaceTextureDestroyed(texture));
        texture.release();
        assertNull(current());
        assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(original).getState());

        texture = new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(texture, 1920, 1080);
        MediaPlayer replacement = current();
        assertNotNull(replacement); assertNotSame(original, replacement);
        Shadows.shadowOf(replacement).invokePreparedListener();
        assertEquals(ShadowMediaPlayer.State.PREPARED, Shadows.shadowOf(replacement).getState());
        assertFalse(stream.isPlaying()); assertFalse(stream.isReady());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45));
        assertSame(replacement, current()); assertFalse(stream.isPlaying()); assertFalse(stream.isReady());
        assertEquals(2, decoders.size()); assertEquals(1, readyCount); assertTrue(errors.isEmpty());

        assertTrue("The replacement has prepared but cannot render until the user resumes", stream.setPaused(false));
        assertTrue(stream.isPlaying()); assertFalse(stream.isReady());
        // No rendering-start event: the rearmed first-frame watchdog must accept clock progress.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13));
        assertTrue(stream.isReady()); assertTrue(stream.isPlaying());
        assertSame(replacement, current()); assertEquals(2, decoders.size()); assertEquals(2, readyCount);
        assertTrue(errors.isEmpty());
    }
}
