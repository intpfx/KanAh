package com.live.tv.remote

/** JNI boundary; a missing ABI is reported in settings rather than crashing Live. */
object AtvNative {
    val available: Boolean = try {
        System.loadLibrary("atv_android")
        true
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    interface Callback {
        fun onButton(button: String)
        fun onTouch(x: Double, y: Double, phase: Int)
        fun onAudio(volume: Double, muted: Boolean)
        fun getAudioVolume(): Double
        fun isAudioMuted(): Boolean
        fun onEvent(event: String, detail: String)
    }

    external fun nativeStartServer(name: String, pin: Int, deviceId: String, serverId: String,
                                   localIp: String, identityDir: String, callback: Callback): Boolean
    external fun nativeStopServer()
    external fun nativeOpenPairingWindow(seconds: Int): Boolean
    external fun nativeClosePairingWindow()
    external fun nativeForgetControllers(identityDir: String): Boolean
}
