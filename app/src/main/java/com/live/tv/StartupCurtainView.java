package com.live.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A single opaque vector surface: full-screen black becomes the existing screen-edge contour. */
final class StartupCurtainView extends View {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private final Path body = new Path();
    private float progress, horizontalReveal = 1f;

    StartupCurtainView(Context context) {
        super(context);
        ink.setColor(Color.BLACK);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setProgress(float value) {
        progress = Math.max(0, Math.min(1, value));
        rebuild(); invalidate();
    }

    void setHorizontalReveal(float value) { horizontalReveal = Math.max(0, value); invalidate(); }

    private void rebuild() {
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        float edge = Math.round(width * .07f);
        contour.rewind();
        if (progress < .46f) {
            // A full-height curtain exits sideways before its ends close into the sidebar.
            float phase = smoothStep(progress / .46f);
            float right = width + (edge - width) * phase;
            float bow = width * .035f * (float)Math.sin(Math.PI * phase);
            contour.moveTo(0, 0);
            for (int step = 0; step <= 128; step++) {
                float y = height * step / 128f;
                float position = 2 * y / height - 1;
                contour.lineTo(right + bow * (1 - position * position), y);
            }
            contour.lineTo(0, height);
            contour.close();
            return;
        }
        if (progress >= 1) {
            EdgePanelView.buildContour(contour, edge, height * EdgePanelView.TOP_RATIO,
                    height * EdgePanelView.BOTTOM_RATIO, edge * .5f);
            return;
        }
        double time = (progress - .46) / .54;
        double damping = 6.4, frequency = 15.5;
        double spring = 1 - Math.exp(-damping * time) *
                (Math.cos(frequency * time) + damping / frequency * Math.sin(frequency * time));
        spring = 1 + (spring - 1) * (1 - smoothStep((float)((time - .82) / .18)));
        double firstCross = (Math.PI - Math.atan(frequency / damping)) / frequency;
        // The attachment closes once and then stays put, even while the body rebounds.
        float closure = time < firstCross ? (float)spring : 1f;
        float deformation = (float)spring - closure;
        float top = height * EdgePanelView.TOP_RATIO * closure;
        float bottom = height * (1 - (1 - EdgePanelView.BOTTOM_RATIO) * closure);
        EdgePanelView.buildContour(body, edge, top, bottom, edge * .5f * closure);
        float[] points = body.approximate(.25f);
        for (int i = 0; i < points.length; i += 3) {
            float x = points[i + 1], y = points[i + 2];
            float weight = x * x / (edge * edge);
            float bodyX = x * (1 - .92f * deformation * weight);
            // A monotone vertical warp keeps the S shoulders from folding over themselves.
            // Leave the wall-facing shoulder band unchanged, including its antialiased edge.
            float freeBody = smoothStep((Math.min(y - top, bottom - y) - edge * .5f) / edge);
            float bodyY = y + height * .08f * deformation * freeBody *
                    (float)Math.sin(2 * Math.PI * (y - top) / (bottom - top));
            if (i == 0) contour.moveTo(bodyX, bodyY);
            else contour.lineTo(bodyX, bodyY);
        }
        contour.close();
    }

    private static float smoothStep(float value) {
        float x = Math.max(0, Math.min(1, value));
        return x * x * (3 - 2 * x);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { rebuild(); }

    @Override protected void onDraw(Canvas canvas) {
        int saved = canvas.save();
        canvas.scale(horizontalReveal, 1, 0, getHeight() / 2f);
        canvas.drawPath(contour, ink);
        canvas.restoreToCount(saved);
    }
}
