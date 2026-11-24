package com.live.tv.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.FrameLayout
import com.live.tv.Channel

/** Live playback state and visibility for the shared native player interface. */
class LivePlaybackControls(context: Context, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun select(index: Int)
        fun toggle()
        fun settings()
        fun desktopMode()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var channels = emptyList<Channel>()
    private var selectedIndex = -1
    private var ready = false
    private var paused = false
    private var suppressed = false
    private var wanted = false
    private var browsing = false
    private var backClaimed = false
    private var disposed = false
    private val hideLater = Runnable { if (!paused) hide() }
    internal val chrome = MediaChromeView(context, object : MediaChromeView.Actions {
        override fun select(index: Int) {
            if (index in channels.indices) {
                hide()
                actions.select(index)
            }
        }

    })

    val isSelectingChannels: Boolean get() = chrome.hasFocus() && chrome.candidateIndex >= 0 && isShowingControls
    val isShowingControls: Boolean get() = wanted && !suppressed && !disposed

    init {
        id = generateViewId()
        isFocusable = false
        visibility = GONE
        addView(chrome, LayoutParams(-1, -1))
        updatePresentation()
    }

    fun setChannels(values: List<Channel>, selected: Int) {
        channels = values.toList()
        selectedIndex = selected.takeIf { it in channels.indices } ?: -1
        renderChrome()
    }

    fun playback(isReady: Boolean, isPaused: Boolean) {
        val changed = ready != isReady || paused != isPaused
        ready = isReady
        paused = isReady && isPaused
        renderChrome()
        if (!ready) hide() else if (changed) show()
    }

    fun show() {
        if (disposed) return
        val wasShowing = isShowingControls
        wanted = true
        updatePresentation()
        if (!wasShowing || !chrome.hasFocus()) chrome.resetFocus()
        interacted()
    }

    fun openChannels() {
        if (channels.isNotEmpty()) {
            browsing = true
            show()
        }
    }

    fun hide() {
        wanted = false
        browsing = false
        handler.removeCallbacks(hideLater)
        updatePresentation()
    }

    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        updatePresentation()
    }

    private fun renderChrome() {
        val title = channels.getOrNull(selectedIndex)?.name ?: "电视直播"
        chrome.render(
            title,
            "",
            channels.map { MediaChromeView.Item(it.name) },
            selectedIndex,
        )
    }

    private fun updatePresentation() {
        val showing = isShowingControls
        descendantFocusability = if (showing) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        visibility = if (showing) VISIBLE else GONE
        if (!showing) clearFocus()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (suppressed || disposed) return false
        val key = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (key == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) backClaimed = browsing && chrome.hasFocus()
            if (!backClaimed) return false
            if (!down) {
                backClaimed = false
                if (!event.isCanceled) hide()
            }
            return true
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) return true
        val directional = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
        if (!chrome.hasFocus() && down && directional && channels.isNotEmpty()) {
            openChannels()
            chrome.dispatchKeyEvent(event)
            return true
        }
        if (!chrome.hasFocus() || (!directional && key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER)) return false
        if (down) {
            if (directional) browsing = true
            interacted()
        }
        // MainActivity routes these keys here before its own channel shortcuts.
        chrome.dispatchKeyEvent(event)
        return true
    }

    private fun interacted() {
        handler.removeCallbacks(hideLater)
        if (isShowingControls && ready && !paused) handler.postDelayed(hideLater, 4000)
    }

    fun dispose() {
        disposed = true
        hide()
    }
}
