package com.live.tv.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Activity-independent Apple Remote receiver. Enabling it does not keep playback running. */
class RemoteReceiverService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private lateinit var preferences: RemotePreferences
    private lateinit var audio: RemoteAudio
    @Volatile private var adb: RemoteAdbController? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var inputWakeLock: PowerManager.WakeLock? = null
    private var serverSignature: String? = null // Protocol worker only.
    private var protocolFailures = 0 // Protocol worker only.
    @Volatile private var pairingDeadline = 0L // elapsedRealtime, never restored across process death.
    @Volatile private var protocolDetail = ""
    @Volatile private var adbDetail = ""
    @Volatile private var foregroundDetail = ""
    private val rebind = Runnable { enqueue { bindReceiver() } }
    private val expirePairing = Runnable {
        pairingDeadline = 0
        update { copy(pairingUntil = 0) }
        enqueue { if (AtvNative.available) AtvNative.nativeClosePairingWindow() }
    }

    override fun onCreate() {
        super.onCreate()
        preferences = RemotePreferences(this)
        audio = RemoteAudio(getSystemService(AudioManager::class.java))
        if (!preferences.enabled) { stopSelf(); return }
        activeOwner.set(this)
        com.live.tv.LanConfigRuntime.retain(this, this)
        RemoteForegroundNotification.promote(this, NOTIFICATION_ID)
        update { RemoteStatus(enabled = true, protocolState = "starting", adbState = "connecting", detail = "正在启动遥控接收服务") }
        val power = getSystemService(PowerManager::class.java)
        inputWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Live:remote-input").apply { setReferenceCounted(false) }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        multicastLock = wifi?.createMulticastLock("Live:apple-remote-mdns")?.apply { setReferenceCounted(false) }
        watchNetwork()
        enqueue {
            if (!AtvNative.available) {
                protocolDetail = "当前安装包未包含适配此电视的 Apple TV 遥控协议组件"
                update { copy(protocolState = "unavailable", adbState = "disabled", detail = protocolDetail) }
                return@enqueue
            }
            val controller = RemoteAdbController(
                sessionFactory = { LocalAdbSession.create(File(noBackupFilesDir, "remote-adb")) },
                onState = { state, detail ->
                    adbDetail = detail
                    update { copy(adbState = state, detail = combinedDetail()) }
                },
                shouldBootstrapForeground = {
                    !destroyed.get() && preferences.enabled && activeOwner.get() === this &&
                        Build.VERSION.SDK_INT >= 29 && foregroundServiceType == 0 &&
                        !RemoteForegroundKeepAliveService.isForegroundActive()
                },
                onForegroundBootstrap = { requested ->
                    foregroundDetail = when {
                        RemoteForegroundKeepAliveService.isForegroundActive() -> "后台保活已启动，可继续在其他应用中使用遥控"
                        requested -> "正在通过已授权的电视控制通道启动后台保活"
                        else -> "电视系统未允许后台保活，退出 Live 后遥控可能中断"
                    }
                    update { copy(detail = combinedDetail()) }
                },
            )
            synchronized(lifecycleLock) {
                if (destroyed.get()) controller.close()
                else { adb = controller; controller.start() }
            }
            bindReceiver()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!preferences.enabled || destroyed.get()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) {
            preferences.enabled = false
            RemoteForegroundKeepAliveService.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        enqueue {
            bindReceiver()
            when (intent?.action) {
                ACTION_PAIR -> openWindow()
                ACTION_CLOSE_PAIR -> closeWindow()
                ACTION_ROTATE_PAIR -> {
                    closeWindow()
                    preferences.rotatePin()
                    bindReceiver(force = true)
                    openWindow()
                }
                ACTION_FORGET -> forgetPairedControllers()
            }
        }
        return START_STICKY
    }

    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRebind()
            override fun onLost(network: Network) = scheduleRebind()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = scheduleRebind()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
        try {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            protocolDetail = "无法监听网络变化，请在局域网页面重新开启遥控"
        }
    }

    private fun scheduleRebind() {
        if (destroyed.get()) return
        main.removeCallbacks(rebind)
        main.postDelayed(rebind, 500)
    }

    private fun localIpv4(): String? {
        val manager = getSystemService(ConnectivityManager::class.java)
        val networks = manager.allNetworks.sortedBy { if (it == manager.activeNetwork) 0 else 1 }
        for (network in networks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val address = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
            }?.address?.hostAddress
            if (address != null) return address
        }
        return null
    }

    /** All native lifecycle calls are serialized on protocolWorker. */
    private fun bindReceiver(force: Boolean = false) {
        if (!AtvNative.available || destroyed.get() || !preferences.enabled) return
        val ip = localIpv4()
        val signature = ip?.let { "$it|${preferences.deviceName}|${preferences.pin}" }
        if (!force && signature != null && signature == serverSignature) return
        AtvNative.nativeStopServer()
        serverSignature = null
        releaseMulticast()
        if (ip == null) {
            protocolDetail = "等待连接 Wi-Fi 或以太网；iPhone 需与电视处于同一局域网"
            update { copy(protocolState = "waiting_network", localIp = null, detail = combinedDetail()) }
            return
        }
        val identity = File(noBackupFilesDir, "apple-remote")
        if (!identity.isDirectory && !identity.mkdirs()) {
            protocolDetail = "无法保存遥控配对身份"
            update { copy(protocolState = "error", detail = combinedDetail()) }
            return
        }
        protocolDetail = "正在广播 ${preferences.deviceName}"
        update { copy(protocolState = "starting", localIp = ip, detail = combinedDetail()) }
        if (!acquireMulticast()) return
        val started = AtvNative.nativeStartServer(preferences.deviceName, preferences.pin.toInt(),
            preferences.deviceId, preferences.serverId, ip, identity.absolutePath, nativeCallback)
        if (started) {
            protocolFailures = 0
            serverSignature = signature
            val remaining = ((pairingDeadline - SystemClock.elapsedRealtime()) / 1_000).toInt()
            if (remaining > 0) AtvNative.nativeOpenPairingWindow(remaining)
        } else {
            releaseMulticast()
            protocolDetail = "Apple TV 遥控监听启动失败，请检查端口和局域网连接"
            update { copy(protocolState = "error", detail = combinedDetail()) }
            scheduleProtocolRetry()
        }
    }

    private val nativeCallback = object : AtvNative.Callback {
        override fun onButton(button: String) {
            if (destroyed.get() || !preferences.enabled) return
            val key = RemoteKey.fromProtocol(button) ?: return
            if (adb?.send(key) == true) {
                // Keep CPU awake only while handling recent input; do not prevent TV deep standby.
                synchronized(lifecycleLock) {
                    if (!destroyed.get()) runCatching { inputWakeLock?.acquire(10_000) }
                }
            }
        }
        override fun onTouch(x: Double, y: Double, phase: Int) = Unit // Core emits directional buttons separately.
        override fun onAudio(volume: Double, muted: Boolean) {
            if (!destroyed.get() && preferences.enabled) audio.setVolume(volume, muted)
        }
        override fun getAudioVolume(): Double = audio.volume()
        override fun isAudioMuted(): Boolean = audio.muted()
        override fun onEvent(event: String, detail: String) {
            if (destroyed.get()) return
            when (event) {
                "listening", "ready" -> {
                    protocolDetail = "Apple TV 遥控正在监听；可接收已配对设备"
                    update { copy(protocolState = "listening", detail = combinedDetail()) }
                }
                "error", "server_error" -> {
                    // Native messages may include network/controller data; show a fixed, safe explanation.
                    protocolDetail = "Apple TV 遥控监听异常，请重新开启服务"
                    update { copy(protocolState = "error", detail = combinedDetail()) }
                    enqueue {
                        if (serverSignature != null) {
                            serverSignature = null
                            releaseMulticast()
                            scheduleProtocolRetry()
                        }
                    }
                }
                "pairing_closed" -> {
                    pairingDeadline = 0
                    main.removeCallbacks(expirePairing)
                    update { copy(pairingUntil = 0) }
                }
            }
        }
    }

    private fun openWindow() {
        if (serverSignature == null || !AtvNative.available) return
        if (!AtvNative.nativeOpenPairingWindow(PAIRING_SECONDS)) return
        pairingDeadline = SystemClock.elapsedRealtime() + PAIRING_SECONDS * 1_000L
        main.removeCallbacks(expirePairing)
        main.postDelayed(expirePairing, PAIRING_SECONDS * 1_000L)
        update { copy(pairingUntil = System.currentTimeMillis() + PAIRING_SECONDS * 1_000L) }
    }

    private fun closeWindow() {
        pairingDeadline = 0
        main.removeCallbacks(expirePairing)
        if (AtvNative.available) AtvNative.nativeClosePairingWindow()
        update { copy(pairingUntil = 0) }
    }

    private fun forgetPairedControllers() {
        pairingDeadline = 0
        main.removeCallbacks(expirePairing)
        update { copy(pairingUntil = 0) }
        if (!AtvNative.available) return
        AtvNative.nativeClosePairingWindow()
        if (!AtvNative.nativeForgetControllers(File(noBackupFilesDir, "apple-remote").absolutePath)) {
            protocolDetail = "清除配对失败，请在局域网监听就绪后重试"
            update { copy(protocolState = "error", detail = combinedDetail()) }
            return
        }
        AtvNative.nativeStopServer()
        serverSignature = null
        preferences.rotatePin()
        bindReceiver(force = true)
    }

    private fun combinedDetail(): String = listOf(protocolDetail, adbDetail, foregroundDetail).filter(String::isNotEmpty).joinToString("\n")

    private fun update(change: RemoteStatus.() -> RemoteStatus) {
        if (destroyed.get() || activeOwner.get() !== this) return
        synchronized(STATUS_LOCK) { latest = change(latest) }
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    private fun enqueue(task: () -> Unit) {
        if (destroyed.get()) return
        runCatching {
            protocolWorker.execute {
                if (destroyed.get() || activeOwner.get() !== this) return@execute
                try { task() } catch (_: Exception) {
                    protocolDetail = "遥控服务暂时不可用，请关闭后重新开启"
                    update { copy(protocolState = "error", detail = combinedDetail()) }
                } catch (_: LinkageError) {
                    protocolDetail = "遥控协议组件与当前安装包不匹配"
                    update { copy(protocolState = "unavailable", detail = combinedDetail()) }
                }
            }
        }
    }

    private fun acquireMulticast(): Boolean = synchronized(lifecycleLock) {
        if (destroyed.get() || !preferences.enabled || activeOwner.get() !== this) return false
        multicastLock?.let { if (!it.isHeld) it.acquire() }
        true
    }

    private fun releaseMulticast() = synchronized(lifecycleLock) {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        Unit
    }

    private fun scheduleProtocolRetry() {
        if (destroyed.get() || !preferences.enabled) return
        val delay = (1_000L shl protocolFailures.coerceAtMost(5)).coerceAtMost(30_000)
        protocolFailures++
        main.removeCallbacks(rebind)
        main.postDelayed(rebind, delay)
    }

    override fun onDestroy() {
        com.live.tv.LanConfigRuntime.release(this)
        destroyed.set(true)
        main.removeCallbacksAndMessages(null)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        synchronized(lifecycleLock) { adb?.close(); adb = null }
        releaseMulticast()
        synchronized(lifecycleLock) { inputWakeLock?.let { if (it.isHeld) runCatching { it.release() } } }
        // JNI owns one process-wide server. A shared queue and owner token prevent an old
        // instance's delayed teardown from stopping a newly enabled instance's server.
        protocolWorker.execute {
            if (activeOwner.compareAndSet(this, null) && AtvNative.available) runCatching { AtvNative.nativeStopServer() }
        }
        if (activeOwner.get() === this || activeOwner.get() == null) synchronized(STATUS_LOCK) {
            latest = RemoteStatus(enabled = preferences.enabled, detail = if (preferences.enabled) "遥控服务已停止，等待系统恢复" else "遥控接收服务已关闭")
        }
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STATUS_CHANGED = "com.live.tv.remote.STATUS_CHANGED"
        internal const val ACTION_STOP = "com.live.tv.remote.STOP"
        private const val ACTION_PAIR = "com.live.tv.remote.PAIR"
        private const val ACTION_CLOSE_PAIR = "com.live.tv.remote.CLOSE_PAIR"
        private const val ACTION_ROTATE_PAIR = "com.live.tv.remote.ROTATE_PAIR"
        private const val ACTION_FORGET = "com.live.tv.remote.FORGET"
        private const val NOTIFICATION_ID = 4401
        private const val PAIRING_SECONDS = 120
        private val STATUS_LOCK = Any()
        private val activeOwner = AtomicReference<RemoteReceiverService?>(null)
        private val protocolWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Live-apple-remote").apply { isDaemon = true }
        }
        @Volatile private var latest = RemoteStatus()

        @JvmStatic fun status(context: Context): RemoteStatus {
            val enabled = RemotePreferences(context).enabled
            return if (enabled) latest.copy(enabled = true) else RemoteStatus()
        }

        @JvmStatic fun start(context: Context) = dispatch(context, null)
        @JvmStatic fun openPairingWindow(context: Context) = dispatch(context, ACTION_PAIR)
        @JvmStatic fun closePairingWindow(context: Context) = dispatch(context, ACTION_CLOSE_PAIR)
        @JvmStatic fun rotatePairingPin(context: Context) = dispatch(context, ACTION_ROTATE_PAIR)
        @JvmStatic fun forgetControllers(context: Context) = dispatch(context, ACTION_FORGET)

        private fun dispatch(context: Context, action: String?) {
            val app = context.applicationContext
            if (!RemotePreferences(app).enabled) return
            try {
                app.startForegroundService(Intent(app, RemoteReceiverService::class.java).setAction(action))
            } catch (_: RuntimeException) {
                synchronized(STATUS_LOCK) {
                    latest = RemoteStatus(enabled = true, protocolState = "error", adbState = "disabled",
                        detail = "系统未允许启动后台遥控服务，请通过 Live 局域网页面重新开启")
                }
                app.sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(app.packageName))
            }
        }

        @JvmStatic fun stop(context: Context) {
            val app = context.applicationContext
            RemotePreferences(app).enabled = false
            RemoteForegroundKeepAliveService.stop(app)
            app.stopService(Intent(app, RemoteReceiverService::class.java))
            synchronized(STATUS_LOCK) { latest = RemoteStatus() }
            app.sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(app.packageName))
        }

        internal fun reportKeepAliveState(active: Boolean) {
            activeOwner.get()?.let { receiver ->
                if (!receiver.preferences.enabled || receiver.destroyed.get()) return
                receiver.foregroundDetail = if (active) "后台保活已启动，可继续在其他应用中使用遥控"
                else "电视后台保活已停止；若系统限制后台运行，遥控可能中断"
                receiver.update { copy(detail = receiver.combinedDetail()) }
            }
        }
    }
}
