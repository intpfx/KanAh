package com.live.tv.remote

import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal interface RemoteAdbSession : AutoCloseable {
    fun connect()
    fun send(key: RemoteKey)
    fun ping()
    fun bootstrapForeground(): Boolean = false
}

internal class AdbAuthorizationPending : IOException()
internal class AdbInputUnsupported : IOException()

/** A cancellable connection to this TV only; no host/port can arrive from the network or UI. */
internal class LocalAdbSession(
    private val keyPair: AdbKeyPair,
    private val socket: Socket = Socket(),
    private val connectTimeoutMillis: Int = 3_000,
    private val authorizationTimeoutMillis: Int = 12_000,
    private val commandTimeoutMillis: Int = 5_000,
) : RemoteAdbSession {
    private val closed = AtomicBoolean(false)
    @Volatile private var connection: DadbSocketConnection? = null
    private val client = object : Dadb {
        override fun open(destination: String): AdbStream =
            requireNotNull(connection) { "ADB is not connected" }.open(destination)
        override fun supportsFeature(feature: String): Boolean = connection?.supportsFeature(feature) == true
        override fun close() = this@LocalAdbSession.close()
    }

    override fun connect() {
        check(!closed.get())
        socket.connect(InetSocketAddress("127.0.0.1", 5555), connectTimeoutMillis)
        socket.tcpNoDelay = true
        socket.soTimeout = authorizationTimeoutMillis
        val established = try {
            DadbSocketConnection.connect(socket, keyPair)
        } catch (_: SocketTimeoutException) {
            throw AdbAuthorizationPending()
        }
        if (closed.get()) {
            established.close()
            throw IOException("Closed")
        }
        connection = established
        socket.soTimeout = commandTimeoutMillis
        if (!client.supportsFeature("shell_v2")) throw AdbInputUnsupported()
        ping()
    }

    override fun send(key: RemoteKey) {
        val response = client.shell(key.shellCommand)
        if (response.exitCode != 0) throw AdbInputUnsupported()
    }

    override fun ping() {
        if (client.shell("echo LIVE_REMOTE_READY").exitCode != 0) throw AdbInputUnsupported()
    }

    override fun bootstrapForeground(): Boolean = client.shell(KEEP_ALIVE_BOOTSTRAP_COMMAND).exitCode == 0

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Socket first: dadb's close alone cannot interrupt a blocking handshake/read.
        runCatching { socket.close() }
        runCatching { connection?.close() }
        connection = null
    }

    companion object {
        private const val KEEP_ALIVE_BOOTSTRAP_COMMAND =
            "am start-foreground-service -n com.live.tv/.remote.RemoteForegroundKeepAliveService"
        fun create(identityDir: File): LocalAdbSession = LocalAdbSession(loadKey(identityDir))

        @Synchronized private fun loadKey(dir: File): AdbKeyPair {
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create private ADB identity" }
            val privateKey = File(dir, "adbkey")
            val publicKey = File(dir, "adbkey.pub")
            if (!privateKey.exists() || !publicKey.exists()) {
                // Stage both files before exposing the identity to a connection.
                val pendingPrivate = File(dir, "adbkey.pending")
                val pendingPublic = File(dir, "adbkey.pub.pending")
                AdbKeyPair.generate(pendingPrivate, pendingPublic)
                check(pendingPrivate.renameTo(privateKey)) { "Cannot save private ADB identity" }
                check(pendingPublic.renameTo(publicKey)) { "Cannot save public ADB identity" }
            }
            privateKey.setReadable(false, false)
            privateKey.setWritable(false, false)
            privateKey.setReadable(true, true)
            privateKey.setWritable(true, true)
            return AdbKeyPair.read(privateKey, publicKey)
        }
    }
}
