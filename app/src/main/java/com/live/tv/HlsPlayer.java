package com.live.tv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A minimal HLS player of our own: playlists, segments, MediaExtractor, MediaCodec, TextureView.
 *
 * <p>It exists because the platform player cannot read CCTV's live streams - handed the master it
 * fails with -10104, and handed one of its variants it accepts the url, reports rendering started and
 * then draws nothing at all. Everything here is framework API: no ExoPlayer, no hls.js, nothing
 * bundled, which is what this app has insisted on throughout.
 *
 * <p>This first cut plays the video track only and paces it against the wall clock. Audio, and with it
 * a proper clock, is the next step; the structure is already split so that lands in the decode loop
 * rather than around it.
 *
 * <p>TextureView, not SurfaceView, for the same reason the IPTV backend uses one: a SurfaceView is a
 * separate layer, so its contents never appear in {@code screencap} and the screenshot-based
 * verification this project runs on would go blind.
 */
final class HlsPlayer implements TextureView.SurfaceTextureListener {

    interface Listener {
        void onReady();
        void onError(String reason);
    }

    private static final String TAG = "HLSPLAYER";
    /** Segments kept on disk; a live window only ever needs a couple. */
    private static final int KEEP_SEGMENTS = 4;
    /** How far behind the newest segment playback starts, in segments. */
    private static final int LIVE_EDGE_BACKOFF = 2;
    /** Temporary: decodes without a surface, to see whether frames come out at all. */
    private static final boolean DIAGNOSTIC_NO_SURFACE = false;
    /**
     * Off: the decoder we now use reads the parameter sets that ride in band inside the keyframe, and
     * handing it a config blob of our own making was one more thing that could disagree with the stream.
     */
    private static final boolean FEED_CODEC_CONFIG = true;
    /**
     * The samples arrive as annex-b start codes. If the track format carries codec-specific data, the
     * codec expects the input buffers in that same framing - a format with AVCC-style csd fed annex-b
     * samples decodes a little and then gives up, which is what the picture has been showing. This
     * rewrites each sample as length-prefixed NALs to match.
     */
    private static final boolean CONVERT_TO_AVCC = false;
    /**
     * Let the decoder render straight into the TextureView's surface, the way every player does it: no
     * copy, no conversion, nothing on the CPU. It failed before - a strip at the top of a green field -
     * but that was with the same input that gives a corner of a frame through the byte-buffer path, so
     * the input is the thing under suspicion and this is the cheapest path to test it with.
     */
    private static final boolean USE_SURFACE_OUTPUT = true;
    /**
     * Temporary: fills the three textures with a constant instead of the frame. All 128 is neutral grey
     * once converted, so the screen says plainly whether anything reaches the shader at all.
     */
    private static final boolean DEBUG_CONSTANT_TEXTURES = false;
    /**
     * Runs the one experiment that splits this problem in two: with the same frozen CPU bytes, does an
     * R8 texture read back through an FBO still match? Nothing of ours is in that path - no shader, no
     * UV, no quad, no TextureView - so a match means the upload is fine and the fault is downstream, and
     * a mismatch means it is upstream or in the upload itself.
     */
    private static final boolean DEBUG_UPLOAD_PROBE = true;
    /** Temporary: draws the chroma plane instead of the picture. */
    private static final boolean DEBUG_SHOW_CHROMA = false;
    /** Which software decoder to use: the Codec2 one, or Google's OMX one. */
    private static final String PREFERRED_DECODER = "c2.android.avc.decoder";
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;

    private final Context context;
    private final Listener listener;
    private final TextureView view;
    private final Map<String, String> headers;

    private Thread worker;
    private volatile boolean running;
    private volatile boolean released;
    private SurfaceTexture surfaceTexture;
    private final GlView glView = new GlView();
    private boolean glAttached;
    private final Object surfaceLock = new Object();
    private String url;
    private boolean readyReported;
        private boolean loggedPacing;
    private int frames;
    /** Bumped whenever the surface changes, so the decoder is rebuilt against the new one. */
    private int surfaceGeneration;
    private int configuredGeneration = -1;

    HlsPlayer(Context context, Map<String, String> headers, Listener listener) {
        this.context = context.getApplicationContext();
        this.headers = headers;
        this.listener = listener;
        view = new TextureView(context);
        view.setSurfaceTextureListener(this);
    }

    View view() {
        return view;
    }

    /** Latest decoded frame as a Bitmap, converted from the same RGBA buffer the GL view uploads. */
    android.graphics.Bitmap snapshot() {
        return glView.snapshot();
    }

    /** Starts (or restarts) playback of a master or variant playlist url. */
    void play(String playlistUrl) {
        stop();
        url = playlistUrl;
        running = true;
        readyReported = false;
        frames = 0;
        worker = new Thread(this::run, "hls-player");
        worker.start();
    }

    void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void release() {
        stop();
        released = true;
        glView.release();
    }

    // ------------------------------------------------------------------ surface

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        synchronized (surfaceLock) {
            surfaceTexture = st;
        }
        glAttached = false;      // the decode thread builds its context on the next frame
        surfaceGeneration++;
        Log.d(TAG, "surface " + w + "x" + h);
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        synchronized (surfaceLock) {
            surfaceTexture = null;
        }
        glAttached = false;
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    // ------------------------------------------------------------------ pipeline

    private void run() {
        try {
            String variant = url.contains("BR=") ? url : pickVariant(url);
            if (variant == null) {
                fail("no variant in master");
                return;
            }
            Log.d(TAG, "variant " + variant);
            long lastSequence = -1;
            String mediaUrl = variant;
            MediaCodec[] codecHolder = new MediaCodec[1];
            long[] clock = new long[]{-1};
            // The window is live: keep asking for the list and play whatever is new, in order.
            while (running && !released) {
                Playlist list = fetchPlaylist(mediaUrl);
                if (list == null) {
                    fail("playlist fetch failed");
                    return;
                }
                if (lastSequence < 0) {
                    // Start near the live edge rather than at the oldest segment still listed.
                    int start = Math.max(0, list.uris.size() - 1 - LIVE_EDGE_BACKOFF);
                    lastSequence = list.sequence + start;
                    Log.d(TAG, "window " + list.sequence + "+" + list.uris.size() + ", starting at " + lastSequence);
                }
                for (int i = 0; i < list.uris.size() && running && !released; i++) {
                    long sequence = list.sequence + i;
                    if (sequence < lastSequence) continue;
                    String segment = resolve(mediaUrl, list.uris.get(i));
                    File file = download(segment, sequence);
                    if (file == null) {
                        fail("segment download failed");
                        return;
                    }
                    lastSequence = sequence + 1;
                    playSegment(file, codecHolder, clock);
                }
                if (running && !released) sleep(700);   // one target duration at a time
            }
        } catch (Throwable t) {
            if (running && !released) fail(t.toString());
        }
    }

    /** Decodes one segment to the surface, paced so it does not run ahead of real time. */
    /**
     * One decoder for the whole stream, one extractor per segment.
     *
     * <p>A fresh codec per four second segment was the last clear difference from every player that works:
     * a decoder torn down and rebuilt every segment never builds a reference chain, which is what a frame
     * decoded only part way looks like. Samples are simply fed on from one segment to the next, with no
     * end-of-stream between them - that marker means "no more input ever" and makes the codec stop.
     */
    private void playSegment(File file, MediaCodec[] codecHolder, long[] clock) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        int mine = 0;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) throw new IllegalStateException("no video track in " + file.getName());
            extractor.selectTrack(track);

            if (codecHolder[0] == null || surfaceGeneration != configuredGeneration) {
                if (codecHolder[0] != null) {
                    try { codecHolder[0].stop(); } catch (Exception ignored) { }
                    codecHolder[0].release();
                    codecHolder[0] = null;
                }
                for (String key : new String[]{"csd-0", "csd-1"}) {
                    if (!format.containsKey(key)) continue;
                    java.nio.ByteBuffer csd = format.getByteBuffer(key);
                    if (csd == null) continue;
                    java.nio.ByteBuffer view = csd.duplicate();
                    view.position(0);
                    StringBuilder head = new StringBuilder();
                    for (int i = 0; i < Math.min(8, view.remaining()); i++) {
                        head.append(String.format(java.util.Locale.ROOT, "%02X ", view.get(i) & 0xFF));
                    }
                    Log.d(TAG, "  " + key + " " + view.remaining() + " bytes head " + head);
                }
                Log.d(TAG, "  format " + format);
                if (!glAttached && !USE_SURFACE_OUTPUT) {
                    SurfaceTexture st = surfaceTextureNow();
                    if (st == null) throw new IllegalStateException("no surface");
                    glView.dumpDir = context.getExternalFilesDir(null);
                    glAttached = glView.attach(st);
                    if (!glAttached) throw new IllegalStateException("gl setup failed");
                }
                Surface surface = USE_SURFACE_OUTPUT ? new Surface(surfaceTextureNow()) : null;
                codecHolder[0] = createDecoder(format);
                codecHolder[0].configure(format, surface, null, 0);
                codecHolder[0].start();
                configuredGeneration = surfaceGeneration;
                Log.d(TAG, "  decoder configured once for the stream");
            }
            MediaCodec codec = codecHolder[0];
            if (clock[0] < 0) clock[0] = -1;   // set from the first decoded frame

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer sampleBuffer = ByteBuffer.allocate(512 * 1024);
            boolean inputDone = false;
            int samples = 0;
            long basePtsUs = -1, baseNs = System.nanoTime();
            while (running && !released) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        sampleBuffer.clear();
                        long declared = android.os.Build.VERSION.SDK_INT >= 28 ? extractor.getSampleSize() : -1;
                        int size = extractor.readSampleData(sampleBuffer, 0);
                        if (declared > 0 && declared != size && samples < 8) {
                            Log.w(TAG, "  sample " + samples + " declares " + declared + " but read " + size);
                        }
                        if (size < 0) {
                            // No end-of-stream here: the next segment carries straight on.
                            inputDone = true;
                        } else {
                            samples++;
                            long pts = extractor.getSampleTime();
                            int sampleFlags = extractor.getSampleFlags();
                            int flags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            sampleBuffer.limit(size);
                            sampleBuffer.position(0);
                            if (samples == 1) {
                                Log.d(TAG, "  first sample of " + file.getName() + " " + size + " bytes"
                                        + " nals " + nalSummary(sampleBuffer, size)
                                        + " " + sliceHeads(sampleBuffer, size));
                            }
                            byte[] payload = null;
                            if (!startsWithStartCode(sampleBuffer)) {
                                payload = toAnnexB(sampleBuffer);
                            } else if (CONVERT_TO_AVCC) {
                                payload = toAvcc(sampleBuffer, size, flags != 0);
                            }
                            ByteBuffer buffer = codec.getInputBuffer(in);
                            buffer.clear();
                            if (payload != null) buffer.put(payload);
                            else buffer.put(sampleBuffer);
                            codec.queueInputBuffer(in, 0, payload != null ? payload.length : size, pts, flags);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, inputDone ? 10_000 : 0);
                if (out >= 0) {
                    if (basePtsUs < 0) {
                        basePtsUs = info.presentationTimeUs;
                        baseNs = System.nanoTime();
                    }
                    // No pacing here. Sleeping to hold the picture back also holds back the *feeding*, and
                    // a stream with B frames needs the decoder to be several frames ahead before it can
                    // emit anything in order - starving it is a way to get a partly decoded frame out of a
                    // perfectly good one. Real players pace presentation, not decoding.
                    if (!loggedPacing) {
                        loggedPacing = true;
                        Log.d(TAG, "  first output pts " + info.presentationTimeUs);
                    }
                    if (USE_SURFACE_OUTPUT) {
                        codec.releaseOutputBuffer(out, true);
                    } else {
                        android.media.Image image = null;
                        try {
                            image = codec.getOutputImage(out);
                            if (image != null) glView.draw(image);
                        } catch (Throwable t) {
                            if (mine == 0) Log.w(TAG, "draw failed: " + t);
                        } finally {
                            if (image != null) image.close();
                        }
                        codec.releaseOutputBuffer(out, false);
                    }
                    frames++;
                    mine++;
                    if (!readyReported) {
                        readyReported = true;
                        Log.d(TAG, "first frame after " + frames + " buffers");
                        if (!released) listener.onReady();
                    }
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "  output format " + codec.getOutputFormat());
                }
                if (inputDone && out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    long waited = (System.nanoTime() - baseNs) / 1_000_000L;
                    if (waited > 1500) break;   // the decoder has nothing left for this segment
                }
            }
            if (samples > 0) Log.d(TAG, file.getName() + ": " + samples + " samples, " + mine
                    + " frames, total " + frames);
        } finally {
            extractor.release();
        }
    }

    private SurfaceTexture surfaceTextureNow() {
        synchronized (surfaceLock) {
            return surfaceTexture;
        }
    }

    // ------------------------------------------------------------------ http

    /** The master's variants, best first by bandwidth, filtered to something a TV can start quickly. */
    private String pickVariant(String masterUrl) throws Exception {
        Playlist master = fetchPlaylist(masterUrl);
        if (master == null || master.uris.isEmpty()) return null;
        int best = -1;
        long bestBandwidth = -1;
        for (int i = 0; i < master.uris.size(); i++) {
            long bandwidth = master.bandwidth.get(i);
            // 720p-ish: high enough for a television, low enough to start in a second or two.
            if (bandwidth > 2_600_000L) continue;
            if (bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth;
                best = i;
            }
        }
        if (best < 0) best = master.uris.size() - 1;   // all too big: take the last (usually smallest)
        return resolve(masterUrl, master.uris.get(best));
    }

    private static final class Playlist {
        long sequence;
        final List<String> uris = new ArrayList<>();
        final List<Long> bandwidth = new ArrayList<>();
    }

    private Playlist fetchPlaylist(String target) throws Exception {
        String body = get(target);
        if (body == null) return null;
        Playlist list = new Playlist();
        String pendingUri = null;
        long pendingBandwidth = 0;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                list.sequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                int at = line.indexOf("BANDWIDTH=");
                if (at >= 0) {
                    int end = at + 10;
                    while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                    pendingBandwidth = Long.parseLong(line.substring(at + 10, end));
                }
                pendingUri = "";
            } else if (line.startsWith("#EXTINF")) {
                pendingUri = "";
            } else if (line.charAt(0) != '#') {
                if (pendingUri != null) {
                    list.uris.add(line);
                    list.bandwidth.add(pendingBandwidth);
                    pendingUri = null;
                    pendingBandwidth = 0;
                }
            }
        }
        return list;
    }

    private String get(String target) throws Exception {
        HttpURLConnection c = open(target);
        try {
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "http " + c.getResponseCode() + " for " + target);
                return null;
            }
            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            reader.close();
            return out.toString();
        } finally {
            c.disconnect();
        }
    }

    private File download(String target, long sequence) throws Exception {
        File dir = new File(context.getCacheDir(), "hls");
        if (!dir.exists() && !dir.mkdirs()) return null;
        File file = new File(dir, sequence + ".ts");
        if (file.exists() && file.length() > 0) return file;
        HttpURLConnection c = open(target);
        try {
            if (c.getResponseCode() != 200) {
                Log.w(TAG, "http " + c.getResponseCode() + " for segment " + sequence);
                return null;
            }
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(file);
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.close();
            in.close();
        } finally {
            c.disconnect();
        }
        prune(dir);
        return file;
    }

    /**
     * Keeps the cache to the last few segments: a live window never looks back.
     *
     * <p>By modification time, not by the number in the file name. The sequence numbers restart per CDN
     * and the CDN changes between runs - one host's 447475690 is far "older" than another's 1789665700 -
     * so sorting by name deleted the segment that had just been downloaded and the extractor was handed
     * a file that was no longer there.
     */
    private void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_SEGMENTS) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP_SEGMENTS; i++) {
            if (!files[i].delete()) Log.w(TAG, "could not delete " + files[i]);
        }
    }

    private HttpURLConnection open(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String resolve(String base, String relative) {
        try {
            return new URL(new URL(base), relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private void fail(String reason) {
        Log.w(TAG, "failed: " + reason);
        if (!released) listener.onError(reason);
    }

    /**
     * Draws decoded frames ourselves.
     *
     * <p>Letting the decoder render into the TextureView's surface produced a stride-mismatched picture:
     * real content in the top seventy rows and flat green below it. The byte buffer output is verified
     * good, so the frames are taken as an {@link android.media.Image} - which carries each plane's row
     * stride, exactly what the surface path got wrong - and the planes go up as textures.
     *
     * <p>Hand-rolled EGL and GLES20/30, the same shape as LightDustView's pipeline: no dependencies, one
     * context, and the decode thread is the only thread that touches it.
     */
    /**
     * Draws decoded frames ourselves.
     *
     * <p>Two things were learned getting here. Letting the decoder render into the TextureView's surface
     * gives a stride-mismatched picture, so the frames are taken from the decoder's byte buffers instead.
     * And on this driver the luma plane uploads and samples correctly while chroma sampled from its own
     * texture unit comes back zero - which converts a black frame to flat green - so rather than keep
     * negotiating with texture formats, the frame is converted to RGBA on the CPU and sent as one plain
     * texture through one sampler.
     *
     * <p>Hand-rolled EGL and GLES, the same shape as LightDustView's pipeline: no dependencies, one
     * context, and the decode thread is the only thread that touches it.
     */
    private static final class GlView {
        private EGLDisplay display;
        private EGLContext context;
        private EGLSurface surface;
        private SurfaceTexture surfaceTexture;
        private int program, uTexture, uScale;
        /**
         * Held for the lifetime of the context. A direct buffer handed to glVertexAttribPointer is read
         * again on every draw, so letting the only reference go out of scope in build() left the driver
         * reading freed memory: the quad came out as a sliver of garbage across the top of the screen.
         */
        private java.nio.FloatBuffer quad;
        private final Object frameLock = new Object();
        private int textureId;
        private int videoWidth, videoHeight;
        private byte[] rgba;
        private int[] argb;
        private java.nio.ByteBuffer upload;
        private boolean loggedFirstFrame;
        /** Where the one-off frame dump goes; set from the outer class, which owns the context. */
        java.io.File dumpDir;
        private int frameLogged;
        private int draws;

        android.graphics.Bitmap snapshot() {
            synchronized (frameLock) {
                if (rgba == null || videoWidth <= 0 || videoHeight <= 0) return null;
                int[] pixels = new int[videoWidth * videoHeight];
                for (int i = 0, j = 0; i < pixels.length; i++, j += 4) {
                    pixels[i] = 0xFF000000 | ((rgba[j] & 0xFF) << 16)
                            | ((rgba[j + 1] & 0xFF) << 8) | (rgba[j + 2] & 0xFF);
                }
                return android.graphics.Bitmap.createBitmap(pixels, videoWidth, videoHeight,
                        android.graphics.Bitmap.Config.ARGB_8888);
            }
        }

        boolean attach(SurfaceTexture st) {
            release();
            surfaceTexture = st;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(TAG, "eglInitialize failed");
                return false;
            }
            int[] attrs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                Log.e(TAG, "no EGL config");
                return false;
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], new Surface(st),
                    new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Log.e(TAG, "egl setup failed");
                release();
                return false;
            }
            program = build();
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            uScale = GLES20.glGetUniformLocation(program, "uScale");
            GLES20.glUseProgram(program);
            GLES20.glUniform1i(uTexture, 0);
            int[] id = new int[1];
            GLES20.glGenTextures(1, id, 0);
            textureId = id[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            Log.d(TAG, "gl " + GLES20.glGetString(GLES20.GL_VERSION));
            return true;
        }

        /**
         * Converts one frame to RGBA and shows it.
         *
         * <p>Integer arithmetic in the standard BT.601 studio-swing form: luma is scaled out of 16-235 and
         * the chroma offsets are centred on 128. Everything is read with the plane's own row stride, so a
         * padded frame would still come out right.
         */
        void draw(android.media.Image image) {
            if (program == 0) return;
            draws++;
            int width = image.getWidth(), height = image.getHeight();
            if (width != videoWidth || height != videoHeight) {
                videoWidth = width;
                videoHeight = height;
                rgba = new byte[width * height * 4];
                argb = new int[width * height];
                upload = java.nio.ByteBuffer.allocateDirect(width * height * 4)
                        .order(java.nio.ByteOrder.nativeOrder());
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                Log.d(TAG, "video " + width + "x" + height + " -> one RGBA texture");
            }
            android.media.Image.Plane[] data = image.getPlanes();
            if (data.length < 3) return;
            if (frameLogged < 3 || draws % 40 == 0) {
                frameLogged++;
                StringBuilder head = new StringBuilder();
                for (int i = 0; i < 3; i++) {
                    java.nio.ByteBuffer b = data[i].getBuffer().duplicate();
                    b.position(0);
                    int zero = 0, nonZero = 0;
                    for (int j = 0; j < Math.min(20000, b.remaining()); j++) {
                        if (b.get(j) == 0) zero++; else nonZero++;
                    }
                    head.append(" plane").append(i).append("[")
                            .append(String.format(java.util.Locale.ROOT, "%02X ", b.get(0) & 0xFF))
                            .append("zero ").append(zero * 100 / Math.max(1, zero + nonZero)).append("%]");
                }
                Log.d(TAG, "  frame " + frameLogged + " " + width + "x" + height + head);
            }
            java.nio.ByteBuffer yPlane = data[0].getBuffer();
            java.nio.ByteBuffer uPlane = data[1].getBuffer();
            java.nio.ByteBuffer vPlane = data[2].getBuffer();
            int yRow = data[0].getRowStride(), yPixel = data[0].getPixelStride();
            int uRow = data[1].getRowStride(), uPixel = data[1].getPixelStride();
            int vRow = data[2].getRowStride(), vPixel = data[2].getPixelStride();
            int at = 0;
            synchronized (frameLock) {
            for (int row = 0; row < height; row++) {
                int yBase = row * yRow;
                int cBaseU = (row >> 1) * uRow;
                int cBaseV = (row >> 1) * vRow;
                for (int col = 0; col < width; col++) {
                    int y = (yPlane.get(yBase + col * yPixel) & 0xFF) - 16;
                    if (y < 0) y = 0;
                    int u = (uPlane.get(cBaseU + (col >> 1) * uPixel) & 0xFF) - 128;
                    int v = (vPlane.get(cBaseV + (col >> 1) * vPixel) & 0xFF) - 128;
                    int luma = 74 * y;                       // 1.164 in 6.6 fixed point
                    int r = (luma + 102 * v) >> 6;           // 1.596
                    int g = (luma - 25 * u - 52 * v) >> 6;   // 0.391 / 0.813
                    int b = (luma + 129 * u) >> 6;           // 2.018
                    rgba[at++] = (byte) (r < 0 ? 0 : r > 255 ? 255 : r);
                    rgba[at++] = (byte) (g < 0 ? 0 : g > 255 ? 255 : g);
                    rgba[at++] = (byte) (b < 0 ? 0 : b > 255 ? 255 : b);
                    rgba[at++] = (byte) 255;
                }
            }
            }
            upload.clear();
            upload.put(rgba);
            upload.position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, upload);
            if (!loggedFirstFrame) {
                loggedFirstFrame = true;
                try {
                    int[] argb = new int[width * height];
                    for (int i = 0, j = 0; i < argb.length; i++, j += 4) {
                        argb[i] = 0xFF000000 | ((rgba[j] & 0xFF) << 16)
                                | ((rgba[j + 1] & 0xFF) << 8) | (rgba[j + 2] & 0xFF);
                    }
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                            argb, width, height, android.graphics.Bitmap.Config.ARGB_8888);
                    java.io.File out = new java.io.File(dumpDir, "frame.png");
                    java.io.FileOutputStream stream = new java.io.FileOutputStream(out);
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream);
                    stream.close();
                    bitmap.recycle();
                    Log.d(TAG, "  frame dumped to " + out);
                } catch (Throwable t) {
                    Log.w(TAG, "frame dump failed: " + t);
                }
                Log.d(TAG, "  first converted frame, gl error 0x"
                        + Integer.toHexString(GLES20.glGetError()));
            }

            GLES20.glViewport(0, 0, eglWidth(), eglHeight());
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            int[] size = new int[2];
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, size, 0);
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, size, 1);
            float screenAspect = size[1] == 0 ? 1f : (float) size[0] / size[1];
            float videoAspect = height == 0 ? 1f : (float) width / height;
            if (videoAspect > screenAspect) GLES20.glUniform2f(uScale, 1f, screenAspect / videoAspect);
            else GLES20.glUniform2f(uScale, videoAspect / screenAspect, 1f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (!EGL14.eglSwapBuffers(display, surface)) {
                Log.w(TAG, "swap failed " + EGL14.eglGetError());
            }
        }

        private int eglWidth() {
            int[] value = new int[1];
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, value, 0);
            return value[0];
        }

        private int eglHeight() {
            int[] value = new int[1];
            EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, value, 0);
            return value[0];
        }

        void release() {
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                EGL14.eglTerminate(display);
            }
            display = null;
            context = null;
            surface = null;
            program = 0;
            textureId = 0;
            surfaceTexture = null;
        }

        private int build() {
            String vs = "attribute vec2 aPosition;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform vec2 uScale;\n"
                    + "void main(){ vUv = vec2(aPosition.x*.5+.5, .5-aPosition.y*.5);"
                    + " gl_Position = vec4(aPosition*uScale, 0.0, 1.0); }\n";
            String fs = "precision mediump float;\n"
                    + "varying vec2 vUv;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "void main(){ gl_FragColor = texture2D(uTexture, vUv); }\n";
            int v = compile(GLES20.GL_VERTEX_SHADER, vs);
            int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
            int id = GLES20.glCreateProgram();
            GLES20.glAttachShader(id, v);
            GLES20.glAttachShader(id, f);
            GLES20.glLinkProgram(id);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(id));
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            int loc = GLES20.glGetAttribLocation(id, "aPosition");
            quad = java.nio.ByteBuffer.allocateDirect(4 * 2 * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
            GLES20.glEnableVertexAttribArray(loc);
            GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad);
            return id;
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(shader));
            return shader;
        }
    }


    /**
     * Picks a decoder, preferring the one that works. createDecoderByType returns the vendor's
     * OMX.MS.AVC.Decoder on this television, which cannot set up its threads and answers every segment
     * with an empty end-of-stream buffer - no error, no frames, and the same thing the platform player
     * does. Codec2's software decoder and Google's are both fine; the first is preferred.
     */
    private static MediaCodec createDecoder(MediaFormat format) throws java.io.IOException {
        String mime = format.getString(MediaFormat.KEY_MIME);
        String chosen = null;
        try {
            for (android.media.MediaCodecInfo info : new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                boolean supported = false;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) { supported = true; break; }
                }
                if (!supported) continue;
                Log.d(TAG, "  decoder candidate " + info.getName());
                boolean preferred = info.getName().equals(PREFERRED_DECODER);
                boolean codec2 = info.getName().startsWith("c2.");
                if (preferred || (chosen == null && codec2)
                        || (chosen == null && !codec2 && !PREFERRED_DECODER.startsWith("c2."))) {
                    chosen = info.getName();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "codec list failed: " + t);
        }
        if (chosen != null) {
            Log.d(TAG, "  using decoder " + chosen);
            return MediaCodec.createByCodecName(chosen);
        }
        return MediaCodec.createDecoderByType(mime);
    }

    /** NAL type histogram of an annex-b sample, e.g. "1x12,5x1" - enough to see slice structure. */
    private static String nalSummary(ByteBuffer data, int size) {
        int[] counts = new int[32];
        int start = -1;
        for (int i = 0; i + 3 < size; i++) {
            boolean three = data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1;
            boolean four = !three && data.get(i) == 0 && data.get(i + 1) == 0
                    && data.get(i + 2) == 0 && data.get(i + 3) == 1;
            if (!three && !four) continue;
            if (start >= 0) counts[data.get(start) & 0x1F]++;
            start = i + (three ? 3 : 4);
            if (four) i++;
        }
        if (start >= 0 && start < size) counts[data.get(start) & 0x1F]++;
        StringBuilder out = new StringBuilder();
        for (int type = 0; type < 32; type++) {
            if (counts[type] == 0) continue;
            if (out.length() > 0) out.append(',');
            out.append(type).append('x').append(counts[type]);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** The first two fields of every slice header, and the SPS's real dimensions. */
    private static String sliceHeads(ByteBuffer data, int size) {
        StringBuilder out = new StringBuilder();
        int start = -1;
        for (int i = 0; i + 3 < size; i++) {
            boolean three = data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1;
            boolean four = !three && data.get(i) == 0 && data.get(i + 1) == 0
                    && data.get(i + 2) == 0 && data.get(i + 3) == 1;
            if (!three && !four) continue;
            int nalStart = i + (three ? 3 : 4);
            if (start >= 0) out.append(describe(data, start, i));
            start = nalStart;
            if (four) i++;
        }
        if (start >= 0 && start < size) out.append(describe(data, start, size));
        return out.length() == 0 ? "(no nal)" : out.toString();
    }

    private static String describe(ByteBuffer data, int from, int to) {
        if (from >= to || from >= data.limit()) return "";
        int type = data.get(from) & 0x1F;
        if (type == 7) {
            Bits bits = new Bits(data, from + 1, to);
            int profile = bits.u(8);
            bits.u(8);
            int level = bits.u(8);
            bits.ue();
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                    || profile == 83 || profile == 86 || profile == 118 || profile == 128) {
                int chroma = bits.ue();
                if (chroma == 3) bits.u(1);
                bits.ue();
                bits.ue();
                bits.u(1);
                if (bits.u(1) != 0) return " sps[scaling lists, not parsed]";
            }
            bits.ue();
            int poc = bits.ue();
            if (poc == 0) bits.ue();
            else if (poc == 1) {
                bits.u(1);
                bits.se();
                bits.se();
                int n = bits.ue();
                for (int i = 0; i < n && i < 256; i++) bits.se();
            }
            bits.ue();
            bits.u(1);
            int widthMbs = bits.ue() + 1;
            int heightUnits = bits.ue() + 1;
            int frameOnly = bits.u(1);
            int heightMbs = heightUnits * (2 - frameOnly);
            return " sps[profile " + profile + " level " + level + " mbs " + widthMbs + "x" + heightMbs
                    + " -> " + (widthMbs * 16) + "x" + (heightMbs * 16) + "]";
        }
        if (type == 1 || type == 5) {
            Bits bits = new Bits(data, from + 1, to);
            return " slice[first_mb " + bits.ue() + " type " + bits.ue() + " bytes " + (to - from) + "]";
        }
        return "";
    }

    /** Just enough H.264 bit reading for the fields above: u(n), ue(v) and se(v). */
    private static final class Bits {
        private final ByteBuffer data;
        private final int end;
        private int at, bit;

        Bits(ByteBuffer data, int from, int to) {
            this.data = data;
            this.at = from;
            this.end = Math.min(to, data.limit());
        }

        int u(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (at >= end) return value;
                value = (value << 1) | ((data.get(at) >> (7 - bit)) & 1);
                if (++bit == 8) { bit = 0; at++; }
            }
            return value;
        }

        /** Exp-Golomb: n leading zeros, then n bits, and the value is 2^n - 1 plus those bits. */
        int ue() {
            int zeros = 0;
            while (at < end && u(1) == 0 && zeros < 32) zeros++;
            if (zeros == 0) return 0;
            return (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int value = ue();
            return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
        }
    }

    private static boolean startsWithStartCode(ByteBuffer data) {
        if (data.limit() < 4) return false;
        return data.get(0) == 0 && data.get(1) == 0
                && (data.get(2) == 1 || (data.get(2) == 0 && data.get(3) == 1));
    }

    /** Rewrites a length-prefixed AVC sample as start-code delimited NALs. */
    private static byte[] toAnnexB(ByteBuffer data) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.limit() + 16);
        int i = 0;
        while (i + 4 <= data.limit()) {
            int length = ((data.get(i) & 0xFF) << 24) | ((data.get(i + 1) & 0xFF) << 16)
                    | ((data.get(i + 2) & 0xFF) << 8) | (data.get(i + 3) & 0xFF);
            i += 4;
            if (length <= 0 || i + length > data.limit()) return null;
            out.write(new byte[]{0, 0, 0, 1}, 0, 4);
            for (int j = 0; j < length; j++) out.write(data.get(i + j));
            i += length;
        }
        return i == data.limit() ? out.toByteArray() : null;
    }

    /** Rewrites an annex-b sample as length-prefixed NALs, which is what AVCC codec data implies. */
    private static byte[] toAvcc(ByteBuffer data, int size, boolean keyFrame) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(size + 32);
        int start = -1;
        int count = 0;
        for (int i = 0; i + 3 < size; i++) {
            boolean three = data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1;
            boolean four = !three && data.get(i) == 0 && data.get(i + 1) == 0
                    && data.get(i + 2) == 0 && data.get(i + 3) == 1;
            if (!three && !four) continue;
            int nalStart = i + (three ? 3 : 4);
            if (start >= 0) {
                int end = i;
                while (end > start && data.get(end - 1) == 0) end--;
                int length = end - start;
                out.write((length >>> 24) & 0xFF);
                out.write((length >>> 16) & 0xFF);
                out.write((length >>> 8) & 0xFF);
                out.write(length & 0xFF);
                for (int j = 0; j < length; j++) out.write(data.get(start + j));
                count++;
            }
            start = nalStart;
            if (four) i++;
        }
        if (start >= 0 && start < size) {
            int length = size - start;
            out.write((length >>> 24) & 0xFF);
            out.write((length >>> 16) & 0xFF);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
            for (int j = 0; j < length; j++) out.write(data.get(start + j));
            count++;
        }
        return count == 0 ? null : out.toByteArray();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
