package com.live.tv;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** Shared native TV dialogs. Android retains scrolling, button callbacks, IME and back handling. */
public final class TvDialogs {
    private static final int INK = 0xff080a0e;
    private static final int WHITE = 0xfff5f5f7;
    private static final int SECONDARY = 0xffb8bbc2;
    private static final int SURFACE = 0xff23262e;
    private static final int INSET = 0xff333740;
    private TvDialogs() {}

    public static Builder builder(Context context) { return new Builder(context); }

    public static EditText searchInput(Context context, String hint) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setTextSize(20);
        input.setTextColor(WHITE);
        input.setHintTextColor(SECONDARY);
        input.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14));
        input.setMinHeight(dp(context, 52));
        input.setBackground(round(INSET, dp(context, 14)));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = round(WHITE, 0);
            cursor.setSize(dp(context, 2), dp(context, 24));
            input.setTextCursorDrawable(cursor);
        }
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setSelectAllOnFocus(true);
        return input;
    }

    /** Capsule treatment for native actions such as website retry and dialog buttons. */
    public static void styleAction(TextView action) {
        Context context = action.getContext();
        action.setTextColor(focusText());
        action.setFocusable(true);
        action.setFocusableInTouchMode(true);
        action.setTextSize(17);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setAllCaps(false);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(context, 48));
        action.setMinimumHeight(dp(context, 48));
        action.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        action.setBackground(focusSurface(context, INSET, 20));
        action.setOnFocusChangeListener((view, focused) -> {
            view.animate().cancel();
            float scale = focused ? 1.055f : 1f;
            if (ValueAnimator.areAnimatorsEnabled()) view.animate().scaleX(scale).scaleY(scale).setDuration(160).start();
            else { view.setScaleX(scale); view.setScaleY(scale); }
        });
    }

    public static final class Builder extends AlertDialog.Builder {
        private int selectedIndex = -1;
        private EditText search;

        private Builder(Context context) { super(context, R.style.TvDialogTheme); }

        /** Focus the current choice while keeping selection separate from remote focus. */
        public Builder selectedIndex(int index) { selectedIndex = index; return this; }

        @Override public Builder setView(View view) {
            search = view instanceof EditText ? (EditText) view : null;
            FrameLayout inset = new FrameLayout(getContext());
            inset.setPadding(dp(getContext(), 24), dp(getContext(), 8), dp(getContext(), 24), dp(getContext(), 16));
            inset.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            super.setView(inset);
            return this;
        }

        @Override public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
            // Adapter values remain the source labels; decorations are presentation only.
            ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(getContext(), android.R.layout.simple_list_item_1, items) {
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    FocusRow row = convertView instanceof FocusRow ? (FocusRow) convertView : new FocusRow(getContext());
                    row.owner = parent instanceof ListView ? (ListView) parent : null;
                    row.setText(getItem(position));
                    row.setTextSize(20);
                    row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    row.setTextColor(focusText());
                    row.setSingleLine(true);
                    row.setEllipsize(TextUtils.TruncateAt.END);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(getContext(), 20), 0, dp(getContext(), 20), 0);
                    row.setMinHeight(dp(getContext(), 52));
                    row.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(getContext(), 52)));
                    row.setBackground(focusSurface(getContext(), Color.TRANSPARENT, 14));
                    row.setCompoundDrawablePadding(dp(getContext(), 12));
                    if (position == selectedIndex) {
                        GradientDrawable dot = round(SECONDARY, dp(getContext(), 3));
                        dot.setSize(dp(getContext(), 6), dp(getContext(), 6));
                        row.setCompoundDrawablesWithIntrinsicBounds(null, null, dot, null);
                        row.setContentDescription(getItem(position) + "，当前选择");
                    } else { row.setCompoundDrawables(null, null, null, null); row.setContentDescription(null); }
                    return row;
                }
            };
            super.setAdapter(adapter, listener);
            return this;
        }

        @Override public AlertDialog show() {
            AlertDialog dialog = super.show();
            styleDialog(dialog, selectedIndex, search);
            return dialog;
        }
    }

    private static void styleDialog(AlertDialog dialog, int selected, EditText input) {
        Context context = dialog.getContext();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(round(SURFACE, dp(context, 22)));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.min(dp(context, 520), context.getResources().getDisplayMetrics().widthPixels - dp(context, 96));
        params.dimAmount = .68f;
        if (input != null) {
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = dp(context, 48);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            input.setOnEditorActionListener((view, action, event) -> {
                boolean keyboardEnter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;
                if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_DONE && !keyboardEnter) return false;
                View submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (submit == null || !submit.isEnabled()) return false;
                submit.performClick();
                return true;
            });
        }
        window.setAttributes(params);
        TextView title = window.getDecorView().findViewById(context.getResources().getIdentifier("alertTitle", "id", "android"));
        if (title != null) {
            title.setTextColor(WHITE);
            title.setTextSize(26);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) { message.setTextColor(SECONDARY); message.setTextSize(18); message.setLineSpacing(dp(context, 4), 1f); }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setDivider(null);
            list.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
            list.setClipToPadding(false);
            list.setSelector(focusSurface(context, Color.TRANSPARENT, 14));
            list.setOnFocusChangeListener((view, focused) -> {
                for (int index = 0; index < list.getChildCount(); index++) list.getChildAt(index).refreshDrawableState();
            });
            int maxHeight = Math.max(dp(context, 104), context.getResources().getDisplayMetrics().heightPixels - dp(context, 250));
            ViewGroup.LayoutParams layout = list.getLayoutParams();
            layout.height = Math.min(dp(context, list.getAdapter().getCount() * 52 + 16), maxHeight);
            list.setLayoutParams(layout);
            list.setFocusable(true);
            list.requestFocusFromTouch();
            int focusIndex = Math.max(0, Math.min(selected, list.getAdapter().getCount() - 1));
            list.setSelection(focusIndex);
            list.post(() -> { if (dialog.isShowing() && list.hasFocus()) list.setSelection(focusIndex); });
        }
        for (int buttonId : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(buttonId);
            if (button == null || button.getVisibility() != View.VISIBLE) continue;
            styleAction(button);
            if (button.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) button.getParent();
                parent.setClipChildren(false);
                parent.setClipToPadding(false);
            }
            if (button.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) button.getLayoutParams();
                layout.setMargins(dp(context, 6), dp(context, 8), dp(context, 6), dp(context, 8));
                button.setLayoutParams(layout);
            }
        }
        if (input != null) input.requestFocus();
    }

    private static ColorStateList focusText() {
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_focused}, {android.R.attr.state_selected}, {android.R.attr.state_pressed}, {}}, new int[]{0x66f5f5f7, INK, INK, INK, WHITE});
    }

    // Platform AlertDialog supplies a native Material theme, not an AppCompat view context.
    @SuppressLint("AppCompatCustomView")
    private static final class FocusRow extends TextView {
        private ListView owner;
        private FocusRow(Context context) { super(context); }
        @Override protected int[] onCreateDrawableState(int extraSpace) {
            int[] states = super.onCreateDrawableState(extraSpace);
            // ListView keeps a remembered selection after focus moves to its buttons.
            // Only remote focus paints a white capsule; the current-choice dot remains.
            if (owner != null && !owner.hasFocus()) {
                // View can return a cached array shared by every view with the same state.
                states = states.clone();
                int write = 0;
                for (int state : states) if (state != android.R.attr.state_selected) states[write++] = state;
                while (write < states.length) states[write++] = 0;
            }
            return states;
        }
    }

    private static StateListDrawable focusSurface(Context context, int idle, int radius) {
        StateListDrawable result = new StateListDrawable();
        for (int state : new int[]{android.R.attr.state_focused, android.R.attr.state_selected, android.R.attr.state_pressed}) result.addState(new int[]{state}, round(WHITE, dp(context, radius)));
        result.addState(new int[]{}, round(idle, dp(context, radius)));
        return result;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        return background;
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
