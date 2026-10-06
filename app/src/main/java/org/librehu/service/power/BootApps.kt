package org.librehu.service.power

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.librehu.service.root.RootShell

/**
 * Which LibreHU apps start by themselves at boot (their BOOT_COMPLETED receivers). Turning one off disables its boot
 * receiver as root (`pm disable`), so a quicker start with fewer apps in the background; the app still works when
 * opened. This service's own receiver is not listed: it drives the hardware.
 */
object BootApps {
    data class App(
        val pkg: String,
        val label: String,
        val receivers: List<ComponentName>,
        val enabled: Boolean,
    )

    fun list(context: Context): List<App> {
        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val found =
            pm.queryBroadcastReceivers(
                Intent(Intent.ACTION_BOOT_COMPLETED),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        return found
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName.startsWith("org.librehu.") && it.packageName != context.packageName }
            .groupBy { it.packageName }
            .map { (pkg, receivers) ->
                App(
                    pkg = pkg,
                    label =
                        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg),
                    receivers = receivers,
                    enabled = receivers.any { isEnabled(pm, it) },
                )
            }.sortedBy { it.label }
    }

    private fun isEnabled(
        pm: PackageManager,
        c: ComponentName,
    ): Boolean =
        when (pm.getComponentEnabledSetting(c)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> true
            else -> false
        }

    /** Blocking (root shell); false without root. */
    fun set(
        app: App,
        enabled: Boolean,
    ): Boolean {
        val verb = if (enabled) "enable" else "disable"
        return app.receivers.all { RootShell.run("pm $verb ${it.flattenToShortString()}") != null }
    }
}
