package com.live.tv.remote

import dadb.AdbKeyPair
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LocalAdbSessionTest {
    @Test fun authorizationHasTimeoutAndConnectIsOnlyLoopback5555() {
        val socket = FakeSocket(object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException()
        })
        val session = LocalAdbSession(keyPair(), socket)
        try {
            session.connect()
            fail("Expected authorization timeout")
        } catch (_: AdbAuthorizationPending) {
            assertEquals("/127.0.0.1:5555", socket.endpoint.toString())
            assertEquals(3_000, socket.connectTimeout)
            assertEquals(12_000, socket.readTimeout)
        } finally { session.close() }
        assertTrue(socket.wasClosed)
    }

    @Test fun postHandshakeCommandUsesShorterReadTimeout() {
        val handshake = ByteArrayInputStream(connectionPacket())
        val socket = FakeSocket(object : InputStream() {
            override fun read(): Int {
                val byte = handshake.read()
                if (byte >= 0) return byte
                throw SocketTimeoutException()
            }
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (handshake.available() > 0) return handshake.read(target, offset, length)
                throw SocketTimeoutException()
            }
        })
        val session = LocalAdbSession(keyPair(), socket)
        try {
            session.connect() // Probe is a shell command and deliberately times out.
            fail("Expected command timeout")
        } catch (_: SocketTimeoutException) {
            assertEquals(5_000, socket.readTimeout)
        } finally { session.close() }
    }

    @Test fun closeInterruptsAnUnfinishedHandshake() {
        val reading = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val socket = FakeSocket(object : InputStream() {
            override fun read(): Int {
                reading.countDown()
                closeGate.await(2, TimeUnit.SECONDS)
                throw IOException("closed")
            }
        }, onClose = { closeGate.countDown() })
        val session = LocalAdbSession(keyPair(), socket)
        val result = AtomicReference<Throwable?>()
        val worker = thread { try { session.connect() } catch (error: Throwable) { result.set(error) } }
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        session.close()
        worker.join(1_000)
        assertFalse(worker.isAlive)
        assertTrue(socket.wasClosed)
        assertTrue(result.get() is IOException)
    }

    private fun keyPair(): AdbKeyPair = AdbKeyPair(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private, ByteArray(0))

    private fun connectionPacket(): ByteArray {
        val command = 0x4e584e43 // CNXN, little endian.
        val payload = "device::features=shell_v2;\u0000".toByteArray()
        return ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(0x01000000).putInt(4096).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).put(payload).array()
    }

    private class FakeSocket(private val input: InputStream, private val onClose: () -> Unit = {}) : Socket() {
        var endpoint: SocketAddress? = null
        var connectTimeout = 0
        var readTimeout = 0
        var wasClosed = false
        override fun connect(endpoint: SocketAddress, timeout: Int) { this.endpoint = endpoint; connectTimeout = timeout }
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setSoTimeout(timeout: Int) { readTimeout = timeout }
        override fun getSoTimeout(): Int = readTimeout
        override fun getInputStream(): InputStream = input
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isClosed(): Boolean = wasClosed
        override fun close() { wasClosed = true; onClose() }
    }
}
