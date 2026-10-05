package org.librehu.service.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Light / dark theme and accent of LibreHU Launcher (provider `org.librehu.launcher.theme` + THEME_CHANGED
 * broadcast), or Android's night mode without the launcher. [onChange] gets (dark, accent ARGB or 0).
 */
class ThemeFollower(
    private val context: Context,
    private val onChange: (Boolean, Int) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    ACTION_THEME_CHANGED -> onChange(intent.getBooleanExtra("dark", true), intent.getIntExtra("accent", 0))
                    else -> refresh() // configuration changed (system night mode)
                }
            }
        }

    private val observer =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    fun start() {
        val filter =
            IntentFilter().apply {
                addAction(ACTION_THEME_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            context.contentResolver.registerContentObserver(URI, false, observer)
        } catch (_: SecurityException) {
        }
        refresh()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
        context.contentResolver.unregisterContentObserver(observer)
    }

    fun refresh() {
        val (dark, accent) = current(context)
        onChange(dark, accent)
    }

    companion object {
        const val ACTION_THEME_CHANGED = "org.librehu.action.THEME_CHANGED"
        val URI: Uri = Uri.parse("content://org.librehu.launcher.theme/theme")

        /** (dark, accent ARGB or 0) right now: the launcher's theme, else Android's night mode. */
        fun current(context: Context): Pair<Boolean, Int> {
            val fromLauncher =
                try {
                    context.contentResolver.query(URI, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) (c.getInt(0) != 0) to c.getInt(1) else null
                    }
                } catch (_: Exception) {
                    null
                }
            if (fromLauncher != null) return fromLauncher
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return (night != Configuration.UI_MODE_NIGHT_NO) to 0
        }
    }
}
