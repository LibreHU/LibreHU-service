package org.librehu.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.root.RootShell
import java.io.File

data class MicSettings(
    /** Hardware gain of the microphones: [Microphone.GAINS] (3 = ROM default), 0 = left to the ROM. */
    val gain: Int = 0,
    /** Third-party echo cancellation / noise reduction of the audio HAL (ECNR 1), null = left to the ROM. */
    val ecnr: Boolean? = null,
    /** WebRTC ECNR of LibreHU, echo canceller: 0 off, 1 AEC3, 2 AECM (lighter). */
    val aec: Int = 1,
    /** WebRTC ECNR noise suppression: 0 off, 1 low, 2 moderate, 3 high, 4 very high. */
    val ns: Int = 2,
    /** WebRTC ECNR automatic gain (AGC2, digital). */
    val agc: Boolean = false,
    /** WebRTC ECNR microphone channel: 0 left, 1 right, 2 average of both. */
    val micChannel: Int = 0,
)

/** What the audio HAL's ECNR hook loads during calls (see [Microphone.ecnrLibrary]). */
enum class EcnrLibrary {
    /** LibreHU's WebRTC library (LibreHU-flashablesZips native/ecnr-webrtc): the WebRTC settings apply. */
    WEBRTC,

    /** Another library (Jancar's uVoice). */
    OTHER,

    /** libecnrhelper.so without libEcnrForCustomer.so: calls go through without processing. */
    MISSING,

    /** No libecnrhelper.so: this vendor's audio HAL has no ECNR hook. */
    NO_HOOK,
}

/**
 * Microphone settings of Jancar's factory settings (ivi-settings, "Mic gain" and "ECNR"), without ivi-settings:
 * - gain: audio HAL parameters `MIC1_HW_GAIN` / `MIC2_HW_GAIN` = 3, 4 or 5 (AudioALSAHardware of
 *   audio.primary.ac8257.so), kept in `persist.jancar.mic.hw.gain` like ivi-settings (which applies it again at
 *   start); not kept by the HAL across restarts, so applied again by [LibreHuService] at each start;
 * - ECNR: `persist.audiohal.third_ecnr_lib.enable` and `vendor.audiohal.third_ecnr_lib.enable` (0 / 1), read by the
 *   HAL when a call or voice stream opens. Properties are written through root.
 * - WebRTC ECNR of LibreHU (`libEcnrForCustomer.so` put next to `libecnrhelper.so` by the LibreHU LineageOS ROM zips or
 *   the zip ecnr-webrtc): `persist.vendor.librehu.ecnr.{aec,ns,agc,mic}`, read by the library at each call and again
 *   every second (changes apply during a call); written through root when changed and again at each service start.
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
            .putInt(KEY_AEC, s.aec)
            .putInt(KEY_NS, s.ns)
            .putBoolean(KEY_AGC, s.agc)
            .putInt(KEY_CHANNEL, s.micChannel)
            .apply()
        val webrtc = s.aec != old.aec || s.ns != old.ns || s.agc != old.agc || s.micChannel != old.micChannel
        Thread({ apply(context, s, gainChanged = s.gain != old.gain, ecnrChanged = s.ecnr != old.ecnr, webrtcChanged = webrtc) }, "mic").start()
    }

    /** At the service start: the chosen gain again (the HAL forgets it), and the WebRTC settings (empty /data, restore). */
    fun applyAtStart(context: Context) {
        load(context)
        val s = _settings.value
        Thread({ apply(context, s, gainChanged = s.gain != 0, ecnrChanged = false, webrtcChanged = true) }, "mic").start()
    }

    /** Which library the audio HAL's ECNR hook loads (file checks; the HAL of this vendor is 32-bit: /vendor/lib first). */
    fun ecnrLibrary(): EcnrLibrary {
        val dir = listOf("/vendor/lib", "/vendor/lib64").firstOrNull { File(it, "libecnrhelper.so").exists() } ?: return EcnrLibrary.NO_HOOK
        val lib = File(dir, "libEcnrForCustomer.so")
        if (!lib.isFile) return EcnrLibrary.MISSING
        return if (contains(lib, WEBRTC_MARK)) EcnrLibrary.WEBRTC else EcnrLibrary.OTHER
    }

    private fun contains(
        f: File,
        mark: String,
    ): Boolean =
        runCatching {
            f.length() <= 64L shl 20 && String(f.readBytes(), Charsets.ISO_8859_1).contains(mark)
        }.getOrDefault(false)

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
        webrtcChanged: Boolean = false,
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
        if (webrtcChanged && RootShell.isAvailable()) {
            RootShell.run(
                "setprop $PROP.aec ${s.aec}; setprop $PROP.ns ${s.ns}; setprop $PROP.agc ${if (s.agc) 1 else 0}; " +
                    "setprop $PROP.mic ${s.micChannel}",
            )
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
        _settings.value =
            MicSettings(
                gain = p.getInt(KEY_GAIN, 0),
                ecnr = if (ecnr < 0) null else ecnr == 1,
                aec = p.getInt(KEY_AEC, 1).coerceIn(0, 2),
                ns = p.getInt(KEY_NS, 2).coerceIn(0, 4),
                agc = p.getBoolean(KEY_AGC, false),
                micChannel = p.getInt(KEY_CHANNEL, 0).coerceIn(0, 2),
            )
    }

    /** Text the WebRTC library contains (its name in AUDIO_ECNR_SYM). */
    private const val WEBRTC_MARK = "LibreHU WebRTC ECNR"
    private const val PROP = "persist.vendor.librehu.ecnr"
    private const val KEY_GAIN = "mic_gain"
    private const val KEY_ECNR = "mic_ecnr"
    private const val KEY_AEC = "mic_webrtc_aec"
    private const val KEY_NS = "mic_webrtc_ns"
    private const val KEY_AGC = "mic_webrtc_agc"
    private const val KEY_CHANNEL = "mic_webrtc_channel"
}
