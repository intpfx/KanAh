package com.live.tv.remote

/** Only these fixed strings reach the shell. Network input is never interpolated. */
enum class RemoteKey(val shellCommand: String) {
    UP("input keyevent 19"), DOWN("input keyevent 20"), LEFT("input keyevent 21"), RIGHT("input keyevent 22"),
    SELECT("input keyevent 23"), BACK("input keyevent 4"), HOME("input keyevent 3"), MENU("input keyevent 82"),
    SELECT_LONG("input keyevent --longpress 23"),
    VOLUME_UP("input keyevent 24"), VOLUME_DOWN("input keyevent 25"), MUTE("input keyevent 164"),
    PLAY_PAUSE("input keyevent 85"), CHANNEL_UP("input keyevent 166"), CHANNEL_DOWN("input keyevent 167"),
    GUIDE("input keyevent 172"), POWER("input keyevent 26"), SLEEP("input keyevent 223"), WAKE("input keyevent 224");

    companion object {
        fun fromProtocol(button: String): RemoteKey? = when (button) {
            "up" -> UP; "down" -> DOWN; "left" -> LEFT; "right" -> RIGHT
            "select", "confirm" -> SELECT
            "select_long" -> SELECT_LONG
            // Apple Remote's Menu button means back, not Android's contextual menu.
            "menu", "back" -> BACK
            "context_menu" -> MENU
            "home" -> HOME
            "volume_up" -> VOLUME_UP; "volume_down" -> VOLUME_DOWN; "mute" -> MUTE
            "play_pause" -> PLAY_PAUSE
            "channel_up" -> CHANNEL_UP; "channel_down" -> CHANNEL_DOWN; "guide" -> GUIDE
            "power" -> POWER; "sleep" -> SLEEP; "wake" -> WAKE
            else -> null
        }
    }
}
