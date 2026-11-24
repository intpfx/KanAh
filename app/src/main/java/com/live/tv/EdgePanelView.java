package com.live.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** A continuous black surface growing from the left screen edge, without a detached card. */
final class EdgePanelView extends View {
    // Keep the compact navigation surface vertically centered with symmetric shoulders.
    static final float TOP_RATIO = .28f, BOTTOM_RATIO = 1f - TOP_RATIO;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();

    EdgePanelView(Context context) {
        super(context);
        ink.setColor(Color.BLACK);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        buildContour(contour, w, h * TOP_RATIO, h * BOTTOM_RATIO, w * .5f);
    }

    /** Shared geometry keeps the launch curtain and the settled sidebar exactly coincident. */
    static void buildContour(Path contour, float width, float top, float bottom, float radius) {
        contour.reset();
        contour.moveTo(0, top);
        float shoulderStart = width - 2 * radius;
        contour.lineTo(shoulderStart, top);
        shoulder(contour, shoulderStart, top, radius, false, false);
        shoulder(contour, width - radius, top + radius, radius, true, false);
        contour.lineTo(width, bottom - 2 * radius);
        shoulder(contour, width - radius, bottom - 2 * radius, radius, true, true);
        shoulder(contour, shoulderStart, bottom - radius, radius, false, true);
        contour.lineTo(0, bottom);
        contour.close();
    }

    /** Superellipse corners have zero curvature at the tangencies, including the shoulder join. */
    private static void shoulder(Path contour, float x, float y, float radius, boolean convex, boolean reverse) {
        double power = 2.0 / 3.3;
        for (int step = 1; step <= 64; step++) {
            double angle = Math.PI * .5 * (reverse ? 1 - step / 64.0 : step / 64.0);
            float sin = (float) Math.pow(Math.sin(angle), power);
            float cos = (float) Math.pow(Math.cos(angle), power);
            float px = convex ? x + radius * sin : x + radius * (1 - cos);
            float py = convex ? y + radius * (1 - cos) : y + radius * sin;
            if (reverse) py = y + radius - (py - y);
            contour.lineTo(px, py);
        }
    }

    @Override protected void onDraw(Canvas canvas) { canvas.drawPath(contour, ink); }
}
