package com.live.tv.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build

/** Same user-facing controls for the normal receiver and the narrowly scoped TV fallback. */
internal object RemoteForegroundNotification {
    private const val CHANNEL = "live-remote-receiver"

    fun promote(service: Service, notificationId: Int) {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "电视遥控接收", NotificationManager.IMPORTANCE_LOW))
        val settings = PendingIntent.getActivity(service, 0, Intent(service, com.live.tv.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("media_browse_tab", "address"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(service, 1,
            Intent(service, RemoteReceiverService::class.java).setAction(RemoteReceiverService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Live 电视遥控已启用")
            .setContentText("在后台接收已配对 iPhone 的遥控；点按显示局域网二维码")
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "关闭遥控", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else service.startForeground(notificationId, notification)
    }
}
