package org.librehu.service.power

import android.content.Context
import android.content.pm.ApplicationInfo
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell
import java.io.File

/**
 * Keeps LibreHU service running whatever stops it (request of 08/11/2026: « impossible à tuer », RAM managers,
 * Android…). The MCU cuts the SoC when nobody answers it, so the service must never stay stopped:
 * - always: a foreground service, `START_STICKY`, started at boot (LOCKED_BOOT_COMPLETED): Android starts it again
 *   after a crash or a kill by the low memory killer;
 * - as a system app (the LineageOS ROM puts it in /system/app): `android:persistent` keeps it at the persistent
 *   priority, out of the low memory killer's reach, restarted at once by Android, no « Force stop » button;
 * - with root, a guard outside this app's process group (a force stop kills the group: Autochips QuickBoot at every
 *   screen off, Settings, a RAM cleaner): every [PERIOD_S] seconds it starts the service again when it is not running
 *   and puts its process at the persistent priority (`oom_score_adj` -800) when Android set it lower;
 * - with Magisk or KernelSU, a boot script ([BOOT_SCRIPT], `/data/adb/service.d`) starts the service and the guard
 *   after a reboot even when a force stop left the app "stopped" (Android sends no BOOT_COMPLETED to a stopped app).
 * On by default; the switch (Power tab) removes the guard and the boot script.
 */
object Keepalive {
    private const val PACKAGE = "org.librehu.service"
    private const val SERVICE = "$PACKAGE/.LibreHuService"
    private const val PREF = "keepalive"

    /** Seconds between two checks of the guard. */
    const val PERIOD_S = 3

    /** Boot script for Magisk and KernelSU (general scripts, run in late_start service mode). */
    const val BOOT_SCRIPT = "/data/adb/service.d/librehu-keepalive.sh"

    private fun dir(context: Context) = context.applicationContext.createDeviceProtectedStorageContext().filesDir

    /** While this file exists the guard runs. */
    private fun marker(context: Context) = File(dir(context), "keepalive")

    private fun pidFile(context: Context) = File(dir(context), "keepalive.pid")

    private fun guardScript(context: Context) = File(dir(context), "keepalive.sh")

    fun enabled(context: Context): Boolean = LibreHuService.prefs(context).getBoolean(PREF, true)

    /** The switch of the Power tab; blocking (root): off the main thread. */
    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        LibreHuService.prefs(context).edit().putBoolean(PREF, on).apply()
        if (on) start(context) else stop(context)
    }

    /** Started by the service when it starts; blocking (root): off the main thread. Only one guard runs. */
    fun start(context: Context) {
        if (!enabled(context)) return
        val m = marker(context)
        runCatching { m.writeText(System.currentTimeMillis().toString()) }
        m.setReadable(true, false)
        if (!RootShell.isAvailable()) return
        val d = dir(context).absolutePath
        val pid = pidFile(context).absolutePath
        // Out of this app's process group (a force stop kills the group), then: the service back when it is not
        // running, its priority back to the persistent one when Android set it lower.
        val guard =
            """
            |#!/system/bin/sh
            |# LibreHU service guard (LibreHU-service, power/Keepalive.kt): starts the service again whatever stops it.
            |[ -f $pid ] && kill -0 "${'$'}(cat $pid)" 2>/dev/null && exit 0
            |echo ${'$'}${'$'} > $pid
            |echo ${'$'}${'$'} > /acct/cgroup.procs 2>/dev/null
            |while [ -f ${m.absolutePath} ]; do
            |  p=${'$'}(pidof $PACKAGE)
            |  if [ -z "${'$'}p" ]; then
            |    am start-foreground-service -n $SERVICE >/dev/null 2>&1
            |  else
            |    for i in ${'$'}p; do
            |      a=${'$'}(cat /proc/${'$'}i/oom_score_adj 2>/dev/null)
            |      [ -n "${'$'}a" ] && [ "${'$'}a" -gt -800 ] && echo -800 > /proc/${'$'}i/oom_score_adj
            |    done
            |  fi
            |  sleep $PERIOD_S
            |done
            |rm -f $pid
            |
            """.trimMargin()
        val g = guardScript(context)
        runCatching { g.writeText(guard) }
        g.setReadable(true, false)
        RootShell.run("nohup sh ${g.absolutePath} >/dev/null 2>&1 &", timeoutS = 5)
        installBootScript(d)
    }

    /** Stops the guard and removes the boot script; blocking (root). */
    fun stop(context: Context) {
        marker(context).delete()
        if (RootShell.isAvailable()) RootShell.run("rm -f $BOOT_SCRIPT", timeoutS = 5)
    }

    /** Magisk / KernelSU only (`/data/adb` exists): after each boot, the service and the guard even for a stopped app. */
    private fun installBootScript(dir: String) {
        val script =
            """
            |#!/system/bin/sh
            |# LibreHU service at every boot, even when a force stop left the app stopped (LibreHU-service, power/Keepalive.kt).
            |until [ "${'$'}(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
            |pm path $PACKAGE >/dev/null 2>&1 || { rm -f "${'$'}0"; exit 0; }
            |[ -f $dir/keepalive ] || exit 0
            |pidof $PACKAGE >/dev/null || am start-foreground-service -n $SERVICE >/dev/null 2>&1
            |sleep 10
            |[ -f $dir/keepalive.sh ] && exec sh $dir/keepalive.sh
            |
            """.trimMargin()
        val cmd =
            "[ -d /data/adb ] || exit 0; mkdir -p /data/adb/service.d && " +
                "cat > $BOOT_SCRIPT && chmod 755 $BOOT_SCRIPT"
        RootShell.run(cmd, stdin = script.toByteArray(), timeoutS = 5)
    }

    /** What protects the service now (Power tab); blocking (root). */
    data class Status(
        val enabled: Boolean,
        val root: Boolean,
        val guard: Boolean,
        val bootScript: Boolean,
        /** Installed as a system app: `android:persistent` applies. */
        val persistent: Boolean,
    )

    fun status(context: Context): Status {
        val info = context.applicationInfo
        val persistent = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (info.flags and ApplicationInfo.FLAG_PERSISTENT) != 0
        val root = RootShell.isAvailable()
        val pid = pidFile(context).absolutePath
        val out =
            if (root) {
                RootShell.run("[ -f $pid ] && kill -0 \"\$(cat $pid)\" 2>/dev/null && echo guard; [ -x $BOOT_SCRIPT ] && echo boot", timeoutS = 5).orEmpty()
            } else {
                ""
            }
        return Status(enabled(context), root, "guard" in out, "boot" in out, persistent)
    }
}
