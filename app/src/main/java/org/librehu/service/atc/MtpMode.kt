package org.librehu.service.atc

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import org.librehu.service.LibreHuService
import org.librehu.service.ServiceState
import org.librehu.service.root.RootShell

/**
 * File transfer with a computer over MTP, off by default. Off: MTP is taken out of the USB functions, the current ones
 * and the screen-unlocked ones that Android applies at each unlock (`svc usb setFunctions` /
 * `setScreenUnlockedFunctions`, kept by UsbDeviceManager across reboots): the port only offers adb and charging, and no
 * MTP server runs. On: MTP in both, and MediaProvider's `MtpService` started at each connection. On Android 9 its
 * `MtpReceiver` does that when the USB state becomes connected + configured with MTP; the Jancar ROM ships MediaProvider
 * without that receiver, so the server never ran (Windows loaded its MTP driver, got no answer, cancelled its commands
 * with `MTP_REQ_CANCEL` after 30 s and showed nothing). The server stops by itself at each unplug (read error on
 * `/dev/mtp_usb`), hence a start at each connection; nothing is started on a ROM that has its own receiver.
 */
object MtpMode {
    private const val TAG = "LibreHU-MTP"

    /** Saved choice ([LibreHuService.prefs]), false by default. */
    const val PREF_MTP = "mtp_to_computer"

    private const val PROVIDER = "com.android.providers.media"
    private const val SERVICE = "com.android.providers.media.MtpService"

    // Hidden constants of android.hardware.usb.UsbManager
    private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    private const val USB_CONNECTED = "connected"
    private const val USB_CONFIGURED = "configured"
    private const val USB_FUNCTION_MTP = "mtp"
    private const val USB_FUNCTION_PTP = "ptp"
    private const val USB_DATA_UNLOCKED = "unlocked"

    // Server started for the current connection (reset when the cable goes or MTP is turned off)
    @Volatile
    private var started = false
    private var registered = false
    private var app: Context? = null

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                onUsbState(intent)
            }
        }

    fun enabled(context: Context) = LibreHuService.prefs(context).getBoolean(PREF_MTP, false)

    /** At the service start: the saved choice applied to the USB functions, and the server watch. */
    fun start(context: Context) {
        app = context.applicationContext
        Thread({ apply(enabled(context)) }, "mtp-mode").start()
    }

    /** From the settings: saved, then applied at once (adb drops for a moment while the port changes functions). */
    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        app = context.applicationContext
        LibreHuService.prefs(context).edit().putBoolean(PREF_MTP, on).apply()
        apply(on)
    }

    @Synchronized
    private fun apply(on: Boolean) {
        val context = app ?: return
        watch(context, on)
        val dump = RootShell.run("dumpsys usb") ?: return log("MTP ${if (on) "on" else "off"}: no root, USB functions unchanged")
        val current = functions(dump, "current_functions")
        val unlocked = functions(dump, "screen_unlocked_functions")
        val changes = mutableListOf<String>()
        if (on) {
            if (USB_FUNCTION_MTP !in unlocked) changes += "svc usb setScreenUnlockedFunctions mtp"
            if (USB_FUNCTION_MTP !in current) changes += "svc usb setFunctions mtp"
        } else {
            if (unlocked.any { it == USB_FUNCTION_MTP || it == USB_FUNCTION_PTP }) changes += "svc usb setScreenUnlockedFunctions"
            if (current.any { it == USB_FUNCTION_MTP || it == USB_FUNCTION_PTP }) changes += "svc usb setFunctions"
        }
        if (changes.isEmpty()) return
        RootShell.run(changes.joinToString("; "))
        log("MTP ${if (on) "on" else "off"}: ${changes.joinToString("; ")} (were current $current, screen unlocked $unlocked)")
    }

    /** The functions of a `dumpsys usb` line such as `current_functions=MTP` or `screen_unlocked_functions=MTP,ADB`. */
    private fun functions(
        dump: String,
        key: String,
    ): List<String> =
        dump
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() && it != "none" }
            ?: emptyList()

    /** The USB state watched (sticky: the current one comes at once) only when MTP is on and the ROM needs it. */
    private fun watch(
        context: Context,
        on: Boolean,
    ) {
        if (on && !registered && romLacksStarter(context)) {
            context.registerReceiver(receiver, IntentFilter(ACTION_USB_STATE))
            registered = true
            log("MTP: the ROM does not start MediaProvider's MTP server, started here at each connection")
        } else if (!on && registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
            started = false
        }
    }

    private fun onUsbState(intent: Intent) {
        val connected = intent.getBooleanExtra(USB_CONNECTED, false)
        val configured = intent.getBooleanExtra(USB_CONFIGURED, false)
        val mtp = intent.getBooleanExtra(USB_FUNCTION_MTP, false)
        val ptp = intent.getBooleanExtra(USB_FUNCTION_PTP, false)
        if (!connected || !(mtp || ptp)) {
            started = false
            return
        }
        if (!configured || started) return
        started = true
        Thread({
            val command =
                "am startservice -n $PROVIDER/$SERVICE --ez $USB_DATA_UNLOCKED true" +
                    (if (ptp) " --ez $USB_FUNCTION_PTP true" else "")
            val out = RootShell.run(command)
            if (out == null) started = false
            log("MTP: server started for the computer (${if (ptp) "PTP" else "MTP"}): ${out?.trim() ?: "no root"}")
        }, "mtp-start").start()
    }

    /** The ROM's MediaProvider has its `MtpService` but no receiver of the USB state to start it. */
    @Suppress("DEPRECATION") // flag overloads of API 33; the unit runs Android 9
    private fun romLacksStarter(context: Context): Boolean {
        val pm = context.packageManager
        val hasService = runCatching { pm.getServiceInfo(ComponentName(PROVIDER, SERVICE), 0) }.isSuccess
        val receivers = runCatching { pm.queryBroadcastReceivers(Intent(ACTION_USB_STATE).setPackage(PROVIDER), 0) }
        return hasService && receivers.getOrNull()?.isEmpty() == true
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        ServiceState.addLog(message)
    }
}
