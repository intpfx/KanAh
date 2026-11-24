package com.live.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * IPTV playback backend built on the platform {@link MediaPlayer}.
 *
 * <p>Kept deliberately dependency-free: the framework already decodes HLS ({@code .m3u8}),
 * progressive mp4 and rtsp, which is what the sources this app targets actually use. Adding
 * ExoPlayer would cost ~0.9 MB in a minified release and ~4.7 MB in a debug build, so the player
 * is isolated here: swapping it later touches this file only.
 *
 * <p>Rendering uses a {@link TextureView} rather than a SurfaceView on purpose: a SurfaceView is a
 * separate SurfaceFlinger layer, so its content never appears in {@code screencap} and the whole
 * screenshot-based verification workflow would go blind on IPTV playback. TextureView costs one
 * extra GPU composite and keeps the picture capturable, which matches how this app is tested.
 *
 * <p>The contract intentionally mirrors what {@link MainActivity} already expects from the WebView
 * pipeline, so both backends drive the same cover/reveal UI:
 * <ul>
 *   <li>{@link Listener#onStreamReady()} means decoded frames are on screen - only then may the
 *       native cover be removed;
 *   <li>preparation and first-frame timeouts surface as {@link Listener#onStreamError};
 *   <li>mirrors are tried in order, then one backed-off retry round, then the error is reported.
 * </ul>
 */
public final class StreamPlayer implements TextureView.SurfaceTextureListener,
        MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, MediaPlayer.OnInfoListener {
    public interface Listener {
        void onStreamReady();

        void onStreamError(String reason);
    }

    private static final String TAG = "Live/stream";
    /** A stream that has not even prepared by then is considered dead. */
    private static final int PREPARE_TIMEOUT_MS = 15000;
    /** Prepared but never painted: some firmwares never send RENDERING_START, so we also accept a
     *  clock that advances while playing. */
    private static final int RENDER_TIMEOUT_MS = 12000;
    private static final long[] RETRY_BACKOFF_MS = {1000L, 3000L};

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextureView surface;

    private Surface videoSurface;
    private MediaPlayer player;
    private List<String> urls = Collections.emptyList();
    private Map<String, String> headers = Collections.emptyMap();
    private int index;
    private int round;
    private boolean surfaceReady;
    private boolean activeSession;
    private boolean pendingPlay;
    private boolean prepared;
    private boolean ready;
    private boolean userPaused;
    private boolean muted;
    private boolean released;

    private final Runnable prepareTimeout = () -> fail("prepare timeout");
    private final Runnable retryOpen = this::openIfActive;
    private final Runnable renderTimeout = () -> {
        if (player == null) return;
        if (player.isPlaying() && player.getCurrentPosition() > 0) {
            reportReady();
        } else {
            fail("no video after prepare");
        }
    };

    public StreamPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.surface = new TextureView(context);
        // TextureView rejects background drawables; the root FrameLayout behind it is black already.
        this.surface.setSurfaceTextureListener(this);
    }

    /** The view to place underneath the native cover UI. */
    public View view() {
        return surface;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isPlaying() {
        return player != null && prepared && safeIsPlaying();
    }

    /** Silence live audio without changing the connection, decoder or the user's pause intent. */
    public void setMuted(boolean muted) {
        this.muted = muted;
        MediaPlayer mp = player;
        if (mp != null) applyVolume(mp);
    }

    /** Retain the decoder, surface and last frame when the user pauses a live channel. */
    public boolean setPaused(boolean paused) {
        if(released || player==null || !prepared || (!ready && !userPaused))return false;
        try {
            if(paused)player.pause(); else player.start();
            userPaused=paused;
            handler.removeCallbacks(renderTimeout);
            if(!paused && !ready)handler.postDelayed(renderTimeout, RENDER_TIMEOUT_MS);
            return true;
        } catch(IllegalStateException error) {
            return false;
        }
    }

    public long positionMs() {
        if (player == null || !prepared) return -1;
        try {
            return player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    public void play(List<String> urls, Map<String, String> headers) {
        if (released || urls == null || urls.isEmpty()) {
            fail("no playable url");
            return;
        }
        this.urls = urls;
        this.headers = headers == null ? Collections.<String, String>emptyMap() : headers;
        this.index = 0;
        this.round = 0;
        this.ready = false;
        this.userPaused = false;
        this.activeSession = true;
        handler.removeCallbacks(retryOpen);
        if (surfaceReady) {
            open();
        } else {
            pendingPlay = true;
            handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        }
    }

    /** Drop the decoder while retaining the texture and mute preference for later playback. */
    public void stop() {
        handler.removeCallbacks(prepareTimeout);
        handler.removeCallbacks(renderTimeout);
        handler.removeCallbacks(retryOpen);
        activeSession = false;
        pendingPlay = false;
        ready = false;
        userPaused = false;
        releasePlayer();
    }

    public void release() {
        released = true;
        stop();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        surface.setSurfaceTextureListener(null);
    }

    private void open() {
        if (released || !activeSession) return;
        releasePlayer();
        pendingPlay = false;
        final String url = urls.get(index);
        Log.d(TAG, "open index=" + index + " round=" + round + " url=" + url);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
            applyVolume(mp);
            if (videoSurface != null) {
                mp.setSurface(videoSurface);
            }
            mp.setOnPreparedListener(this);
            mp.setOnErrorListener(this);
            mp.setOnInfoListener(this);
            mp.setDataSource(context, Uri.parse(url), headers.isEmpty() ? null : headers);
            mp.prepareAsync();
            handler.removeCallbacks(prepareTimeout);
            handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "prepare failed for " + url, e);
            fail("prepare failed: " + e.getClass().getSimpleName());
        }
    }

    @Override public void onPrepared(MediaPlayer mp) {
        if (mp != player) return;
        handler.removeCallbacks(prepareTimeout);
        prepared = true;
        applyVolume(mp);
        if (!userPaused) mp.start();
        handler.removeCallbacks(renderTimeout);
        if (!userPaused) handler.postDelayed(renderTimeout, RENDER_TIMEOUT_MS);
    }

    @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
        if (mp != player) return false;
        // Some firmwares never send RENDERING_START for HLS; a started, advancing clock also proves
        // that frames reached the screen, which is all the reveal gate needs.
        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                || what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
            reportReady();
        }
        return false;
    }

    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.w(TAG, "player error what=" + what + " extra=" + extra + " url=" + safeUrl());
        if (mp == player) {
            if (userPaused) {
                // An expired live connection needs an explicit retry, never an unsolicited resume.
                fail("paused player error " + what + "/" + extra);
                return true;
            }
            releasePlayer();
            advance("player error " + what + "/" + extra);
        }
        return true;
    }

    /** Mirror failover, then a bounded backoff retry round, then surface the failure. */
    private void advance(String reason) {
        ready = false;
        prepared = false;
        if (index + 1 < urls.size()) {
            index++;
            handler.postDelayed(retryOpen, 300);
            return;
        }
        index = 0;
        if (round < RETRY_BACKOFF_MS.length) {
            long delay = RETRY_BACKOFF_MS[round];
            round++;
            Log.d(TAG, "retry round " + round + " in " + delay + "ms (" + reason + ")");
            handler.postDelayed(retryOpen, delay);
            return;
        }
        fail(reason);
    }

    private void openIfActive() {
        if (!released && activeSession && !userPaused && !pendingPlay && !urls.isEmpty() && player == null) open();
    }

    private void reportReady() {
        if (ready || player == null) return;
        ready = true;
        handler.removeCallbacks(renderTimeout);
        Log.d(TAG, "ready url=" + safeUrl() + " pos=" + positionMs());
        listener.onStreamReady();
    }

    private void fail(String reason) {
        if (released) return;
        handler.removeCallbacks(prepareTimeout);
        handler.removeCallbacks(renderTimeout);
        handler.removeCallbacks(retryOpen);
        activeSession = false;
        pendingPlay = false;
        releasePlayer();
        Log.w(TAG, "failed: " + reason + " url=" + safeUrl());
        listener.onStreamError(reason);
    }

    private void releasePlayer() {
        prepared = false;
        ready = false;
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            mp.setSurface(null);
            mp.reset();
        } catch (IllegalStateException ignored) {
            // Already in an error state; nothing to reset.
        }
        mp.release();
    }

    private boolean safeIsPlaying() {
        try {
            return player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void applyVolume(MediaPlayer mp) {
        float volume = muted ? 0f : 1f;
        try {
            mp.setVolume(volume, volume);
        } catch (IllegalStateException ignored) {
            // A retiring decoder may reject updates; its replacement inherits the retained state.
        }
    }

    private String safeUrl() {
        return urls.isEmpty() ? "-" : urls.get(Math.min(index, urls.size() - 1));
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        videoSurface = new Surface(texture);
        surfaceReady = true;
        if (pendingPlay) open();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        // The player scales to the surface it was given; nothing to do.
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        surfaceReady = false;
        boolean wasActive = activeSession;
        boolean wasPaused = userPaused;
        stop();
        // A surface can be replaced without an Activity pause; rebuilding it must keep user intent.
        userPaused = wasPaused;
        activeSession = wasActive && !released;
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        pendingPlay = activeSession && !urls.isEmpty();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        // Frame callbacks are not needed: readiness is decided by MEDIA_INFO/the progressing clock.
    }
}
