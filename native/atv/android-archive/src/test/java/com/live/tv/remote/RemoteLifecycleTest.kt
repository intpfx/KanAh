package com.live.tv.remote

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteLifecycleTest {
    private lateinit var context: Application
    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("apple-tv-remote", Context.MODE_PRIVATE).edit().clear().commit()
        while (shadowOf(context).nextStartedService != null) { /* Empty previous explicit starts. */ }
    }

    @Test fun receiverDefaultsOffAndIdentitySurvivesSettingsInstances() {
        val first = RemotePreferences(context)
        assertFalse(first.enabled)
        assertTrue(first.pin.matches(Regex("[0-9]{4}")))
        val second = RemotePreferences(context)
        assertEquals(first.pin, second.pin)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.serverId, second.serverId)
        first.deviceName = " \n Living\u0000 Room \t "
        assertEquals("Living Room", second.deviceName)
    }

    @Test fun bootAndServiceCommandsCannotEnableReceiver() {
        val receiver = RemoteBootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        RemoteReceiverService.start(context)
        RemoteReceiverService.openPairingWindow(context)
        RemoteReceiverService.forgetControllers(context)
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(RemotePreferences(context).enabled)
        assertEquals("stopped", RemoteReceiverService.status(context).protocolState)
    }

    @Test fun optedInBootRestoresServiceButUnrelatedBroadcastDoesNot() {
        RemotePreferences(context).enabled = true
        val receiver = RemoteBootReceiver()
        receiver.onReceive(context, Intent("custom.broadcast"))
        assertNull(shadowOf(context).nextStartedService)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(RemoteReceiverService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
    }

    @Test fun staleStickyServiceDoesNotStartWhileDisabled() {
        val service = Robolectric.buildService(RemoteReceiverService::class.java).create()
        assertEquals(android.app.Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1))
        service.destroy()
        assertFalse(RemoteReceiverService.status(context).enabled)
    }

    @Test fun oldInstanceTeardownCannotOverwriteReenabledInstance() {
        RemotePreferences(context).enabled = true
        val old = Robolectric.buildService(RemoteReceiverService::class.java).create()
        val replacement = Robolectric.buildService(RemoteReceiverService::class.java).create()
        try {
            old.destroy()
            val status = RemoteReceiverService.status(context)
            assertTrue(status.enabled)
            assertNotEquals("stopped", status.protocolState)
        } finally { replacement.destroy() }
    }
}
