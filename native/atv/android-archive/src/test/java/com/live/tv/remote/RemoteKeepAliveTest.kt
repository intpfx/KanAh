package com.live.tv.remote

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteKeepAliveTest {
    private lateinit var context: Application

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("apple-tv-remote", Context.MODE_PRIVATE).edit().clear().commit()
        RemoteForegroundKeepAliveService.stop(context)
        while (shadowOf(context).nextStartedService != null) { }
        while (shadowOf(context).nextStoppedService != null) { }
    }

    @Test fun staleShellIntentCannotEnableReceiverOrPromoteService() {
        val controller = Robolectric.buildService(RemoteForegroundKeepAliveService::class.java).create()
        try {
            val service = controller.get()
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(0, shadowOf(service).lastForegroundNotificationId)
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
            assertNull(shadowOf(context).nextStartedService)
            assertFalse(RemotePreferences(context).enabled)
            assertFalse(RemoteForegroundKeepAliveService.isForegroundActive())
        } finally { controller.destroy() }
    }

    @Test fun stickyRestorePromotesConnectedDeviceAndRestoresOnlyOptedInReceiver() {
        RemotePreferences(context).enabled = true
        val controller = Robolectric.buildService(RemoteForegroundKeepAliveService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
            assertTrue(RemoteForegroundKeepAliveService.isForegroundActive())
            assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
            val started = shadowOf(context).nextStartedService
            assertEquals(RemoteReceiverService::class.java.name, started?.component?.className)
            assertNull(started?.action) // No pairing window is opened by recovery.
            assertNotNull(shadowOf(service).lastForegroundNotification)
            RemotePreferences(context).enabled = false
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse(RemoteForegroundKeepAliveService.isForegroundActive())
        } finally { controller.destroy() }
    }

    @Test fun explicitStopTargetsBothServices() {
        RemotePreferences(context).enabled = true
        RemoteReceiverService.stop(context)
        val stopped = listOfNotNull(shadowOf(context).nextStoppedService, shadowOf(context).nextStoppedService)
            .map { it.component?.className }.toSet()
        assertEquals(setOf(RemoteReceiverService::class.java.name, RemoteForegroundKeepAliveService::class.java.name), stopped)
        assertFalse(RemotePreferences(context).enabled)
    }

    @Test fun notificationStopAlsoStopsKeepAlive() {
        RemotePreferences(context).enabled = true
        val controller = Robolectric.buildService(RemoteReceiverService::class.java).create()
        try {
            val result = controller.get().onStartCommand(Intent().setAction(RemoteReceiverService.ACTION_STOP), 0, 1)
            assertEquals(Service.START_NOT_STICKY, result)
            assertFalse(RemotePreferences(context).enabled)
            assertEquals(RemoteForegroundKeepAliveService::class.java.name,
                shadowOf(context).nextStoppedService?.component?.className)
        } finally { controller.destroy() }
    }

    @Test fun exportedBootstrapIsProtectedByPlatformDumpPermission() {
        val info = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, RemoteForegroundKeepAliveService::class.java), 0)
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.permission)
        val receiver = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, RemoteReceiverService::class.java), 0)
        assertFalse(receiver.exported)
    }

    @Test fun bootstrapRunsOnlyAfterAuthorizationAndDoesNotReconnectOrInterruptKeys() {
        val authorization = CountDownLatch(1)
        val connecting = CountDownLatch(1)
        val bootstrapped = CountDownLatch(1)
        val keyReceived = CountDownLatch(1)
        val connects = AtomicInteger()
        val bootstraps = AtomicInteger()
        val controller = RemoteAdbController(
            sessionFactory = {
                object : RemoteAdbSession {
                    override fun connect() { connects.incrementAndGet(); connecting.countDown(); authorization.await() }
                    override fun ping() = Unit
                    override fun send(key: RemoteKey) { assertEquals(RemoteKey.HOME, key); keyReceived.countDown() }
                    override fun bootstrapForeground(): Boolean { bootstraps.incrementAndGet(); return true }
                    override fun close() { authorization.countDown() }
                }
            }, onState = { _, _ -> }, shouldBootstrapForeground = { true },
            onForegroundBootstrap = { success -> assertTrue(success); bootstrapped.countDown() },
        )
        try {
            controller.start()
            assertTrue(connecting.await(2, TimeUnit.SECONDS))
            assertEquals(0, bootstraps.get())
            authorization.countDown()
            assertTrue(bootstrapped.await(2, TimeUnit.SECONDS))
            assertTrue(controller.send(RemoteKey.HOME))
            assertTrue(keyReceived.await(2, TimeUnit.SECONDS))
            assertEquals(1, connects.get())
            assertEquals(1, bootstraps.get())
        } finally { controller.close() }
    }

    @Test fun normalForegroundDeviceNeverSendsBootstrapCommand() {
        val connected = CountDownLatch(1)
        val keyReceived = CountDownLatch(1)
        val bootstraps = AtomicInteger()
        val controller = RemoteAdbController(sessionFactory = {
            object : RemoteAdbSession {
                override fun connect() = Unit
                override fun ping() = Unit
                override fun send(key: RemoteKey) { keyReceived.countDown() }
                override fun bootstrapForeground(): Boolean { bootstraps.incrementAndGet(); return true }
                override fun close() = Unit
            }
        }, onState = { state, _ -> if (state == "connected") connected.countDown() },
            shouldBootstrapForeground = { false })
        try {
            controller.start()
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            assertTrue(controller.send(RemoteKey.HOME))
            assertTrue(keyReceived.await(2, TimeUnit.SECONDS))
            assertEquals(0, bootstraps.get())
        } finally { controller.close() }
    }
}
