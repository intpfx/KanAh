package com.live.tv.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot/package replacement restores only a previously opted-in receiver. */
class RemoteBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (RemotePreferences(context).enabled) RemoteReceiverService.start(context)
    }
}
