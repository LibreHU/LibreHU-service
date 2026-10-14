package org.librehu.service.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.provider.Settings
import org.librehu.service.root.RootShell

/**
 * VIDEO OUT RCA. On this head unit it is not a video output of the SoC but a USB display chip (MacroSilicon, USB
 * `534d:6021` or `345f:9132`, CVBS output) fed by Jancar's `com.jancar.usbdisplay`: its `CaptureService` captures the
 * screen (MediaProjection, auto-granted to this system app) and sends the frames over USB bulk.
 *
 * What turns it on (decompiled `ivi-usbdisplay`): `Settings.Global key_rear_display = 1` (its `MainService` then starts
 * `CaptureService` at each start, its `UsbReceiver` does it when the chip appears), and `change_rear_display` sent to
 * `CaptureService` (extra `change`; property `runtime.reardisplay.status` 54 = sending, 55 = stopped). Format (PAL /
 * NTSC), size, offsets and scaling are its own settings screen (`SettingActivity`, exported), kept in
 * `/jancar/config/usb_display.json`. Neither the metazone "rear display" of Autochips nor `hwc.rear_disp_*` is read.
 */
object VideoOut {
    const val PACKAGE = "com.jancar.usbdisplay"
    private const val CAPTURE = "$PACKAGE/.service.CaptureService"
    private const val SETTINGS = "$PACKAGE.activity.SettingActivity"
    private const val ACTION_CHANGE = "change_rear_display"

    /** Makes `CaptureService` look for the chip again (its receiver; harmless when not running). */
    private const val ACTION_REFRESH = "com.jancar.usbdisplay.USB_REFRESH"
    private const val KEY = "key_rear_display"
    private const val STATUS = "runtime.reardisplay.status"

    /** USB ids of the chips `ivi-usbdisplay` drives (its `device_filter.xml`). */
    private val CHIPS = setOf(0x534D to 0x6021, 0x345F to 0x9132)

    fun installed(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun enabled(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, KEY, 0) == 1

    /** USB devices seen by Android, "vvvv:pppp name"; the chip first when present. */
    fun usbDevices(context: Context): List<Pair<String, Boolean>> {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return runCatching { usb.deviceList.values.toList() }
            .getOrDefault(emptyList())
            .map { d ->
                val id = String.format("%04x:%04x", d.vendorId, d.productId)
                val name = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ")
                "$id $name".trim() to ((d.vendorId to d.productId) in CHIPS)
            }.sortedByDescending { it.second }
    }

    fun chipPresent(context: Context): Boolean = usbDevices(context).any { it.second }

    /** `runtime.reardisplay.status` of `CaptureService`: true = sending, false = stopped, null = never set. */
    fun sending(): Boolean? =
        when (prop(STATUS)) {
            "54" -> true
            "55" -> false
            else -> null
        }

    /** On / off as Jancar's settings do it (root: `key_rear_display` is a global setting). Null = done, else the error. */
    fun setEnabled(
        context: Context,
        on: Boolean,
    ): String? {
        if (!RootShell.isAvailable()) return "root"
        if (RootShell.run("settings put global $KEY ${if (on) 1 else 0}") == null) return "settings"
        val start = "am startservice -n $CAPTURE"
        // Created first (it looks for the chip on creation), then told to send / stop.
        val command = if (on) "$start; sleep 1; $start -a $ACTION_CHANGE --ez change true" else "$start -a $ACTION_CHANGE --ez change false"
        RootShell.run(command)
        if (on) refresh(context)
        return null
    }

    fun refresh(context: Context) {
        runCatching { context.sendBroadcast(Intent(ACTION_REFRESH)) }
    }

    /** Jancar's output settings: quality, size (PAL / NTSC), offsets, scaling. */
    fun openSettings(context: Context): Boolean =
        runCatching {
            context.startActivity(Intent().setComponent(ComponentName(PACKAGE, SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess

    private fun prop(name: String): String =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String
        }.getOrDefault("")
}
