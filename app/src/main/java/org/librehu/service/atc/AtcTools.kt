package org.librehu.service.atc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import org.librehu.service.root.RootShell
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Metazone addresses of Autochips' settings (ATC Settings, `AtcDisplaySettingsUtils` / `OtherFragment`). */
object Mtz {
    /** Rear display on / off. */
    const val REAR_DISPLAY = 65550

    /** Fast reverse mirror, CVBS source: 1 left ↔ right + 2 upside down. */
    const val CVBS_MIRROR = 65588

    /** Fast reverse crop, CVBS source: x, y, width, height (4 addresses). */
    const val CVBS_CROP = 65594
    const val SHARPNESS = 65598
    const val DITHER = 65599

    /** Same as [CVBS_MIRROR] / [CVBS_CROP] for the MIPI (AHD) source. */
    const val MIPI_MIRROR = 65600
    const val MIPI_CROP = 65601

    /** AHD camera: 4 = 720p, 6 = 1080p. */
    const val AHD_MODE = 65605

    /** AVM camera: 0 auto, 1 NTSC, 2 PAL, 3 720p25, 4 720p30, 5 1080p25, 6 1080p30. */
    const val AVM_MODE = 65606

    /** Fast reverse source: 0 debug, 1 CVBS, 2 MIPI (AHD), 3 360. */
    const val SOURCE = 65607
    const val FAST_AVM = 65608
    const val CVBS_AVM = 65611

    /** USB port 0: 2 host, 1 device. */
    const val USB_MODE = 65616

    /** Jancar's sensor value (`ADDR_AHD_SENSOR_VALUE`): 0 AHD 720p25, 1 1080p25, 2 720p30, 3 1080p30. */
    const val SENSOR = 66018

    val FAST_REVERSE =
        listOf(SOURCE, AHD_MODE, AVM_MODE, SENSOR, CVBS_MIRROR, MIPI_MIRROR, SHARPNESS, DITHER, FAST_AVM, CVBS_AVM, REAR_DISPLAY) +
            (0..3).map { CVBS_CROP + it } + (0..3).map { MIPI_CROP + it }
}

/**
 * Rear camera format, as Jancar's settings set it on the AC8257 (`ImportSingleConfigUtil.importBackCarVideoSource`,
 * index = `BackCarVideoSource` of `ivi-customer.ini`): 0 CVBS, 1 AHD 720p 25 fps, 2 AHD 720p 30, 3 AHD 1080p 25,
 * 4 AHD 1080p 30. The AHD camera goes through source 3 (the 360 / AVM path of Autochips' list), mode = index + 2 in
 * both camera mode addresses, and a crop of the 720p picture. Read at start-up (LK, ARM2, camera driver): restart.
 */
object CameraFormat {
    const val COUNT = 5

    fun values(index: Int): Map<Int, Int> {
        if (index <= 0) return mapOf(Mtz.SOURCE to 1)
        val i = index.coerceAtMost(COUNT - 1)
        val crop = if (i <= 2) intArrayOf(12, 0, 1268, 720) else IntArray(4)
        return mapOf(
            Mtz.SOURCE to 3,
            Mtz.SENSOR to intArrayOf(0, 0, 2, 1, 3)[i],
            Mtz.AHD_MODE to i + 2,
            Mtz.AVM_MODE to i + 2,
        ) + (0..3).associate { Mtz.MIPI_CROP + it to crop[it] }
    }

    /** Index of the metazone values, null when they match none (set by hand or by ATC Settings). */
    fun current(values: Map<Int, Int>): Int? =
        when (values[Mtz.SOURCE]) {
            1 -> 0
            3 -> values[Mtz.AHD_MODE]?.minus(2)?.takeIf { it in 1 until COUNT }
            else -> null
        }
}

/**
 * Autochips features of the stock ROM, without ATC Settings: metazone values (fast reverse of the ARM2, USB mode) and
 * the boot logo through [AtcHelper] run as root, the USB port role, and the hidden tools (Engineer Mode…). Blocking
 * calls: not on the main thread.
 */
object AtcTools {
    private const val TAG = "LibreHU-ATC"

    val ENGINEER_MODE = ComponentName("com.mediatek.engineermode", "com.mediatek.engineermode.EngineerMode")
    val ATC_SETTINGS = ComponentName("com.android.settings", "com.autochips.atcsettings.mainui.MainActivity")
    val ATC_LOGGER = ComponentName("com.autochips.atclogger", "com.autochips.atclogger.MainActivity")

    fun installed(
        context: Context,
        c: ComponentName,
    ): Boolean = runCatching { context.packageManager.getActivityInfo(c, 0) }.isSuccess

    /** Opens an activity, as root (`am start`) when it is not exported. */
    fun launch(
        context: Context,
        c: ComponentName,
    ): Boolean =
        try {
            context.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w(TAG, "open $c: ${e.message}")
            RootShell.isAvailable() && RootShell.run("am start -n ${c.flattenToShortString()}") != null
        }

    /** Lines `key=value` of [AtcHelper]; null without root. */
    private fun helper(
        context: Context,
        args: String,
    ): Map<String, String>? {
        if (!RootShell.isAvailable()) return null
        val apk = context.applicationInfo.sourceDir
        val out =
            RootShell.run("CLASSPATH='$apk' app_process /system/bin ${AtcHelper::class.java.name} $args", timeoutS = 40) ?: return null
        return out
            .lineSequence()
            .mapNotNull { line ->
                line
                    .trim()
                    .split('=', limit = 2)
                    .takeIf { it.size == 2 }
                    ?.let { it[0] to it[1] }
            }.toMap()
    }

    sealed class Result {
        data class Values(
            val values: Map<Int, Int>,
        ) : Result()

        data class Error(
            val message: String,
        ) : Result()

        data object NoRoot : Result()
    }

    fun read(
        context: Context,
        addresses: List<Int>,
    ): Result {
        val r = helper(context, "get " + addresses.joinToString(" ")) ?: return Result.NoRoot
        r["error"]?.let { return Result.Error(it) }
        return Result.Values(addresses.associateWith { r[it.toString()]?.toIntOrNull() ?: 0 })
    }

    /** Writes and saves (flush); null = done, else the error. */
    fun write(
        context: Context,
        values: Map<Int, Int>,
    ): String? {
        if (values.isEmpty()) return null
        val r = helper(context, "set " + values.entries.joinToString(" ") { "${it.key}=${it.value}" }) ?: return "root"
        return r["error"]
    }

    /** The ARM2 (fast reverse before Android) takes the metazone settings. */
    fun applyArm2(context: Context): String? {
        val r = helper(context, "arm2") ?: return "root"
        return r["error"]
    }

    /**
     * USB port 0 as host (USB keys, phones for Android Auto / CarPlay) or device (adb from a PC): metazone value read at
     * start-up (like ATC Settings), then the port role now through `dumpsys usb set-port-roles` (Android 9). adb is
     * turned on in device mode, as ATC Settings does. A restart may be needed when the port has no role switch.
     */
    fun setUsbMode(
        context: Context,
        host: Boolean,
    ): String? {
        write(context, mapOf(Mtz.USB_MODE to if (host) 2 else 1))?.let { return it }
        val ports = RootShell.run("dumpsys usb ports").orEmpty()
        val ids =
            Regex("""id=([^,\s}]+)""")
                .findAll(ports)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        for (id in ids) RootShell.run("dumpsys usb set-port-roles '$id' source ${if (host) "host" else "device"}")
        if (!host) RootShell.run("settings put global adb_enabled 1")
        return null
    }

    fun reboot() {
        if (RootShell.run("svc power reboot") == null) RootShell.run("reboot")
    }

    /** Copy of the logo partition (boot logo and the panel's start-up description) on the internal storage. */
    fun backupLogo(): String? {
        if (!RootShell.isAvailable()) return null
        val block =
            RootShell
                .run("ls /dev/block/by-name/logo /dev/block/platform/*/by-name/logo /dev/block/platform/*/*/by-name/logo 2>/dev/null")
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = "/sdcard/LibreHU/logo-$stamp.img"
        return RootShell.run("mkdir -p /sdcard/LibreHU && dd if=$block of=$out bs=1M 2>&1 && sync", timeoutS = 60)?.let { out }
    }

    /**
     * New boot logo from a picture: fitted in the screen (its real size, black around), BGRA, top row first, alpha
     * 0xFF, as ATC Settings writes it. The logo partition is copied first ([backupLogo]); nothing is written without
     * that copy.
     */
    fun writeLogo(
        context: Context,
        uri: Uri,
    ): LogoResult {
        if (!RootShell.isAvailable()) return LogoResult(false, null, "root")
        val backup = backupLogo() ?: return LogoResult(false, null, "backup")
        val size = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(size)
        val w = maxOf(size.x, size.y)
        val h = min(size.x, size.y)
        val src =
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return LogoResult(false, backup, "image")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.BLACK)
            val scale = min(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = (src.width * scale).roundToInt()
            val dh = (src.height * scale).roundToInt()
            val left = (w - dw) / 2
            val top = (h - dh) / 2
            drawBitmap(src, null, Rect(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        // ARGB_8888 is stored R, G, B, A: swapped to B, G, R, A.
        val buf = ByteBuffer.allocate(w * h * 4)
        bmp.copyPixelsToBuffer(buf)
        val px = buf.array()
        for (i in px.indices step 4) {
            val r = px[i]
            px[i] = px[i + 2]
            px[i + 2] = r
            px[i + 3] = -1
        }
        val file = File(context.cacheDir, "logo.bgra")
        file.writeBytes(px)
        file.setReadable(true, false)
        val r = helper(context, "logo '${file.absolutePath}'")
        file.delete()
        return when {
            r == null -> LogoResult(false, backup, "root")
            r["error"] != null -> LogoResult(false, backup, r["error"])
            r["logo"] == "0" -> LogoResult(true, backup, "${w}x$h")
            else -> LogoResult(false, backup, "writelogo=${r["logo"]}")
        }
    }

    data class LogoResult(
        val ok: Boolean,
        val backup: String?,
        val detail: String?,
    )
}
