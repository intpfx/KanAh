package com.live.tv.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single bounded worker; retries never run on the UI or protocol callback thread. */
internal class RemoteAdbController(
    private val sessionFactory: () -> RemoteAdbSession,
    private val onState: (String, String) -> Unit,
    private val queue: RemoteInputQueue = RemoteInputQueue(),
    private val retryDelaysMillis: LongArray = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
    private val heartbeatMillis: Long = 15_000,
    private val shouldBootstrapForeground: () -> Boolean = { false },
    private val onForegroundBootstrap: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val current = AtomicReference<RemoteAdbSession?>(null)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "Live-local-adb").apply { isDaemon = true } }
    @Volatile private var connected = false

    fun start() {
        if (running.compareAndSet(false, true)) executor.execute(::runLoop)
    }

    fun send(key: RemoteKey): Boolean = running.get() && connected && queue.offer(key)

    private fun runLoop() {
        var failures = 0
        while (running.get()) {
            var session: RemoteAdbSession? = null
            try {
                onState("connecting", "正在连接电视控制通道；首次连接请确认调试授权提示")
                session = sessionFactory()
                current.set(session)
                if (!running.get()) break
                session.connect()
                queue.clear()
                connected = true
                failures = 0
                onState("connected", "全局按键已连接；可遥控桌面和其他应用")
                if (running.get() && shouldBootstrapForeground()) {
                    val started = try { session.bootstrapForeground() } catch (_: Exception) { false }
                    if (running.get()) onForegroundBootstrap(started)
                }
                var lastActivity = System.nanoTime() / 1_000_000
                while (running.get()) {
                    val key = queue.poll()
                    val time = System.nanoTime() / 1_000_000
                    if (key != null) {
                        session.send(key)
                        lastActivity = time
                    } else if (time - lastActivity >= heartbeatMillis) {
                        session.ping()
                        lastActivity = time
                    } else Thread.sleep(20)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (error: Exception) {
                if (running.get()) {
                    val state = when (error) {
                        is AdbAuthorizationPending -> "authorization_required"
                        is AdbInputUnsupported -> "unsupported"
                        is ConnectException -> "unavailable"
                        is SocketTimeoutException -> "unavailable"
                        else -> "error"
                    }
                    val detail = when (state) {
                        "authorization_required" -> "本机 ADB 尚未完成授权或响应，请确认电视上的调试授权提示"
                        "unsupported" -> "电视 ADB 不支持所需的全局输入命令"
                        "unavailable" -> "本机 ADB 未开启或已断开，请在电视设置中开启网络调试 5555 端口"
                        else -> "本机 ADB 连接失败，正在自动重试"
                    }
                    onState(state, detail)
                }
            } finally {
                connected = false
                queue.clear()
                current.compareAndSet(session, null)
                runCatching { session?.close() }
            }
            if (!running.get()) break
            try {
                Thread.sleep(retryDelaysMillis[failures.coerceAtMost(retryDelaysMillis.lastIndex)])
                failures++
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    override fun close() {
        running.set(false)
        connected = false
        queue.clear()
        runCatching { current.getAndSet(null)?.close() }
        executor.shutdownNow()
    }
}
