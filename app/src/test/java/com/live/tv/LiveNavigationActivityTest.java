package com.live.tv;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.FrameLayout;
import com.live.tv.media.LivePlaybackControls;
import com.live.tv.media.VodLibraryActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Real MainActivity key routing, with an attached native UI and no application services/network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w1920dp-h1080dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LiveNavigationActivityTest {
    private MainActivity activity;
    private ActivityController<Activity> window;
    private TvUi ui;
    private LivePlaybackControls controls;
    private TestWeb web;
    private static final class TestWeb extends WebView {
        int pauses;
        TestWeb(Activity context) { super(context); }
        @Override public void evaluateJavascript(String script, ValueCallback<String> callback) {
            if (script.contains("window.__liveSetPaused")) { pauses++; if (callback!=null)callback.onReceiveValue("true"); }
            else if (callback!=null) callback.onReceiveValue("null");
        }
    }
    @Before public void createAttachedLiveUi() {
        activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.LiveTheme); activity.setIntent(new Intent(Intent.ACTION_MAIN));
        ui=new TvUi(activity,(TvUi.Actions)Proxy.newProxyInstance(TvUi.Actions.class.getClassLoader(),
                new Class<?>[]{TvUi.Actions.class},(proxy,method,args)->{
                    switch(method.getName()) {
                        case "toggleLivePlayback": call("toggleLivePlayback");break;
                        case "live": call("openLiveChannels");break;
                        case "library": call("openMediaLibrary");break;
                        case "desktopMode": call("toggleDesktopModeFromSidebar");break;
                        case "settings": call("openSystemSettingsPage");break;
                        default:break;
                    }
                    return null;
                }));
        controls=new LivePlaybackControls(activity,new LivePlaybackControls.Actions() {
            public void select(int index) { ReflectionHelpers.callInstanceMethod(activity,"load",ReflectionHelpers.ClassParameter.from(int.class,index)); }
            public void toggle() { call("toggleLivePlayback"); }
            public void settings() {}
            public void desktopMode() {}
        });
        FrameLayout root=new FrameLayout(activity);
        root.setBackgroundColor(Color.rgb(75,93,111));
        web=new TestWeb(activity);web.setBackgroundColor(Color.TRANSPARENT);root.addView(web);root.addView(ui);
        set("ui",ui);set("root",root);set("web",web);set("liveControls",controls);
        set("active",true);set("foreground",true);set("revealed",true);set("committed",true);
        set("script","probe");set("ownFallback",true);
        List<Channel> playlist=ReflectionHelpers.getField(activity,"playlist");
        playlist.add(Channel.web(0));playlist.add(Channel.web(1));playlist.add(Channel.web(2));
        PlaybackGate gate=ReflectionHelpers.getField(activity,"gate");gate.setActive(true);gate.begin();
        window=Robolectric.buildActivity(Activity.class).setup().visible();
        window.windowFocusChanged(true);window.get().setContentView(root);
        ui.attachLivePlaybackControls(controls);ui.setPlaylist(playlist,false);ui.revealPlain(()->{});
        controls.setChannels(playlist,0);controls.playback(true,false);
        idle(64);
        assertEquals(View.VISIBLE,ui.getWindowVisibility());
        assertFalse(ui.isOpen());
    }
    @After public void release() {
        ((Handler)ReflectionHelpers.getField(activity,"handler")).removeCallbacksAndMessages(null);
        controls.dispose();ui.dispose();web.destroy();window.pause().stop().destroy();
    }
    private void set(String name,Object value) { ReflectionHelpers.setField(activity,name,value); }
    private void call(String name) { ReflectionHelpers.callInstanceMethod(activity,name); }
    private boolean flag(String name) { return ReflectionHelpers.getField(activity,name); }
    private void press(int code) {
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code)));
    }
    private void idle(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private void capture(String name) throws Exception {
        String output=System.getenv("LIVE_NAV_CAPTURE_DIR");
        if(output==null)return;
        FrameLayout root=ReflectionHelpers.getField(activity,"root");
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            root.draw(new Canvas(bitmap));
            File directory=new File(output);directory.mkdirs();
            try(FileOutputStream stream=new FileOutputStream(new File(directory,name+".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);
            }
        } finally {bitmap.recycle();}
    }

    @Test public void transportPausesAndVerticalPreviewOnlyCommitsOnConfirm() throws Exception {
        assertTrue("controls visible before transport", controls.isShowingControls());
        assertTrue("controls focused before transport: " + controls.findFocus(), controls.hasFocus());
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        assertTrue(flag("livePaused"));
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        assertFalse(flag("livePaused"));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(controls.isSelectingChannels());
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(0,(int)ReflectionHelpers.getField(activity,"position"));
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(1,(int)ReflectionHelpers.getField(activity,"position"));
        assertEquals(2,web.pauses);assertTrue(flag("queued"));assertFalse(controls.isSelectingChannels());
    }

    @Test public void libraryNotificationRoutesToTheSameNativeScreen() {
        Intent intent=new Intent().putExtra("media_browse_tab","library");
        ReflectionHelpers.callInstanceMethod(activity,"handleMediaBrowseIntent",ReflectionHelpers.ClassParameter.from(Intent.class,intent));
        idle(32);
        assertFalse(intent.hasExtra("media_browse_tab"));
        assertEquals(VodLibraryActivity.class.getName(),Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        assertFalse(ui.isOpen());
    }

    @Test public void defaultHomeSurvivesANormalLauncherIntentAndBackKeepsItsTask() {
        enableDesktopMode(true);
        press(KeyEvent.KEYCODE_MENU);idle(400);
        deliverEntry(Intent.CATEGORY_HOME);
        assertFalse(ui.isOpen());assertFalse(controls.isSelectingChannels());
        press(KeyEvent.KEYCODE_BACK);assertFalse(activity.isFinishing());
        deliverEntry(Intent.CATEGORY_LAUNCHER);
        assertFalse(activity.getIntent().hasCategory(Intent.CATEGORY_HOME));
        assertTrue(DesktopMode.INSTANCE.isDefaultHome(activity));
        press(KeyEvent.KEYCODE_BACK);assertFalse(activity.isFinishing());
    }

    @Test public void disablingDesktopModeKeepsBackOnTheLivePage() {
        enableDesktopMode(true);
        deliverEntry(Intent.CATEGORY_HOME);
        assertTrue(DesktopMode.INSTANCE.setEnabled(activity,false));
        assertTrue(activity.getIntent().hasCategory(Intent.CATEGORY_HOME));
        // A role service can observe component disablement later; the local opt-out wins now.
        assertTrue(activity.getSystemService(RoleManager.class).isRoleHeld(RoleManager.ROLE_HOME));
        press(KeyEvent.KEYCODE_BACK);assertFalse(activity.isFinishing());
    }

    @Test public void backDismissesLiveControlsThenBackgroundsTheTask() {
        enableDesktopMode(false);
        deliverEntry(Intent.CATEGORY_LAUNCHER);
        assertTrue(DesktopMode.INSTANCE.isEnabled(activity));
        assertFalse(DesktopMode.INSTANCE.isDefaultHome(activity));
        assertTrue(controls.isShowingControls());
        press(KeyEvent.KEYCODE_BACK);
        assertFalse(controls.isShowingControls());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        press(KeyEvent.KEYCODE_BACK);
        assertTrue(Shadows.shadowOf(activity).getNextStartedActivity().hasCategory(Intent.CATEGORY_HOME));
    }

    @Test public void aHomeChoiceForThisLaunchKeepsItsTaskWithoutClaimingTheDefaultRole() {
        enableDesktopMode(false);
        press(KeyEvent.KEYCODE_MENU);idle(400);
        deliverEntry(Intent.CATEGORY_HOME);
        assertFalse(ui.isOpen());assertFalse(controls.isSelectingChannels());
        assertFalse(DesktopMode.INSTANCE.isDefaultHome(activity));
        press(KeyEvent.KEYCODE_BACK);assertFalse(activity.isFinishing());
    }

    private void enableDesktopMode(boolean isDefault) {
        assertTrue(DesktopMode.INSTANCE.setEnabled(activity,true));
        RoleManager roles=activity.getSystemService(RoleManager.class);
        Shadows.shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME);
        if(isDefault)Shadows.shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME);
        else if(roles.isRoleHeld(RoleManager.ROLE_HOME))Shadows.shadowOf(roles).removeHeldRole(RoleManager.ROLE_HOME);
        ResolveInfo resolved=new ResolveInfo();resolved.isDefault=true;
        resolved.activityInfo=new ActivityInfo();resolved.activityInfo.exported=true;
        resolved.activityInfo.packageName=isDefault?activity.getPackageName():"fixture.launcher";
        resolved.activityInfo.name=isDefault?DesktopMode.INSTANCE.component(activity).getClassName():"fixture.launcher.Home";
        Shadows.shadowOf(activity.getPackageManager()).setResolveInfosForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),Collections.singletonList(resolved));
    }

    @Test public void reopeningFromTheTvLauncherRestoresMusic() {
        activity.getSharedPreferences("app-session", 0).edit().putString("last_screen", "music").apply();
        deliverEntry(Intent.CATEGORY_LEANBACK_LAUNCHER);
        assertEquals("com.live.tv.music.MusicActivity",
                Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    private void deliverEntry(String category) {
        activity.onNewIntent(new Intent(Intent.ACTION_MAIN).addCategory(category));
        idle(400);
    }
}
