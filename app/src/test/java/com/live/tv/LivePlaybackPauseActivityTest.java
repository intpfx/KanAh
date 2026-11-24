package com.live.tv;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.FrameLayout;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real native dispatch/recovery with a controllable WebView; application services stay stopped. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class LivePlaybackPauseActivityTest {
    private MainActivity activity;
    private TvUi ui;
    private TestWeb web;
    private PlaybackGate gate;
    private SurfaceTexture nativeTexture;

    private static final class Pending {
        final ValueCallback<String> callback;
        final String value;
        Pending(ValueCallback<String> callback, String value) { this.callback=callback; this.value=value; }
        void deliver() { callback.onReceiveValue(value); }
    }

    private static final class TestWeb extends WebView {
        final List<String> scripts = new ArrayList<>();
        String sample, ownSample, pauseReply="true";
        boolean deferProbe, deferOwn, disposed;
        Pending probePending, ownPending;
        TestWeb(Activity host) { super(host); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            scripts.add(script);
            if (callback == null) return;
            if (script.endsWith("probe")) {
                Pending pending=new Pending(callback, JSONObject.quote(sample));
                if (deferProbe) probePending=pending; else pending.deliver();
            } else if ("JSON.stringify(window.__live||{})".equals(script)) {
                Pending pending=new Pending(callback, JSONObject.quote(ownSample));
                if (deferOwn) ownPending=pending; else pending.deliver();
            } else if (script.contains("window.__liveSetPaused")) callback.onReceiveValue(pauseReply);
        }
        @Override public void postVisualStateCallback(long id, VisualStateCallback callback) { callback.onComplete(id); }
        @Override public void destroy() { if (!disposed) { disposed=true; super.destroy(); } }
        int probeCount() { return (int) scripts.stream().filter(value -> value.endsWith("probe")).count(); }
        int pauseCount() { return (int) scripts.stream().filter(value -> value.contains("window.__liveSetPaused")).count(); }
    }

    @Before public void setUp() {
        activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.LiveTheme);
        ui=new TvUi(activity, (TvUi.Actions) Proxy.newProxyInstance(TvUi.Actions.class.getClassLoader(),
                new Class<?>[]{TvUi.Actions.class}, (proxy, method, args) -> {
                    if ("toggleLivePlayback".equals(method.getName())) toggle();
                    return null;
                }));
        ReflectionHelpers.setField(ui, "startupRevealPending", false);
        web=new TestWeb(activity);
        FrameLayout root=new FrameLayout(activity);
        root.addView(web); root.addView(ui);
        set("ui", ui); set("web", web); set("root", root); set("script", "probe");
        set("warmLibrary", (Runnable) () -> {});
        set("active", true); set("foreground", true); set("committed", true); set("ownFallback", true);
        List<Channel> playlist=ReflectionHelpers.getField(activity, "playlist");
        playlist.add(Channel.web(0)); playlist.add(Channel.web(1));
        gate=ReflectionHelpers.getField(activity, "gate");
        gate.setActive(true); gate.begin();
        set("startedAt", SystemClock.uptimeMillis()); set("lastProgress", SystemClock.uptimeMillis());
        LivePlaybackRecovery recovery=ReflectionHelpers.getField(activity, "liveRecovery");
        recovery.beginAttempt(SystemClock.uptimeMillis());
        inspect(4, 1, 0); inspect(4, 2, 0);
        assertTrue("The fixture must reach the real first-frame reveal gate", flag("revealed"));
        assertFalse(ui.isOpen());
    }

    @After public void tearDown() {
        ((Handler) ReflectionHelpers.getField(activity, "handler")).removeCallbacksAndMessages(null);
        StreamPlayer stream=ReflectionHelpers.getField(activity, "streamPlayer");
        if (stream != null) stream.release();
        if (nativeTexture != null) nativeTexture.release();
        if (ui != null) ui.dispose();
        WebView current=ReflectionHelpers.getField(activity, "web");
        if (current != null && current != web) current.destroy();
        if (web != null) web.destroy();
        ShadowMediaPlayer.resetStaticState();
    }

    private void set(String key, Object value) { ReflectionHelpers.setField(activity, key, value); }
    private boolean flag(String key) { return ReflectionHelpers.getField(activity, key); }
    private long time(String key) { return ReflectionHelpers.getField(activity, key); }
    private void call(String method) { ReflectionHelpers.callInstanceMethod(activity, method); }
    private boolean toggle() { return ReflectionHelpers.callInstanceMethod(activity, "toggleLivePlayback"); }
    private void inspect(int ready, double position, int error) {
        web.sample="{\"token\":"+gate.token()+",\"instance\":1,\"layout\":true,\"ready\":"+ready
                +",\"paused\":false,\"error\":"+error+",\"time\":"+position+",\"frameCallback\":true,\"frames\":60}";
        call("inspect");
    }
    private void ownSample(int frames, double position, boolean error) {
        web.ownSample="{\"token\":"+gate.token()+",\"channel\":"+JSONObject.quote(Channels.playerId(0))
                +",\"layout\":true,\"ready\":4,\"paused\":false,\"error\":"+(error ? "\"network\"" : "null")
                +",\"time\":"+position+",\"frames\":"+frames+",\"audioBytes\":4096}";
    }
    private boolean key(int key, int action, int repeat) {
        long now=SystemClock.uptimeMillis();
        return activity.dispatchKeyEvent(new KeyEvent(now, now, action, key, repeat));
    }
    private void press(int key) {
        assertTrue(key(key, KeyEvent.ACTION_DOWN, 0));
        assertTrue(key(key, KeyEvent.ACTION_UP, 0));
    }
    private void assertUnchangedPlayback() {
        assertSame(web, ReflectionHelpers.getField(activity, "web"));
        assertEquals(0, (int) ReflectionHelpers.getField(activity, "loadCount"));
        assertNull(ReflectionHelpers.getField(activity, "liveRecoveryTask"));
        assertTrue(flag("revealed")); assertFalse(flag("failed"));
    }

    private void initializeParentFragmentLifecycle() {
        // MainActivity.onCreate starts app services and real playback. Initialize only its AndroidX
        // host so the real super.onPause can run against an attached, resumed FragmentManager.
        androidx.savedstate.SavedStateRegistryController savedState=
                ReflectionHelpers.getField(activity, "mSavedStateRegistryController");
        savedState.performRestore(null);
        androidx.fragment.app.FragmentController fragments=ReflectionHelpers.getField(activity, "mFragments");
        fragments.attachHost(null);
        androidx.lifecycle.LifecycleRegistry lifecycle=ReflectionHelpers.getField(activity, "mFragmentLifecycleRegistry");
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE);
        fragments.dispatchCreate(); fragments.dispatchActivityCreated();
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START);
        fragments.dispatchStart();
        lifecycle.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME);
        fragments.dispatchResume();
    }

    @Test public void confirmEnterAndMediaKeysToggleOnlyOncePerPressWithoutOpeningTheMenu() {
        assertTrue(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0));
        assertTrue(flag("livePaused"));
        assertTrue(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1));
        assertTrue(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, 0));
        assertTrue(flag("livePaused")); assertEquals(1, web.pauseCount());
        press(KeyEvent.KEYCODE_ENTER);
        assertFalse(flag("livePaused")); assertEquals(2, web.pauseCount());
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 0));
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN, 3));
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_UP, 0));
        assertTrue(flag("livePaused")); assertEquals(3, web.pauseCount());
        assertFalse(ui.isOpen()); assertUnchangedPlayback();
    }

    @Test public void unstableBackgroundAndMenuStatesRejectPauseWithoutTouchingThePlayer() {
        String[] rejected={"busy", "queued", "failed", "destroyed"};
        for (String field : rejected) {
            set(field, true); assertFalse(field, toggle()); set(field, false);
        }
        String[] required={"active", "revealed", "committed"};
        for (String field : required) {
            set(field, false); assertFalse(field, toggle()); set(field, true);
        }
        set("web", null); assertFalse(toggle()); set("web", web);
        ui.open(0, true); assertFalse("The sidebar owns confirmation while open", toggle()); ui.close();
        assertFalse(flag("livePaused")); assertEquals(0, web.pauseCount()); assertUnchangedPlayback();
    }

    @Test public void failedPlaybackKeepsConfirmAsRetryAndDoesNotRepeatOnKeyUp() {
        set("failed", true); set("revealed", false); ui.loading(0); ui.error();
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1, (int) ReflectionHelpers.getField(activity, "loadCount"));
        assertFalse(flag("livePaused")); assertFalse(ui.isOpen());
        assertEquals(0, web.pauseCount());
        assertTrue(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1));
        assertTrue(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, 0));
        assertEquals(1, (int) ReflectionHelpers.getField(activity, "loadCount"));
    }

    @Test public void aLongPauseDoesNotPollBufferOrReloadAndResumeGetsAFreshTimeoutBaseline() {
        assertTrue(toggle()); assertTrue(flag("livePaused"));
        int probes=web.probeCount();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3));
        inspect(2, 2, 4);
        ReflectionHelpers.callInstanceMethod(activity, "recoverLive",
                ReflectionHelpers.ClassParameter.from(String.class, "network event during user pause"));
        Runnable poll=ReflectionHelpers.getField(activity, "poll"); poll.run();
        assertEquals(probes, web.probeCount()); assertUnchangedPlayback();
        assertTrue(flag("livePaused"));
        long resumedAt=SystemClock.uptimeMillis();
        assertTrue(toggle()); assertFalse(flag("livePaused"));
        assertEquals(resumedAt, time("startedAt")); assertEquals(resumedAt, time("lastProgress"));
        inspect(4, 2, 0);
        assertUnchangedPlayback();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        inspect(4, 3, 0);
        assertUnchangedPlayback(); assertEquals(SystemClock.uptimeMillis(), time("lastProgress"));
    }

    @Test public void anOldOfficialProbeCannotRecoverOrCoverPlaybackAfterPauseAndResume() {
        web.deferProbe=true;
        inspect(2, 2, 4);
        Pending stale=web.probePending; assertNotNull(stale);
        assertTrue(toggle());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        assertTrue(toggle());
        stale.deliver();
        assertFalse(flag("livePaused")); assertUnchangedPlayback();
        web.deferProbe=false;
        inspect(4, 3, 0);
        assertUnchangedPlayback();
    }

    @Test public void ownWatcherIgnoresPausedTimeAndOldErrorsThenAcceptsFreshProgress() {
        set("ownMode", true);
        ownSample(61, 3, false);
        call("watchForFirstFrame");
        Runnable previous=ReflectionHelpers.getField(activity, "ownFrameCheck"); previous.run();
        ownSample(62, 4, false); previous.run();
        web.deferOwn=true; ownSample(62, 4, true); previous.run();
        Pending stale=web.ownPending; assertNotNull(stale);
        assertTrue(toggle());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        previous.run();
        assertTrue(flag("ownMode")); assertUnchangedPlayback();
        assertTrue(toggle());
        stale.deliver();
        assertTrue(flag("ownMode")); assertUnchangedPlayback();
        web.deferOwn=false; ownSample(63, 5, false);
        Runnable resumed=ReflectionHelpers.getField(activity, "ownFrameCheck"); resumed.run();
        ownSample(64, 6, false); resumed.run();
        assertTrue(flag("ownMode")); assertFalse(flag("livePaused")); assertUnchangedPlayback();
    }

    @Test public void aMissingWebVideoRollsBackPauseInsteadOfLeavingRecoveryDisabled() {
        web.pauseReply="false";
        assertTrue(toggle());
        assertFalse(flag("livePaused"));
        assertUnchangedPlayback();
        inspect(4, 3, 0);
        assertEquals(SystemClock.uptimeMillis(), time("lastProgress"));
    }

    @Test public void channelSelectionClearsPauseBeforeTheNextDocumentStarts() {
        assertTrue(toggle());
        ReflectionHelpers.callInstanceMethod(activity, "load", ReflectionHelpers.ClassParameter.from(int.class, 1));
        assertFalse(flag("livePaused"));
        assertTrue(flag("queued")); assertFalse(flag("revealed"));
        assertEquals(1, (int) ReflectionHelpers.getField(activity, "position"));
        assertEquals(0, (int) ReflectionHelpers.getField(activity, "loadCount"));
    }

    @Test public void internalBrowsingKeepsTheWebSessionAndGateWithoutStartingAnotherDocument() {
        initializeParentFragmentLifecycle();
        set("resumed", true);
        long token=gate.token();
        int scripts=web.scripts.size();

        activity.onPause();
        assertFalse(flag("foreground"));assertTrue(flag("active"));assertTrue(flag("committed"));
        assertTrue(flag("revealed"));assertFalse(web.disposed);
        assertSame(web,ReflectionHelpers.getField(activity,"web"));
        assertEquals(token,gate.token());assertTrue(gate.accepts(token));
        assertFalse("Browsing must not stop the current MSE session",web.scripts.subList(scripts,web.scripts.size())
                .stream().anyMatch(value -> value.contains("window.__liveStop")));

        activity.onResume();
        assertTrue(flag("foreground"));assertTrue(flag("active"));assertTrue(flag("committed"));
        assertEquals(token,gate.token());assertTrue(gate.accepts(token));
        assertFalse(web.disposed);assertEquals(0,web.pauseCount());assertUnchangedPlayback();
        inspect(4,3,0);
        assertUnchangedPlayback();
    }

    @Test public void internalBrowsingKeepsTheUsersWebPauseAcrossALongReturn() {
        initializeParentFragmentLifecycle();
        set("resumed", true);
        assertTrue(toggle());
        long token=gate.token();
        int pauses=web.pauseCount();

        activity.onPause();
        assertTrue(flag("livePaused"));assertTrue(flag("active"));assertTrue(flag("committed"));
        assertFalse(web.disposed);assertSame(web,ReflectionHelpers.getField(activity,"web"));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3));
        activity.onResume();
        assertTrue(flag("livePaused"));assertTrue(flag("active"));assertTrue(flag("foreground"));
        assertEquals("A lifecycle return must not issue a user resume command",pauses,web.pauseCount());
        assertEquals(token,gate.token());assertTrue(gate.accepts(token));assertUnchangedPlayback();

        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(flag("livePaused"));assertEquals(pauses+1,web.pauseCount());
        inspect(4,3,0);
        assertUnchangedPlayback();
    }

    @Test public void lifecycleStopClearsUserPauseAndDestroysTheWebDecoderWithoutStartingAnother() {
        initializeParentFragmentLifecycle();
        assertTrue(toggle());
        activity.onPause();
        call("onStop");
        assertFalse(flag("livePaused")); assertFalse(flag("active")); assertFalse(flag("foreground"));
        assertNull(ReflectionHelpers.getField(activity, "web")); assertTrue(web.disposed);
        assertEquals(0, (int) ReflectionHelpers.getField(activity, "loadCount"));
    }

    @Test public void nativePlaybackUsesTheSameToggleWithoutReplacingItsDecoder() {
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(900_000, -1));
        StreamPlayer stream=new StreamPlayer(activity, new StreamPlayer.Listener() {
            @Override public void onStreamReady() { }
            @Override public void onStreamError(String reason) { fail(reason); }
        });
        nativeTexture=new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(nativeTexture, 1920, 1080);
        stream.play(Collections.singletonList("https://fixture.example/live.m3u8"), Collections.emptyMap());
        MediaPlayer player=ReflectionHelpers.getField(stream, "player");
        Shadows.shadowOf(player).invokePreparedListener();
        Shadows.shadowOf(player).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        set("streamMode", true); set("streamPlayer", stream);
        assertTrue(toggle()); assertTrue(flag("livePaused")); assertFalse(stream.isPlaying());
        assertEquals(ShadowMediaPlayer.State.PAUSED, Shadows.shadowOf(player).getState());
        assertTrue(toggle()); assertFalse(flag("livePaused")); assertTrue(stream.isPlaying());
        assertSame(player, ReflectionHelpers.getField(stream, "player"));
        assertEquals(0, web.pauseCount()); assertEquals(0, (int) ReflectionHelpers.getField(activity, "loadCount"));
    }

    @Test public void aQueuedErrorFromPausedNativeChannelCannotCoverTheNewlySelectedChannel() {
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(900_000, -1));
        set("streamMode", true); call("ensureStreamPlayer");
        StreamPlayer stream=ReflectionHelpers.getField(activity, "streamPlayer");
        nativeTexture=new SurfaceTexture(0);
        stream.onSurfaceTextureAvailable(nativeTexture, 1920, 1080);
        stream.play(Collections.singletonList("https://first.example/live.m3u8"), Collections.emptyMap());
        MediaPlayer first=ReflectionHelpers.getField(stream, "player");
        Shadows.shadowOf(first).invokePreparedListener();
        Shadows.shadowOf(first).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(toggle()); assertTrue(flag("livePaused"));
        Shadows.shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004);
        assertFalse("The error UI is still queued on the main handler", flag("failed"));

        Channel replacement=Channel.stream("新频道", "测试", null, "replacement",
                Collections.singletonList("https://second.example/live.m3u8"), Collections.emptyMap());
        ReflectionHelpers.callInstanceMethod(activity, "startStream",
                ReflectionHelpers.ClassParameter.from(Channel.class, replacement));
        MediaPlayer second=ReflectionHelpers.getField(stream, "player");
        assertNotSame(first, second);
        Shadows.shadowOf(second).invokePreparedListener();
        Shadows.shadowOf(second).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        assertTrue(flag("revealed")); assertFalse(flag("livePaused"));
        long curtainGeneration=ReflectionHelpers.getField(ui, "curtainGeneration");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(flag("failed")); assertTrue(flag("revealed"));
        assertEquals(curtainGeneration, (long) ReflectionHelpers.getField(ui, "curtainGeneration"));
        assertSame(second, ReflectionHelpers.getField(stream, "player"));
        assertTrue(stream.isReady()); assertTrue(stream.isPlaying());
    }

    @Test public void aDelayedNativeRevealCannotMarkANewerChannelAsRevealed() {
        set("streamMode", true); set("revealed", false); call("ensureStreamPlayer");
        StreamPlayer stream=ReflectionHelpers.getField(activity, "streamPlayer");
        StreamPlayer.Listener listener=ReflectionHelpers.getField(stream, "listener");
        ReflectionHelpers.setField(ui, "startupRevealPending", true);
        ui.loading(0);
        listener.onStreamReady();
        android.animation.ValueAnimator reveal=ReflectionHelpers.getField(ui, "curtainAnimator");
        assertNotNull("The native reveal callback must remain pending during the startup animation", reveal);
        assertFalse(flag("revealed"));
        gate.begin(); // A subsequent channel selection invalidates the older decoder's visual callback.
        reveal.end();
        assertFalse(flag("revealed")); assertFalse(flag("failed"));
    }
}
