package com.live.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import androidx.appcompat.widget.AppCompatTextView;

/** Text-only destinations with the sidebar's shared remote focus treatment. */
final class SidebarNavTextView extends AppCompatTextView {
    private final int labelLength;

    SidebarNavTextView(Context context, String label) {
        super(context);
        labelLength = label.length();
        setText(label);
        setContentDescription(label);
        setGravity(Gravity.CENTER);
        setTypeface(Typeface.DEFAULT_BOLD);
        setIncludeFontPadding(false);
        setSingleLine(true);
        setPadding(0, 0, 0, 0);
        setBackgroundColor(Color.TRANSPARENT);
        setDefaultFocusHighlightEnabled(false);
        setTextColor(0xCCFFFFFF);
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // All three labels share a size, with space for the longest label and focused scaling.
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(height * 32f / 56f, width * .78f / Math.max(3, labelLength)));
    }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        setScaleX(focused ? 1.06f : 1f);
        setScaleY(focused ? 1.06f : 1f);
        setTextColor(focused ? Color.WHITE : 0xCCFFFFFF);
        invalidate();
    }
}
