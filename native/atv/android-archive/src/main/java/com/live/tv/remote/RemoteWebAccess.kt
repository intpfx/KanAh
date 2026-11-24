package com.live.tv.remote

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Process-local management grants. Neither codes nor browser sessions survive a TV restart. */
class RemoteWebAccess(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val random: SecureRandom = SecureRandom(),
) {
    data class Grant(val code: String, val expiresAt: Long)
    private var grant: Grant? = null
    private var failures = 0
    private val sessions = LinkedHashMap<String, Long>()

    @Synchronized fun currentAccess(): Grant? = grant?.takeIf { clock() < it.expiresAt }
    @Synchronized fun openAccess(): Grant = currentAccess() ?: renewAccess()
    @Synchronized fun renewAccess(): Grant {
        failures = 0
        return Grant("%06d".format(java.util.Locale.ROOT, random.nextInt(1_000_000)), clock() + CODE_MS)
            .also { grant = it }
    }
    @Synchronized fun closeAccess() { grant = null; failures = 0 }
    @Synchronized fun managementAvailable(): Boolean = currentAccess() != null && failures < MAX_FAILURES

    /** Wrong attempts share one budget across all peers and do not reset on reconnect. */
    @Synchronized fun unlock(code: String): String? {
        val current = currentAccess() ?: return null
        if (failures >= MAX_FAILURES) return null
        val valid = code.matches(Regex("[0-9]{6}")) && MessageDigest.isEqual(
            code.toByteArray(Charsets.US_ASCII), current.code.toByteArray(Charsets.US_ASCII))
        if (!valid) { failures++; return null }
        pruneSessions()
        val bytes = ByteArray(32)
        var token: String
        do { random.nextBytes(bytes); token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }
        while (sessions.containsKey(token))
        if (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
        sessions[token] = clock() + SESSION_MS
        return token
    }
    @Synchronized fun authorized(token: String): Boolean {
        if (token.length != 43) return false
        pruneSessions()
        return sessions.containsKey(token)
    }
    @Synchronized fun logout(token: String) { sessions.remove(token) }
    private fun pruneSessions() { val now = clock(); sessions.entries.removeAll { now >= it.value } }

    companion object {
        const val CODE_MS = 10 * 60 * 1_000L
        const val SESSION_MS = 12 * 60 * 60 * 1_000L
        const val MAX_FAILURES = 10
        const val MAX_SESSIONS = 8
    }
}
