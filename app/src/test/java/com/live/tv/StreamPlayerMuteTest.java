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

/** Synthetic platform media verifies audio control without making network requests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class StreamPlayerMuteTest {
    private StreamPlayer stream;
    private SurfaceTexture texture;
    private final List<MediaPlayer> decoders = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int readyCount;

    @Before public void setUp() {
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

    private MediaPlayer prepareChannel() {
        stream.play(Arrays.asList("https://first.example/live.m3u8", "https://mirror.example/live.m3u8"),
                Collections.emptyMap());
        MediaPlayer player = current();
        assertNotNull(player);
        assertEquals(ShadowMediaPlayer.State.PREPARING, Shadows.shadowOf(player).getState());
        return player;
    }

    private void ready(MediaPlayer player) {
        Shadows.shadowOf(player).invokePreparedListener();
        Shadows.shadowOf(player).setCurrentPosition(4_000);
        Shadows.shadowOf(player).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(stream.isReady()); assertTrue(stream.isPlaying());
    }

    private static void assertVolume(MediaPlayer player, float expected) {
        assertEquals(expected, Shadows.shadowOf(player).getLeftVolume(), 0f);
        assertEquals(expected, Shadows.shadowOf(player).getRightVolume(), 0f);
    }

    @Test public void mutingAndRestoringPlayingAudioKeepsTheDecoderSurfaceAndClockRunning() {
        MediaPlayer player = prepareChannel(); ready(player);
        View view = stream.view();
        Surface surface = ReflectionHelpers.getField(stream, "videoSurface");
        long position = stream.positionMs();
        assertVolume(player, 1f);

        stream.setMuted(true); stream.setMuted(true);
        assertVolume(player, 0f);
        assertEquals(ShadowMediaPlayer.State.STARTED, Shadows.shadowOf(player).getState());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertTrue("muting must not pause the live connection", stream.positionMs() > position);
        assertTrue(stream.isPlaying()); assertTrue(stream.isReady());
        assertSame(player, current()); assertSame(view, stream.view());
        assertSame(surface, ReflectionHelpers.getField(stream, "videoSurface"));
        assertTrue(surface.isValid());

        stream.setMuted(false); stream.setMuted(false);
        assertVolume(player, 1f);
        assertTrue(stream.isPlaying()); assertSame(player, current());
        assertEquals(1, decoders.size()); assertEquals(1, readyCount); assertTrue(errors.isEmpty());
    }

    @Test public void mutingAndRestoringAudioNeverResumesAUserPausedChannel() {
        MediaPlayer player = prepareChannel(); ready(player);
        assertTrue(stream.setPaused(true));
        long position = stream.positionMs();

        stream.setMuted(true); assertVolume(player, 0f);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        stream.setMuted(false); assertVolume(player, 1f);
        assertEquals(ShadowMediaPlayer.State.PAUSED, Shadows.shadowOf(player).getState());
        assertFalse(stream.isPlaying()); assertTrue(stream.isReady());
        assertEquals(position, stream.positionMs());
        assertSame(player, current()); assertEquals(1, decoders.size());
        assertTrue(stream.setPaused(false)); assertTrue(stream.isPlaying());
        assertVolume(player, 1f); assertTrue(errors.isEmpty());
    }

    @Test public void muteBeforePlaybackAndChangesDuringPreparationApplyBeforeTheFirstStart() {
        stream.setMuted(true);
        MediaPlayer player = prepareChannel();
        assertVolume(player, 0f);
        stream.setMuted(false); assertVolume(player, 1f);
        stream.setMuted(true); assertVolume(player, 0f);
        assertEquals(ShadowMediaPlayer.State.PREPARING, Shadows.shadowOf(player).getState());
        ready(player);
        assertVolume(player, 0f);
        assertEquals(1, decoders.size()); assertTrue(stream.isPlaying()); assertTrue(errors.isEmpty());
    }

    @Test public void stopAndChannelChangesRetainMuteUntilItIsExplicitlyRestored() {
        stream.setMuted(true);
        MediaPlayer original = prepareChannel(); ready(original);
        stream.stop();
        assertNull(current()); assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(original).getState());
        MediaPlayer second = prepareChannel(); assertVolume(second, 0f); ready(second); assertVolume(second, 0f);
        MediaPlayer third = prepareChannel(); assertVolume(third, 0f); ready(third); assertVolume(third, 0f);
        assertEquals(ShadowMediaPlayer.State.END, Shadows.shadowOf(second).getState());

        stream.setMuted(false);
        MediaPlayer fourth = prepareChannel(); assertVolume(fourth, 1f); ready(fourth); assertVolume(fourth, 1f);
        assertEquals(4, decoders.size()); assertTrue(stream.isPlaying()); assertTrue(errors.isEmpty());
    }

    @Test public void mirrorFailoverAndBackoffRetriesInheritMuteWithoutStoppingPlayback() {
        stream.setMuted(true);
        MediaPlayer first = prepareChannel(); ready(first);
        Shadows.shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        MediaPlayer mirror = current();
        assertNotNull(mirror); assertNotSame(first, mirror); assertVolume(mirror, 0f);
        ready(mirror); assertVolume(mirror, 0f);

        Shadows.shadowOf(mirror).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_050));
        MediaPlayer retry = current();
        assertNotNull(retry); assertNotSame(mirror, retry); assertVolume(retry, 0f);
        ready(retry); assertVolume(retry, 0f);
        stream.setMuted(false); assertVolume(retry, 1f); assertTrue(stream.isPlaying());
        assertEquals(3, decoders.size()); assertTrue(errors.isEmpty());
    }

    @Test public void restoringAudioBetweenAnErrorAndItsRetryAppliesToTheReplacement() {
        stream.setMuted(true);
        MediaPlayer first = prepareChannel(); ready(first);
        Shadows.shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        assertNull(current());
        stream.setMuted(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        MediaPlayer replacement = current();
        assertVolume(replacement, 1f); ready(replacement); assertVolume(replacement, 1f);
        assertEquals(2, decoders.size()); assertTrue(errors.isEmpty());
    }

    @Test public void replacingTheSurfaceKeepsBothMuteAndTheUsersPauseIntent() {
        MediaPlayer original = prepareChannel(); ready(original);
        assertTrue(stream.setPaused(true)); stream.setMuted(true);
        assertTrue(stream.onSurfaceTextureDestroyed(texture)); texture.release();
        texture = new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(texture, 1920, 1080);
        MediaPlayer replacement = current();
        assertNotNull(replacement); assertNotSame(original, replacement); assertVolume(replacement, 0f);
        Shadows.shadowOf(replacement).invokePreparedListener();
        assertEquals(ShadowMediaPlayer.State.PREPARED, Shadows.shadowOf(replacement).getState());
        assertVolume(replacement, 0f); assertFalse(stream.isPlaying());

        stream.setMuted(false); assertVolume(replacement, 1f);
        assertFalse(stream.isPlaying());
        assertTrue(stream.setPaused(false)); assertTrue(stream.isPlaying());
        assertEquals(2, decoders.size()); assertTrue(errors.isEmpty());
    }

    @Test public void stopCancelsAnAlreadyScheduledMirrorRetry() {
        MediaPlayer first = prepareChannel(); ready(first);
        Shadows.shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        assertNull(current());
        stream.stop();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));
        assertNull(current()); assertFalse(stream.isPlaying());
        assertEquals("a stopped session must not reopen its pending mirror", 1, decoders.size());
        assertTrue(errors.isEmpty());
    }

    @Test public void stopBeforeSurfaceReplacementCannotReviveThePreviousSession() {
        stream.setMuted(true);
        MediaPlayer first = prepareChannel(); ready(first);
        stream.stop();
        assertTrue(stream.onSurfaceTextureDestroyed(texture)); texture.release();
        texture = new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(texture, 1920, 1080);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertNull(current()); assertFalse(stream.isPlaying());
        assertEquals(1, decoders.size()); assertTrue(errors.isEmpty());

        MediaPlayer explicitlyStarted = prepareChannel();
        assertVolume(explicitlyStarted, 0f); ready(explicitlyStarted);
        assertEquals(2, decoders.size()); assertTrue(stream.isPlaying());
    }

    @Test public void stopWhileWaitingForAReplacementSurfaceCancelsThePendingSession() {
        MediaPlayer first = prepareChannel(); ready(first);
        assertTrue(stream.onSurfaceTextureDestroyed(texture)); texture.release();
        stream.stop();
        texture = new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(texture, 1920, 1080);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        assertNull(current()); assertFalse(stream.isPlaying());
        assertEquals(1, decoders.size()); assertTrue(errors.isEmpty());
    }
}
