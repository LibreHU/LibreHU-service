package org.librehu.service.power

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell

/**
 * Apps started as soon as the user's storage is unlocked instead of waiting for BOOT_COMPLETED. That broadcast is
 * ordered: apps get it one after the other, and on this head unit a slow receiver (`com.jancar.services`, 60 s
 * broadcast timeouts in the logs) holds the whole queue, so the audio effects came up long after the sound. This
 * service starts at LOCKED_BOOT_COMPLETED; on ACTION_USER_UNLOCKED (or at once when already unlocked) it sends
 * BOOT_COMPLETED to these apps' own boot receivers as root. They get the system's one later too: their receivers only
 * start a service, which is harmless twice.
 */
object EarlyStart {
    private const val TAG = "LibreHU-EarlyStart"
    private const val PREF = "early_start"
    private const val BOOT_WINDOW_MS = 5 * 60_000L

    /** Package → name shown. ViPER4Android: its effects follow from its boot receiver (ViperService). */
    val APPS = listOf("com.llsl.viper4android" to "ViPER4Android")

    @Volatile
    private var done = false
    private var receiver: BroadcastReceiver? = null

    fun enabled(context: Context): Boolean = LibreHuService.prefs(context).getBoolean(PREF, true)

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) = LibreHuService
        .prefs(context)
        .edit()
        .putBoolean(PREF, on)
        .apply()

    /** At service start. */
    fun start(context: Context) {
        val app = context.applicationContext
        // Only at boot: a later restart of this service (update, crash) finds the apps already running.
        if (done || !enabled(app) || SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        val um = app.getSystemService(UserManager::class.java)
        if (um?.isUserUnlocked != false) {
            kick(app)
            return
        }
        if (receiver != null) return
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    runCatching { app.unregisterReceiver(this) }
                    receiver = null
                    kick(app)
                }
            }
        receiver = r
        // Sent to registered receivers only, before the BOOT_COMPLETED queue.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(r, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(r, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
    }

    private fun kick(context: Context) {
        if (done) return
        done = true
        Thread({
            if (!RootShell.isAvailable()) return@Thread
            for ((pkg, _) in APPS) {
                @Suppress("DEPRECATION")
                val receivers =
                    runCatching {
                        context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(pkg), 0)
                    }.getOrDefault(emptyList())
                for (r in receivers) {
                    val c = ComponentName(r.activityInfo.packageName, r.activityInfo.name)
                    if (context.packageManager.getComponentEnabledSetting(c) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) continue
                    RootShell.run("am broadcast -a android.intent.action.BOOT_COMPLETED -n ${c.flattenToShortString()}")
                    Log.i(TAG, "started early: ${c.flattenToShortString()}")
                }
            }
        }, "early-start").start()
    }
}
