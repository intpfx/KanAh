package com.live.tv.remote

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteWebManagerTest {
    private lateinit var context: Application
    private lateinit var manager: RemoteWebManager
    private lateinit var preferences: RemotePreferences
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("apple-tv-remote", Context.MODE_PRIVATE).edit().clear().commit()
        manager = RemoteWebManager(context)
        preferences = RemotePreferences(context)
        while (shadowOf(context).nextStartedService != null) { }
    }
    private fun token(): String = JSONObject(manager.unlock(manager.openAccess().code)).getString("token")

    @Test fun lockedStateNeverExposesReceiverIdentityOrPairingPin() {
        preferences.deviceName = "Private Room"
        val locked = JSONObject(manager.state(""))
        assertEquals(2, locked.length())
        assertFalse(locked.getBoolean("authorized"))
        assertFalse(locked.getBoolean("managementAvailable"))
        manager.openAccess()
        assertTrue(JSONObject(manager.state("")).getBoolean("managementAvailable"))
        assertFalse(preferences.enabled)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun unknownSessionCannotMutateAndOpenPairingDoesNotEnableService() {
        try { manager.control("fake", mapOf("action" to "enable")); fail("must reject missing authentication") }
        catch (_: SecurityException) { }
        val reply = JSONObject(manager.control(token(), mapOf("action" to "open_pairing")))
        assertFalse(reply.getBoolean("ok"))
        assertFalse(preferences.enabled)
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun validManagementChangesNameWithoutReplacingTrustedIdentity() {
        val deviceId = preferences.deviceId
        val serverId = preferences.serverId
        val pin = preferences.pin
        val session = token()
        assertTrue(JSONObject(manager.control(session, mapOf("action" to "save_name", "value" to " 客厅\u0000电视 "))).getBoolean("ok"))
        assertEquals("客厅电视", preferences.deviceName)
        assertEquals(deviceId, preferences.deviceId)
        assertEquals(serverId, preferences.serverId)
        assertEquals(pin, preferences.pin)
        assertFalse(JSONObject(manager.control(session, mapOf("action" to "save_name", "value" to "x".repeat(49)))).getBoolean("ok"))
        assertEquals("客厅电视", preferences.deviceName)
        assertFalse(JSONObject(manager.control(session, mapOf("action" to "shell", "value" to "anything"))).getBoolean("ok"))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun enableIsExplicitAndLogoutRevokesOnlyTheCallingBrowser() {
        val first = token()
        val second = token()
        assertTrue(JSONObject(manager.control(first, mapOf("action" to "enable"))).getBoolean("ok"))
        assertTrue(preferences.enabled)
        assertEquals(RemoteReceiverService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
        assertEquals("", JSONObject(manager.state(first)).getString("pin"))
        val reply = JSONObject(manager.control(first, mapOf("action" to "logout")))
        assertFalse(reply.getBoolean("authorized"))
        assertFalse(manager.authorized(first))
        assertTrue(manager.authorized(second))
    }

    @Test fun authorizedPinIsVisibleOnlyDuringAnActivePairingWindow() {
        val field = RemoteReceiverService::class.java.getDeclaredField("latest").apply { isAccessible = true }
        val previous = field.get(null)
        try {
            preferences.enabled = true
            val session = token()
            field.set(null, RemoteStatus(enabled = true, pairingUntil = System.currentTimeMillis() + 60_000))
            assertEquals(preferences.pin, JSONObject(manager.state(session)).getString("pin"))
            assertFalse(JSONObject(manager.state("")).has("pin"))
            field.set(null, RemoteStatus(enabled = true, pairingUntil = System.currentTimeMillis() - 1))
            assertEquals("", JSONObject(manager.state(session)).getString("pin"))
            assertEquals(0L, JSONObject(manager.state(session)).getLong("pairingUntil"))
        } finally { field.set(null, previous) }
    }
}
