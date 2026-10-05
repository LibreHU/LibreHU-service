package org.librehu.service.config

import android.content.Context
import android.util.Log
import org.librehu.core.mcu.McuFrame
import org.librehu.core.mcu.McuProfiles
import org.librehu.service.LibreHuService
import java.io.File

/**
 * Start options of the service: the settings of the app, overridden by a `key=value` file when present (first found):
 *
 * - `/data/local/tmp/librehu-service.conf` (`adb push librehu-service.conf /data/local/tmp/`, readable by the app);
 * - `/sdcard/Android/data/org.librehu.service/files/librehu-service.conf`.
 *
 * Keys: `watchdog.disarm` (true/false), `watchdog.toast` (true/false), `watchdog.frame` (MCU frame in hex, command
 * first, e.g. `1F 05`; default: the one of the MCU protocol). `#` starts a comment.
 */
object ServiceConfig {
    data class Watchdog(
        val disarm: Boolean,
        val toast: Boolean,
        /** Custom frame of the file, null = protocol default. */
        val frame: McuFrame?,
        /** Keys set by the file (the app shows them as locked). */
        val fromFile: Set<String>,
        val file: String?,
    )

    const val KEY_DISARM = "watchdog.disarm"
    const val KEY_TOAST = "watchdog.toast"
    const val KEY_FRAME = "watchdog.frame"
    private const val PREF_DISARM = "watchdog_disarm"
    private const val PREF_TOAST = "watchdog_toast"

    fun files(context: Context): List<File> =
        listOfNotNull(
            File("/data/local/tmp/librehu-service.conf"),
            context.getExternalFilesDir(null)?.let { File(it, "librehu-service.conf") },
        )

    /** Values of the first readable file, empty when none. */
    fun read(context: Context): Pair<File?, Map<String, String>> {
        for (f in files(context)) {
            val text = runCatching { if (f.canRead()) f.readText() else null }.getOrNull() ?: continue
            val map =
                text
                    .lineSequence()
                    .map { it.substringBefore('#').trim() }
                    .filter { '=' in it }
                    .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
            return f to map
        }
        return null to emptyMap()
    }

    fun watchdog(context: Context): Watchdog {
        val prefs = LibreHuService.prefs(context)
        val (file, values) = read(context)
        val frame =
            values[KEY_FRAME]?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { McuProfiles.template(text, emptyMap(), ByteArray(0)) }
                    .onFailure { Log.w("LibreHU", "$KEY_FRAME: ${it.message}") }
                    .getOrNull()
            }
        return Watchdog(
            disarm = values[KEY_DISARM]?.toBooleanStrictOrNull() ?: prefs.getBoolean(PREF_DISARM, true),
            toast = values[KEY_TOAST]?.toBooleanStrictOrNull() ?: prefs.getBoolean(PREF_TOAST, true),
            frame = frame,
            fromFile = values.keys.filter { it.startsWith("watchdog.") }.toSet(),
            file = file?.path,
        )
    }

    fun setDisarm(
        context: Context,
        on: Boolean,
    ) = LibreHuService
        .prefs(context)
        .edit()
        .putBoolean(PREF_DISARM, on)
        .apply()

    fun setToast(
        context: Context,
        on: Boolean,
    ) = LibreHuService
        .prefs(context)
        .edit()
        .putBoolean(PREF_TOAST, on)
        .apply()
}
