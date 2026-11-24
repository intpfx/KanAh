package com.live.tv;

import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import java.lang.reflect.Proxy;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises the real cover -> inspect -> reveal chain without starting app services. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class LivePlaybackRecoveryActivityTest {
    private MainActivity activity;
    private TvUi ui;
    private TestWeb web;
    private PlaybackGate gate;

    private static class TestWeb extends WebView {
        String sample;
        boolean defer;
        ValueCallback<String> pending;
        String pendingValue;
        TestWeb(Activity host) { super(host); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (script.endsWith("probe") && callback != null) {
                if (defer) { pending=callback;pendingValue=JSONObject.quote(sample); }
                else callback.onReceiveValue(JSONObject.quote(sample));
            }
        }
        @Override public void postVisualStateCallback(long id, VisualStateCallback callback) { callback.onComplete(id); }
    }
    @Before public void setUp() {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.LiveTheme);
        ui = new TvUi(activity, (TvUi.Actions) Proxy.newProxyInstance(TvUi.Actions.class.getClassLoader(),
                new Class<?>[]{TvUi.Actions.class}, (proxy, method, args) -> null));
        ReflectionHelpers.setField(ui, "startupRevealPending", false);
        web = new TestWeb(activity);
        set("ui", ui); set("web", web); set("script", "probe");
        set("root", new android.widget.FrameLayout(activity));
        set("active", true); set("committed", true); set("ownFallback", true);
        gate = ReflectionHelpers.getField(activity, "gate");
        gate.setActive(true); gate.begin();
        set("startedAt", SystemClock.uptimeMillis());
        set("lastProgress", SystemClock.uptimeMillis());
        ((LivePlaybackRecovery) ReflectionHelpers.getField(activity, "liveRecovery")).beginAttempt(SystemClock.uptimeMillis());
    }
    @After public void tearDown() {
        ((Handler) ReflectionHelpers.getField(activity, "handler")).removeCallbacksAndMessages(null);
        if (ui != null) ui.dispose(); if (web != null) web.destroy();
        WebView current=ReflectionHelpers.getField(activity, "web");
        if(current!=null && current!=web)current.destroy();
    }
    private void set(String key, Object value) { ReflectionHelpers.setField(activity, key, value); }
    private void inspect(int ready, double time) {
        web.sample = "{\"token\":" + gate.token() + ",\"instance\":1,\"layout\":true,\"ready\":" + ready
                + ",\"paused\":false,\"error\":0,\"time\":" + time + ",\"frameCallback\":true,\"frames\":60}";
        ReflectionHelpers.callInstanceMethod(activity, "inspect");
    }
    @Test public void stalledPlaybackAfterStartupDeadlineCanRevealAgain() {
        inspect(4, 1); inspect(4, 2); // Actual first-frame gate and reveal, not a fabricated started flag.
        assertTrue((boolean) ReflectionHelpers.getField(activity, "revealed"));
        for (int i=0; i<60; i++) {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1));
            inspect(4, 3+i);
        } // Continuous playback now exceeds the original startup deadline.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(10));
        inspect(2, 62); // Buffering covers the video.
        inspect(4, 63); // Network recovers before the independent stall deadline.
        assertFalse("Buffering must not become an expired startup", (boolean) ReflectionHelpers.getField(activity, "failed"));
        assertTrue("Recovered video must be revealed automatically", (boolean) ReflectionHelpers.getField(activity, "revealed"));
    }

    private Runnable recover() {
        ReflectionHelpers.callInstanceMethod(activity, "recoverLive",
                ReflectionHelpers.ClassParameter.from(String.class, "test network failure"));
        return ReflectionHelpers.getField(activity, "liveRecoveryTask");
    }

    @Test public void recoveryRebuildsDocumentOnlyTwiceThenOffersManualRetry() {
        Runnable first=recover(); assertNotNull(first); first.run();
        assertEquals(1, (int) ReflectionHelpers.getField(activity, "loadCount"));
        assertNotSame(web, ReflectionHelpers.getField(activity, "web"));
        Runnable second=recover(); assertNotNull(second); second.run();
        assertEquals(2, (int) ReflectionHelpers.getField(activity, "loadCount"));
        set("samePage", true); // Exhaustion must not sneak through fail()'s navigation fallback.
        assertNull(recover());
        assertEquals(2, (int) ReflectionHelpers.getField(activity, "loadCount"));
        assertTrue((boolean) ReflectionHelpers.getField(activity, "failed"));
    }

    @Test public void retryCannotReviveBackgroundPlayback() {
        Runnable retry=recover(); assertNotNull(retry);
        set("active", false); gate.setActive(false); gate.begin();
        retry.run();
        assertEquals(0, (int) ReflectionHelpers.getField(activity, "loadCount"));
    }

    @Test public void retryCannotOverrideAChannelSelection() {
        Runnable retry=recover(); assertNotNull(retry);
        set("channel", 1); set("queued", true); gate.begin();
        retry.run();
        assertEquals(0, (int) ReflectionHelpers.getField(activity, "loadCount"));
    }

    @Test public void lateSuccessfulProbeCannotRevealTheFailedDocument() {
        inspect(4, 1); inspect(4, 2);
        web.defer=true; inspect(4, 3);
        assertNotNull(web.pending);
        assertNotNull(recover());
        web.pending.onReceiveValue(web.pendingValue);
        assertFalse((boolean) ReflectionHelpers.getField(activity, "revealed"));
        assertTrue((boolean) ReflectionHelpers.getField(activity, "failed"));
    }
}
