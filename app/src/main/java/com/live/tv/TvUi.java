package com.live.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.live.tv.music.catalog.CatalogTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Native presentation stays independent of playback and its loading lifecycle.
 *
 * <p>The black screen-edge menu contains the three fixed navigation destinations. Playback
 * controls own channel selection independently of this sidebar.
 */
public final class TvUi extends FrameLayout {
    public interface Actions {
        void select(int channel);
        void retry();
        void exit();
        default void live() {}
        default void library() {}
        default void desktopMode() {}
        default void settings() {}
        default void toggleLivePlayback() {}
        /**
         * Hands over the frame the picture is on right now, or null if it cannot be had. The stream keeps
         * playing - the sound must not stop - so the "pause" the viewer sees is this frame held over the
         * live picture, later dissolved back into it.
         */
        void captureFrame(java.util.function.Consumer<android.graphics.Bitmap> ready);
        /**
         * Takes the picture out of the composition while the code is up. The stream keeps playing - the
         * sound has to - but there is no reason for the compositor to keep blending a layer that the
         * frozen frame is covering anyway.
         */
        void setPictureHidden(boolean hidden);
    }

    private static final int INK = 0xFFF5F5F7;
    private static final int SECONDARY = 0xFFB8BBC2;
    private static final int BASE = 0xFF080A0E;
    private static final int LIVE = 0xFF76D9A5;
    /** Header type: a dimmed white that lifts to pure white when its item is selected. */
    private static final int HEADER_DIM = 0xB3FFFFFF;
    // Scale the compact composition from the 1920x1080 reference viewport.
    // Compact screen-edge composition follows IMG_9254.JPG.
    private static final float MENU_REFERENCE_HEIGHT = 1080f;

    private final FrameLayout curtain, browser;
    private View browserScrim;
    private long curtainGeneration;
    private final StartupCurtainView curtainSurface;
    private ValueAnimator curtainAnimator;
    private float curtainProgress;
    private boolean startupRevealPending = true, curtainMorphing, openingFromCurtain, cardPaused;
    private com.live.tv.media.LivePlaybackControls livePlaybackControls;
    private boolean disposed, navigationOnly, navigationClosePending;
    private Runnable onNavigationClosed;
    private static final long SIDEBAR_IDLE_MS = 10_000;
    private final Runnable hideIdleSidebar = this::hideIdleSidebar;

    private void hideIdleSidebar() {
        if (menuOpen && !cardPaused && curtain.getVisibility() != VISIBLE) close();
    }
    private final LinearLayout hud;
    private final LinearLayout info;
    private final TextView loadingChannel, loadingName, loadingStatus;
    private static final String[] NAVIGATION_LABELS = {"直播", "流媒体", "桌面模式", "设置"};
    private final LinearLayout navigation;
    private final SidebarNavTextView[] navigationItems = new SidebarNavTextView[NAVIGATION_LABELS.length];
    private int focusedNavigation;
    private final FrameLayout edgeMenu;
    private ValueAnimator menuAnimator;
    private int menuMotionGeneration;
    private float edgeReveal;

    private int panelLayoutWidth, panelLayoutHeight;
    private final Actions actions;

    private boolean menuOpen;
    /** Production playback has no sidebar; tests for the legacy navigation layer keep it enabled. */
    private boolean sidebarEnabled = true;
    /** A playback retry may clear automatic startup navigation, but never a user's navigation. */
    private boolean userNavigation;
    private int playingIndex = -1;
    /** Current playback identity is retained for loading/HUD presentation, not sidebar navigation. */
    private int currentIndex;
    private List<Channel> playlist = new ArrayList<>();

    private LinearLayout loadingBox;

    public TvUi(Context context, Actions callbacks) {
        super(context);
        actions = callbacks;
        setClipChildren(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        // This full-screen input target must never tint the programme when menu focus returns here.
        setDefaultFocusHighlightEnabled(false);

        curtain = new FrameLayout(context);
        curtain.setBackground(null);
        curtain.setClickable(true);
        addView(curtain, new LayoutParams(-1, -1));
        curtainSurface = new StartupCurtainView(context);
        curtain.addView(curtainSurface, new LayoutParams(-1, -1));
        LinearLayout waiting = new LinearLayout(context);
        loadingBox = waiting;
        waiting.setOrientation(LinearLayout.VERTICAL);
        waiting.setGravity(Gravity.CENTER);
        loadingChannel = text("", 54, INK, true);
        loadingChannel.setLetterSpacing(-.045f);
        loadingChannel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(-2, -2);
        waiting.addView(loadingChannel, big);
        loadingName = text("", 20, SECONDARY, false);
        loadingName.setGravity(Gravity.CENTER);
        waiting.addView(loadingName);
        // Only failures need extra text; ordinary loading is black with the channel identity.
        loadingStatus = text("", 13, SECONDARY, false);
        loadingStatus.setGravity(Gravity.CENTER);
        loadingStatus.setVisibility(GONE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.topMargin = dp(28);
        waiting.addView(loadingStatus, statusParams);
        curtain.addView(waiting, new LayoutParams(-2, -2, Gravity.CENTER));

        hud = new LinearLayout(context);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setGravity(Gravity.RIGHT);
        LayoutParams hp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        hp.rightMargin = dp(40);
        hp.bottomMargin = dp(34);
        addView(hud, hp);

        // A green live mark, flat type and a shadow over the picture.
        info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.RIGHT);
        hud.addView(info);
        info.setVisibility(GONE);

        browser = new FrameLayout(context);
        // Deliberately no scrim: the launcher's list pages keep the picture at full brightness, so
        // only the cards themselves are translucent.
        browser.setVisibility(GONE);
        browser.setClickable(true);
        addView(browser, new LayoutParams(-1, -1));

        // Composite the small sidebar as one bitmap so focus repaints keep every label.
        edgeMenu = new FrameLayout(context) {
            @Override protected void dispatchDraw(Canvas canvas) { drawEdgeMenu(canvas); }
        };
        edgeMenu.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        browser.addView(edgeMenu, new LayoutParams(dp(92), -1, Gravity.LEFT));
        // The sidebar's black surface is one native path attached to the screen edge.
        View scrim = new EdgePanelView(context);
        browserScrim = scrim;
        edgeMenu.addView(scrim, new LayoutParams(-1, -1));

        navigation = new LinearLayout(context);
        navigation.setOrientation(LinearLayout.VERTICAL);
        navigation.setClipChildren(false);
        navigation.setClipToPadding(false);
        edgeMenu.addView(navigation, new LayoutParams(-1, dp(84), Gravity.TOP | Gravity.LEFT));
        for (int index = 0; index < navigationItems.length; index++) {
            final int destination = index;
            SidebarNavTextView item = new SidebarNavTextView(context, NAVIGATION_LABELS[index]);
            item.setId(View.generateViewId());
            item.setOnFocusChangeListener((view, focused) -> {
                if (focused) focusedNavigation = destination;
                edgeMenu.invalidate();
            });
            item.setOnClickListener(view -> activateNavigation(destination));
            navigationItems[index] = item;
            navigation.addView(item, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        for (int index = 0; index < navigationItems.length; index++) {
            SidebarNavTextView item = navigationItems[index];
            item.setNextFocusUpId(navigationItems[Math.floorMod(index - 1, navigationItems.length)].getId());
            item.setNextFocusDownId(navigationItems[(index + 1) % navigationItems.length].getId());
            item.setNextFocusLeftId(item.getId());
            item.setNextFocusRightId(item.getId());
        }
        browser.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> updatePanelLayout());
        setPlaylist(defaultPlaylist(), false);
        updatePanelPresentation();
    }

    /** The same navigation surface can sit over another Activity without owning its player. */
    public void setNavigationOnly(boolean value) {
        navigationOnly = value;
        if (!value) return;
        startupRevealPending = false;
        cancelCurtainExit();
        curtain.setVisibility(GONE);
        loadingBox.setVisibility(GONE);
    }

    public void setOnNavigationClosed(Runnable callback) { onNavigationClosed = callback; }

    public void openNavigation(int destination) {
        setNavigationOnly(true);
        open(-1, false);
        focusedNavigation = Math.max(0, Math.min(navigationItems.length - 1, destination));
        focusNavigation();
    }

    private void navigationClosed() {
        if (!navigationOnly || !navigationClosePending || menuOpen || browser.getVisibility() == VISIBLE) return;
        navigationClosePending = false;
        if (onNavigationClosed != null) onNavigationClosed.run();
    }

    /** Playback owns when its controls open; the sidebar only provides the shared layer/lifecycle. */
    public void attachLivePlaybackControls(com.live.tv.media.LivePlaybackControls controls) {
        if (livePlaybackControls == controls) return;
        if (livePlaybackControls != null) removeView(livePlaybackControls);
        livePlaybackControls = controls;
        if (controls != null) addView(controls, new LayoutParams(-1, -1));
        updatePlaybackVisibility();
    }

    private void updatePlaybackVisibility() {
        if (browser != null && curtain != null && livePlaybackControls != null) {
            livePlaybackControls.setSuppressed(disposed || cardPaused
                    || !isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE
                    || menuOpen || browser.getVisibility() == VISIBLE);
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updatePlaybackVisibility();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updatePlaybackVisibility();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updatePlaybackVisibility();
    }

    /** Live owns the edge menu; no secondary browsing surface is mounted. */
    private void updatePanelPresentation() {
        browser.setVisibility(menuOpen ? VISIBLE : GONE);
        edgeMenu.setVisibility(VISIBLE);
        browserScrim.setVisibility(VISIBLE);
        navigation.setVisibility(VISIBLE);
        panelLayoutWidth = -1;
        updatePanelLayout();
        updatePlaybackVisibility();
    }

    private void updatePanelLayout() {
        int width = browser.getWidth() > 0 ? browser.getWidth() : dp(960);
        int height = browser.getHeight() > 0 ? browser.getHeight() : dp(540);
        if (panelLayoutWidth == width && panelLayoutHeight == height) return;
        panelLayoutWidth = width; panelLayoutHeight = height;
        int edgeWidth = Math.round(width * .07f);
        edgeMenu.setLayoutParams(new LayoutParams((int)Math.ceil(edgeWidth * 1.12f), -1, Gravity.LEFT));
        browserScrim.setLayoutParams(new LayoutParams(edgeWidth, -1, Gravity.LEFT));
        int navigationHeight = NAVIGATION_LABELS.length * Math.max(1, Math.round(height * 56f / MENU_REFERENCE_HEIGHT));
        LayoutParams navigationLp = new LayoutParams(edgeWidth, navigationHeight, Gravity.TOP | Gravity.LEFT);
        navigationLp.topMargin = (height - navigationHeight) / 2;
        navigation.setLayoutParams(navigationLp);
    }

    private void focusNavigation() {
        navigationItems[focusedNavigation].requestFocusFromTouch();
    }

    private void activateNavigation(int destination) {
        if (!menuOpen || cardPaused || disposed) return;
        close();
        switch (destination) {
            case 0: actions.live(); break;
            case 1: actions.library(); break;
            case 2: actions.desktopMode(); break;
            default: actions.settings(); break;
        }
    }

    public void showLiveBrowser() {
        if (!menuOpen) open(currentIndex, playingIndex >= 0);
        focusNavigation();
        sidebarInteracted();
    }

    private static List<Channel> defaultPlaylist() {
        List<Channel> list = new ArrayList<>();
        for (int i = 0; i < Channels.IDS.length; i++) list.add(Channel.web(i));
        return list;
    }

    /** Header commands, shared by the icons and the remote so each is defined once. */
    private void exitMenu() {
        actions.exit();
    }

    // ---------------------------------------------------------------- playback identity

    public void setPlaylist(List<Channel> list, boolean invalid) {
        if (list != null) playlist = list;
        if (playlist.isEmpty()) playlist = defaultPlaylist();
    }

    /** One complete raster pass owns the spring, navigation labels and static display items. */
    private void drawEdgeMenu(Canvas canvas) {
        if (edgeReveal <= .001f) return;
        int frame = canvas.save();
        float stretchY = edgeReveal <= 1 ? .92f + .08f * edgeReveal : 1 - .16f * (edgeReveal - 1);
        canvas.scale(edgeReveal, stretchY, 0, edgeMenu.getHeight() / 2f);
        if (!curtainMorphing) drawEdgeChild(canvas, browserScrim);
        float opacity = smoothStep((edgeReveal - .45f) / .55f);
        if (curtainMorphing) opacity *= smoothStep((curtainProgress - .78f) / .22f);
        int content = canvas.saveLayerAlpha(0, 0, edgeMenu.getWidth(), edgeMenu.getHeight(),
                Math.round(255 * opacity));
        drawEdgeChild(canvas, navigation);
        canvas.restoreToCount(content);
        canvas.restoreToCount(frame);
    }

    private void drawEdgeChild(Canvas canvas, View child) {
        if (child == null || child.getVisibility() != VISIBLE) return;
        int save = canvas.save();
        canvas.translate(child.getLeft(), child.getTop());
        child.draw(canvas);
        canvas.restoreToCount(save);
    }

    /** Compatibility entry points: the shared QR is now a display-only part of the live menu. */
    public boolean exitScan() { return false; }
    public boolean hasOverlay() { return false; }
    public boolean isScanning() { return false; }

    /** Stop timed UI work while the app is in the background. */
    public void pauseCard() {
        pauseCard(false);
    }

    /** Keep a readiness callback alive while a browsing page covers the live window. */
    public void pauseCardForNavigation() {
        pauseCard(true);
    }

    private void pauseCard(boolean retainPlayback) {
        cardPaused = true;
        removeCallbacks(hideIdleSidebar);
        if (!retainPlayback) cancelCurtainExit();
        stopMenuMotion();
        updatePlaybackVisibility();
    }

    public void resumeCard() {
        // A warm return to live TV is an app entry too.
        if (cardPaused && curtain.getVisibility() == VISIBLE) startupRevealPending = true;
        cardPaused = false;
        sidebarInteracted();
        updatePlaybackVisibility();
    }

    /** Explicit channel selection takes precedence over this entry's automatic sidebar. */
    public void suppressStartupSidebar() {
        startupRevealPending = false;
        // A morph already accepted by the playback gate must still finish its readiness callback.
        // Its normal close animation can dismiss navigation without cancelling that completion.
    }

    @Override protected void onDetachedFromWindow() {
        cardPaused = true;
        removeCallbacks(hideIdleSidebar);
        cancelCurtainExit();
        stopMenuMotion();
        updatePlaybackVisibility();
        super.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------- overlay lifecycle

    /** A previous channel's exit must never remove the current channel's loading cover. */
    private void cancelCurtainExit() {
        boolean wasMorphing = curtainMorphing;
        curtainGeneration++;
        if (curtainAnimator != null) {
            curtainAnimator.removeAllListeners(); curtainAnimator.removeAllUpdateListeners();
            curtainAnimator.cancel(); curtainAnimator = null;
        }
        curtainMorphing = false;
        if (wasMorphing && curtain.getVisibility() == VISIBLE) {
            curtainSurface.setProgress(0);
            curtainSurface.setHorizontalReveal(1);
            loadingBox.setAlpha(1);
            loadingBox.setTranslationX(0);
            loadingBox.setVisibility(VISIBLE);
        }
        edgeMenu.invalidate();
    }

    public void loading(int channel) {
        if (navigationOnly) return;
        boolean interruptedStartup = curtainMorphing;
        playingIndex = channel;
        cancelCurtainExit();
        if (interruptedStartup && !userNavigation) {
            close();
            cancelMenuAnimator();
            edgeReveal = 0;
            browser.setVisibility(GONE);
        }
        removeCallbacks(hideIdleSidebar);
        curtain.animate().cancel();
        curtain.setAlpha(1);
        curtainSurface.setProgress(0);
        curtainSurface.setHorizontalReveal(1);
        curtain.setVisibility(VISIBLE);
        loadingBox.setVisibility(VISIBLE);
        loadingBox.setAlpha(1);
        loadingBox.setTranslationX(0);
        loadingChannel.setText(code(channel));
        loadingName.setText(name(channel));
        loadingStatus.setVisibility(GONE);
        info.animate().cancel();
        info.setVisibility(GONE);
        updatePlaybackVisibility();
    }

    public void error() {
        loadingStatus.setText("暂时无法播放 · 按确认键重试或换台");
        loadingStatus.setVisibility(VISIBLE);
    }

    public void buffering() {
        loadingStatus.setText("正在缓冲，请稍候…");
        loadingStatus.setVisibility(VISIBLE);
    }

    /** Music handoff clears the cover without reopening the live sidebar. */
    public void revealPlain(Runnable finished) {
        cancelCurtainExit();
        curtain.animate().cancel();
        loadingBox.setVisibility(GONE);
        curtain.setVisibility(GONE);
        updatePlaybackVisibility();
        sidebarInteracted();
        finished.run();
    }

    /** Called only after the native first-frame / playback gate has accepted this attempt. */
    public void reveal(Runnable finished) {
        if (curtainAnimator != null) return; // Duplicate readiness must not restart a live morph.
        if (cardPaused) { startupRevealPending = false; revealPlain(finished); return; }
        if (!startupRevealPending || curtain.getVisibility() != VISIBLE) { revealPlain(finished); return; }
        cancelCurtainExit();
        final long generation = curtainGeneration;
        curtainMorphing = true;
        curtainProgress = 0;
        openingFromCurtain = true;
        try { if (sidebarEnabled) open(playingIndex, true); } finally { openingFromCurtain = false; }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishStartupReveal(finished);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        curtainAnimator = animator;
        animator.setDuration(1750);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(value -> {
            curtainProgress = (float)value.getAnimatedValue();
            curtainSurface.setProgress(curtainProgress);
            curtainSurface.setHorizontalReveal(edgeReveal);
            float identityExit = smoothStep((curtainProgress - .06f) / .36f);
            loadingBox.setAlpha(1 - identityExit);
            loadingBox.setTranslationX(0);
            edgeMenu.invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator ignored) {
                if (generation != curtainGeneration || curtainAnimator != animator) return;
                curtainAnimator = null;
                finishStartupReveal(finished);
            }
        });
        animator.start();
    }

    private void finishStartupReveal(Runnable finished) {
        startupRevealPending = false;
        curtainMorphing = false;
        curtainProgress = 1;
        curtainSurface.setProgress(1);
        loadingBox.setVisibility(GONE);
        curtain.setVisibility(GONE);
        edgeMenu.invalidate();
        updatePlaybackVisibility();
        sidebarInteracted();
        finished.run();
    }

    /** Real input extends the live sidebar lease; polling and focus repainting do not. */
    private void sidebarInteracted() {
        removeCallbacks(hideIdleSidebar);
        if (menuOpen && !cardPaused && curtain.getVisibility() != VISIBLE)
            postDelayed(hideIdleSidebar, SIDEBAR_IDLE_MS);
    }

    /** LAN commands are user input too; state polling never calls this entry point. */
    public void interacted() { sidebarInteracted(); }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (menuOpen) sidebarInteracted();
        return super.dispatchTouchEvent(event);
    }

    public void showInfo(int channel) {
        if (menuOpen) return;
        info.removeAllViews();
        // The dot is the app's live mark; the label matches the HUD's type size, and everything
        // carries a shadow because it now sits straight on the picture.
        TextView live = text("●  正在直播", 13, LIVE, true);
        live.setGravity(Gravity.RIGHT);
        live.setShadowLayer(dp(6), 0, dp(1), 0xE6000000);
        info.addView(live);
        int index = Math.min(Math.max(channel, 0), Math.max(playlist.size() - 1, 0));
        TextView title = text(playlist.isEmpty() ? "" : playlist.get(index).name, 24, INK, true);
        title.setGravity(Gravity.RIGHT);
        title.setShadowLayer(dp(8), 0, dp(2), 0xF0000000);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(5);
        info.addView(title, tp);
        info.animate().cancel();
        info.setAlpha(0);
        info.setVisibility(VISIBLE);
        info.animate().alpha(1).setDuration(duration(180)).start();
    }

    public void hideInfo() {
        info.animate().alpha(0).setDuration(duration(180))
                .withEndAction(() -> info.setVisibility(GONE)).start();
    }

    public boolean dismissInfo() {
        if (info.getVisibility() != VISIBLE) return false;
        info.animate().cancel();
        info.setVisibility(GONE);
        return true;
    }

    public void setSidebarEnabled(boolean enabled) {
        sidebarEnabled = enabled;
        if (!enabled) close();
    }

    /** Opens the existing sidebar with television as its first navigation destination. */
    public void open(int current, boolean playing) {
        if (!sidebarEnabled) return;
        navigationClosePending = false;
        boolean keepDestination = openingFromCurtain && menuOpen;
        if (!openingFromCurtain) userNavigation = true;
        cancelMenuAnimator();
        menuOpen = true;
        currentIndex = current;
        if (current >= 0) playingIndex = current;
        if (!keepDestination) focusedNavigation = 0;
        info.setVisibility(GONE);
        updatePanelPresentation();
        browser.animate().cancel();
        browser.setVisibility(VISIBLE);
        browser.setAlpha(1);
        if (openingFromCurtain) { edgeReveal = 1; edgeMenu.invalidate(); }
        else animateMenu(true);
        sidebarInteracted();
        post(() -> { if (!menuOpen) return; focusNavigation(); });
    }

    public void close() {
        if (menuOpen) navigationClosePending = true;
        removeCallbacks(hideIdleSidebar);
        boolean animate = browser.getVisibility() == VISIBLE;
        menuOpen = false;
        userNavigation = false;
        browser.animate().cancel();
        if (animate) animateMenu(false);
        else { cancelMenuAnimator(); edgeReveal = 0; browser.setVisibility(GONE); }
        requestFocus();
        updatePlaybackVisibility();
        navigationClosed();
    }

    private void cancelMenuAnimator() {
        ++menuMotionGeneration;
        if (menuAnimator == null) return;
        menuAnimator.removeAllListeners(); menuAnimator.removeAllUpdateListeners();
        menuAnimator.cancel(); menuAnimator = null;
    }

    private void animateMenu(boolean opening) {
        float from = edgeReveal;
        cancelMenuAnimator();
        int generation = menuMotionGeneration;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            edgeReveal = opening ? 1 : 0;
            if (!opening) { browser.setVisibility(GONE); }
            edgeMenu.invalidate();
            updatePlaybackVisibility();
            if (!opening) navigationClosed();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        menuAnimator = animator;
        animator.setDuration(opening ? 480 : 280);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(value -> {
            float progress = (float)value.getAnimatedValue();
            if (opening) {
                double response = 1 - Math.exp(-5.5 * progress) * (Math.cos(6.1 * progress)
                        + 5.5 / 6.1 * Math.sin(6.1 * progress));
                edgeReveal = from + (1 - from) * (float)response;
            } else {
                edgeReveal = from * (1 - smoothStep(progress)
                        + .025f * (float)(Math.sin(Math.PI * progress) * Math.exp(-6 * progress)));
            }
            edgeMenu.invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator ignored) {
                if (generation != menuMotionGeneration || menuAnimator != animator) return;
                edgeReveal = opening ? 1 : 0;
                menuAnimator = null;
                if (!opening && !menuOpen) { browser.setVisibility(GONE); }
                edgeMenu.invalidate();
                updatePlaybackVisibility();
                if (!opening) navigationClosed();
            }
        });
        animator.start();
    }

    private void stopMenuMotion() {
        cancelMenuAnimator();
        edgeReveal = menuOpen ? 1 : 0;
        if (!menuOpen) { browser.setVisibility(GONE); }
        edgeMenu.invalidate();
        updatePlaybackVisibility();
    }

    private static float smoothStep(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    public boolean isOpen() {
        return menuOpen;
    }

    /** The closing animation still owns remote input until its visible navigation layer is gone. */
    public boolean isNavigationVisible() {
        return browser.getVisibility() == VISIBLE;
    }

    /** Up/down follows the three fixed destinations. */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (menuOpen && event.getAction() == android.view.KeyEvent.ACTION_DOWN) sidebarInteracted();
        int key = event.getKeyCode();
        if (menuOpen && (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                navigationItems[focusedNavigation].performClick();
            return true;
        }
        if (!menuOpen || event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return true;
        if (key == android.view.KeyEvent.KEYCODE_DPAD_UP
                || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            userNavigation = true;
            int step = key == android.view.KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
            navigationItems[Math.floorMod(focusedNavigation + step, navigationItems.length)].requestFocusFromTouch();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public void dispose() {
        disposed = true;
        onNavigationClosed = null;
        cardPaused = true;
        updatePlaybackVisibility();
        removeCallbacks(hideIdleSidebar);
        stopMenuMotion();
        cancelCurtainExit();
        curtain.animate().cancel();
        browser.animate().cancel();
        info.animate().cancel();
    }

    // ---------------------------------------------------------------- helpers

    private TextView text(String value, float size, int color, boolean medium) {
        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private static long duration(long ms) {
        return ValueAnimator.areAnimatorsEnabled() ? ms : 0;
    }

    private String code(int index) {
        if (index < 0 || index >= playlist.size()) return "";
        Channel channel = playlist.get(index);
        if (channel.kind == Channel.WEB) return split(channel.name)[0];
        return channel.group;
    }

    private String name(int index) {
        if (index < 0 || index >= playlist.size()) return "";
        Channel channel = playlist.get(index);
        if (channel.kind == Channel.WEB) return split(channel.name)[1];
        return channel.name;
    }

    private static String[] split(String value) {
        String[] parts = value.split(" ", 2);
        return parts.length == 2 ? parts : new String[]{value, ""};
    }
}
