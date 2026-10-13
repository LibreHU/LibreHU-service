package org.librehu.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.root.RootShell

data class MicSettings(
    /** Hardware gain of the microphones: [Microphone.GAINS] (3 = ROM default), 0 = left to the ROM. */
    val gain: Int = 0,
    /** Third-party echo cancellation / noise reduction of the audio HAL (ECNR 1), null = left to the ROM. */
    val ecnr: Boolean? = null,
)

/**
 * Microphone settings of Jancar's factory settings (ivi-settings, "Mic gain" and "ECNR"), without ivi-settings:
 * - gain: audio HAL parameters `MIC1_HW_GAIN` / `MIC2_HW_GAIN` = 3, 4 or 5 (AudioALSAHardware of
 *   audio.primary.ac8257.so), kept in `persist.jancar.mic.hw.gain` like ivi-settings (which applies it again at
 *   start); not kept by the HAL across restarts, so applied again by [LibreHuService] at each start;
 * - ECNR: `persist.audiohal.third_ecnr_lib.enable` and `vendor.audiohal.third_ecnr_lib.enable` (0 / 1), read by the
 *   HAL when a call or voice stream opens. Properties are written through root.
 */
object Microphone {
    private const val TAG = "LibreHU-Mic"

    /** The three levels of ivi-settings (MicGainAdjust8257Activity). */
    val GAINS = listOf(3, 4, 5)

    private val _settings = MutableStateFlow(MicSettings())
    val settings: StateFlow<MicSettings> = _settings.asStateFlow()
    private var loaded = false

    fun get(context: Context): StateFlow<MicSettings> {
        load(context)
        return settings
    }

    fun update(
        context: Context,
        transform: (MicSettings) -> MicSettings,
    ) {
        load(context)
        val old = _settings.value
        val s = transform(old)
        _settings.value = s
        LibreHuService
            .prefs(context)
            .edit()
            .putInt(KEY_GAIN, s.gain)
            .putInt(KEY_ECNR, s.ecnr?.let { if (it) 1 else 0 } ?: -1)
            .apply()
        Thread({ apply(context, s, gainChanged = s.gain != old.gain, ecnrChanged = s.ecnr != old.ecnr) }, "mic").start()
    }

    /** At the service start: the chosen gain again (the HAL forgets it). */
    fun applyAtStart(context: Context) {
        load(context)
        val s = _settings.value
        if (s.gain != 0) Thread({ apply(context, s, gainChanged = true, ecnrChanged = false) }, "mic").start()
    }

    /** Values in use now: gain from `persist.jancar.mic.hw.gain`, ECNR from the vendor property. */
    fun current(): Pair<Int?, Boolean?> {
        val gain = prop("persist.jancar.mic.hw.gain").substringAfter("=", "").toIntOrNull()
        val ecnr = prop("vendor.audiohal.third_ecnr_lib.enable").toIntOrNull()?.let { it != 0 }
        return gain to ecnr
    }

    private fun apply(
        context: Context,
        s: MicSettings,
        gainChanged: Boolean,
        ecnrChanged: Boolean,
    ) {
        if (gainChanged && s.gain in GAINS) {
            val am = context.getSystemService(AudioManager::class.java)
            runCatching {
                am.setParameters("MIC1_HW_GAIN=${s.gain}")
                am.setParameters("MIC2_HW_GAIN=${s.gain}")
            }.onFailure { Log.w(TAG, "gain: ${it.message}") }
            if (RootShell.isAvailable()) RootShell.run("setprop persist.jancar.mic.hw.gain MIC1_HW_GAIN=${s.gain}")
        }
        if (ecnrChanged && s.ecnr != null && RootShell.isAvailable()) {
            val v = if (s.ecnr) 1 else 0
            RootShell.run("setprop persist.audiohal.third_ecnr_lib.enable $v; setprop vendor.audiohal.third_ecnr_lib.enable $v")
        }
    }

    private fun prop(name: String): String =
        runCatching {
            Runtime
                .getRuntime()
                .exec(arrayOf("getprop", name))
                .inputStream
                .bufferedReader()
                .use { it.readText().trim() }
        }.getOrDefault("")

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = LibreHuService.prefs(context)
        val ecnr = p.getInt(KEY_ECNR, -1)
        _settings.value = MicSettings(p.getInt(KEY_GAIN, 0), if (ecnr < 0) null else ecnr == 1)
    }

    private const val KEY_GAIN = "mic_gain"
    private const val KEY_ECNR = "mic_ecnr"
}
