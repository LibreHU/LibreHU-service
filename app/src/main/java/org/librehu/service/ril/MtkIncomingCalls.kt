package org.librehu.service.ril

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.root.RootShell

/**
 * Incoming calls on UJC201 boards with a MediaTek 4G modem: keeps [MtkCallHelper] running as root while the setting is
 * on (default on). On a board without modem the RIL is not running: the helper says `none` and is tried again a few
 * times (the RIL starts late), then left alone until the next start. When the RIL restarts, the helper exits and is
 * started again.
 *
 * Experimental: written from the vendor binaries, not tested on a board with a modem and a SIM at the time of writing.
 */
object MtkIncomingCalls {
    private const val TAG = "LibreHU-ril"
    private const val PREFS = "ril"
    private const val KEY_ENABLED = "accept_mtk_calls"
    private const val NONE_RETRIES = 6
    private const val NONE_DELAY_MS = 30_000L
    private const val RESTART_DELAY_MS = 5_000L

    /** What the helper said last: "", "off", "noril", "none", "registered slot1"… */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var thread: Thread? = null

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        on: Boolean,
    ) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start(context) else stop()
    }

    @Synchronized
    fun start(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        thread =
            Thread({ run(app) }, "mtk-calls").apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
        process?.destroy()
        process = null
        _status.value = "off"
    }

    private fun run(context: Context) {
        try {
            RootShell.waitAvailable()
            if (!enabled(context)) {
                _status.value = "off"
                return
            }
            // librehu_modem (ROM) sets ro.radio.noril=1 when the kernel reports no active modem.
            if (RootShell.run("getprop ro.radio.noril")?.trim() == "1") {
                _status.value = "noril"
                return
            }
            val apk = context.applicationInfo.sourceDir
            var none = 0
            while (!Thread.currentThread().isInterrupted && enabled(context)) {
                val p =
                    RootShell.spawn("CLASSPATH='$apk' exec app_process /system/bin ${MtkCallHelper::class.java.name}")
                        ?: return
                process = p
                p.outputStream.close()
                var sawNone = false
                p.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(TAG, line)
                    if (line == "none") sawNone = true
                    if (!line.startsWith("call ")) _status.value = line
                }
                p.errorStream.bufferedReader().readText().takeIf { it.isNotBlank() }?.let { Log.w(TAG, it.trim()) }
                p.waitFor()
                process = null
                if (sawNone) {
                    if (++none >= NONE_RETRIES) return
                    Thread.sleep(NONE_DELAY_MS)
                } else {
                    Thread.sleep(RESTART_DELAY_MS)
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "stopped: ${e.message}")
            _status.value = "error"
        }
    }
}
