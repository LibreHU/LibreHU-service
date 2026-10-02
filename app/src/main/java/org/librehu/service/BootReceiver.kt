package org.librehu.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service at boot (LOCKED_BOOT_COMPLETED: before the user unlocks, the MCU needs PC_READY early). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        LibreHuService.start(context)
    }
}
