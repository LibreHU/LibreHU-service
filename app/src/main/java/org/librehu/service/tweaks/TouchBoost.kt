package org.librehu.service.tweaks

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.touch.TouchSample
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell
import org.librehu.service.touch.TouchPanel
import java.io.OutputStream

/**
 * CPU boost on touch, like Android's own input boost on phones (absent from the AC8257 kernel): as soon as a finger
 * lands on the panel, each cpufreq policy's minimum frequency is raised to its maximum, so the first frames of a scroll
 * or an animation do not wait for the governor to ramp up; [HOLD_MS] after the finger leaves, the minimum goes back to
 * the one chosen in Tweaks (or the one read at start). Raw panel events (`mtk-tpd`, [TouchPanel]); writes go through
 * one root shell kept open, not one `su` per touch. Off by default (more heat and current while touching).
 */
class TouchBoost private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)
    private val thread = HandlerThread("touch-boost").apply { start() }
    private val handler = Handler(thread.looper)

    private val _enabled = MutableStateFlow(prefs.getBoolean(PREF, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Policy directory to (boosted, rest) kHz; read once when the boost starts. */
    private var policies: Map<String, Pair<Int, Int>> = emptyMap()
    private var shell: Process? = null
    private var input: OutputStream? = null

    @Volatile
    private var boosted = false

    @Volatile
    private var lastTouch = 0L
    private var listening = false

    private val listener: (TouchSample) -> Unit = { s ->
        if (s.down) {
            lastTouch = SystemClock.uptimeMillis()
            if (!boosted) {
                boosted = true
                handler.post { boost() }
            }
        }
    }

    private val release =
        object : Runnable {
            override fun run() {
                val idle = SystemClock.uptimeMillis() - lastTouch
                if (idle < HOLD_MS) {
                    handler.postDelayed(this, HOLD_MS - idle)
                    return
                }
                write(false)
                boosted = false
            }
        }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(PREF, on).apply()
        _enabled.value = on
        if (on) start() else stop()
    }

    /** At service start, when enabled. */
    fun start() {
        if (!_enabled.value) return
        handler.post {
            if (listening) return@post
            if (!RootShell.isAvailable()) return@post
            val tweaks = Tweaks.get(app)
            val st = tweaks.state.value.takeIf { it.cpu.isNotEmpty() } ?: tweaks.refresh() ?: return@post
            policies =
                st.cpu.associate { p ->
                    val base = if (p.id == "legacy") "/sys/devices/system/cpu/cpu0/cpufreq" else "/sys/devices/system/cpu/cpufreq/policy${p.id}"
                    base to (p.maxKhz to p.minKhz)
                }.filter { (_, v) -> v.first > 0 && v.second > 0 }
            if (policies.isEmpty()) return@post
            listening = true
            TouchPanel.get(app).addListener(listener)
        }
    }

    private fun stop() {
        handler.post {
            if (!listening) return@post
            listening = false
            TouchPanel.get(app).removeListener(listener)
            handler.removeCallbacks(release)
            if (boosted) write(false)
            boosted = false
            closeShell()
        }
    }

    /** First touch: boost now, and give the minimum back [HOLD_MS] after the last touch report. */
    private fun boost() {
        write(true)
        handler.removeCallbacks(release)
        handler.postDelayed(release, HOLD_MS)
    }

    private fun write(boost: Boolean) {
        // The minimum chosen in Tweaks may have changed since the start.
        val chosen = Tweaks.get(app).settings.value.minKhz
        val script =
            policies.entries.joinToString("\n") { (base, v) ->
                val id = base.substringAfter("policy", "legacy")
                val khz = if (boost) v.first else chosen[id] ?: v.second
                "echo $khz > $base/scaling_min_freq 2>/dev/null"
            } + "\n"
        try {
            val out = input ?: openShell() ?: return
            out.write(script.toByteArray())
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "write: ${e.message}")
            closeShell()
        }
    }

    private fun openShell(): OutputStream? =
        try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            // Nothing is expected back; its output is drained so the shell never blocks on a full pipe.
            Thread({
                runCatching {
                    val buf = ByteArray(256)
                    while (p.inputStream.read(buf) >= 0) Unit
                }
            }, "touch-boost-out").apply {
                isDaemon = true
                start()
            }
            shell = p
            p.outputStream.also { input = it }
        } catch (e: Exception) {
            Log.w(TAG, "su: ${e.message}")
            null
        }

    private fun closeShell() {
        runCatching { input?.close() }
        shell?.destroy()
        input = null
        shell = null
    }

    companion object {
        private const val TAG = "LibreHU-TouchBoost"
        private const val PREF = "touch_boost"
        const val HOLD_MS = 1500L

        @Volatile
        private var instance: TouchBoost? = null

        fun get(context: Context): TouchBoost = instance ?: synchronized(this) { instance ?: TouchBoost(context).also { instance = it } }
    }
}
