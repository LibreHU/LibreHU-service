package org.librehu.service.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import org.librehu.service.LibreHu

/**
 * Notifications and calls of the projected phone, told by the projection apps: Open Headunit LibreHU (Android Auto)
 * and CarPlay (xcertplay LibreHU). Each one is a notification for the front LEDs ([FrontLed.onNotification]), as a
 * notification of this head unit's Android is.
 *
 * `org.librehu.action.PROJECTION_NOTIFICATION`, extras `source` (`android_auto` or `carplay`) and `kind` (`message`,
 * `call`). Only from an app holding the signature permission VEHICLE: no other app makes the LEDs blink. The content
 * of the notification is never sent.
 */
object ProjectionNotifications {
    const val ACTION = "org.librehu.action.PROJECTION_NOTIFICATION"
    private const val TAG = "LibreHU-ProjNotif"

    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    val source =
                        when (intent.getStringExtra("source")) {
                            "android_auto" -> NotificationSource.ANDROID_AUTO
                            "carplay" -> NotificationSource.CARPLAY
                            else -> return
                        }
                    Log.i(TAG, "${source.name} ${intent.getStringExtra("kind") ?: "?"}")
                    FrontLed.get(app).onNotification(source)
                }
            }
        receiver = r
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(r, IntentFilter(ACTION), LibreHu.PERMISSION_VEHICLE, null, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(r, IntentFilter(ACTION), LibreHu.PERMISSION_VEHICLE, null)
        }
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }
}
