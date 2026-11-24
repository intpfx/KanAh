package com.live.tv.remote

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Some TV firmware permits a foreground promotion only for a system/shell caller.
 * This component is exported solely behind android.permission.DUMP in the manifest.
 * The already authorized local ADB connection sends one fixed startup command; it
 * does not change system allowlists or expose a generic shell/network command API.
 */
class RemoteForegroundKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (!RemotePreferences(this).enabled) { foregroundActive = false; stopSelf(); return }
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Also reject a shell request that arrived after the user switched the receiver off.
        if (!RemotePreferences(this).enabled) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        promote()
        // A sticky system restore must bring back the receiver, without reopening pairing.
        RemoteReceiverService.start(this)
        return START_STICKY
    }

    private fun promote() {
        RemoteForegroundNotification.promote(this, NOTIFICATION_ID)
        foregroundActive = Build.VERSION.SDK_INT < 29 || foregroundServiceType != 0
        RemoteReceiverService.reportKeepAliveState(foregroundActive)
    }

    override fun onDestroy() {
        foregroundActive = false
        RemoteReceiverService.reportKeepAliveState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 4402
        @Volatile private var foregroundActive = false
        internal fun isForegroundActive(): Boolean = foregroundActive
        internal fun stop(context: Context) {
            foregroundActive = false
            context.applicationContext.stopService(Intent(context.applicationContext, RemoteForegroundKeepAliveService::class.java))
        }
    }
}
