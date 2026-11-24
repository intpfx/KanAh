package com.live.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * The light-dust QR: thousands of GPU points that fly out of a drifting cloud, gather into this exact
 * matrix, and then stay there. The dust keeps moving; the shape does not.
 *
 * <p>Ported from a WebGL study so the TV owns the effect outright: no WebView, no second browser, no
 * asset to regenerate when the address changes. Everything that was a knob there is a constant here
 * (see the TUNABLES block) - brightness, particle count and the bloom weights are the ones that
 * decide whether a phone can read it, so they are the ones worth touching.
 *
 * <p>A {@link TextureView} rather than a GLSurfaceView on purpose: this project verifies its UI with
 * {@code screencap}, and a SurfaceView's contents do not come back in those captures.
 */
public final class LightDustView extends TextureView implements TextureView.SurfaceTextureListener {

    // ---------------------------------------------------------------- tunables
    /** Particles on the code's dark modules; more means denser, brighter cells for a camera to sample. */
    private static final int PARTICLES = 20000;
    /** Free-floating dust that never joins the code, so the field never looks empty. */
    private static final int AMBIENT = 600;
    /** Seconds for the cloud to become the code. After this the shape is permanent. */
    private static final float GATHER_SECONDS = 2.3f;
    /** Backing-store scale. 1.0 renders at the view's own pixel size; lower is cheaper and softer. */
    /**
     * The whole effect is rendered at this fraction of the view and scaled up on composite. It is the
     * cheapest lever there is - cost falls with its square, and a glow loses almost nothing: at 1.0 the
     * frame measured 35.9 ms (27 fps), which is not smooth next to 60 Hz video.
     */
    private static final float RENDER_SCALE = 1.0f;
    /** Bloom weights. Wide and mist lift the light modules too, which costs contrast. */
    private static final float BLOOM_FINE = 1.0f, BLOOM_WIDE = 1.45f;
    /** The widest bloom level is no longer rendered; see the note in render(). */
    private static final float BLOOM_MIST = 0f;
    private static final float TONE_MAP = 2.15f;
    /**
     * Cycle mode, for the waiting mark on the loading screen: the shape gathers, holds, comes apart and
     * gathers again. The reference does the same thing inside its 18 second loop; here it is a switch so
     * the code on the scan layer can keep holding instead.
     */
    private static final float CYCLE_HOLD = 5.4f, CYCLE_DISSOLVE = 1.8f, CYCLE_LENGTH = 7.8f;
    /** How long the way out takes: apart into the cloud, then away past the edges. */
    private static final float EXIT_SECONDS = 1.9f;
    /** Packets released along the outline per second, and how many may be alive at once. */
    /** The reference bakes 22 bursts, drawn over the four sides in this order. */
    private static final int EVENT_COUNT = 22, GRAINS_PER_EVENT = 280;
    /** The shader only ever lifts the rim for the eight most relevant bursts. */
    private static final int MAX_EVENTS = 8;
    /**
     * One knob for the leap: it scales the rim lift, the ripple and the sparks together. Measured on
     * the TV, 1.0 costs about 4 points of module contrast (46% -> 42%), so turning it down is the
     * first thing to try if the code ever stops scanning.
     */
    private static final float CORONA_STRENGTH = 1.0f;
    /**
     * The plumes' own gain. The reference's body is about half as bright as ours - ours was raised so a
     * camera could read the code - which left the (untouched) plumes looking faint by comparison. This
     * restores the balance the reference has between the code and its prominences.
     */
    private static final float FLARE_GAIN = 2.4f;
    /**
     * The plumes were over almost as soon as they appeared. Both dials matter: the burst lives longer,
     * and each grain takes longer to fly its arc, so the loop reads as a plume rising and falling rather
     * than a flicker at the edge.
     */
    private static final float BURST_LENGTH = 1.55f;
    private static final float FLIGHT_LENGTH = 1.5f;
    /**
     * Backing for the code lives in the view's background, not in this shader: a TextureView's buffer
     * alpha is not honoured the way a normal layer's is (measured: a 0.62 scrim darkened the picture
     * by only 9%), so a dark value written here would simply be thrown away.
     */
    private static final float SCRIM_ALPHA = 0f;
    private static final float SCRIM_FALLOFF = 2.1f;

    private static final String TAG = "LIGHTDUST";

    private boolean[][] matrix;
    private volatile boolean pendingMatrix;

    private Thread thread;
    private volatile boolean running;
    private SurfaceTexture surface;
    private volatile boolean surfaceReady;
    private volatile int viewWidth, viewHeight;
    private volatile boolean released;
    private volatile long frames;
    /**
     * When false the loop keeps its GL context but stops drawing. It starts false: nothing is on
     * screen until the reveal is asked for, so an idle menu costs no GPU at all.
     */
    private volatile boolean active = false;
    /** Set to replay the reveal: the clock goes back to zero, which is the loose cloud. */
    private volatile boolean replay;
    private volatile boolean cycle;
    /** Scales the rim leap. A thin ring is all rim, and erupting along all of it tears it apart. */
    private volatile float coronaScale = 1f;
    /** Cloud only: never gathers into a shape. This is the waiting screen, not a reveal. */
    private volatile boolean cloudOnly;
    /** Scatters the cloud across the frame instead of parking it outside the edges. */
    private volatile boolean cloudInside;
    /** How far outside the cloud starts before it drifts in; 1 draws it in place. */
    private volatile float enterFrom = 1f;
    /** How much of the view the shape spans; the wait mark is a small ring, the code fills the frame. */
    private float formation = 1.46f;
    private volatile boolean formationDirty;
    /** Exit progress, 0 = fully formed code, 1 = the dust has left the screen. */
    private float exit;
    private volatile boolean exiting;
    /** Told when the exit animation is over, so the host can take the layer down. */
    private volatile Runnable exitFinished;
    /** Corner progress, visible while the code is on screen and after it has formed. */

    public LightDustView(Context context) {
        super(context);
        // Not opaque: the composite pass emits premultiplied light over an empty background, so the
        // code can sit straight on the picture.
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    /** Hands the page its code. The caller encodes; this only draws. */
    public void setMatrix(boolean[][] modules) {
        synchronized (this) {
            matrix = modules;
            pendingMatrix = true;
        }
    }

    public void start() {
        if (released) return;
        running = true;
        // stop() lets the thread finish, so a plain null check would never restart it and the card
        // would stay frozen after the first pause/resume.
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::renderLoop, "light-dust");
            thread.start();
        }
    }

    public void stop() {
        running = false;
    }

    /**
     * Starts the reveal over. The particles keep their cloud positions in the buffer, so resetting the
     * clock is all it takes to fly them into the code again.
     */
    public void replay() {
        replay = true;
        active = true;
        start();   // a thread that stop() let finish has to be brought back as well
    }

    /** Hidden: stop drawing entirely rather than burning a core behind an invisible card. */
    /**
     * Plays the way out: the code comes apart into the cloud and the cloud keeps travelling until it
     * has left the screen. The listener runs on the UI thread once that is done, which is the moment
     * to take the layer down - hiding it earlier would just make the dust vanish.
     */
    public void playExit(Runnable finished) {
        exitFinished = finished;
        exit = 0f;
        exiting = true;
        start();
    }

    /** Gather, hold, dissolve, repeat - for a shape that is watched while something loads. */
    public void setCycle(boolean on) {
        cycle = on;
    }

    /** 0 leaves the rim calm; 1 is the full leap. */
    public void setCoronaScale(float scale) {
        coronaScale = Math.max(0f, Math.min(1.6f, scale));
    }

    /** Keeps the dust as a drifting cloud: no gathering, so nothing ever forms. */
    public void setCloudOnly(boolean on) {
        cloudOnly = on;
    }

    /** Scatters the dust over the whole frame, and rebuilds on the next frame. */
    public void setCloudInside(boolean on) {
        if (cloudInside == on) return;
        cloudInside = on;
        formationDirty = true;
        start();
    }

    /** The cloud starts this many times further out and drifts in over the first second. */
    public void setEnterFrom(float scale) {
        enterFrom = Math.max(1f, scale);
    }

    /** Sets how much of the view the shape spans, and rebuilds on the next frame. */
    public void setFormationScale(float scale) {
        float next = Math.max(0.12f, scale);
        if (Math.abs(next - formation) < 0.001f) return;
        formation = next;
        formationDirty = true;
        start();
    }

    public void idle() {
        active = false;
    }

    /** Frames drawn so far; the only cheap way to tell the loop is alive from a log. */
    public long frames() { return frames; }

    public void release() {
        released = true;
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    // ---------------------------------------------------------------- TextureView plumbing

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = st;
        viewWidth = w;
        viewHeight = h;
        surfaceReady = true;
        start();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        viewWidth = w;
        viewHeight = h;
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        surface = null;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {
        // Frames arrive on our own thread; nothing to do here.
    }

    // ---------------------------------------------------------------- render thread

    private void renderLoop() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed");
            return;
        }
        int[] configAttrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            Log.e(TAG, "no EGL config");
            return;
        }
        EGLConfig config = configs[0];
        EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed");
            return;
        }

        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        SurfaceTexture bound = null;
        Surface window = null;
        Program points = null, flares = null, blur = null, composite = null;
        Scene scene = null;
        FloatBuffer quad = quadBuffer();
        long fpsAt = System.nanoTime();
        long busyNanos = 0L;
        long startNanos = System.nanoTime();

        while (running && !released) {
            if (!active) {
                sleep(80);      // context stays current; nothing is drawn while the card is hidden
                continue;
            }
            if (!surfaceReady || surface == null) {
                sleep(60);
                continue;
            }
            // (Re)create the window surface whenever the TextureView hands us a new one.
            if (eglSurface == EGL14.EGL_NO_SURFACE || bound != surface) {
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface);
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (window != null) window.release();
                window = new Surface(surface);
                int[] surfaceAttrs = {EGL14.EGL_NONE};
                eglSurface = EGL14.eglCreateWindowSurface(display, config, window, surfaceAttrs, 0);
                bound = surface;
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "eglCreateWindowSurface failed");
                    sleep(200);
                    continue;
                }
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    Log.e(TAG, "eglMakeCurrent failed");
                }
                points = new Program(POINT_VS, POINT_FS, "points");
                flares = new Program(FLARE_VS, FLARE_FS, "flares");
                blur = new Program(QUAD_VS, BLUR_FS, "blur");
                composite = new Program(QUAD_VS, COMPOSITE_FS, "composite");
                startNanos = System.nanoTime();
                Log.d(TAG, "gl ready: " + GLES20.glGetString(GLES20.GL_VERSION));
            }

            int w = viewWidth, h = viewHeight;
            if (w <= 0 || h <= 0) { sleep(30); continue; }
            long frameStart = System.nanoTime();
            // The view is already in device pixels; RENDER_SCALE trades sharpness for fill rate.
            int pw = Math.max(2, Math.round(w * RENDER_SCALE));
            int ph = Math.max(2, Math.round(h * RENDER_SCALE));
            // A new scene has no particle buffer, and a new surface (after pause/resume) always makes
            // a new scene: rebuild from the matrix we are still holding, or the card comes back empty.
            boolean rebuild = false;
            if (scene == null || scene.width != pw || scene.height != ph) {
                if (scene != null) scene.dispose();
                scene = new Scene(pw, ph, w, h, points);
                rebuild = true;
            }
            synchronized (this) {
                if (pendingMatrix) { pendingMatrix = false; rebuild = true; }
            }
            synchronized (this) {
                if (formationDirty) { formationDirty = false; rebuild = true; }
            }
            if (rebuild) scene.buildParticles(matrix, Math.max(1f, (float) pw / Math.max(1, ph)), formation, cloudInside);

            if (replay) {
                replay = false;
                exiting = false;
                exit = 0f;
                startNanos = System.nanoTime();   // back to the cloud, then gather again
            }
            float t = (System.nanoTime() - startNanos) / 1e9f;
            if (cycle && !exiting && t > CYCLE_LENGTH) {
                startNanos = System.nanoTime();   // straight into the next pass
                t = 0f;
            }
            if (exiting) {
                // The exit is its own clock: the reveal time is left alone so a replay still starts
                // from the cloud.
                exit = Math.min(1f, exit + 1f / (EXIT_SECONDS * 60f));
                if (exit >= 1f) {
                    exiting = false;
                    active = false;               // nothing left on screen; stop drawing
                    Runnable done = exitFinished;
                    if (done != null) post(done);
                }
            }
            scene.cycle = cycle;
            scene.coronaScale = coronaScale;
            scene.enterFrom = enterFrom;
            scene.cloudOnly = cloudOnly;
            scene.render(points, flares, blur, composite, quad, t, exit);
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                Log.w(TAG, "eglSwapBuffers failed: " + EGL14.eglGetError());
                eglSurface = EGL14.EGL_NO_SURFACE;
                continue;
            }
            frames++;
            long now = System.nanoTime();
            busyNanos += now - frameStart;
            if (now - fpsAt > 10_000_000_000L) {
                Log.d(TAG, "fps " + String.format(java.util.Locale.ROOT, "%.0f", frames * 1e9 / (now - fpsAt))
                        + " frame " + String.format(java.util.Locale.ROOT, "%.1f", busyNanos / 1e6 / frames)
                        + "ms [scene " + String.format(java.util.Locale.ROOT, "%.1f", scene.sceneMs)
                        + " blur " + String.format(java.util.Locale.ROOT, "%.1f", scene.blurMs)
                        + " comp " + String.format(java.util.Locale.ROOT, "%.1f", scene.compositeMs)
                        + "] particles " + scene.particleCount());
                frames = 0;
                busyNanos = 0;
                fpsAt = now;
            }
            // Pace to a 60 fps budget by subtracting what the frame already cost. The old fixed
            // sleep(16) was added on top of that cost, which is exactly why a 24 ms frame showed up as
            // 25 fps: it was really spending 40 ms per frame, 16 of them asleep.
            sleep(Math.max(1L, 16L - (System.nanoTime() - frameStart) / 1_000_000L));
        }

        if (scene != null) scene.dispose();
        if (points != null) points.dispose();
        if (flares != null) flares.dispose();
        if (blur != null) blur.dispose();
        if (composite != null) composite.dispose();
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
        if (window != null) window.release();
        EGL14.eglDestroyContext(display, context);
        EGL14.eglTerminate(display);
        Log.d(TAG, "render thread stopped");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---------------------------------------------------------------- GL helpers

    private static FloatBuffer quadBuffer() {
        float[] v = {-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1};
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    /** One compiled program with its uniform locations cached. */
    private static final class Program {
        final int id;
        private final String name;

        Program(String vs, String fs, String name) {
            this.name = name;
            id = GLES20.glCreateProgram();
            int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) { Log.e(TAG, "shader compile failed for " + name); return; }
            GLES20.glAttachShader(id, v);
            GLES20.glAttachShader(id, f);
            GLES20.glLinkProgram(id);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) Log.e(TAG, "link failed for " + name + ": " + GLES20.glGetProgramInfoLog(id));
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
        }

        private final java.util.HashMap<String, Integer> uniformCache = new java.util.HashMap<>();

        /**
         * Cached: the effect asks for about 27 locations a frame (six blur passes alone ask for two
         * each), and every one of those was a string hash inside the driver.
         */
        int uniform(String n) {
            Integer at = uniformCache.get(n);
            if (at == null) {
                at = GLES20.glGetUniformLocation(id, n);
                uniformCache.put(n, at);
            }
            return at;
        }
        int attrib(String n) { return GLES20.glGetAttribLocation(id, n); }
        void use() { GLES20.glUseProgram(id); }
        void dispose() { GLES20.glDeleteProgram(id); }
        @Override public String toString() { return name; }
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static int makeTexture(int w, int h) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int makeTarget(int texture, int w, int h) {
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "framebuffer incomplete: " + status);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return f[0];
    }

    /** A texture plus the framebuffer that renders into it. */
    private static final class Target {
        final int texture, fbo, width, height;
        Target(int w, int h) {
            width = w; height = h;
            texture = makeTexture(w, h);
            fbo = makeTarget(texture, w, h);
        }
        void dispose() {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        }
    }

    // ---------------------------------------------------------------- the scene

    /** Owns the particle buffer and the bloom pyramid. */
    private static final class Scene {
        private final int width, height;
        /**
         * The surface's own size. The composite writes there and it is NOT the render size once
         * RENDER_SCALE is below 1 - using the render size as the viewport quietly shrank the whole
         * effect into a corner of the screen.
         */
        private final int surfaceWidth, surfaceHeight;
        private final Target scene, fineA, fineB, wideA, wideB, mistA, mistB;
        private FloatBuffer particles;
        private int particleVbo, count;
        /** The baked bursts: start, duration, height, radius, x, y - the shader owns the motion. */
        private final float[] burstStart = new float[EVENT_COUNT];
        private final float[] burstDuration = new float[EVENT_COUNT];
        private final float[] burstHeight = new float[EVENT_COUNT];
        private final float[] burstRadius = new float[EVENT_COUNT];
        private final float[] burstX = new float[EVENT_COUNT];
        private final float[] burstY = new float[EVENT_COUNT];
        private final float[] eruptions = new float[MAX_EVENTS * 4];
        private int flareVbo, flarePoints;
        private float exit, coronaStrength = 1f;
        /** Set from the render loop: cycle mode belongs to the view, the shader needs it per frame. */
        boolean cycle;
        float coronaScale = 1f;
        float enterFrom = 1f;
        boolean cloudOnly;
        /** Last frame's cost per stage, in ms: guessing which pass is expensive wasted a build. */
        float sceneMs, blurMs, compositeMs;
        private final int aTarget, aCloud, aSeed, aEdge;

        Scene(int w, int h, int surfaceW, int surfaceH, Program source) {
            width = w; height = h;
            surfaceWidth = surfaceW; surfaceHeight = surfaceH;
            scene = new Target(w, h);
            fineA = new Target(Math.max(2, w / 2), Math.max(2, h / 2));
            fineB = new Target(Math.max(2, w / 2), Math.max(2, h / 2));
            wideA = new Target(Math.max(2, w / 4), Math.max(2, h / 4));
            wideB = new Target(Math.max(2, w / 4), Math.max(2, h / 4));
            mistA = new Target(Math.max(2, w / 8), Math.max(2, h / 8));
            mistB = new Target(Math.max(2, w / 8), Math.max(2, h / 8));
            int[] vbo = new int[1];
            GLES20.glGenBuffers(1, vbo, 0);
            particleVbo = vbo[0];
            // Every packet slot is allocated up front; a burst is one glBufferSubData into its slice.
            GLES20.glGenBuffers(1, vbo, 0);
            flareVbo = vbo[0];
            // Locations belong to the program that draws, so they come from that one.
            aTarget = source.attrib("aTarget");
            aCloud = source.attrib("aCloud");
            aSeed = source.attrib("aSeed");
            aEdge = source.attrib("aEdge");
        }

        void dispose() {
            scene.dispose(); fineA.dispose(); fineB.dispose();
            wideA.dispose(); wideB.dispose(); mistA.dispose(); mistB.dispose();
            GLES20.glDeleteBuffers(1, new int[]{particleVbo}, 0);
        }

        int particleCount() { return count; }

        /**
         * Lays the particles out: a share per dark module, evenly spread inside it with a small inset
         * so no rigid square outlines appear, plus a few that never join the code.
         */
        void buildParticles(boolean[][] modules, float halfWidth, float formation, boolean inside) {
            int n = modules == null ? 0 : modules.length;
            if (n == 0) { count = 0; return; }
            java.util.ArrayList<int[]> cells = new java.util.ArrayList<>();
            for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (modules[y][x]) cells.add(new int[]{x, y});
            if (cells.isEmpty()) { count = 0; return; }
            count = PARTICLES + AMBIENT;
            // aTarget(3) aCloud(3) aSeed(4) aEdge(4): the rim normal and its weight ride along so the
            // outline can leap without the CPU touching a particle per frame.
            float[] values = new float[count * 14];
            java.util.Random rand = new java.util.Random(413029);
            float side = formation;
            Envelope env = new Envelope(modules, n, side);   // side is the formation scale
            for (int i = 0; i < count; i++) {
                float x, y, z, nx = 0, ny = 0, weight = 0, phase = rand.nextFloat() * 6.2831855f;
                if (i < PARTICLES) {
                    int[] c = cells.get(rand.nextInt(cells.size()));
                    x = ((c[0] + .045f + rand.nextFloat() * .91f) / n - .5f) * side;
                    y = (.5f - (c[1] + .045f + rand.nextFloat() * .91f) / n) * side;
                    z = (rand.nextFloat() - .5f) * .042f;
                    float[] rim = env.at(x, y);
                    nx = rim[0]; ny = rim[1]; weight = rim[2];
                    phase = ((x * 2.31f + y * 3.73f) % 6.2831855f + 6.2831855f) % 6.2831855f;
                } else {
                    x = (rand.nextFloat() - .5f) * 3.0f;
                    y = (rand.nextFloat() - .5f) * 2.8f;
                    z = (rand.nextFloat() - .5f) * 1.7f;
                }
                // Spawn on the border of the *visible* rectangle, just outside it. The shader divides x
                // by the aspect, so the visible band in these units is +/-halfWidth on x and +/-1 on y -
                // a ring of radius ~1.2 was inside the frame on a 16:9 screen, which is why the dust
                // used to appear out of nowhere in the middle of the picture.
                float cx, cy;
                if (inside) {
                    // Over the whole frame: the waiting cloud fills the screen rather than sitting outside.
                    cx = (rand.nextFloat() - .5f) * 2f * halfWidth * .96f;
                    cy = (rand.nextFloat() - .5f) * 1.92f;
                } else {
                    float f = 1.06f + rand.nextFloat() * 0.34f;
                    float hw = halfWidth * f, hh = f;
                    float edge = rand.nextFloat() * 4f;
                    if (edge < 1f) { cx = -hw + 2f * hw * edge; cy = -hh; }
                    else if (edge < 2f) { cx = hw; cy = -hh + 2f * hh * (edge - 1f); }
                    else if (edge < 3f) { cx = hw - 2f * hw * (edge - 2f); cy = hh; }
                    else { cx = -hw; cy = hh - 2f * hh * (edge - 3f); }
                }
                int k = i * 14;
                values[k] = x; values[k + 1] = y; values[k + 2] = z;
                values[k + 3] = cx;
                values[k + 4] = cy;
                values[k + 5] = (rand.nextFloat() - .5f) * 1.65f;
                values[k + 6] = rand.nextFloat(); values[k + 7] = rand.nextFloat();
                values[k + 8] = rand.nextFloat(); values[k + 9] = i < PARTICLES ? 0 : 2;
                values[k + 10] = nx; values[k + 11] = ny;
                values[k + 12] = weight; values[k + 13] = phase * 6.2831855f;
            }
            if (particles == null || particles.capacity() < values.length) {
                particles = ByteBuffer.allocateDirect(values.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
            }
            particles.clear();
            particles.put(values).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, values.length * 4, particles, GLES20.GL_STATIC_DRAW);
            bakeBursts(env);
            java.util.Arrays.fill(eruptions, 0f);
            Log.d(TAG, "particles " + count + " over " + cells.size() + " modules, flares "
                    + flarePoints);
        }

        /**
         * Bakes the 22 bursts exactly as the reference lays them out: a fixed order of sides, a point on
         * that side's envelope per burst, and 200-280 grains per burst carrying the root segment, the
         * normal, the schedule and their own random shares. Same mulberry32 stream and same draw order,
         * so the sequence is the authored one rather than anything re-invented here.
         */
        /**
         * The code's outer silhouette: for every column and row, the outermost dark cell. Shared by the
         * rim lift and by the bursts so both agree on where the limb is.
         */
        private static final class Envelope {
            final int n, band;
            final float cell, half;
            final int[] top, bottom, left, right;

            Envelope(boolean[][] m, int n, float side) {
                this.n = n;
                cell = side / n;
                half = side / 2;
                band = Math.max(2, Math.round(n * .12f));
                top = new int[n];
                bottom = new int[n];
                left = new int[n];
                right = new int[n];
                java.util.Arrays.fill(top, n);
                java.util.Arrays.fill(bottom, -1);
                java.util.Arrays.fill(left, n);
                java.util.Arrays.fill(right, -1);
                for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
                    if (!m[y][x]) continue;
                    top[x] = Math.min(top[x], y);
                    bottom[x] = Math.max(bottom[x], y);
                    left[y] = Math.min(left[y], x);
                    right[y] = Math.max(right[y], x);
                }
            }

            /**
             * The rim, evaluated for the particle's own position. Doing this per cell was my mistake: it
             * gave every particle in a boundary cell the same middling weight, so the limb moved as a
             * slab and the plumes looked like a separate system. Per particle, the ones sitting on the
             * silhouette get the full weight and their neighbours fall away across the cell - which is
             * what makes the edge look dragged along by the plume.
             */
            float[] at(float px, float py) {
                int col = Math.max(0, Math.min(n - 1, (int) Math.floor((px + half) / cell)));
                int row = Math.max(0, Math.min(n - 1, (int) Math.floor((half - py) / cell)));
                float best = Float.MAX_VALUE, nx = 0, ny = 0;
                if (row == top[col] && top[col] <= band) {
                    float d = half - top[col] * cell - py;
                    if (d >= -.0001f && d < best) { best = d; nx = 0; ny = 1; }
                }
                if (row == bottom[col] && bottom[col] >= n - 1 - band) {
                    float d = py - (half - (bottom[col] + 1) * cell);
                    if (d >= -.0001f && d < best) { best = d; nx = 0; ny = -1; }
                }
                if (col == left[row] && left[row] <= band) {
                    float d = px - (-half + left[row] * cell);
                    if (d >= -.0001f && d < best) { best = d; nx = -1; ny = 0; }
                }
                if (col == right[row] && right[row] >= n - 1 - band) {
                    float d = (-half + (right[row] + 1) * cell) - px;
                    if (d >= -.0001f && d < best) { best = d; nx = 1; ny = 0; }
                }
                float weight = best == Float.MAX_VALUE ? 0f : 1f - smoothstep(0f, cell * .78f, best);
                return new float[]{nx, ny, weight};
            }

            boolean valid(int sideIndex, int value) {
                if (value < 0 || value >= n) return false;
                return (sideIndex == 0 || sideIndex == 3) ? value <= band : value >= n - 1 - band;
            }

            /** A point on that side's silhouette, as the reference anchors its bursts. */
            float[] anchor(int sideIndex, float u) {
                u = Math.max(.005f, Math.min(.995f, u));
                float q = sideIndex < 2 ? u : 1f - u;
                int index = Math.min(n - 1, (int) Math.floor(q * n));
                float f = q * n - index;
                int[] ext = sideIndex == 0 ? top : sideIndex == 1 ? right : sideIndex == 2 ? bottom : left;
                boolean any = false;
                for (int i = 0; i < n; i++) if (valid(sideIndex, ext[i])) { any = true; break; }
                if (!any) return null;
                if (!valid(sideIndex, ext[index])) {
                    for (int d = 1; d < n; d++) {
                        if (index - d >= 0 && valid(sideIndex, ext[index - d])) { index -= d; break; }
                        if (index + d < n && valid(sideIndex, ext[index + d])) { index += d; break; }
                    }
                    f = .5f;
                }
                if (sideIndex == 0) return new float[]{-half + (index + f) * cell, half - ext[index] * cell - .002f, 0f, 1f};
                if (sideIndex == 1) return new float[]{-half + (ext[index] + 1) * cell - .002f, half - (index + f) * cell, 1f, 0f};
                if (sideIndex == 2) return new float[]{-half + (index + f) * cell, half - (ext[index] + 1) * cell + .002f, 0f, -1f};
                return new float[]{-half + ext[index] * cell + .002f, half - (index + f) * cell, -1f, 0f};
            }
        }

        /** GLSL's smoothstep, for the CPU-side curves the rim and the burst schedule need. */
        private static float smoothstep(float edge0, float edge1, float x) {
            float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
            return t * t * (3f - 2f * t);
        }

        /**
         * Ages the baked bursts for the rim lift. The reference keeps the eight strongest alive as
         * uniforms and lets the shader do the rest; the same list drives every plume grain, so nothing
         * about the leap needs the CPU once it is baked.
         */
        private void updateEruptions(float t) {
            java.util.Arrays.fill(eruptions, 0f);
            int slot = 0;
            for (int e = 0; e < EVENT_COUNT && slot < MAX_EVENTS; e++) {
                float start = (burstStart[e] - 1.65f) * (18f / 8.70f);
                float elapsed = (t - start + 36f) % 8.70f;   // see FLARE_VS: no empty half-cycle
                float age = elapsed / burstDuration[e];
                float energy = smoothstep(0f, .22f, age) * (1f - smoothstep(.52f, .92f, age));
                if (energy <= .0001f) continue;
                int k = slot++ * 4;
                eruptions[k] = burstX[e];
                eruptions[k + 1] = burstY[e];
                eruptions[k + 2] = energy * burstHeight[e] / .18f;
                eruptions[k + 3] = burstRadius[e];
            }
        }

        /** The plumes, drawn additively into the same scene as the body. */
        void drawFlares(Program flares, float t) {
            if (flarePoints == 0) return;
            flares.use();
            GLES20.glUniform1f(flares.uniform("uTime"), t);
            GLES20.glUniform1f(flares.uniform("uCorona"), coronaStrength);
            GLES20.glUniform2f(flares.uniform("uResolution"), width, height);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, flareVbo);
            bind(flares.attrib("aRoots"), 4, 80, 0);
            bind(flares.attrib("aAxis"), 4, 80, 16);
            bind(flares.attrib("aEvent"), 4, 80, 32);
            bind(flares.attrib("aFlow"), 4, 80, 48);
            bind(flares.attrib("aGrain"), 4, 80, 64);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, flarePoints);
        }

        private float gauss(Mulberry rand) {
            float g = (float) Math.sqrt(-2 * Math.log(Math.max(1e-8f, rand.next())))
                    * (float) Math.cos(6.2831855f * rand.next());
            return Math.max(-2.3f, Math.min(2.3f, g));
        }

        private void bakeBursts(Envelope env) {
            int n = env.n;
            float[][] anchors = new float[4][];
            final int[] sides = {0, 3, 1, 2, 0, 1, 3, 2, 0, 3, 1, 2, 3, 0, 2, 1, 0, 3, 2, 1, 0, 2};
            Mulberry rand = new Mulberry(20260920);
            float[] data = new float[EVENT_COUNT * GRAINS_PER_EVENT * 20];
            int at = 0, events = 0, grainsTotal = 0;
            for (int e = 0; e < sides.length; e++) {
                int sideIndex = sides[e];
                float u = .15f + .70f * rand.next();
                float span = .055f + .055f * rand.next();
                float bStart = 1.65f + e * .395f + (rand.next() - .5f) * .15f;
                float bDuration = (1.45f + rand.next() * .82f) * BURST_LENGTH;
                float bHeight = .115f + rand.next() * .105f;
                float direction = rand.next() < .5f ? -1f : 1f;
                float phase = rand.next() * 6.2831855f;
                float[] a = env.anchor(sideIndex, u - span / 2f);
                float[] b = env.anchor(sideIndex, u + span / 2f);
                if (a == null || b == null) continue;
                if (direction < 0) { float[] swap = a; a = b; b = swap; }
                float[] center = env.anchor(sideIndex, u);
                if (center == null) continue;
                burstStart[events] = bStart;
                burstDuration[events] = bDuration;
                burstHeight[events] = bHeight;
                burstRadius[events] = .052f + span * .46f;
                burstX[events] = center[0];
                burstY[events] = center[1];
                events++;
                int grains = 200 + (int) Math.floor(rand.next() * 80);
                for (int g = 0; g < grains; g++) {
                    float delay = rand.next() * .47f;
                    float gj1 = gauss(rand), gj2 = gauss(rand);
                    data[at++] = a[0]; data[at++] = a[1]; data[at++] = b[0]; data[at++] = b[1];
                    data[at++] = a[2]; data[at++] = a[3]; data[at++] = 0f; data[at++] = phase;
                    data[at++] = bStart; data[at++] = bDuration; data[at++] = bHeight; data[at++] = span;
                    data[at++] = delay; data[at++] = gj1; data[at++] = gj2; data[at++] = direction;
                    data[at++] = rand.next(); data[at++] = rand.next();
                    data[at++] = rand.next(); data[at++] = rand.next();
                }
                grainsTotal += grains;
            }
            flarePoints = grainsTotal;
            FloatBuffer buf = ByteBuffer.allocateDirect(at * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            buf.put(data, 0, at).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, flareVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, at * 4, buf, GLES20.GL_STATIC_DRAW);
        }

        void render(Program points, Program flares, Program blur, Program composite, FloatBuffer quad,
                    float t, float exitNow) {
            if (count == 0) { clear(); return; }
            long mark = System.nanoTime();
            exit = exitNow;
            // The leap belongs to a formed code: it dies away as the code comes apart.
            coronaStrength = CORONA_STRENGTH * coronaScale * (1f - smoothstep(0f, .5f, exit));
            // --- particles into the scene target
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, scene.fbo);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            points.use();
            GLES20.glUniform1f(points.uniform("uTime"), t);
            GLES20.glUniform1f(points.uniform("uExit"), exit);
            GLES20.glUniform1f(points.uniform("uCycle"), cycle ? 1f : 0f);
            GLES20.glUniform1f(points.uniform("uCloud"), cloudOnly ? 1f : 0f);
            GLES20.glUniform1f(points.uniform("uEnterFrom"), enterFrom);
            GLES20.glUniform1f(points.uniform("uCycleHold"), CYCLE_HOLD);
            GLES20.glUniform1f(points.uniform("uCycleFade"), CYCLE_DISSOLVE);
            GLES20.glUniform1f(points.uniform("uCorona"), coronaStrength);
            updateEruptions(t);
            GLES20.glUniform4fv(points.uniform("uEruptions[0]"), MAX_EVENTS, eruptions, 0);
            GLES20.glUniform2f(points.uniform("uResolution"), width, height);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleVbo);
            int stride = 56;
            bind(aTarget, 3, stride, 0);
            bind(aCloud, 3, stride, 12);
            bind(aSeed, 4, stride, 24);
            bind(aEdge, 4, stride, 40);
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count);
            // Same target, same additive blend: the blur then treats the sparks as more light.
            drawFlares(flares, t);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            sceneMs = (System.nanoTime() - mark) / 1e6f;
            mark = System.nanoTime();

            // --- bloom pyramid
            blurTarget(blur, quad, scene, fineA, 1.4f, 0);
            blurTarget(blur, quad, fineA, fineB, 0, 1.0f);
            blurTarget(blur, quad, fineB, wideA, 2.0f, 0);
            blurTarget(blur, quad, wideA, wideB, 0, 1.6f);
            blurTarget(blur, quad, wideB, mistA, 2.0f, 0);

            blurMs = (System.nanoTime() - mark) / 1e6f;
            mark = System.nanoTime();
            // --- composite to the screen
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            composite.use();
            GLES20.glUniform1f(composite.uniform("uBloomFine"), BLOOM_FINE);
            GLES20.glUniform1f(composite.uniform("uBloomWide"), BLOOM_WIDE);
            GLES20.glUniform1f(composite.uniform("uToneMap"), TONE_MAP);
            GLES20.glUniform2f(composite.uniform("uResolution"), surfaceWidth, surfaceHeight);
            bindTexture(composite.uniform("uScene"), 0, scene.texture);
            bindTexture(composite.uniform("uFine"), 1, fineB.texture);
            bindTexture(composite.uniform("uWide"), 2, wideB.texture);
            drawQuad(composite, quad);
            compositeMs = (System.nanoTime() - mark) / 1e6f;
        }

        private void clear() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            GLES20.glClearColor(0.01f, 0.03f, 0.055f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        private static void bind(int attrib, int size, int stride, int offset) {
            if (attrib < 0) return;
            GLES20.glEnableVertexAttribArray(attrib);
            GLES20.glVertexAttribPointer(attrib, size, GLES20.GL_FLOAT, false, stride, offset);
        }

        private static void bindTexture(int uniform, int unit, int texture) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glUniform1i(uniform, unit);
        }

        private static void blurTarget(Program blur, FloatBuffer quad, Target from, Target to,
                                       float dx, float dy) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, to.fbo);
            GLES20.glViewport(0, 0, to.width, to.height);
            blur.use();
            GLES20.glUniform2f(blur.uniform("uStep"), dx / to.width, dy / to.height);
            bindTexture(blur.uniform("uTexture"), 0, from.texture);
            drawQuad(blur, quad);
        }

        private static void drawQuad(Program p, FloatBuffer quad) {
            int a = p.attrib("aPosition");
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glEnableVertexAttribArray(a);
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(a);
        }
    }

    // ---------------------------------------------------------------- shaders
    // Straight ports of the WebGL study, with the loop removed (the code forms once and stays) and the
    // brightness/bloom numbers retuned for a small card on a TV panel.

    /**
     * The packets that leap off the outline. Each grain knows its root on the rim, the outward normal
     * there, and which burst it belongs to, so a whole plume is computed from the clock - no per frame
     * CPU work beyond letting a new burst go.
     */
    /** mulberry32: the reference's generator, kept so the burst sequence is the same one. */
    private static final class Mulberry {
        private int state;

        Mulberry(int seed) { state = seed; }

        float next() {
            state += 0x6D2B79F5;
            int t = state;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return ((t ^ (t >>> 14)) >>> 0) / 4294967296f;
        }
    }

    /**
     * The plumes, ported line for line from the reference. A grain rides a segment of the envelope
     * (aRoots), rises along the outward normal there, rolls into a curl, and a small minority peels off
     * from the crest instead of completing the loop. Everything is a function of the clock, so the whole
     * sequence is baked once and costs the CPU nothing.
     */
    private static final String FLARE_VS =
            "precision highp float;\n"
            + "attribute vec4 aRoots;\n"
            + "attribute vec4 aAxis;\n"
            + "attribute vec4 aEvent;\n"
            + "attribute vec4 aFlow;\n"
            + "attribute vec4 aGrain;\n"
            + "uniform float uTime;\n"
            + "uniform float uCorona;\n"
            + "uniform vec2 uResolution;\n"
            + "varying vec3 vColor;\n"
            + "varying float vAlpha;\n"
            + "varying float vSoft;\n"
            + "const float PI=3.14159265359;\n"
            + "const float TAU=6.28318530718;\n"
            + "void main(){\n"
            + "  float t=mod(uTime,18.0),ph=t/18.0*TAU;\n"
            // Hold mode: the sequence is compressed into 8.70 s and wraps every 18.
            + "  float start=(aEvent.x-1.65)*(18.0/8.70);\n"
            + "  float elapsed=mod(t-start+36.0,8.70);\n"
            + "  float age=elapsed/aEvent.y;\n"
            + "  float flight=(age-aFlow.x)/((0.63+0.08*aGrain.y)*" + FLIGHT_LENGTH + ");\n"
            + "  float u=clamp(flight,0.0,1.0);\n"
            + "  float gate=smoothstep(0.0,0.09,flight)*(1.0-smoothstep(0.70,1.0,flight));\n"
            + "  if(gate<=0.0001||uCorona<=0.0001){\n"
            + "    gl_Position=vec4(2.0,2.0,0.0,1.0);gl_PointSize=1.0;\n"
            + "    vColor=vec3(0.0);vAlpha=0.0;vSoft=0.0;return;\n"
            + "  }\n"
            + "  vec2 normal=aAxis.xy,tangent=vec2(normal.y,-normal.x);\n"
            + "  float phase=aAxis.w;\n"
            + "  float rise=sin(PI*u);\n"
            + "  float height=aEvent.z*uCorona;\n"
            + "  float swelling=0.83+0.17*sin(clamp(age,0.0,1.0)*PI);\n"
            + "  float plume=height*pow(max(0.0,rise),0.86)*swelling;\n"
            + "  plume*=0.90+0.10*aGrain.y;\n"
            + "  float curl=height*0.26*rise*sin(TAU*u*1.12+phase)*aFlow.w;\n"
            + "  plume+=height*0.12*rise*sin(TAU*u*1.12+phase+1.5);\n"
            + "  float escape=step(0.88,aGrain.z);\n"
            + "  plume+=escape*height*0.58*smoothstep(0.5,1.0,u);\n"
            + "  curl+=escape*height*0.30*smoothstep(0.4,1.0,u)*aFlow.w;\n"
            + "  vec2 base=mix(aRoots.xy,aRoots.zw,u);\n"
            + "  vec2 xy=base+normal*plume+tangent*curl;\n"
            + "  float n1=sin(xy.x*18.0+xy.y*11.0-ph*5.0+phase);\n"
            + "  float n2=cos(xy.y*21.0-xy.x*9.0+ph*6.0-phase);\n"
            + "  xy+=rise*(tangent*n1+normal*n2)*0.006*uCorona;\n"
            + "  float spread=(0.0035+0.010*rise)*(.55+.45*aGrain.y);\n"
            + "  xy+=normal*aFlow.y*spread+tangent*aFlow.z*spread*0.75;\n"
            + "  xy.x+=0.012*sin(base.y*6.0+ph*2.0+0.65*sin(base.x*4.0-ph));\n"
            + "  xy.y+=0.009*cos(base.x*6.0-ph*2.0+0.55*cos(base.y*4.0+ph));\n"
            + "  float z=(aGrain.y-.5)*.05+height*.25*rise*sin(PI*u+phase);\n"
            + "  vec3 pos=vec3(xy,z);\n"
            + "  float yaw=0.018*sin(ph),pitch=0.012*cos(ph*2.0);\n"
            + "  pos=vec3(pos.x*cos(yaw)+pos.z*sin(yaw),pos.y,-pos.x*sin(yaw)+pos.z*cos(yaw));\n"
            + "  pos=vec3(pos.x,pos.y*cos(pitch)-pos.z*sin(pitch),pos.y*sin(pitch)+pos.z*cos(pitch));\n"
            + "  float persp=3.8/(3.8+pos.z);\n"
            + "  vec2 scale=vec2(min(uResolution.x,uResolution.y)/uResolution.x,"
            + "min(uResolution.x,uResolution.y)/uResolution.y);\n"
            + "  gl_Position=vec4(pos.xy*persp*scale,0.0,1.0);\n"
            + "  float focus=abs(pos.z)*.55;\n"
            + "  gl_PointSize=max(1.0,(3.7+1.6*aGrain.x+focus*3.5)*persp"
            + "*min(uResolution.x,uResolution.y)/960.0);\n"
            + "  float light=smoothstep(-.9,1.1,base.y*.85-base.x*.45);\n"
            + "  vec3 color=mix(vec3(.065,.31,.88),vec3(.065,.75,1.0),.30+.60*light);\n"
            + "  color=mix(color,vec3(.48,.87,1.0),pow(aGrain.x,7.0)*.65);\n"
            + "  vColor=color;\n"
            + "  float sparkle=.83+.17*sin(ph*8.0+aGrain.w*TAU+u*5.0);\n"
            + "  vAlpha=gate*(.26+.84*pow(aGrain.x,1.4))*(.68+.32*light)*sparkle;\n"
            + "  vAlpha*=mix(.82,.44,u)*(1.0-.24*escape)*min(1.0,uCorona)*" + FLARE_GAIN + ";\n"
            + "  vAlpha*=.68+.32*abs(cos(PI*u));\n"
            + "  vSoft=focus;\n"
            + "}\n";

    /** The reference's own sprite: a tight core plus a faint halo, so the plumes read as light. */
    private static final String FLARE_FS =
            "precision mediump float;\n"
            + "varying vec3 vColor;\n"
            + "varying float vAlpha;\n"
            + "varying float vSoft;\n"
            + "void main(){\n"
            + "  vec2 p=gl_PointCoord*2.0-1.0;\n"
            + "  float r=dot(p,p);\n"
            + "  if(r>1.0)discard;\n"
            + "  float core=exp(-r*(7.0-vSoft*2.0));\n"
            + "  float halo=exp(-r*2.6)*.10;\n"
            + "  float a=(core+halo)*vAlpha;\n"
            + "  gl_FragColor=vec4(mix(vColor,vec3(.55,.90,1.0),core*.24)*a,a);\n"
            + "}\n";

    private static final String POINT_VS =
            "precision highp float;\n"
            + "attribute vec3 aTarget;\n"
            + "attribute vec3 aCloud;\n"
            + "attribute vec4 aSeed;\n"
            + "attribute vec4 aEdge;\n"
            + "uniform float uTime;\n"
            + "uniform float uExit;\n"
            + "uniform float uCorona;\n"
            + "uniform float uCycle;\n"
            + "uniform float uCycleHold;\n"
            + "uniform float uCycleFade;\n"
            + "uniform float uCloud;\n"
            + "uniform float uEnterFrom;\n"
            + "uniform vec4 uEruptions[8];\n"
            + "uniform vec2 uResolution;\n"
            + "varying vec3 vColor;\n"
            + "varying float vAlpha;\n"
            + "varying float vSoft;\n"
            + "const float TAU=6.28318530718;\n"
            + "void main(){\n"
            + "  float t=uTime;\n"
            // The dust keeps the original 18 second rhythm even though the shape no longer loops.
            + "  float ph=mod(uTime,18.0)/18.0*TAU;\n"
            + "  float isAmbient=step(1.5,aSeed.w);\n"
            + "  float fringe=step(0.90,aSeed.x)*(1.0-isAmbient);\n"
            + "  float delay=aSeed.y*0.45;\n"
            + "  float joining=smoothstep(0.10+delay," + GATHER_SECONDS + "+delay,t);\n"
            + "  float gather=(1.0-pow(1.0-joining,2.8))*(1.0-isAmbient);\n"
            // Leaving: the code comes apart back into the cloud over the first half of the exit, then
            // the whole cloud keeps travelling outward until it is off the screen.
            + "  gather*=mix(1.0,1.0-smoothstep(uCycleHold,uCycleHold+uCycleFade,t),uCycle);\n"
            + "  gather*=1.0-smoothstep(0.0,0.52,uExit);\n"
            + "  float outward=1.0+1.30*smoothstep(0.26,1.0,uExit);\n"
            + "  gather*=1.0-uCloud;\n"
            // The waiting cloud drifts in from beyond the edges and, on the way out, keeps going.
            + "  float cloudScale=mix(uEnterFrom,1.0,smoothstep(0.0,1.35,t));\n"
            + "  float rr=length(aCloud.xy)*outward*cloudScale;\n"
            + "  float theta=atan(aCloud.y,aCloud.x)+0.55*sin(ph+rr*1.5)+0.20*sin(ph*2.0+aCloud.z*2.0);\n"
            + "  vec3 cloud=vec3(cos(theta)*rr,sin(theta)*rr,aCloud.z);\n"
            + "  cloud.x+=0.14*sin(aCloud.y*2.4+ph)*cos(aCloud.z*2.1+ph);\n"
            + "  cloud.y+=0.13*cos(aCloud.x*2.9-ph)*sin(aCloud.y+ph*2.0);\n"
            + "  cloud.z+=0.22*sin(ph+aCloud.x*2.0+aCloud.y*1.2);\n"
            + "  float p=TAU*aSeed.z;\n"
            + "  vec3 target=aTarget;\n"
            + "  target.x+=0.0028*sin(ph*5.0+p)+0.0016*cos(ph*2.0+p*1.7+aTarget.y*14.0);\n"
            + "  target.y+=0.0028*cos(ph*4.0+p*1.3)+0.0016*sin(ph*3.0+p+aTarget.x*14.0);\n"
            + "  target.z+=0.008*sin(ph*2.0+p);\n"
            + "  target.x+=0.012*sin(aTarget.y*6.0+ph*2.0+0.65*sin(aTarget.x*4.0-ph));\n"
            + "  target.y+=0.009*cos(aTarget.x*6.0-ph*2.0+0.55*cos(aTarget.y*4.0+ph));\n"
            + "  float leave=pow(0.5+0.5*sin(ph*3.0+p),5.0)*fringe;\n"
            + "  target.xy+=leave*vec2(0.026*sin(ph*2.0+p*2.0),0.036*cos(ph*2.0+p));\n"
            + "  target.z+=leave*0.06;\n"
            // The leap: only the outline takes part, lifted along its outward normal by travelling
            // packets, with a ripple running along the edge and a small fold sideways.
            + "  float edgeWeight=aEdge.z*uCorona;\n"
            + "  if(edgeWeight>0.001){\n"
            + "    float energy=0.0;\n"
            + "    for(int i=0;i<8;i++){\n"
            + "      vec2 d=aTarget.xy-uEruptions[i].xy;\n"
            + "      float radius=max(0.01,uEruptions[i].w);\n"
            + "      energy+=uEruptions[i].z*exp(-dot(d,d)/(radius*radius));\n"
            + "    }\n"
            + "    energy=min(1.25,energy);\n"
            + "    vec2 normal=aEdge.xy,tangent=vec2(normal.y,-normal.x);\n"
            + "    float along=dot(aTarget.xy,tangent);\n"
            + "    float ripple=pow(0.5+0.5*sin(along*29.0-ph*7.0+0.8*sin(along*12.0+ph*3.0)),3.0);\n"
            + "    target.xy+=normal*(0.003+0.013*ripple+0.062*energy*(0.30+0.70*aSeed.z))*edgeWeight;\n"
            + "    target.xy+=tangent*(0.018*energy*sin(along*17.0-ph*8.0+aEdge.w)*edgeWeight);\n"
            + "    target.z+=0.017*energy*sin(ph*6.0+aEdge.w)*edgeWeight;\n"
            + "  }\n"
            + "  vec3 pos=mix(cloud,target,gather);\n"
            + "  float arc=sin(gather*3.14159265)*(1.0-isAmbient);\n"
            + "  pos.xy+=arc*vec2(0.20*sin(ph+p+aTarget.y*3.0),0.15*cos(ph+p+aTarget.x*3.0));\n"
            + "  pos.z+=arc*0.18*sin(p*1.7+ph);\n"
            + "  float yaw=0.018*sin(ph),pitch=0.012*cos(ph*2.0);\n"
            + "  pos=vec3(pos.x*cos(yaw)+pos.z*sin(yaw),pos.y,-pos.x*sin(yaw)+pos.z*cos(yaw));\n"
            + "  pos=vec3(pos.x,pos.y*cos(pitch)-pos.z*sin(pitch),pos.y*sin(pitch)+pos.z*cos(pitch));\n"
            + "  float persp=3.8/(3.8+pos.z);\n"
            + "  vec2 scale=vec2(min(uResolution.x,uResolution.y)/uResolution.x,"
            + "min(uResolution.x,uResolution.y)/uResolution.y);\n"
            + "  gl_Position=vec4(pos.xy*persp*scale,0.0,1.0);\n"
            + "  float focus=abs(pos.z)*0.55;\n"
            + "  float star=step(.987,aSeed.y);\n"
            // About the reference's own diameter. Ours had grown to twice that - four times the
            // area - while the modules still had five times the coverage they need, so the extra
            // overdraw bought nothing and cost GPU time the video decoder also wanted.
            + "  float size=(4.3+2.3*aSeed.x+star*.5+focus*3.5)*persp;\n"
            + "  gl_PointSize=max(1.0,size*min(uResolution.x,uResolution.y)/960.0);\n"
            + "  float light=smoothstep(-.9,1.1,aTarget.y*.85-aTarget.x*.45);\n"
            + "  float wave=.65+.35*sin(aTarget.x*3.8+aTarget.y*4.4-ph*2.0);\n"
            + "  vec3 blue=vec3(.065,.31,.88);\n"
            + "  vec3 cyan=vec3(.065,.75,1.0);\n"
            + "  vec3 ice=vec3(.48,.87,1.0);\n"
            + "  vec3 col=mix(blue,cyan,clamp(light*.80+wave*.35,0.0,1.0));\n"
            + "  col=mix(col,ice,pow(aSeed.z,9.0)*.86);\n"
            + "  vColor=col;\n"
            + "  float flicker=.70+.30*sin(ph*4.0+p+aTarget.x*2.0);\n"
            + "  float luminance=(.95+1.70*pow(aSeed.y,1.7))*(.62+.38*light)*flicker;\n"
            + "  luminance*=mix(.30,1.15,gather);\n"
            + "  luminance*=1.0+star*.4;\n"
            + "  luminance*=.84+.16*sin(aTarget.x*11.0+ph*2.0)*cos(aTarget.y*12.0-ph);\n"
            + "  luminance*=1.0-.62*leave;\n"
            + "  if(isAmbient>.5)luminance*=.55;\n"
            + "  vAlpha=luminance/(1.0+focus*1.8);\n"
            + "  vSoft=focus;\n"
            + "}\n";

    private static final String POINT_FS =
            "precision highp float;\n"
            + "varying vec3 vColor;\n"
            + "varying float vAlpha;\n"
            + "varying float vSoft;\n"
            + "void main(){\n"
            + "  vec2 p=gl_PointCoord*2.0-1.0;\n"
            + "  float r=dot(p,p);\n"
            + "  if(r>1.0)discard;\n"
            + "  float core=exp(-r*(7.0-vSoft*2.0));\n"
            + "  float halo=exp(-r*2.6)*.20;\n"
            + "  float a=(core+halo)*vAlpha;\n"
            + "  gl_FragColor=vec4(mix(vColor,vec3(.55,.90,1.0),core*.24)*a,a);\n"
            + "}\n";

    private static final String QUAD_VS =
            "attribute vec2 aPosition;\n"
            + "varying vec2 vUv;\n"
            + "void main(){vUv=aPosition*.5+.5;gl_Position=vec4(aPosition,0.0,1.0);}\n";

    private static final String BLUR_FS =
            "precision mediump float;\n"
            + "varying vec2 vUv;\n"
            + "uniform sampler2D uTexture;\n"
            + "uniform vec2 uStep;\n"
            + "void main(){\n"
            // Five samples instead of seven: the two outer pairs of a 13-tap kernel carry under 15%
            // of the energy and sit far from the centre, so dropping them is invisible in a glow but
            // takes a third off the blur's texture reads - and the pyramid runs it six times a frame.
            + "  vec3 c=texture2D(uTexture,vUv).rgb*.272727;\n"
            + "  c+=texture2D(uTexture,vUv+uStep*1.384615).rgb*.363636;\n"
            + "  c+=texture2D(uTexture,vUv-uStep*1.384615).rgb*.363636;\n"
            + "  gl_FragColor=vec4(c,1.0);\n"
            + "}\n";

    private static final String COMPOSITE_FS =
            "precision highp float;\n"
            + "varying vec2 vUv;\n"
            + "uniform sampler2D uScene;\n"
            + "uniform sampler2D uFine;\n"
            + "uniform sampler2D uWide;\n"

            + "uniform vec2 uResolution;\n"
            + "uniform float uBloomFine;\n"
            + "uniform float uBloomWide;\n"

            + "uniform float uToneMap;\n"
            + "void main(){\n"
            + "  vec2 p=(vUv-.5)*vec2(uResolution.x/uResolution.y,1.0);\n"
            + "  vec3 c=texture2D(uScene,vUv).rgb;\n"
            + "  c+=texture2D(uFine,vUv).rgb*uBloomFine;\n"
            + "  c+=texture2D(uWide,vUv).rgb*uBloomWide;\n"

            + "  c=vec3(1.0)-exp(-c*uToneMap);\n"
            // Premultiplied: alpha is the brightest channel, so rgb never exceeds it and the dust
            // blends into whatever is behind the card.
            + "  float a=max(max(c.r,c.g),c.b);\n"
            + "  float scrim=" + SCRIM_ALPHA + "*exp(-dot(p,p)*" + SCRIM_FALLOFF + ");\n"
            + "  float A=a+scrim*(1.0-a);\n"
            + "  gl_FragColor=vec4(c,A);\n"
            + "}\n";
}
