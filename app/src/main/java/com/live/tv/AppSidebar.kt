package com.live.tv

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.ListView
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import com.live.tv.media.VodLibraryActivity
import java.lang.ref.WeakReference
import java.util.function.Consumer

/** The same navigation surface over any app page, without changing its media lifecycle. */
class AppSidebar @JvmOverloads constructor(
    private val activity: Activity,
    private val currentDestination: String = ""
) {
    private var popup: Dialog? = null
    private var navigation: TvUi? = null
    private var previousFocus: WeakReference<View>? = null
    private var previousSelection: AdapterSelection? = null
    private var restoreFocus = true
    private var disposed = false
    private var routed = false
    // A closing dialog can disappear before the physical key is released. Its remaining edges
    // still belong to the menu, including repeat events delivered to the original activity.
    private val capturedKeys = mutableSetOf<Int>()

    fun isShowing() = popup?.isShowing == true

    fun show() = showFrom(activity.currentFocus)

    private fun showFrom(focus: View?) {
        if (disposed || activity.isFinishing || activity.isDestroyed) return
        navigation?.let { it.openNavigation(destinationIndex()); return }
        previousFocus = focus?.let(::WeakReference)
        previousSelection = rememberSelection(focus)
        restoreFocus = true
        routed = false
        val dialog = object : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
            override fun dispatchKeyEvent(event: KeyEvent) = handleKey(event) || super.dispatchKeyEvent(event)
        }
        val ui = TvUi(activity, object : TvUi.Actions {
            override fun live() = navigate(DESTINATION_LIVE)
            override fun library() = navigate(DESTINATION_LIBRARY)
            override fun desktopMode() = toggleDesktopMode()
            override fun settings() = openSystemSettings()
            override fun select(channel: Int) {}
            override fun retry() {}
            override fun exit() = close()
            override fun captureFrame(ready: Consumer<Bitmap>) {}
            override fun setPictureHidden(hidden: Boolean) {}
        })
        popup = dialog
        navigation = ui
        ui.tag = "app-sidebar"
        ui.setNavigationOnly(true)
        ui.setOnNavigationClosed { releaseDialog(dialog, ui) }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(ui, ViewGroup.LayoutParams(-1, -1))
        dialog.setOnDismissListener { releaseDialog(dialog, ui) }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ui.openNavigation(destinationIndex())
    }

    fun handleKey(event: KeyEvent) = handleKeyFrom(event, null)

    /** Drain a dialog-owned key after its window closes, without claiming a new MENU gesture. */
    fun consumeCapturedKey(event: KeyEvent): Boolean {
        if (disposed) return false
        if (event.action == KeyEvent.ACTION_UP && capturedKeys.remove(event.keyCode)) return true
        return event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode in capturedKeys
    }

    private fun handleKeyFrom(event: KeyEvent, owner: Dialog?): Boolean {
        if (disposed) return false
        val key = event.keyCode
        if (consumeCapturedKey(event)) return true
        if (key == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                capturedKeys.add(key)
                // MiTV reinjects MENU DOWN/UP with FLAG_CANCELED after intercepting it.
                // Accept that first DOWN; releases and held repeats still cannot toggle again.
                if (event.repeatCount == 0) {
                    if (navigation?.isOpen == true) close()
                    else showFrom(owner?.currentFocus ?: activity.currentFocus)
                }
            }
            return true
        }
        if (!isShowing()) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            capturedKeys.add(key)
            if (!event.isCanceled) {
                if (key == KeyEvent.KEYCODE_BACK) {
                    if (event.repeatCount == 0) close()
                } else if (navigation?.isOpen == true) navigation?.dispatchKeyEvent(event)
            }
        }
        return true
    }

    /** Existing app dialogs keep their own keys while closed and regain their original focus. */
    @JvmOverloads fun attachTo(dialog: Dialog, fallback: DialogInterface.OnKeyListener? = null) {
        dialog.setOnKeyListener { owner, key, event ->
            handleKeyFrom(event, dialog) || fallback?.onKey(owner, key, event) == true
        }
    }

    fun close() { navigation?.close() }

    private fun releaseDialog(dialog: Dialog, ui: TvUi) {
        if (popup !== dialog) return
        // OnDismissListener is posted asynchronously. Clear ownership now so dismiss -> show
        // in one lifecycle turn cannot reopen a detached TvUi or close its replacement later.
        popup = null
        navigation = null
        val focused = previousFocus?.get()
        val selection = previousSelection
        previousFocus = null
        previousSelection = null
        ui.setOnNavigationClosed(null)
        dialog.setOnDismissListener(null)
        ui.dispose()
        dialog.dismiss()
        if (restoreFocus && !disposed && !activity.isFinishing && !activity.isDestroyed &&
            focused?.isAttachedToWindow == true && focused.isFocusable) {
            focused.requestFocusFromTouch()
            // A single-choice dialog can have a checked row but no remote selection in touch
            // mode. Restoring only focus makes the next DOWN land on that row again.
            selection?.restore()
        }
    }

    private class AdapterSelection(view: AdapterView<*>, adapter: Adapter, private val position: Int) {
        private val view = WeakReference(view)
        private val adapter = WeakReference(adapter)
        fun restore() {
            val target = view.get() ?: return
            val original = adapter.get() ?: return
            if (target.isAttachedToWindow && target.adapter === original && position in 0 until original.count)
                target.setSelection(position)
        }
    }

    private fun rememberSelection(focus: View?): AdapterSelection? {
        var view = focus
        while (view != null && view !is AdapterView<*>) view = view.parent as? View
        val target = view as? AdapterView<*> ?: return null
        val adapter = target.adapter ?: return null
        val position = target.selectedItemPosition.takeIf { it >= 0 }
            ?: (target as? ListView)?.checkedItemPosition ?: return null
        return if (position in 0 until adapter.count) AdapterSelection(target, adapter, position) else null
    }

    /** Release the window on Activity.onStop; the same helper can be opened after a warm return. */
    fun dismiss() {
        restoreFocus = false
        val dialog = popup
        val ui = navigation
        if (dialog != null && ui != null) releaseDialog(dialog, ui)
        capturedKeys.clear()
    }

    fun dispose() {
        disposed = true
        dismiss()
    }

    private fun destinationIndex() = when (currentDestination) {
        DESTINATION_LIBRARY -> 1
        else -> 0
    }

    private fun openSystemSettings() {
        if (!DesktopMode.openSystemSettings(activity))
            Toast.makeText(activity, "此设备未提供可打开的系统设置页面", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDesktopMode() {
        val message = DesktopMode.toggleFromSidebar(activity)
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun navigate(destination: String) {
        if (routed || disposed || activity.isFinishing || activity.isDestroyed) return
        routed = true
        if (destination == currentDestination) return
        val target = when (destination) {
            DESTINATION_LIVE -> MainActivity::class.java
            else -> VodLibraryActivity::class.java
        }
        val intent = Intent(activity, target).putExtra(EXTRA_DESTINATION, destination)
        intent.addFlags(if (destination == DESTINATION_LIVE)
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        else Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)
    }

    companion object {
        const val EXTRA_DESTINATION = "app_sidebar_destination"
        const val DESTINATION_LIVE = "live"
        const val DESTINATION_LIBRARY = "library"
    }
}
