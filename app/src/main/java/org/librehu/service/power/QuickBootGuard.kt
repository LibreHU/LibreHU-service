package org.librehu.service.power

import android.content.Context
import org.librehu.service.root.RootShell
import java.io.File

/**
 * Autochips QuickBoot: on this ROM every screen off (Android's goToSleep) is a "QuickBoot power off". The framework
 * broadcasts `autochips.intent.action.QB_POWEROFF` and QuickBootManager (`com.autochips.quickbootmanager`) force stops
 * every app outside its own list, this service and the LibreHU apps included (logcat of 28/10: "Force stopping
 * org.librehu.service … from pid 924"), then Bluetooth, Wi-Fi and the cameras go off. A force stopped app gets no
 * restart and loses its alarms: after the MCU wakes the SoC nobody answers PC_READY and the MCU cuts it again.
 *
 * The guard is a root shell outside this app's process group (a force stop kills the group): while the [marker]
 * file exists it starts this service again whenever it is not running. Once the head unit is awake again
 * [restartApps] starts what QuickBoot stopped (home screen, LibreHU apps' boot receivers, ViPER4Android).
 */
object QuickBootGuard {
    private const val SERVICE = "org.librehu.service/.LibreHuService"

    /** Other apps started again after a wake up, besides the LibreHU ones (their BOOT_COMPLETED receivers). */
    private val EXTRA_APPS = listOf("com.llsl.viper4android")

    private fun marker(context: Context) = File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, "quickboot_guard")

    /** Blocking (root); false without root. */
    fun start(context: Context): Boolean {
        val m = marker(context)
        runCatching { m.writeText(System.currentTimeMillis().toString()) }
        m.setReadable(true, false)
        // The loop sleeps with the SoC; at each turn awake it puts the service back if QuickBoot stopped it.
        val loop =
            "echo \$\$ > /acct/cgroup.procs 2>/dev/null; " +
                "while [ -f ${m.absolutePath} ]; do " +
                "pidof org.librehu.service >/dev/null || am start-foreground-service -n $SERVICE >/dev/null 2>&1; " +
                "sleep 2; done"
        return RootShell.run("nohup sh -c '$loop' >/dev/null 2>&1 &", timeoutS = 5) != null
    }

    fun stop(context: Context) {
        marker(context).delete()
    }

    fun active(context: Context): Boolean = marker(context).exists()

    /** Home screen and the apps QuickBoot stopped (root, blocking): what the start of the head unit would start. */
    fun restartApps(context: Context): List<String> {
        val pkgs =
            BootApps
                .list(context)
                .filter { it.enabled }
                .map { it.pkg } + EXTRA_APPS.filter { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
        val cmd =
            buildString {
                append("am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; ")
                for (p in pkgs) append("am broadcast -a android.intent.action.BOOT_COMPLETED -p $p >/dev/null 2>&1; ")
            }
        RootShell.run(cmd, timeoutS = 30)
        return pkgs
    }
}
