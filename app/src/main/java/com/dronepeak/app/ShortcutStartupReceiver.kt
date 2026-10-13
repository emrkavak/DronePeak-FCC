package com.dronepeak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** System boot/package replacement starts navigation only, never FCC or another hardware operation. */
class ShortcutStartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        FloatingShortcutController.ensureStarted(context)
    }
}
