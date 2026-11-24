package com.live.tv;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * One-shot diagnostics for tightly packed R8 uploads on OpenGL ES 3.0+.
 * Only Android framework / Java standard library APIs are used.
 *
 * Run GL methods on the renderer thread, with the renderer's EGL context current.
 * Freeze the CPU bytes and stop overwriting/reusing them during the comparison.
 * Pass the actual texture level-0 dimensions, not the window dimensions.
 *
 * This is diagnostic code, NOT a per-frame production path: glReadPixels stalls.
 * It has not been executed on the reported television / vendor driver.
 */
public final class GlUploadProbe {
    private GlUploadProbe() {}

    private static int pixelCount(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        // The largest temporary buffer is an RGBA readback.
        long n = (long) width * height;
        if (n > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("Image is too large");
        }
        return (int) n;
    }

    /** Independent copy of bytes [0, width*height); does not alter src state. */
    public static ByteBuffer freezeTightR8(ByteBuffer src, int width, int height) {
        int n = pixelCount(width, height);
        requireBytes(src, n);
        ByteBuffer view = src.duplicate();
        view.position(0);
        view.limit(n);
        ByteBuffer copy = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        copy.put(view);
        copy.flip();
        return copy;
    }

    /** Nonconstant two-dimensional pattern: every input byte is nonzero. */
    public static ByteBuffer makePattern(int width, int height) {
        int n = pixelCount(width, height);
        ByteBuffer data = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder());
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int value = 16 + (int) (((long) y * 37 + (y / 16L) * 11
                        + (x / 16L) * 53 + (x & 15)) % 224);
                data.put((byte) value);
            }
        }
        data.flip();
        return data;
    }

    public static void requireCurrentContext(EGLContext expected) {
        EGLContext current = EGL14.eglGetCurrentContext();
        if (current == null || EGL14.EGL_NO_CONTEXT.equals(current)) {
            throw new IllegalStateException("No EGL context is current on this thread");
        }
        if (expected != null && !expected.equals(current)) {
            throw new IllegalStateException("Wrong EGL context: current=" + current
                    + ", expected=" + expected);
        }
    }

    /** Read BEFORE resetting, to preserve evidence of leaked state. */
    public static String unpackState() {
        requireCurrentContext(null);
        checkGl("before unpack-state query (pre-existing errors)");
        String text = "thread=" + Thread.currentThread().getName()
                + " context=" + EGL14.eglGetCurrentContext()
                + " unpackBuffer=" + getInt(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING)
                + " alignment=" + getInt(GLES30.GL_UNPACK_ALIGNMENT)
                + " rowLength=" + getInt(GLES30.GL_UNPACK_ROW_LENGTH)
                + " skipPixels=" + getInt(GLES30.GL_UNPACK_SKIP_PIXELS)
                + " skipRows=" + getInt(GLES30.GL_UNPACK_SKIP_ROWS)
                + " imageHeight=" + getInt(GLES30.GL_UNPACK_IMAGE_HEIGHT)
                + " skipImages=" + getInt(GLES30.GL_UNPACK_SKIP_IMAGES);
        checkGl("unpack-state query");
        return text;
    }

    /**
     * Intentionally changes context state. Call immediately before client-memory
     * uploads of tightly packed planes. Does not change texture/sampler bindings.
     */
    public static void resetTightUnpack() {
        requireCurrentContext(null);
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0);
        // These two affect 3D/array transfers, not this ordinary TexImage2D upload.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_IMAGE_HEIGHT, 0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_IMAGES, 0);
        checkGl("reset tight unpack state");
    }

    /**
     * Attach an existing R8 level-0 texture directly to a temporary READ FBO.
     * Read GL_RGBA/GL_UNSIGNED_BYTE: inspect the R byte of each returned pixel.
     * There is no draw, shader, UV interpolation, filtering, or eglSwapBuffers.
     * Restores READ-FBO and all modified PACK state, including pack-PBO binding.
     * Does not change the draw FBO, viewport, texture bindings, or unpack state.
     *
     * No vertical flip is needed to compare with raw upload order: uploaded texel
     * y=0 and readback row y=0 refer to the same texture row.
     */
    public static ByteBuffer readR8AsRgba(int texture, int width, int height) {
        requireCurrentContext(null);
        checkGl("before R8 readback (pre-existing errors)");
        int n = pixelCount(width, height);
        if (texture == 0 || !GLES30.glIsTexture(texture)) {
            throw new IllegalArgumentException("Not an existing texture: " + texture);
        }
        int oldFbo = getInt(GLES30.GL_READ_FRAMEBUFFER_BINDING);
        int oldPackBuffer = getInt(GLES30.GL_PIXEL_PACK_BUFFER_BINDING);
        int oldAlignment = getInt(GLES30.GL_PACK_ALIGNMENT);
        int oldRowLength = getInt(GLES30.GL_PACK_ROW_LENGTH);
        int oldSkipRows = getInt(GLES30.GL_PACK_SKIP_ROWS);
        int oldSkipPixels = getInt(GLES30.GL_PACK_SKIP_PIXELS);
        ByteBuffer rgba = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder());
        int[] fbo = new int[1];
        try {
            GLES30.glGenFramebuffers(1, fbo, 0);
            if (fbo[0] == 0) throw new IllegalStateException("FBO allocation failed");
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0);
            GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_READ_FRAMEBUFFER);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("R8 read FBO incomplete: 0x"
                        + Integer.toHexString(status));
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1);
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0);
            checkGl("R8 readback setup");
            GLES30.glReadPixels(0, 0, width, height,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba);
            checkGl("R8 glReadPixels");
            rgba.position(0);
            rgba.limit(n * 4);
            return rgba;
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, oldFbo);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, oldPackBuffer);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, oldAlignment);
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, oldRowLength);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, oldSkipRows);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, oldSkipPixels);
            if (fbo[0] != 0) GLES30.glDeleteFramebuffers(1, fbo, 0);
        }
    }

    public static String compareR8(int texture, int width, int height,
                                   ByteBuffer frozenUpload) {
        ByteBuffer rgba = readR8AsRgba(texture, width, height);
        return compareCpuToRgba(frozenUpload, rgba, width, height);
    }

    /** CPU-only comparison; useful for verifying diagnostic/test data as well. */
    public static String compareCpuToRgba(ByteBuffer expected, ByteBuffer rgba,
                                          int width, int height) {
        int n = pixelCount(width, height);
        requireBytes(expected, n);
        requireBytes(rgba, n * 4);
        long mismatches = 0;
        int first = -1, maxDifference = 0;
        int cpuZeroRows = 0, gpuZeroRows = 0;
        for (int y = 0; y < height; ++y) {
            boolean cpuZero = true, gpuZero = true;
            for (int x = 0; x < width; ++x) {
                int i = y * width + x;
                int a = expected.get(i) & 255;
                int b = rgba.get(i * 4) & 255;
                cpuZero &= a == 0;
                gpuZero &= b == 0;
                int difference = Math.abs(a - b);
                if (difference != 0) {
                    if (first < 0) first = i;
                    ++mismatches;
                    maxDifference = Math.max(maxDifference, difference);
                }
            }
            if (cpuZero) ++cpuZeroRows;
            if (gpuZero) ++gpuZeroRows;
        }
        StringBuilder out = new StringBuilder();
        out.append("size=").append(width).append('x').append(height)
                .append(" mismatchedPixels=").append(mismatches).append('/').append(n)
                .append(" maxAbsDifference=").append(maxDifference)
                .append(" CPUzeroRows=").append(cpuZeroRows)
                .append(" GPUzeroRows=").append(gpuZeroRows);
        if (first >= 0) {
            out.append(" firstMismatch=(x=").append(first % width)
                    .append(",y=").append(first / width).append(')');
        }
        Set<Integer> rows = new LinkedHashSet<Integer>();
        int[] sampleRows = {0, 26, 27, height / 4, height / 2,
                (int) (3L * height / 4), height - 1};
        for (int row : sampleRows) if (row >= 0 && row < height) rows.add(row);
        if (first >= 0) rows.add(first / width);
        for (int row : rows) {
            out.append("\nrow=").append(row)
                    .append(" CPU[").append(rowStats(expected, row * width, width, 1))
                    .append("] GPU[").append(rowStats(rgba, row * width * 4, width, 4))
                    .append(']');
        }
        return out.toString();
    }

    private static String rowStats(ByteBuffer buffer, int start, int width, int step) {
        int min = 255, max = 0, zeros = 0;
        long sum = 0;
        CRC32 crc = new CRC32();
        for (int x = 0; x < width; ++x) {
            int value = buffer.get(start + x * step) & 255;
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            if (value == 0) ++zeros;
            crc.update(value);
        }
        return String.format(Locale.ROOT, "min=%d max=%d avg=%.3f zeros=%d crc=%08x",
                min, max, sum / (double) width, zeros, crc.getValue());
    }

    private static void requireBytes(ByteBuffer buffer, int count) {
        if (buffer == null || buffer.limit() < count) {
            throw new IllegalArgumentException("Need bytes [0," + count + ")");
        }
    }

    private static int getInt(int name) {
        int[] value = new int[1];
        GLES30.glGetIntegerv(name, value, 0);
        return value[0];
    }

    /** Also detects errors from draw calls if called immediately after drawing. */
    public static void checkGl(String phase) {
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < 32; ++i) {
            int error = GLES30.glGetError();
            if (error == GLES30.GL_NO_ERROR) break;
            if (errors.length() != 0) errors.append(',');
            errors.append("0x").append(Integer.toHexString(error));
        }
        if (errors.length() != 0) {
            throw new IllegalStateException(phase + ": " + errors);
        }
    }
}
