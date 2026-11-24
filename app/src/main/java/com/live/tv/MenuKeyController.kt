package com.live.tv

import android.view.KeyEvent

/** A single MENU press opens the global search surface. */
class MenuKeyController(private val onSearch: () -> Unit) {
    private var menuDown = false

    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MENU) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                menuDown = true
                return true
            }
            KeyEvent.ACTION_UP -> {
                val tap = menuDown
                menuDown = false
                if (tap) onSearch()
                return true
            }
        }
        return true
    }

    fun cancel() { menuDown = false }
}
