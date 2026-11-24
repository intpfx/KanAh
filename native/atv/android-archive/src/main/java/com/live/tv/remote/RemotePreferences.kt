package com.live.tv.remote

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/** Receiver identity and opt-in; independent of the activity/player lifecycle. */
class RemotePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("apple-tv-remote", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    var deviceName: String
        get() = prefs.getString("name", "Live TV") ?: "Live TV"
        set(value) {
            val clean = value.filter { !it.isISOControl() }.trim().take(48).ifEmpty { "Live TV" }
            prefs.edit().putString("name", clean).apply()
        }

    val adbPort: Int get() = 5555
    val pin: String get() = synchronized(IDENTITY_LOCK) {
        prefs.getString("pin", null) ?: newPin().also { prefs.edit().putString("pin", it).commit() }
    }
    val pairingPin: String get() = pin
    val deviceId: String get() = identity("deviceId") {
        val bytes = ByteArray(6).also { RANDOM.nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() or 2) and 254).toByte()
        bytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
    }
    val serverId: String get() = identity("serverId") { UUID.randomUUID().toString().uppercase() }

    fun rotatePin(): String = synchronized(IDENTITY_LOCK) {
        newPin().also { prefs.edit().putString("pin", it).commit() }
    }

    private fun identity(key: String, generate: () -> String): String = synchronized(IDENTITY_LOCK) {
        prefs.getString(key, null) ?: generate().also { prefs.edit().putString(key, it).commit() }
    }

    // Native SRP accepts an integer PIN; avoid a displayed leading zero being lost there.
    private fun newPin(): String = (1_000 + RANDOM.nextInt(9_000)).toString()

    companion object {
        private val IDENTITY_LOCK = Any()
        private val RANDOM = SecureRandom()
    }
}

data class RemoteStatus(
    val enabled: Boolean = false,
    val protocolState: String = "stopped",
    val adbState: String = "disabled",
    val detail: String = "遥控接收服务已关闭",
    /** Wall-clock time for display only; service expiration uses a monotonic timer. */
    val pairingUntil: Long = 0,
    val localIp: String? = null,
)
