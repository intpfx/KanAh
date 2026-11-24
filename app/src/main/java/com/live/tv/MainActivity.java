package com.live.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    private static java.lang.ref.WeakReference<MainActivity> mediaHost = new java.lang.ref.WeakReference<>(null);
    public static android.app.Activity mediaHostActivity() { return mediaHost.get(); }
    /** Called on the UI thread before another app player acquires its decoder. */
    public static void releaseLiveForPlayback() {
        MainActivity host = mediaHost.get();
        if (host != null && !host.destroyed) host.releaseLiveSession();
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable warmLibrary = () -> {
        if (this.foreground && !this.destroyed) com.live.tv.media.LibraryWarmup.start(getApplicationContext());
    };
    private final PlaybackGate gate = new PlaybackGate();
    private final LivePlaybackRecovery liveRecovery = new LivePlaybackRecovery();
    private Runnable liveRecoveryTask;
    private WebView web;
    private FrameLayout root;
    private GlobalSearchOverlay globalSearch;
    private MenuKeyController menuKey;
    private TvUi ui;
    private boolean pendingSidebarLive;
    private com.live.tv.media.LivePlaybackControls liveControls;
    private volatile boolean foreground;
    private boolean liveRetained, liveBackgroundMuted;
    private int channel, loadingChannel, pageChannel;
    /** Merged playlist: the 18 built-in CCTV pages, then any imported IPTV entries. */
    private final List<Channel> playlist = new ArrayList<>();
    /** Index into {@link #playlist}; {@link #channel} remains the built-in page index. */
    private int position;
    private StreamPlayer streamPlayer;
    private boolean streamMode;
    /** Set while playing a url learned from a channel's page, so a failure can fall back to it. */
    private int learnedWebIndex = -1;
    /** Our own HLS player, used for the learned cctv urls the platform player cannot read. */
    private HlsPlayer hlsPlayer;
    /**
     * Off, deliberately, after verifying it end to end. The platform player cannot play this stream:
     * handed the master playlist it fails outright with "player error -10104"; handed one of its
     * variants - fetched and picked here - it accepts it, reports no error and even claims rendering
     * started, and then draws nothing at all: a black screen. So the framework's HLS path is a dead end
     * for CCTV live, and a player of our own (MediaExtractor over the variant's TS segments, MediaCodec
     * for decode) is the work that is actually missing. The learning, the master->variant step and the
     * fall back all stay, so that work has somewhere to plug in; startup stays on the page until then.
     */
    private static final boolean USE_LEARNED_URL = false;
    /** Temporary: set to a public HLS stream to check the integration on its own. */
    private static final String MEDIA3_TEST_URL = null;
    /**
     * Let the page derive the url, then hand that url to the native player straight away. A cached url is
     * minutes old when it is reused and these carry a window parameter; this measures whether freshness is
     * the difference without waiting for the native path to derive urls of its own.
     */
    private static final boolean RELEARN_THEN_NATIVE = false;
    /**
     * Play the variant the master playlist points at instead of the master itself. The master parses
     * through curl without complaint, but media3 never starts loading it, and the variant is the next
     * thing in the chain to ask about.
     */
    private static final boolean MEDIA3_VARIANT_ONLY = false;
    private String lastKey = "";
    private String iptvStatus = "未配置 IPTV 源";
    /** Shared listener; the remote receiver can retain it after this player is destroyed. */
    private String script, switchScript, remuxScript, adapterScript, ownStartScript, ownEarlyScript;
    private boolean active, committed, revealed, revealPending, resumed;
    private android.widget.ImageView startupFrame;
    private final Runnable cacheLiveFrame = new Runnable() {
        @Override public void run() {
            if (!active || !foreground || !revealed || destroyed) return;
            captureLiveBitmap(bitmap -> {
                if (bitmap != null) saveStartupFrame(bitmap);
                if (active && foreground && revealed && !destroyed) handler.postDelayed(this, 5000);
            });
        }
    };
    private java.io.File startupFrameFile;
    private volatile boolean destroyed;
    private boolean busy, samePage, failed, queued;
    /** User transport intent is separate from buffering and Activity lifecycle suspension. */
    private boolean livePaused;
    private long livePauseGeneration;
    private long requestedAt;
    private final Runnable dispatchSwitch = () -> {
        if (!active || destroyed || busy || !queued) return;
        queued=false;
        beginLoad(committed && web!=null && !failed);
    };
    private long startedAt, lastProgress;
    /**
     * Serve a page of our own - container plus the player's own scripts - instead of the website. The
     * player comes along; the website does not.
     */
    private static final boolean USE_LOCAL_PAGE = false;
    private LocalCdn localCdn;
    /**
     * Proof of concept: play one segment ourselves, repackaging the transport stream into fragmented mp4
     * for MSE. Their player costs eight seconds of cpu before it asks for anything; this is the only way
     * left to reach a picture in three.
     */
    private static final boolean POC_REMUX = false;
    /** Our MSE player still needs CCTV's converter in the genuine official page context. */
    private static final boolean USE_OWN_PLAYER = true;
    private static final boolean USE_EARLY_PLAYER = true;
    private boolean ownMode, ownFallback, requestedOwn, ownProbePending, ownScriptsLoaded, measureStartup, bypassCache, requestedEarly, earlyDocument, earlyBootstrapped, earlyFallback;
    private String earlySource;
    private Runnable ownFrameCheck;
    private long ownFrameToken;
    /**
     * When the process was started, so every phase can be measured from the user pressing the icon.
     * Read from the system rather than captured in a field: a field is initialised whenever the class
     * happens to be loaded, which produced negative offsets.
     */
    private static long processStart() {
        long started = android.os.Process.getStartUptimeMillis();
        return started > 0 ? started : SystemClock.uptimeMillis();
    }
    private static boolean firstFrameSeen;

    private static void phase(String what) {
        android.util.Log.d("Live", String.format(java.util.Locale.ROOT, "phase %-20s +%5d ms",
                what, SystemClock.uptimeMillis() - processStart()));
    }
    private final Runnable hideInfo = () -> ui.hideInfo();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active || destroyed || livePaused || web == null) return;
            inspect();
            // inspect may synchronously start the fallback and schedule a poll.
            handler.removeCallbacks(this);
            if(active && !destroyed && !livePaused && web!=null)handler.postDelayed(this, revealed ? 1000 : 300);
        }
    };

    @Override public void onCreate(Bundle state) {
        setTheme(com.live.tv.R.style.LiveTheme);
        super.onCreate(state);
        com.live.tv.ObsoleteDataCleanup.INSTANCE.run(this);
        mediaHost = new java.lang.ref.WeakReference<>(this);
        requestedEarly=BuildConfig.DEBUG ? getIntent().getBooleanExtra("test_early_player",USE_EARLY_PLAYER) : USE_EARLY_PLAYER;
        requestedOwn = requestedEarly || (BuildConfig.DEBUG ? getIntent().getBooleanExtra("test_own_player", USE_OWN_PLAYER) : USE_OWN_PLAYER);
        measureStartup=BuildConfig.DEBUG && getIntent().getBooleanExtra("measure_startup",true);
        bypassCache=measureStartup && getIntent().getBooleanExtra("bypass_web_cache",false);
        phase("onCreate");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        try {
            script = new String(readAsset("player.js"), StandardCharsets.UTF_8);
            switchScript = new String(readAsset("switch.js"), StandardCharsets.UTF_8);
            remuxScript = new String(readAsset("remux.js"), StandardCharsets.UTF_8);
            adapterScript = new String(readAsset("cctv-adapter.js"), StandardCharsets.UTF_8);
            ownStartScript = new String(readAsset("own-start.js"), StandardCharsets.UTF_8);
            ownEarlyScript = new String(readAsset("own-early.js"), StandardCharsets.UTF_8);
        }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        for (int i = 0; i < Channels.IDS.length; i++) playlist.add(Channel.web(i));
        ui = new TvUi(this, new TvUi.Actions() {
            public void select(int next) { ui.close(); load(next); }
            public void retry() { ui.close(); retryCurrent(); }
            public void toggleLivePlayback() { MainActivity.this.toggleLivePlayback(); }
            public void live() { openLiveChannels(); }
            public void desktopMode() { toggleDesktopModeFromSidebar(); }
            public void settings() { openSystemSettingsPage(); }
            public void exit() { finish(); }
            public void library() { openMediaLibrary(); }
            public void setPictureHidden(boolean hidden) {
                if (web != null) web.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
            }
            public void captureFrame(java.util.function.Consumer<android.graphics.Bitmap> ready) {
                // Must not be named captureFrame: inside this anonymous class that name resolves to this
                // very method, and the "call the helper" line became infinite recursion.
                captureVideoFrame(ready);
            }
        });
        ui.setPlaylist(new ArrayList<>(playlist), false);
        ui.setSidebarEnabled(false);
        liveControls = new com.live.tv.media.LivePlaybackControls(this, new com.live.tv.media.LivePlaybackControls.Actions() {
            public void select(int index) { ui.close(); load(index); }
            public void toggle() { toggleLivePlayback(); }
            public void settings() { openSystemSettingsPage(); }
            public void desktopMode() { toggleDesktopModeFromSidebar(); }
        });
        ui.attachLivePlaybackControls(liveControls);
        root.addView(ui, new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        globalSearch = new GlobalSearchOverlay(this);
        root.addView(globalSearch, new FrameLayout.LayoutParams(-1, -1));
        menuKey = new MenuKeyController(() -> { showGlobalSearch(); return kotlin.Unit.INSTANCE; });
        startupFrameFile = new java.io.File(getCacheDir(), "live-last.jpg");
        showStartupFrame();
        if (state == null) restoreLastScreen(getIntent());
        handleMediaBrowseIntent(getIntent());
        handleSidebarIntent(getIntent());
        channel = Channels.wrap(getPreferences(0).getInt("channel",13));
        position = channel; // built-in pages occupy the first slots of the playlist
        lastKey = IptvStore.lastChannel(this);
        // Start-up does not go through load(), so the learned-url shortcut has to be taken here too -
        // this is the path the first frame time actually depends on.
        String learnedAtStart = (!USE_LEARNED_URL || playlist.isEmpty()) ? null : learnedUrl(position);
        if (learnedAtStart != null) {
            startLearnedStream(playlist.get(position), learnedAtStart);
        } else {
            beginLoad(false);
        }
        reloadIptv();
    }

    /** Loads the IPTV source in the background and appends whatever it yields. */
    private void reloadIptv() {
        while (playlist.size() > Channels.IDS.length) playlist.remove(playlist.size() - 1);
        if (position >= playlist.size()) position = playlist.size() - 1;
        ui.setPlaylist(new ArrayList<>(playlist), false);
        updateLiveChannels();
        IptvStore.loadAsync(this, result -> {
            iptvStatus = result.status;
            playlist.addAll(result.channels);
            ui.setPlaylist(new ArrayList<>(playlist), false);
            updateLiveChannels();
            int restored = indexOfKey(lastKey);
            if (!streamMode && restored >= 0 && playlist.get(restored).kind == Channel.STREAM) {
                load(restored); // the remembered channel was an IPTV entry
            }
        });
    }

    private int indexOfKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).key.equals(key)) return i;
        }
        return -1;
    }

    /**
     * Requests the player does not need. Kept as a list of things to drop rather than a list of things to
     * keep, so that anything unfamiliar - the player's own scripts, the stream, whatever the page decides
     * to use next - still goes through.
     */
    private static boolean notNeededForPlayback(String url) {
        // The page's own scripts and styles stay: the player initialises from them.
        if (url.contains("js.player.cntv.cn")) return false;
        if (url.contains("jquery")) return false;
        if (url.contains(".css")) return false;
        if (url.contains(".m3u8") || url.contains(".ts")) return false;
        if (url.contains("tv.cctv.com/live/")) return false;
        if (url.contains("p.data.cctv.com")) return false;
        // Only what is certainly nothing to do with video: third party scripts, analytics, and images. The
        // page's own scripts stay, including configtool and indexPC - dropping those stopped the player
        // from starting at all, which is what the first attempt at this did.
        String[] dropped = {
                "wx.qq.com", "g.alicdn.com", "time.tv.cctv.com", "cntv_Advertise",
                "google-analytics", "doubleclick", "cpro.baidu", "hm.baidu", "mediadata.xuexi.cn",
                // The player holds a flag named aliJsLoaded and waits for alibaba's analytics plugin
                // before it goes on. Analytics cannot be worth waiting for.
                "js.data.cctv.com", "aplus",
                ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".woff", ".ttf",
        };
        for (String needle : dropped) {
            if (url.contains(needle)) return true;
        }
        return false;
    }

    /** Poll actual current video state; event names/history never establish playback. */
    private void watchForFirstFrame() {
        if (ownFrameCheck != null) handler.removeCallbacks(ownFrameCheck);
        final WebView target=web;
        final long token=gate.token();
        final long pauseGeneration=livePauseGeneration;
        final int expected=loadingChannel;
        final long started=SystemClock.uptimeMillis();
        final long[] progressed={started};
        final int[] previousFrames={0};
        final boolean[] querying={false};
        final Runnable[] check=new Runnable[1];
        check[0]=()->{
            if(destroyed || !active || livePaused || pauseGeneration!=livePauseGeneration || !ownMode || web!=target || !gate.accepts(token))return;
            if(querying[0])return;
            querying[0]=true;
            target.evaluateJavascript("JSON.stringify(window.__live||{})",raw->{
                querying[0]=false;
                if(destroyed || !active || livePaused || pauseGeneration!=livePauseGeneration || !ownMode || web!=target || !gate.accepts(token))return;
                try {
                    Object value=new JSONTokener(raw).nextValue();
                    JSONObject report=value instanceof String ? new JSONObject((String)value) : new JSONObject();
                    boolean current=report.optLong("token",-1)==token
                            && Channels.playerId(expected).equals(report.optString("channel"));
                    boolean ready=current && gate.sample(token,0,report.optBoolean("layout"),report.optInt("ready"),
                            report.optBoolean("paused",true),report.isNull("error")?0:1,
                            report.optDouble("time",-1),true,report.optInt("frames"));
                    int frames=report.optInt("frames");
                    if(!firstFrameSeen && frames>0) { firstFrameSeen=true;phase("first own video frame"); }
                    ready=ready && frames>previousFrames[0] && frames>=2 && report.optLong("audioBytes")>0;
                    previousFrames[0]=frames;
                    if(ready) {
                        progressed[0]=SystemClock.uptimeMillis();busy=false;
                        if(queued) {handler.removeCallbacks(dispatchSwitch);handler.post(dispatchSwitch);return;}
                        if(!revealed && !revealPending) {
                            revealPending=true;
                            target.postVisualStateCallback(token,new WebView.VisualStateCallback(){
                                @Override public void onComplete(long id) {
                                    if(destroyed || failed || !revealPending || !gate.accepts(token) || target!=web || queued || expected!=channel)return;
                                    ui.reveal(()->{
                                        if(destroyed || failed || !revealPending || !gate.accepts(token) || target!=web || queued || expected!=channel)return;
                                        revealed=true;revealPending=false;
                                        target.evaluateJavascript("window.__liveAudible=true;var v=document.getElementById('livetv-video');if(v){v.volume=1;v.muted=window.__liveUserPaused===true||window.__liveBackgroundMuted===true;}",null);
                                        showLiveInfo();
                                        android.util.Log.d("Live","own revealed channel="+Channels.IDS[expected]+" elapsedMs="+(SystemClock.uptimeMillis()-startedAt));
                                        phase("own reveal complete");
                                        learnUrl();
                                        if(measureStartup) {
                                            final long before=SystemClock.uptimeMillis()-processStart();
                                            target.evaluateJavascript("performance.now()",jsNow->{
                                                long after=SystemClock.uptimeMillis()-processStart();
                                                android.util.Log.d("Live","clockBridge before="+before+" after="+after+" js="+jsNow);
                                            });
                                        }
                                    });
                                }
                            });
                        }
                    }
                    if((report.has("error") && !report.isNull("error")) || report.optBoolean("blocked") ||
                            (!revealed && SystemClock.uptimeMillis()-started>15000) ||
                            (revealed && SystemClock.uptimeMillis()-progressed[0]>10000)) {
                        android.util.Log.w("Live","own failure state="+report.optString("state")+" error="+report.optString("error"));
                        fallbackOwn();return;
                    }
                } catch(JSONException ignored) {
                    if(SystemClock.uptimeMillis()-started>15000) {fallbackOwn();return;}
                }
                handler.removeCallbacks(check[0]);
                handler.postDelayed(check[0],revealed?750:200);
            });
        };
        ownFrameCheck=check[0];ownFrameToken=token;
        handler.post(check[0]);
    }

    private void fallbackOwn() {
        if(!ownMode || livePaused)return;
        android.util.Log.w("Live","own playback unavailable; restoring official page");
        if(earlyDocument) {
            earlyFallback=true;ownFallback=false;ownMode=false;
            android.util.Log.w("Live","minimal startup failed; restoring stable own path");
            beginLoad(false);return;
        }
        ownFallback=true;ownMode=false;
        // A later explicit channel selection may retry; this attempt cannot loop.
        beginLoad(false);
    }

    private void createWebView() {
        phase("webview create");
        web = new WebView(this);
        phase("webview constructed");
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        root.addView(web,0,new FrameLayout.LayoutParams(-1,-1));
        final WebView bridgeTarget=web;
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void onProgress(String value) {
                final long reported;
                try {reported=Long.parseLong(value);}catch(NumberFormatException ignored){return;}
                handler.post(()->{
                    if(destroyed || bridgeTarget!=web || !ownMode || revealed || ownFrameCheck==null
                            || reported!=ownFrameToken || !gate.accepts(reported))return;
                    handler.removeCallbacks(ownFrameCheck);
                    ownFrameCheck.run();
                });
            }
        }, "SmartTvPlayback");
        WebSettings settings = web.getSettings();
        if(bypassCache)settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptEnabled(!earlyDocument);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString()
                .replace("; wv", "").replace("Android", "Linux").replace("Mobile", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.setWebViewClient(new WebViewClient() {
            /**
             * What the page's own player actually asks for, headers and all. Chromium plays this stream
             * while both our player and ExoPlayer stall on it, so the difference is somewhere in the
             * request, and this is where to read it rather than guess.
             */
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if(earlyDocument && !request.isForMainFrame()) {
                    android.net.Uri uri=request.getUrl();
                    String path=uri.getPath()==null?"":uri.getPath();
                    boolean converter="https".equals(uri.getScheme()) && "js.player.cntv.cn".equals(uri.getHost()) && "/creator/live.worker.js".equals(path);
                    // The unmodified converter also requests its official runtime configuration.
                    // Returning an empty success here lets startup proceed with corrupt later video.
                    boolean converterConfig="https".equals(uri.getScheme()) && "tv.cctv.com".equals(uri.getHost()) && "/Library/H5player.json".equals(path);
                    boolean media="https".equals(uri.getScheme()) && (path.endsWith(".m3u8") || path.endsWith(".ts"));
                    if(!converter && !converterConfig && !media)return new android.webkit.WebResourceResponse("text/plain","utf-8",new java.io.ByteArrayInputStream(new byte[0]));
                }
                // Everything the page asks for, with when it asked: the ten seconds between the page
                // painting and the first video frame are spent in here, and guessing what fills them has
                // not worked.
                // The ten seconds between the page painting and the first video frame are mostly the
                // website's front end: advertising scripts, analytics, social sharing, a qr code library,
                // navigation, pictures. None of it plays video. Everything that is not needed for the
                // player is answered here with an empty response instead of being fetched.
                if (url.startsWith("http://127.0.0.1:")) return null;
                if (notNeededForPlayback(url)) {
                    android.util.Log.d("Live", String.format(java.util.Locale.ROOT,
                            "phase %-20s +%5d ms  BLOCKED %s", "req", 
                            SystemClock.uptimeMillis() - processStart(),
                            url.length() > 90 ? url.substring(0, 90) : url));
                    return new android.webkit.WebResourceResponse("text/plain", "utf-8", 204,
                            "No Content", new java.util.HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
                }
                android.util.Log.d("Live", String.format(java.util.Locale.ROOT,
                        "phase %-20s +%5d ms  ALLOW %s", "req",
                        SystemClock.uptimeMillis() - processStart(),
                        url.length() > 130 ? url.substring(0, 130) : url));
                return null;   // let the WebView do exactly what it would have done
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                if (ownMode) return !matches(request.getUrl().toString());
                if (POC_REMUX) return false;
                return !matches(request.getUrl().toString());
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon) {
                if(view != web || destroyed)return;
                if(ownMode) {
                    // A new main document cannot inherit the previous video's reveal.
                    cover();
                    gate.begin();
                    android.util.Log.w("Live","own document navigated; restoring controlled playback");
                    fallbackOwn();
                    return;
                }
                if (POC_REMUX || !matches(url)) return;
                phase("page started");
                if(samePage) { beginLoad(false); return; }
                gate.begin();
                ownScriptsLoaded=false;ownProbePending=false;
                startedAt=lastProgress=SystemClock.uptimeMillis();
                liveRecovery.beginAttempt(startedAt);
                committed=false;
                cover();
            }
            @Override public void onPageCommitVisible(WebView view,String url) {
                if (POC_REMUX || ownMode) return;
                phase("page visible");
                if(earlyDocument)return;
                // Ask the page what the player left behind, a few seconds in: whether its script defined
                // anything at all, and whether it complained on the way.
                view.postDelayed(() -> {
                    if (destroyed || view != web) return;
                    view.evaluateJavascript("(function(){try{return JSON.stringify({"
                            + "lpo:typeof livePlayerObjs,keys:(window.livePlayerObjs?"
                            + "Object.keys(window.livePlayerObjs):null),"
                            + "lp:typeof liveplayer,creator:typeof creator,swf:typeof swfobject,"
                            + "jq:typeof jQuery,box:!!document.getElementById('player'),"
                            + "err:(window.__err||[]).slice(0,4)});}catch(e){return 'probe '+e;}})()",
                            value -> android.util.Log.d("Live", "page state: " + value));
                }, 4000);
                // Watch the quiet stretch: the player's scripts are all in by seven seconds and nothing is
                // requested until eleven, so something local takes four. Ask it repeatedly what it has.
                for (int i = 1; i <= 12; i++) {
                    view.postDelayed(() -> {
                        if (destroyed || view != web) return;
                        view.evaluateJavascript("(function(){try{var o=window.livePlayerObjs||{};"
                                + "var p=o.player||{};return JSON.stringify({k:Object.keys(o).length,"
                                + "names:Object.keys(o).join(',').slice(0,90),p:!!o.player,"
                                + "rs:p.readyState,ns:p.networkState,"
                                + "p2p:(typeof o.isP2p!=='undefined'?o.isP2p:null),"
                                + "w:(typeof Worker!=='undefined')});}catch(e){return 'probe '+e;}})()",
                                value -> android.util.Log.d("Live", "tick: " + value));
                    }, 500L * i);
                }
                committed(view,url);
                // Can this WebView play the stream itself, in a page of our own with no site scripts at
                // all? The page's player goes through MSE; the platform's own HLS path is broken on this
                // television, so the answer decides whether a minimal page is worth building.
                view.evaluateJavascript("(function(){try{var t=['video/mp2t; codecs=\"avc1.64001f\"',"
                        + "'video/mp4; codecs=\"avc1.64001f\"','video/mp2t','video/mp4'];"
                        + "var o=[];for(var i=0;i<t.length;i++){o.push(t[i]+'='+"
                        + "(window.MediaSource&&MediaSource.isTypeSupported?MediaSource.isTypeSupported(t[i]):'no-ms'));}"
                        + "return o.join(' | ');}catch(e){return 'err '+e;}})()",
                        value -> android.util.Log.d("Live", "mse support: " + value));
            }
            @Override public void onPageFinished(WebView view,String url) {
                if(earlyDocument && !earlyBootstrapped && !failed && !destroyed && view==web && matches(url)) {
                    committed=true;earlyBootstrapped=true;
                    phase("minimal document ready");
                    startEarlyPlayer();return;
                }
                if (POC_REMUX || ownMode) return;
                committed(view,url);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {
                if(view!=web)return;
                logLiveRequestError(request, "network", error.getErrorCode());
                if(request.isForMainFrame() && matches(request.getUrl().toString())) recoverLive("document network " + error.getErrorCode());
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response) {
                if(view!=web)return;
                logLiveRequestError(request, "http", response.getStatusCode());
                if(request.isForMainFrame() && matches(request.getUrl().toString())) recoverLive("document HTTP " + response.getStatusCode());
            }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {
                if(view!=web) { view.destroy(); return true; }
                gate.begin();
                handler.removeCallbacks(poll);
                root.removeView(view);
                web=null;
                view.destroy();
                busy=false;failed=true;queued=false;
                cover();ui.error();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            /** The player's own complaints are the only way to find out what a page of ours is missing. */
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if(message.message().startsWith("SMARTTV_FRAME:")) {
                    try {
                        final long reported=Long.parseLong(message.message().substring(14));
                        handler.post(()->{
                            if(destroyed || bridgeTarget!=web || !ownMode || revealed || ownFrameCheck==null
                                    || reported!=ownFrameToken || !gate.accepts(reported))return;
                            handler.removeCallbacks(ownFrameCheck);ownFrameCheck.run();
                        });
                    }catch(NumberFormatException ignored){}
                    return true;
                }
                android.util.Log.d("Live", "console " + message.messageLevel() + " "
                        + message.message() + " @" + message.lineNumber());
                return true;
            }

            // WebView's default poster is a giant play icon, even with controls=false.
            // Loading/error feedback belongs to the native curtain; this is never readiness.
            @Override public android.graphics.Bitmap getDefaultVideoPoster() {
                android.graphics.Bitmap poster=android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);
                poster.eraseColor(Color.BLACK);
                return poster;
            }
            // CSS owns fullscreen. Never allow a web-created view to cover native UI.
            @Override public void onShowCustomView(View view,CustomViewCallback callback) { callback.onCustomViewHidden(); }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
        });
    }

    private boolean matches(String url) {
        if(localCdn != null && url.startsWith("http://127.0.0.1:"))return true;
        if(!Channels.allowed(url))return false;
        String path=android.net.Uri.parse(url).getPath();
        String base="/live/"+Channels.IDS[pageChannel]+"/";
        return path!=null && (path.equals(base)||path.equals(base+"index.shtml")||path.equals(base+"m/"));
    }

    private void committed(WebView view,String url) {
        if(view!=web || destroyed || !matches(url))return;
        committed=true;
        if(active)inspect();
    }

    private void cover() {
        clearLivePause();
        revealed=false;revealPending=false;
        handler.removeCallbacks(hideInfo);
        updateLiveChannels();
        // The curtain names the entry being loaded, and an IPTV entry is identified by its playlist
        // position - passing the page index here named the previous CCTV channel instead.
        ui.loading(position);
    }

    /** Moves to a playlist position and picks the backend that entry belongs to. */
    private void load(int next) {
        if (playlist.isEmpty()) return;
        position = Math.floorMod(next, playlist.size());
        Channel target = playlist.get(position);
        lastKey = target.key;
        IptvStore.setLastChannel(this, target.key);
        if (target.kind == Channel.WEB) {
            String learned = USE_LEARNED_URL ? learnedUrl(target.webIndex) : null;
            if (learned != null) {
                startLearnedStream(target, learned);
            } else {
                stopStream();
                loadWeb(target.webIndex);
            }
        } else {
            startStream(target);
        }
    }

    /** The original WebView navigation path, unchanged. */
    private void loadWeb(int webIndex) {
        cancelLiveRecovery();
        liveRecovery.reset();
        learnedWebIndex = -1;
        ownFallback=false;earlyFallback=false;
        if(ownMode)busy=false;
        channel=Channels.wrap(webIndex);
        requestedAt=SystemClock.uptimeMillis();
        phase("channel requested "+Channels.IDS[channel]);
        queued=true;
        gate.begin(); // Cancel pending visual callbacks as soon as a remote selection arrives.
        ownProbePending=false; // Its old-token callback can no longer clear this latch.
        if(web!=null)web.evaluateJavascript("window.__liveTvOwnToken=null;window.__liveUserPaused=false;window.__liveTvAudible=false;window.__liveAudible=false;document.querySelectorAll('video').forEach(v=>v.muted=true)",null);
        cover();
        getPreferences(0).edit().putInt("channel",channel).apply();
        handler.removeCallbacks(dispatchSwitch);
        if(!busy)handler.postDelayed(dispatchSwitch,220);
    }

    private void retryCurrent() {
        cancelLiveRecovery();
        liveRecovery.reset();
        ownFallback=false;earlyFallback=false;
        if (streamMode && !playlist.isEmpty()) {
            startStream(playlist.get(position));
        } else {
            beginLoad(false);
        }
    }

    /** Only one decoder may exist at a time, so the WebView is torn down before streaming. */
    private void destroyWeb() {
        cancelLiveRecovery();
        handler.removeCallbacks(poll);
        handler.removeCallbacks(dispatchSwitch);
        if (web != null) {
            WebView old = web;
            web = null;
            root.removeView(old);
            old.stopLoading();
            old.destroy();
        }
        busy = false;
        queued = false;
        committed = false;
        revealPending = false;
    }

    /** The stream url the page itself resolved last time, remembered per channel. */
    private String learnedUrl(int webIndex) {
        return getPreferences(0).getString("native_" + Channels.wrap(webIndex), null);
    }

    /**
     * Plays a channel natively on a url learned from its page. The page needs about thirteen seconds to
     * put a first frame on screen - WebView start-up, the site's own scripts, hls.js and a manifest -
     * while the platform player does the same job from the same url in a fraction of that. The url can
     * go stale, so any failure forgets it and hands the channel back to the page, which learns a fresh
     * one; that is the same path a channel with no learned url takes.
     */
    private static final String CDN_REFERER = "https://tv.cctv.com/";
    private static final String CDN_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private java.util.Map<String, String> cdnHeaders() {
        return cdnHeaders(null);
    }

    /**
     * Headers for the cdn, including the cookies the page's own session collected.
     *
     * <p>The url is learned inside the WebView, and that session carries cookies for the cdn domain that
     * a bare request does not. Chromium plays this stream while both our player and ExoPlayer stall on
     * it, and a cookie is the cheapest difference left between the two.
     */
    private java.util.Map<String, String> cdnHeaders(String url) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Referer", CDN_REFERER);
        // Read off the page's own requests: Chromium sends an Origin and its own user agent, and the cdn
        // is entitled to care. Sending the same three headers is the point of this exercise.
        headers.put("Origin", "https://tv.cctv.com");
        headers.put("Accept", "*/*");
        if (web != null) headers.put("User-Agent", web.getSettings().getUserAgentString());
        else headers.put("User-Agent", CDN_UA);
        try {
            android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
            String forUrl = url != null ? url : CDN_REFERER;
            String cookie = cookies.getCookie(forUrl);
            if (cookie == null || cookie.isEmpty()) cookie = cookies.getCookie(CDN_REFERER);
            if (cookie != null && !cookie.isEmpty()) {
                headers.put("Cookie", cookie);
                android.util.Log.d("Live", "cdn cookie " + cookie.length() + " chars");
            } else {
                android.util.Log.d("Live", "no cdn cookie stored");
            }
        } catch (Throwable t) {
            android.util.Log.w("Live", "cookie lookup failed: " + t);
        }
        return headers;
    }

    private void startLearnedStream(Channel target, String url) {
        // onCreate and onResume both route here for the same channel; the second call used to tear down a
        // player that was already loading.
        destroyWeb();
        streamMode = true;
        learnedWebIndex = target.webIndex;
        cover();
        ensureStreamPlayer();
        android.util.Log.d("Live", "native attempt channel=" + Channels.IDS[target.webIndex] + " url=" + url);
        if (hlsPlayer == null) {
            hlsPlayer = new HlsPlayer(this, cdnHeaders(), new HlsPlayer.Listener() {
                @Override public void onReady() {
                    handler.post(() -> {
                        if (destroyed || !streamMode) return;
                        ui.reveal(() -> {
                            if (destroyed || !streamMode) return;
                            revealed = true;
                            showLiveInfo();
                        });
                    });
                }
                @Override public void onError(String reason) {
                    // Off the UI thread: the fall back posts, so this is safe from here.
                    fallBackToPage(reason);
                }
            });
            root.addView(hlsPlayer.view(), 0, new FrameLayout.LayoutParams(-1, -1));
        }
        showOnlyStreamView(hlsPlayer.view());
        hlsPlayer.play(url);
    }

    /**
     * Hands the channel back to its page and forgets the url. Everything here is posted: the player's
     * error callback does not come from the UI thread, and calling straight into the view code from it is
     * how the fallback silently failed to happen.
     */
    private void fallBackToPage(String why) {
        final int fallback = learnedWebIndex;
        if (fallback < 0) return;
        android.util.Log.w("Live", "native failed (" + why + "), falling back to the page");
        learnedWebIndex = -1;
        getPreferences(0).edit().remove("native_" + Channels.wrap(fallback)).apply();
        handler.post(() -> {
            if (destroyed) return;
            stopStream();
            loadWeb(fallback);
        });
    }

    /** The player and its callbacks, shared by the IPTV path and the learned-cctv path. */
    private void ensureStreamPlayer() {
        handler.removeCallbacks(poll);
        if (streamPlayer != null) return;
        streamPlayer = new StreamPlayer(this, new StreamPlayer.Listener() {
            @Override public void onStreamReady() {
                if (destroyed || !active || !streamMode) return;
                final long token=gate.token();
                ui.reveal(() -> {
                    if (destroyed || !active || !streamMode || !gate.accepts(token)) return;
                    revealed = true;
                    showLiveInfo();
                });
            }
            @Override public void onStreamError(String reason) {
                android.util.Log.w("Live", "stream error: " + reason);
                if (destroyed || !active || !streamMode) return;
                final long token=gate.token();
                if (learnedWebIndex >= 0) {
                    // Stale url (or a stream the player cannot read): let the page take over, which also
                    // learns a fresh one.
                    fallBackToPage(reason);
                    return;
                }
                handler.post(() -> {
                    if (destroyed || !active || !streamMode || !gate.accepts(token)) return;
                    failed = true;
                    cover();
                    ui.error();
                });
            }
        });
        streamPlayer.setMuted(liveBackgroundMuted);
        root.addView(streamPlayer.view(), 0, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Asks the playing page for its hls url and remembers it for this channel. */
    private void learnUrl() {
        final WebView target = web;
        if (target == null) return;
        final int forChannel = channel;
        final long forToken=gate.token();
        target.evaluateJavascript("(function(){try{if(window.__liveTvSource)return window.__liveTvSource;var f=[],s=new Set();var w=function(o,d){"
                + "if(!o||d>4||s.has(o))return;s.add(o);"
                + "try{if(typeof o.url==='string'&&o.url.indexOf('m3u8')>=0)f.push(o.url);}catch(e){}"
                + "for(var k in o){try{var c=o[k];if(c&&typeof c==='object')w(c,d+1);}catch(e){}}};"
                + "w(window.livePlayerObjs,0);return f.length?f[0]:'';}catch(e){return '';}})()", raw -> {
            if (destroyed || target!=web || !gate.accepts(forToken) || raw == null) return;
            try {
                Object value = new JSONTokener(raw).nextValue();
                String url = value instanceof String ? (String) value : "";
                if (url.startsWith("http") && url.contains("m3u8")) {
                    getPreferences(0).edit().putString("native_" + Channels.wrap(forChannel), url).apply();
                    android.util.Log.d("Live", "learned url for " + Channels.IDS[forChannel]);
                    // The cached url is minutes old by the time it is reused, and these cdn urls carry a
                    // window parameter. Hand the one that was just derived straight to the native player,
                    // in the same session, to see whether freshness was the whole difference.
                    if (RELEARN_THEN_NATIVE && !streamMode && !destroyed && !playlist.isEmpty()) {
                        android.util.Log.d("Live", "handing a fresh url to the native player");
                        startLearnedStream(playlist.get(position), url);
                    }
                }
            } catch (JSONException ignored) { }
        });
    }

    /**
     * Streams an IPTV entry. The reveal contract matches the WebView path: the cover only lifts once
     * the player reports decoded frames, so a dead source never flashes a black screen.
     */
    private void startStream(Channel target) {
        destroyWeb();
        gate.begin();
        streamMode = true;
        learnedWebIndex = -1;
        failed = false;
        cover();
        ensureStreamPlayer();
        android.util.Log.d("Live", "stream position=" + position + " urls=" + target.urls.size());
        showOnlyStreamView(streamPlayer.view());
        streamPlayer.play(target.urls, target.headers);
    }

    private void stopStream() {
        streamMode = false;
        if (hlsPlayer != null) hlsPlayer.stop();
        if (streamPlayer != null) streamPlayer.stop();
        // A stopped TextureView keeps its last frame, and createWebView() inserts the new WebView at the
        // bottom of the stack - so leaving a player surface attached put the old source's picture on top
        // of the page that had just been selected. Only the path that is playing may show a surface.
        hideStreamViews();
    }

    /** Exactly one player surface may be visible, and only while the source it belongs to plays. */
    private void showOnlyStreamView(View keep) {
        if (streamPlayer != null) {
            streamPlayer.view().setVisibility(streamPlayer.view() == keep ? View.VISIBLE : View.GONE);
        }
        if (hlsPlayer != null) {
            hlsPlayer.view().setVisibility(hlsPlayer.view() == keep ? View.VISIBLE : View.GONE);
        }
    }

    private void hideStreamViews() {
        if (streamPlayer != null) streamPlayer.view().setVisibility(View.GONE);
        if (hlsPlayer != null) hlsPlayer.view().setVisibility(View.GONE);
    }

    /**
     * Freezes the picture for the code layer without stopping the stream: the page draws its own video
     * element into a canvas and hands back a small JPEG, which becomes the blurred backdrop. Pausing the
     * element instead would take the sound with it, and the sound is meant to carry on.
     */
    private void captureVideoFrame(java.util.function.Consumer<android.graphics.Bitmap> ready) {
        final WebView target = web;
        if (target == null) { ready.accept(null); return; }
        String js = "(function(){try{"
                + "var v=document.querySelector('#player video')||document.querySelector('video');"
                + "var probe='?';"
                + "try{"
                + "  var found=[],seen=new Set();"
                + "  var walk=function(o,d){"
                + "    if(!o||d>4||seen.has(o))return;seen.add(o);"
                + "    try{if(typeof o.url==='string'&&o.url.indexOf('m3u8')>=0)found.push(o.url);}catch(e){}"
                + "    try{if(o.audioTracks)found.push('audioTracks='+o.audioTracks.length);}catch(e){}"
                + "    for(var k in o){try{var c=o[k];if(c&&typeof c==='object')walk(c,d+1);}catch(e){}}"
                + "  };"
                + "  walk(window.livePlayerObjs,0);"
                + "  probe=JSON.stringify({src:(v.currentSrc||v.src||'').slice(0,120),found:found.slice(0,6)});"
                + "}catch(e){probe='probe-failed';}"
                + "if(!v||!v.videoWidth)return 'PROBE='+probe;"
                + "var w=Math.min(640,v.videoWidth),h=Math.round(w*v.videoHeight/v.videoWidth);"
                + "var c=document.createElement('canvas');c.width=w;c.height=h;"
                + "c.getContext('2d').drawImage(v,0,0,w,h);"
                + "return 'PROBE='+probe+'##'+c.toDataURL('image/jpeg',0.72);"
                + "}catch(e){return 'ERR='+e.name;}})()";
        target.evaluateJavascript(js, raw -> {
            if (destroyed || target != web || raw == null) { ready.accept(null); return; }
            int mark = raw.indexOf("##");
            if (mark > 0) raw = raw.substring(mark);   // drop the probe prefix, keep the frame
            ready.accept(decodeDataUrl(raw));
        });
    }

    /** Turns the page's "data:image/jpeg;base64,..." into a bitmap, or null if there is nothing usable. */
    private static android.graphics.Bitmap decodeDataUrl(String raw) {
        if (raw.length() < 32 || raw.startsWith("null")) return null;
        int comma = raw.indexOf(',');
        if (comma < 0) return null;
        try {
            String payload = raw.substring(comma + 1, raw.length() - 1);
            byte[] bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Only one official player initialization may be in flight in a document. */
    private int loadCount;

    private void beginLoad(boolean reuse) {
        cancelLiveRecovery();
        String candidate=getPreferences(0).getString("native_"+Channels.wrap(channel),null);
        boolean useEarly=requestedEarly && !earlyFallback && candidate!=null && candidate.startsWith("https://") && candidate.contains("m3u8");
        if(reuse && earlyDocument!=useEarly)reuse=false;
        boolean reuseEarly=reuse && earlyDocument;
        earlySource=useEarly?candidate:null;
        liveRecovery.beginAttempt(SystemClock.uptimeMillis());
        if(web!=null)web.evaluateJavascript("window.__liveTvTiming={};window.__liveTvOwnToken=null;window.__liveUserPaused=false;if(window.__liveStop)window.__liveStop();",null);
        ownMode=false;ownProbePending=false;
        firstFrameSeen=false;
        loadCount++;
        android.util.Log.d("Live", "beginLoad #" + loadCount + " reuse=" + reuse
                + " poc=" + POC_REMUX + " streamMode=" + streamMode);
        handler.removeCallbacks(dispatchSwitch);
        queued=false;busy=true;failed=false;samePage=reuse;
        loadingChannel=channel;
        final long token=gate.begin();
        startedAt=lastProgress=SystemClock.uptimeMillis();
        cover();
        if(reuseEarly) { startEarlyPlayer();return; }
        if(reuse) {
            final WebView target=web;
            String command=switchScript.replace("__NAV_TOKEN__",Long.toString(token))
                    .replace("__EXPECTED_CHANNEL__",JSONObject.quote(Channels.playerId(loadingChannel)))
                    .replace("__EXPECTED_PATH__",JSONObject.quote(pagePath()));
            target.evaluateJavascript(command,raw->{
                if(!gate.accepts(token)||destroyed||target!=web)return;
                try {
                    Object value=new JSONTokener(raw).nextValue();
                    JSONObject result=value instanceof String ? new JSONObject((String)value) : new JSONObject();
                    if(result.optLong("token",-1)==token && result.optBoolean("accepted"))return;
                } catch(JSONException ignored) {}
                beginLoad(false);
            });
        } else {
            // A fresh document cancels old page requests after timeout, retry or resume.
            committed=false;pageChannel=loadingChannel;
            earlyDocument=useEarly;earlyBootstrapped=false;
            if(web!=null) {
                WebView old=web;web=null;
                root.removeView(old);old.stopLoading();old.destroy();
            }
            createWebView();
            phase("navigate");
            String target = Channels.url(pageChannel);
            if (USE_LOCAL_PAGE || POC_REMUX) {
                try {
                    if (localCdn == null) {
                        final android.webkit.CookieManager jar = android.webkit.CookieManager.getInstance();
                        localCdn = new LocalCdn(MainActivity.this, CDN_REFERER,
                                web.getSettings().getUserAgentString(), () -> {
                            StringBuilder all = new StringBuilder();
                            for (String host : new String[]{"https://tv.cctv.com/", "https://vdnx.live.cntv.cn/",
                                    "https://js.player.cntv.cn/", "https://api.cntv.cn/", "https://vdn.apps.cntv.cn/"}) {
                                String one = jar.getCookie(host);
                                if (one != null && !one.isEmpty()) {
                                    if (all.length() > 0) all.append("; ");
                                    all.append(one);
                                }
                            }
                            return all.toString();
                        });
                    }
                    if (USE_LOCAL_PAGE) target = localCdn.pageUrl(pageChannel);
                } catch (java.io.IOException e) {
                    android.util.Log.w("Live", "local page unavailable: " + e);
                }
            }
            if (POC_REMUX && localCdn != null) {
                String learned = getPreferences(0).getString("native_" + Channels.wrap(pageChannel), null);
                android.util.Log.d("Live", "poc learned url " + learned);
                if (learned != null && learned.contains("m3u8")) {
                    String encoded;
                    try {
                        encoded = java.net.URLEncoder.encode(learned, "UTF-8");
                    } catch (java.io.UnsupportedEncodingException e) {
                        encoded = learned;
                    }
                    target = "http://127.0.0.1:" + localCdn.port() + "/poc.html?live=1&u=" + encoded;
                    android.util.Log.d("Live", "poc navigating to " + target);
                    web.loadUrl(target);
                    for (int i = 1; i <= 26; i++) {
                        web.postDelayed(() -> web.evaluateJavascript(
                                "JSON.stringify(window.__poc||{})",
                                value -> android.util.Log.d("Live", "poc state " + value)), 700L * i);
                    }
                    return;
                }
            }
            android.util.Log.d("Live", "navigating to " + target);
            web.loadUrl(target);
        }
        handler.removeCallbacks(poll);
        if(active)handler.post(poll);
        android.util.Log.d("Live","load token="+gate.token()+" channel="+Channels.IDS[loadingChannel]
                +" mode="+(reuse?"same-page":"document"));
    }

    private String pagePath() {
        return android.net.Uri.parse(Channels.url(pageChannel)).getPath();
    }

    /** Enable script only after the genuine official document finishes parsing without it. */
    private void startEarlyPlayer() {
        final WebView target=web;
        final long token=gate.token();
        if(target==null || earlySource==null)return;
        ownMode=true;
        handler.removeCallbacks(poll);
        target.getSettings().setJavaScriptEnabled(true);
        String command=ownEarlyScript.replace("__NAV_TOKEN__",Long.toString(token))
                .replace("__EXPECTED_CHANNEL__",JSONObject.quote(Channels.playerId(loadingChannel)))
                .replace("__KNOWN_STREAM__",JSONObject.quote(earlySource));
        String injected=liveAudioScript()+"window.__liveTvMeasure="+measureStartup+";\n"
                +(ownScriptsLoaded?"":remuxScript+"\n"+adapterScript+"\n")+command;
        target.evaluateJavascript(injected,raw->{
            if(destroyed || target!=web || !gate.accepts(token))return;
            ownScriptsLoaded=true;
            phase("minimal bootstrap");
            watchForFirstFrame();
        });
    }

    /** Wait for the site's source resolver and converter, then take over only playback. */
    private void tryOwnPlayer() {
        if(ownProbePending || ownMode)return;
        final WebView target=web;
        final long token=gate.token();
        ownProbePending=true;
        String known=getPreferences(0).getString("native_"+Channels.wrap(loadingChannel),null);
        String command=ownStartScript.replace("__NAV_TOKEN__",Long.toString(token))
                .replace("__EXPECTED_CHANNEL__",JSONObject.quote(Channels.playerId(loadingChannel)))
                .replace("__KNOWN_STREAM__",known==null?"null":JSONObject.quote(known));
        String silence="window.__liveTvMeasure="+measureStartup+";window.__liveTvAudible=false;document.querySelectorAll('video').forEach(v=>v.muted=true);";
        String injected=liveAudioScript()+silence+(ownScriptsLoaded?"":remuxScript+"\n"+adapterScript+"\n")+command;
        target.evaluateJavascript(injected,raw->{
            if(destroyed || target!=web || !gate.accepts(token) || ownFallback)return;
            ownProbePending=false;ownScriptsLoaded=true;
            if("true".equals(raw)) {
                ownMode=true;
                phase("own converter ready");
                handler.removeCallbacks(poll);
                watchForFirstFrame();
            }
        });
    }

    /** Keep diagnostic metadata; never log a source query, cookie or credential-bearing URL. */
    private void logLiveRequestError(WebResourceRequest request, String type, int code) {
        android.net.Uri uri=request.getUrl();
        String path=uri.getPath()==null?"":uri.getPath();
        String kind=request.isForMainFrame()?"document":path.endsWith(".m3u8")?"playlist"
                :path.endsWith(".ts")?"segment":path.endsWith("live.worker.js")||path.endsWith("H5player.json")?"runtime":null;
        if(kind!=null)android.util.Log.w("Live/recovery",type+" code="+code+" kind="+kind+" host="+uri.getHost());
    }

    private void cancelLiveRecovery() {
        if(liveRecoveryTask!=null)handler.removeCallbacks(liveRecoveryTask);
        liveRecoveryTask=null;
    }

    /** Rebuild one failed document; stale retries may never revive a background app or old channel. */
    private void recoverLive(String reason) {
        if(destroyed || !active || livePaused || failed || liveRecoveryTask!=null)return;
        if(queued) {busy=false;handler.removeCallbacks(dispatchSwitch);handler.post(dispatchSwitch);return;}
        if(earlyDocument && !earlyFallback) {fail();return;}
        long delay=liveRecovery.nextRetryDelay();
        android.util.Log.w("Live/recovery","channel="+Channels.IDS[loadingChannel]+" reason="+reason+" retryDelayMs="+delay);
        // The retry budget also applies after a same-page channel switch.
        if(delay<0) {samePage=false;fail();return;}
        final WebView target=web;
        final int expected=channel;
        final long token=gate.begin();
        failed=true;busy=false;ownMode=false;
        handler.removeCallbacks(poll);
        if(target!=null)target.evaluateJavascript("window.__liveTvAudible=false;window.__liveAudible=false;if(window.__liveStop)window.__liveStop();document.querySelectorAll('video').forEach(v=>{v.muted=true;v.pause();})",null);
        cover();ui.buffering();
        liveRecoveryTask=()->{
            liveRecoveryTask=null;
            if(destroyed || !active || !gate.accepts(token) || queued || channel!=expected || web!=target)return;
            // Keep the fallback choice: do not repeatedly return to an already failed fast path.
            beginLoad(false);
        };
        handler.postDelayed(liveRecoveryTask,delay);
    }

    private void inspect() {
        if(livePaused || ownMode || failed || (!busy && queued))return;
        long elapsed=SystemClock.uptimeMillis()-startedAt;
        if(busy && samePage && elapsed>12000) { beginLoad(false);return; }
        if(liveRecovery.state(SystemClock.uptimeMillis())==LivePlaybackRecovery.State.TIMED_OUT) {
            recoverLive("playback progress timeout");return;
        }
        else if(!revealed && elapsed>14000)ui.buffering();
        if(!committed || (earlyDocument && !earlyBootstrapped))return;
        if(requestedOwn && !ownFallback) {
            if(elapsed<15000) { tryOwnPlayer(); return; }
            // The website may remain playable even when its converter API changes.
            ownFallback=true;
            android.util.Log.w("Live","own initialization unavailable; keeping official player");
        }
        final long token=gate.token();
        final long pauseGeneration=livePauseGeneration;
        final WebView target=web;
        String path=pagePath();
        String probe=script.replace("__NAV_TOKEN__",Long.toString(token))
                .replace("__EXPECTED_PATH__",JSONObject.quote(path))
                .replace("__EXPECTED_CHANNEL__",JSONObject.quote(Channels.playerId(loadingChannel)))
                .replace("__SAME_PAGE__",Boolean.toString(samePage))
                .replace("__AUDIBLE__",Boolean.toString(revealed && !queued));
        target.evaluateJavascript(liveAudioScript()+probe,raw->{
            if(!gate.accepts(token)||destroyed||livePaused||pauseGeneration!=livePauseGeneration||target!=web)return;
            try {
                Object value=new JSONTokener(raw).nextValue();
                if(!(value instanceof String))return;
                JSONObject sample=new JSONObject((String)value);
                if(sample.optLong("token",-1)!=token)return;
                boolean ready=gate.sample(token,sample.optInt("instance",-1),sample.optBoolean("layout"),
                        sample.optInt("ready"),sample.optBoolean("paused",true),sample.optInt("error"),
                        sample.optDouble("time",-1),sample.optBoolean("frameCallback"),sample.optInt("frames"));
                if (!firstFrameSeen && sample.optInt("frames", 0) > 0) {
                    firstFrameSeen = true;
                    phase("first video frame");
                }
                if(ready) {
                    busy=false;
                    if(queued) {
                        handler.removeCallbacks(dispatchSwitch);
                        handler.postDelayed(dispatchSwitch,Math.max(0,requestedAt+220-SystemClock.uptimeMillis()));
                        return;
                    }
                    lastProgress=SystemClock.uptimeMillis();
                    liveRecovery.progress(lastProgress);
                    if(!revealed && !revealPending)reveal(target,token);
                } else if(revealed && liveRecovery.state(SystemClock.uptimeMillis())==LivePlaybackRecovery.State.BUFFERING) {
                    cover();ui.buffering();
                }
                if(sample.optInt("error")!=0)recoverLive("video error " + sample.optInt("error"));
            } catch(JSONException ignored) { /* A navigation can replace the document mid-sample. */ }
        });
    }

    private void reveal(WebView target,long token) {
        revealPending=true;
        target.postVisualStateCallback(token,new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) {
                if(!gate.accepts(token)||destroyed||target!=web||!revealPending||queued||channel!=loadingChannel)return;
                // A native cover stays in place until both video progress and page painting are ready.
                ui.reveal(()->{
                    if(!gate.accepts(token)||destroyed||target!=web||queued||channel!=loadingChannel)return;
                    revealed=true;revealPending=false;
                    android.util.Log.d("Live","revealed token="+token+" channel="+Channels.IDS[channel]+" elapsedMs="+(SystemClock.uptimeMillis()-startedAt));
                phase("revealed");
                    learnUrl();   // so the next launch of this channel can skip the page
                    inspect();
                    showLiveInfo();
                });
            }
        });
    }

    private void fail() {
        if(earlyDocument && !earlyFallback) {
            earlyFallback=true;ownFallback=false;ownMode=false;
            android.util.Log.w("Live","minimal document failed; restoring stable path");
            beginLoad(false);return;
        }
        if(samePage || queued) { beginLoad(false);return; }
        gate.begin();busy=false;queued=false;failed=true;
        if(web!=null)web.evaluateJavascript("window.__liveTvAudible=false;window.__liveAudible=false;if(window.__liveStop)window.__liveStop();document.querySelectorAll('video').forEach(v=>{v.muted=true;v.pause();})",null);
        handler.removeCallbacks(dispatchSwitch);
        cover();ui.error();
    }
    private void updateLiveChannels() {
        if(liveControls!=null)liveControls.setChannels(new ArrayList<>(playlist),position);
    }

    private void syncLiveControls() {
        if(liveControls==null)return;
        updateLiveChannels();
        liveControls.playback(active && revealed && !busy && !queued && !failed,livePaused);
    }

    private void showLiveInfo() {
        if (!foreground) return;
        fadeStartupFrame();
        handler.removeCallbacks(cacheLiveFrame);
        handler.postDelayed(cacheLiveFrame, 2500);
        handler.removeCallbacks(hideInfo);
        if(liveControls!=null) {
            ui.hideInfo();syncLiveControls();liveControls.show();
        } else {
            ui.showInfo(position);handler.postDelayed(hideInfo,3200);
        }
    }

    private void openLiveChannels() {
        ui.suppressStartupSidebar();
        ui.showLiveBrowser();ui.close();
        syncLiveControls();
        if(liveControls!=null)liveControls.openChannels();
    }

    private void openMenu(){
        handler.removeCallbacks(hideInfo);
        if(liveControls!=null)liveControls.hide();
        ui.open(position,revealed);
    }

    private void clearLivePause() {
        livePaused=false;
        livePauseGeneration++;
        if(liveControls!=null)liveControls.playback(false,false);
    }

    private String liveAudioScript() {
        return "window.__liveBackgroundMuted="+liveBackgroundMuted+";"
                +"if(typeof window.__liveSetBackgroundMuted==='function')window.__liveSetBackgroundMuted(window.__liveBackgroundMuted);"
                +"else if(window.__liveBackgroundMuted)document.querySelectorAll('video').forEach(v=>v.muted=true);";
    }

    private void setLiveBackgroundMuted(boolean muted) {
        liveBackgroundMuted = muted;
        if (streamPlayer != null) streamPlayer.setMuted(muted);
        if (web != null) web.evaluateJavascript(liveAudioScript(), null);
    }

    /** Pause the current decoder in place; a stopped clock must never trigger live recovery. */
    private boolean toggleLivePlayback() {
        if(destroyed || !active || !revealed || busy || queued || failed
                || ui.isOpen() || ui.isNavigationVisible())return false;
        final boolean paused=!livePaused;
        if(streamMode) {
            if(streamPlayer==null || !streamPlayer.setPaused(paused))return false;
        } else if(web==null || !committed)return false;
        livePaused=paused;
        final long generation=++livePauseGeneration;
        syncLiveControls();
        if(paused) {handler.removeCallbacks(hideInfo);ui.hideInfo();}
        cancelLiveRecovery();
        handler.removeCallbacks(poll);
        if(ownFrameCheck!=null)handler.removeCallbacks(ownFrameCheck);
        if(!streamMode) {
            final WebView target=web;
            final long token=gate.token();
            String command="(function(){var paused="+paused+";window.__liveUserPaused=paused;"
                    +"if(typeof window.__liveSetPaused==='function')return window.__liveSetPaused(paused);"
                    +"var v=document.getElementById('livetv-video')||document.querySelector('#player video')||document.querySelector('video');"
                    +"if(!v)return false;if(paused){v.pause();}else{var p=v.play();if(p&&p.catch)p.catch(function(){});}return true;})()";
            target.evaluateJavascript(command, value->{
                if(destroyed || target!=web || !gate.accepts(token) || generation!=livePauseGeneration)return;
                if("false".equals(value) || "null".equals(value)) {
                    // A site may replace its video between readiness and the user's key press.
                    clearLivePause();
                    target.evaluateJavascript("window.__liveUserPaused=false;",null);
                    resumeLiveChecks();
                }
            });
        }
        if(!paused)resumeLiveChecks();
        return true;
    }

    private void resumeLiveChecks() {
        syncLiveControls();
        if(destroyed || !active || livePaused || streamMode || web==null)return;
        // Paused time is intentional silence, not time spent waiting for network progress.
        startedAt=lastProgress=SystemClock.uptimeMillis();
        liveRecovery.beginAttempt(lastProgress);
        if(ownMode)watchForFirstFrame();
        else {handler.removeCallbacks(poll);handler.post(poll);}
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();
        if(globalSearch!=null && globalSearch.isOpen()) {
            if(key==KeyEvent.KEYCODE_BACK) {
                if(event.getAction()==KeyEvent.ACTION_UP) globalSearch.hide();
                return true;
            }
            return globalSearch.dispatchKeyEvent(event);
        }
        if(menuKey!=null && menuKey.handle(event)) return true;
        if(key==KeyEvent.KEYCODE_BACK) {
            if(event.getAction()==KeyEvent.ACTION_UP && !event.isCanceled()) {
                if(ui.isOpen()) ui.close();
                else {
                    boolean dismissed=ui.dismissInfo();
                    if(liveControls!=null && liveControls.isShowingControls()) {
                        liveControls.hide();
                        dismissed=true;
                    }
                    if(!dismissed) DesktopMode.returnToHome(this);
                }
            }
            return true;
        }
        if(ui.isOpen()) {
            if(key==KeyEvent.KEYCODE_MENU) {
                if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) ui.close();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        if(liveControls!=null && !ui.isNavigationVisible() && liveControls.handleKey(event))return true;
        boolean handled=key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN
                ||key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT
                ||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER
                ||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                ||key==KeyEvent.KEYCODE_MENU;
        if(handled) {
            // The outgoing sidebar still owns its key sequence until it has completely disappeared.
            if(ui.isNavigationVisible() && key!=KeyEvent.KEYCODE_MENU)return true;
            if(event.getAction()==KeyEvent.ACTION_DOWN && event.getRepeatCount()==0) {
                if(failed && (key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER))retryCurrent();
                else if(key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER
                        ||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)toggleLivePlayback();
                else if(key==KeyEvent.KEYCODE_MENU)showGlobalSearch();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void openMediaLibrary() {
        if(destroyed)return;
        if(liveControls!=null)liveControls.hide();
        ui.close();
        startActivity(new android.content.Intent(this,com.live.tv.media.VodLibraryActivity.class));
    }

    private void captureLiveBitmap(java.util.function.Consumer<android.graphics.Bitmap> ready) {
        try {
            if (streamMode && hlsPlayer == null && streamPlayer != null) {
                android.view.View view = streamPlayer.view();
                if (view instanceof android.view.TextureView) {
                    android.graphics.Bitmap bitmap = ((android.view.TextureView) view).getBitmap();
                    if (bitmap != null) { android.util.Log.d("LiveCache", "texture"); ready.accept(bitmap); return; }
                }
            }
            android.view.TextureView webTexture = findTextureView(web);
            if (webTexture != null) {
                android.graphics.Bitmap bitmap = webTexture.getBitmap();
                if (bitmap != null) { android.util.Log.d("LiveCache", "web texture"); ready.accept(bitmap); return; }
            }
            android.view.SurfaceView surface = findSurfaceView(web);
            if (surface == null) surface = findSurfaceView(getWindow().getDecorView());
            if (surface != null && surface.getWidth() > 0 && surface.getHeight() > 0
                    && surface.getHolder() != null && surface.getHolder().getSurface() != null
                    && surface.getHolder().getSurface().isValid()) {
                final android.view.SurfaceView source = surface;
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                        source.getWidth(), source.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                try {
                    android.view.PixelCopy.request(source, bitmap, result -> {
                        if (result == android.view.PixelCopy.SUCCESS) { android.util.Log.d("LiveCache", "surface"); ready.accept(bitmap); }
                        else { bitmap.recycle(); capturePictureFrame(ready); }
                    }, new Handler(Looper.getMainLooper()));
                } catch (Exception e) { bitmap.recycle(); capturePictureFrame(ready); }
                return;
            }
            capturePictureFrame(ready);
        } catch (Exception ignored) { ready.accept(null); }
    }

    private android.view.TextureView findTextureView(android.view.View view) {
        if (view == null) return null;
        if (view instanceof android.view.TextureView) return (android.view.TextureView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.TextureView found = findTextureView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private android.view.SurfaceView findSurfaceView(android.view.View view) {
        if (view == null) return null;
        if (view instanceof android.view.SurfaceView) return (android.view.SurfaceView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.SurfaceView found = findSurfaceView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void capturePictureFrame(java.util.function.Consumer<android.graphics.Bitmap> ready) {
        try {
            if (web != null && web.getWidth() > 0 && web.getHeight() > 0) {
                android.graphics.Picture picture = web.capturePicture();
                if (picture != null && picture.getWidth() > 0 && picture.getHeight() > 0) {
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                            picture.getWidth(), picture.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                    canvas.drawPicture(picture);
                    android.util.Log.d("LiveCache", "picture");
                    ready.accept(bitmap); return;
                }
            }
        } catch (Exception ignored) { }
        ready.accept(null);
    }

    private void captureWindowFrame(java.util.function.Consumer<android.graphics.Bitmap> ready) {
        if (destroyed) { ready.accept(null); return; }
        View decor = getWindow().getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) { ready.accept(null); return; }
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        try {
            android.view.PixelCopy.request(getWindow(), bitmap, result -> {
                if (result == android.view.PixelCopy.SUCCESS) ready.accept(bitmap);
                else { bitmap.recycle(); ready.accept(null); }
            }, new Handler(Looper.getMainLooper()));
        } catch (Exception e) {
            bitmap.recycle(); ready.accept(null);
        }
    }

    private void showStartupFrame() {
        if (startupFrameFile == null || !startupFrameFile.isFile()) return;
        android.util.Log.d("LiveCache", "show startup " + startupFrameFile.length());
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(startupFrameFile.getAbsolutePath());
        if (bitmap == null) return;
        if (invalidFrame(bitmap)) {
            bitmap.recycle(); startupFrameFile.delete(); return;
        }
        startupFrame = new android.widget.ImageView(this);
        startupFrame.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        startupFrame.setImageBitmap(bitmap);
        root.addView(startupFrame, new FrameLayout.LayoutParams(-1, -1));
    }

    private void fadeStartupFrame() {
        final android.widget.ImageView frame = startupFrame;
        if (frame == null) return;
        startupFrame = null;
        frame.animate().alpha(0f).setDuration(320).withEndAction(() -> root.removeView(frame)).start();
    }

    private void saveStartupFrame(android.graphics.Bitmap bitmap) {
        if (bitmap == null || startupFrameFile == null) return;
        android.util.Log.d("LiveCache", "check " + bitmap.getWidth() + "x" + bitmap.getHeight() + " invalid=" + invalidFrame(bitmap));
        if (invalidFrame(bitmap)) { bitmap.recycle(); return; }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(startupFrameFile)) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out);
            android.util.Log.d("LiveCache", "saved " + startupFrameFile.length());
        } catch (Exception e) {
            android.util.Log.d("LiveCache", "save failed " + e);
            // A stale or unwritable cache must never affect playback.
        } finally {
            bitmap.recycle();
        }
    }

    private boolean invalidFrame(android.graphics.Bitmap bitmap) {
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return true;
        int lit = 0, total = 0, first = 0, same = 0, green = 0;
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 24)) {
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 32)) {
                int color = bitmap.getPixel(x, y);
                if (total == 0) first = color;
                if (color == first) same++;
                int red = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, blue = color & 0xFF;
                if (g > red + 20 && g > blue + 20) green++;
                int luma = red * 30 + g * 59 + blue * 11;
                if (luma > 20 * 100) lit++;
                total++;
            }
        }
        return total == 0 || lit * 100 < total * 4 || same * 100 > total * 85
                || green * 100 > total * 72;
    }

    private void toggleDesktopModeFromSidebar() {
        if (destroyed) return;
        android.widget.Toast.makeText(this, DesktopMode.INSTANCE.toggleFromSidebar(this), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void openSystemSettingsPage() {
        if (destroyed) return;
        if (!DesktopMode.INSTANCE.openSystemSettings(this))
            android.widget.Toast.makeText(this, "此设备未提供可打开的系统设置页面", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void handleMediaBrowseIntent(android.content.Intent intent) {
        String tab = intent.getStringExtra("media_browse_tab");
        if (tab == null) return;
        intent.removeExtra("media_browse_tab");
        handler.post(() -> { if (!destroyed && "library".equals(tab)) openMediaLibrary(); });
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            ui.showLiveBrowser();ui.close();
            if(liveControls!=null)liveControls.hide();
        }
        if (android.content.Intent.ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    || intent.hasCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER))) {
            restoreLastScreen(intent);
        }
        handleMediaBrowseIntent(intent);
        handleSidebarIntent(intent);
    }

    private void restoreLastScreen(android.content.Intent entry) {
        if (entry.hasCategory(android.content.Intent.CATEGORY_HOME)
                || entry.hasExtra("media_browse_tab")
                || entry.getBooleanExtra("explicit_live_page", false)
                || AppSidebar.DESTINATION_LIVE.equals(entry.getStringExtra(AppSidebar.EXTRA_DESTINATION))) return;
        String lastScreen = getSharedPreferences("app-session", MODE_PRIVATE).getString("last_screen", "live");
        Class<?> target;
        if ("music".equals(lastScreen)) target = com.live.tv.music.MusicActivity.class;
        else if ("vod".equals(lastScreen)) target = com.live.tv.media.VodLibraryActivity.class;
        else return;
        startActivity(new android.content.Intent(this, target));
    }

    private void handleSidebarIntent(android.content.Intent intent) {
        if (!AppSidebar.DESTINATION_LIVE.equals(intent.getStringExtra(AppSidebar.EXTRA_DESTINATION))) return;
        intent.removeExtra(AppSidebar.EXTRA_DESTINATION);
        pendingSidebarLive = true;
        // Select the live surface now, but let onResume own restarting its decoder.
        if (foreground && !destroyed) { pendingSidebarLive = false; openLiveChannels(); }
    }

    @Override protected void onResume() {
        getSharedPreferences("app-session", MODE_PRIVATE).edit().putString("last_screen", "live").apply();
        super.onResume(); foreground=true;
        warmLibrary.run();
        if (liveRetained) {
            liveRetained=false;
            ui.resumeCard();
            if(web!=null)web.onResume();
            setLiveBackgroundMuted(false);
            syncLiveControls();
            if (pendingSidebarLive) { pendingSidebarLive=false; openLiveChannels(); }
            return;
        }
        setLiveBackgroundMuted(false);
        active=true;gate.setActive(true);
        ui.resumeCard();   // Resume sidebar inactivity and clock work.
        if(web!=null)web.onResume();
        // Only on a real return to the foreground: this branch also runs on the first resume, right after
        // onCreate has already started the stream, and it was killing a player that was still loading.
        if(resumed && streamMode && !playlist.isEmpty()) {
            // Rejoin the live edge after leaving the app. A channel that is playing natively on a
            // learned url has no urls of its own in the playlist - the page url sits there - so it must
            // be restarted from the learned one. Feeding the page url to the player was exactly the
            // -10104 seen in the logs.
            String resumeUrl = learnedWebIndex >= 0 ? learnedUrl(learnedWebIndex) : null;
            if (resumeUrl != null) {
                if (hlsPlayer != null) hlsPlayer.play(resumeUrl);
            } else {
                startStream(playlist.get(position));
            }
        } else if(resumed) {
            beginLoad(false); // Rejoin the live edge after leaving the app.
        }
        resumed=true;
        handler.removeCallbacks(poll);handler.post(poll);
        if (pendingSidebarLive) { pendingSidebarLive = false; openLiveChannels(); }
    }
    @Override protected void onPause() {
        foreground=false;
        handler.removeCallbacks(cacheLiveFrame);
        android.util.Log.d("LiveCache", "onPause revealed=" + revealed);
        if (revealed && !destroyed) captureLiveBitmap(bitmap -> {
            if (bitmap != null) { android.util.Log.d("LiveCache", "direct frame"); saveStartupFrame(bitmap); }
        });
        if (active && !destroyed) {
            liveRetained=true;
            setLiveBackgroundMuted(true);
            ui.pauseCardForNavigation();
            handler.removeCallbacks(hideInfo);
            ui.hideInfo();
            if (liveControls != null) liveControls.hide();
        } else releaseLiveSession();
        super.onPause();
    }

    @Override protected void onStop() {
        releaseLiveSession();
        super.onStop();
    }

    private void releaseLiveSession() {
        liveRetained=false;
        clearLivePause();
        cancelLiveRecovery();
        ownMode=false;
        active=false;gate.setActive(false);gate.begin();
        ui.pauseCard();    // Stop timed UI work while the app is in the background.
        handler.removeCallbacks(poll);handler.removeCallbacks(hideInfo);handler.removeCallbacks(dispatchSwitch);
        queued=false;busy=false;
        cover(); ui.close();
        committed=false;
        if(streamPlayer!=null)streamPlayer.stop(); // streamMode stays set so resume can restart it
        if(hlsPlayer!=null)hlsPlayer.stop();
        if(web!=null) {
            WebView old=web;web=null;
            root.removeView(old);old.stopLoading();old.onPause();old.destroy();
        }
    }
    private void showGlobalSearch() { if(globalSearch!=null) globalSearch.show(); }

    @Override protected void onDestroy() {
        if(menuKey!=null) menuKey.cancel();
        if(liveControls!=null)liveControls.dispose();
        destroyed=true;gate.setActive(false);handler.removeCallbacksAndMessages(null);ui.dispose();
        if(localCdn!=null){localCdn.stop();localCdn=null;}
        if(streamPlayer!=null){streamPlayer.release();streamPlayer=null;}
        if(web!=null){root.removeView(web);web.destroy();web=null;}
        super.onDestroy();
    }
    private byte[] readAsset(String name) throws java.io.IOException {
        try(java.io.InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return out.toByteArray();
        }
    }
}
