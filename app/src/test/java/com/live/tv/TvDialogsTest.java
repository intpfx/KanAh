package com.live.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w960dp-h540dp-land-xhdpi")
public class TvDialogsTest {
    @Test public void nativeListRetainsLabelsCallbackAndBackBehavior() {
        var controller = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            AtomicInteger choice = new AtomicInteger(-1);
            AlertDialog dialog = TvDialogs.builder(controller.get()).selectedIndex(2)
                    .setTitle("选择分类").setItems(new String[]{"全部", "电影", "剧集"}, (d, index) -> choice.set(index))
                    .setNegativeButton("返回", null).show();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("剧集", dialog.getListView().getAdapter().getItem(2));
            View row = dialog.getListView().getAdapter().getView(2, null, dialog.getListView());
            assertEquals("剧集，当前选择", row.getContentDescription());
            assertEquals(2, dialog.getListView().getSelectedItemPosition());
            dialog.getListView().performItemClick(row, 2, 2);
            assertEquals(2, choice.get());
            assertFalse(dialog.isShowing());
            AlertDialog next = TvDialogs.builder(controller.get()).setTitle("剧集").setItems(new String[]{"第一集"}, null).show();
            next.onBackPressed();
            assertFalse(next.isShowing());
            assertFalse(controller.get().isFinishing());
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void keyboardSearchRunsTheSamePositiveActionAndClosesDialog() {
        var controller = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            EditText input = TvDialogs.searchInput(controller.get(), "影片名称");
            input.setText("16bit");
            AtomicInteger submitted = new AtomicInteger();
            AlertDialog dialog = TvDialogs.builder(controller.get()).setTitle("搜索当前来源").setView(input)
                    .setPositiveButton("搜索", (d, which) -> { assertEquals("16bit", input.getText().toString()); submitted.incrementAndGet(); })
                    .setNeutralButton("显示全部", null).setNegativeButton("返回", null).show();
            assertTrue(input.isFocused());
            assertEquals(EditorInfo.IME_ACTION_SEARCH, input.getImeOptions());
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1, submitted.get());
            assertFalse(dialog.isShowing());
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void longListsAreScrollableInsideSafeViewportAndFocusUsesDarkInk() {
        var controller = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            String[] episodes = new String[100];
            for (int index = 0; index < episodes.length; index++) episodes[index] = "第 " + (index + 1) + " 集";
            AlertDialog dialog = TvDialogs.builder(controller.get()).setTitle("选择剧集").setItems(episodes, null).setNegativeButton("返回", null).show();
            float density = controller.get().getResources().getDisplayMetrics().density;
            assertEquals(100, dialog.getListView().getAdapter().getCount());
            assertTrue(dialog.getListView().getLayoutParams().height <= (540 - 250) * density);
            assertEquals(Math.round(520 * density), dialog.getWindow().getAttributes().width);
            TextView button = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            button.requestFocus();
            assertEquals(0xff080a0e, button.getCurrentTextColor());
            assertEquals("返回", button.getText().toString());
            dialog.dismiss();
        } finally { controller.pause().stop().destroy(); }
    }

    @Test public void leavingListDoesNotMutateSharedNativeDrawableStatesAndReturningRestoresDarkInk() {
        var controller = Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            AlertDialog dialog = TvDialogs.builder(controller.get()).setTitle("点播来源")
                    .setItems(new String[]{"尤酷 JSON", "备用源"}, null).setNegativeButton("返回", null).show();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            TextView row = (TextView) dialog.getListView().getSelectedView();
            assertNotNull(row);
            assertEquals(0xff080a0e, row.getCurrentTextColor());

            TextView button = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            button.requestFocus();
            assertEquals(0xfff5f5f7, row.getCurrentTextColor());
            TextView nativePeer = new TextView(controller.get());
            nativePeer.setSelected(true);
            int[] nativeState = nativePeer.getDrawableState().clone();
            // The detached row has the same state flags as the native peer. Filtering
            // its selected state must not alter View's shared drawable-state cache.
            TextView detachedRow = (TextView) dialog.getListView().getAdapter().getView(0, null, dialog.getListView());
            detachedRow.setSelected(true);
            detachedRow.refreshDrawableState();
            assertArrayEquals(nativeState, nativePeer.getDrawableState());

            dialog.getListView().requestFocusFromTouch();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            row = (TextView) dialog.getListView().getSelectedView();
            assertEquals(0xff080a0e, row.getCurrentTextColor());
            android.view.ViewGroup buttons = (android.view.ViewGroup) button.getParent();
            assertFalse(buttons.getClipChildren());
            assertFalse(buttons.getClipToPadding());
            dialog.dismiss();
        } finally { controller.pause().stop().destroy(); }
    }
}
