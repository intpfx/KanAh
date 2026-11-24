package com.live.tv.remote

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteInputTest {
    @Test fun queueRejectsFloodAndExpiresOldInput() {
        var time = 10L
        val queue = RemoteInputQueue(capacity = 2, maxAgeMillis = 100) { time }
        assertTrue(queue.offer(RemoteKey.UP))
        assertTrue(queue.offer(RemoteKey.POWER))
        assertFalse(queue.offer(RemoteKey.DOWN))
        time += 101
        assertNull(queue.poll())
        assertEquals(0, queue.size())
        assertTrue(queue.offer(RemoteKey.HOME))
        assertEquals(RemoteKey.HOME, queue.poll())
    }

    @Test fun protocolCannotInjectShellCommandsAndAppleMenuMeansBack() {
        assertNull(RemoteKey.fromProtocol("up; reboot"))
        assertNull(RemoteKey.fromProtocol("$(id)"))
        assertEquals(RemoteKey.BACK, RemoteKey.fromProtocol("menu"))
        assertEquals("input keyevent 3", RemoteKey.fromProtocol("home")?.shellCommand)
        assertEquals("input keyevent 26", RemoteKey.fromProtocol("power")?.shellCommand)
        RemoteKey.entries.forEach { assertTrue(it.shellCommand.matches(Regex("input keyevent (--longpress )?[0-9]+"))) }
    }

    @Test fun connectionFailureDropsPendingKeysAndReconnectDoesNotReplay() {
        val firstClosed = CountDownLatch(1)
        val secondConnected = CountDownLatch(1)
        val firstConnected = CountDownLatch(1)
        val states = CopyOnWriteArrayList<String>()
        val secondKeys = CopyOnWriteArrayList<RemoteKey>()
        val failureGate = CountDownLatch(1)
        val firstSending = CountDownLatch(1)
        var attempts = 0
        val controller = RemoteAdbController(sessionFactory = {
            attempts++
            if (attempts == 1) object : RemoteAdbSession {
                override fun connect() = Unit
                override fun send(key: RemoteKey) { firstSending.countDown(); failureGate.await(); throw IOException() }
                override fun ping() = Unit
                override fun close() { firstClosed.countDown() }
            } else object : RemoteAdbSession {
                override fun connect() = Unit
                override fun send(key: RemoteKey) { secondKeys.add(key) }
                override fun ping() = Unit
                override fun close() = Unit
            }
        }, onState = { state, _ ->
            states.add(state)
            if (state == "connected") {
                if (attempts == 1) firstConnected.countDown() else secondConnected.countDown()
            }
        }, retryDelaysMillis = longArrayOf(5))
        try {
            assertFalse(controller.send(RemoteKey.POWER))
            controller.start()
            assertTrue(firstConnected.await(2, TimeUnit.SECONDS))
            assertTrue(controller.send(RemoteKey.UP))
            assertTrue(firstSending.await(2, TimeUnit.SECONDS))
            assertTrue(controller.send(RemoteKey.POWER))
            failureGate.countDown()
            assertTrue(firstClosed.await(2, TimeUnit.SECONDS))
            assertTrue(secondConnected.await(2, TimeUnit.SECONDS))
            assertTrue(controller.send(RemoteKey.HOME))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (secondKeys.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(listOf(RemoteKey.HOME), secondKeys.toList())
            assertTrue(states.contains("error"))
        } finally { failureGate.countDown(); controller.close() }
    }

    @Test fun stopClosesPendingAuthorizationAndRejectsFurtherInput() {
        val connecting = CountDownLatch(1)
        val released = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val controller = RemoteAdbController(sessionFactory = {
            object : RemoteAdbSession {
                override fun connect() { connecting.countDown(); released.await(); exited.countDown(); throw IOException() }
                override fun send(key: RemoteKey) = fail("No input before authorization")
                override fun ping() = Unit
                override fun close() { released.countDown() }
            }
        }, onState = { _, _ -> })
        controller.start()
        assertTrue(connecting.await(2, TimeUnit.SECONDS))
        assertFalse(controller.send(RemoteKey.POWER))
        controller.close()
        assertTrue(released.await(1, TimeUnit.SECONDS))
        assertFalse(controller.send(RemoteKey.HOME))
    }
}
