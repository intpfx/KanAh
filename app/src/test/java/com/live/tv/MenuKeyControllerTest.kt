package com.live.tv

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MenuKeyControllerTest {
    @Test fun menuTapOpensSearch() {
        var searches = 0
        val controller = MenuKeyController({ searches++ })
        controller.handle(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        controller.handle(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        assertEquals(1, searches)
    }
}
