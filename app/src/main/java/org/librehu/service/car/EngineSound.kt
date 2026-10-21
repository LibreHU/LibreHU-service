package org.librehu.service.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.librehu.core.obd.ObdPid
import org.librehu.service.LibreHuService
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Engine character: cylinders, exhaust resonances and how uneven the firing sounds (V8 burble). */
enum class EngineProfile(
    val cylinders: Int,
    val resonanceLow: Double,
    val resonanceHigh: Double,
    val uneven: Double,
) {
    I4(4, 110.0, 420.0, 0.05),
    V6(6, 95.0, 380.0, 0.12),
    V8(8, 75.0, 300.0, 0.35),
    V12(12, 120.0, 520.0, 0.04),
}

data class EngineSoundSettings(
    val enabled: Boolean = false,
    val profile: EngineProfile = EngineProfile.V8,
    val volume: Int = 60,
    /** 0 off, 1 light, 2 strong: whistle with the load, blow-off when the throttle closes. */
    val turbo: Int = 0,
    /** Crackles on the overrun (throttle closed at high RPM). */
    val pops: Boolean = true,
    /** Highest RPM played (the real RPM is followed, capped here). */
    val redline: Int = 6500,
)

/**
 * Engine sound made up from the car's OBD values (RPM, throttle or load, read by [ObdManager]), as Potenza Drive does
 * on phones: a synthesis, no recorded sound. Each cylinder firing gives a short pulse (rate = RPM / 60 × cylinders /
 * 2, four-stroke), shaped by two exhaust resonances (band-pass) and a muffler (low-pass opening with the load), with
 * noise, an optional turbo whistle and blow-off, and crackles on the overrun. Played through the media output without
 * asking the audio focus (mixed with the music). Off by default.
 */
class EngineSound private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collect: Job? = null

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<EngineSoundSettings> = _settings.asStateFlow()

    @Volatile
    private var targetRpm = 0.0

    @Volatile
    private var targetLoad = 0.0

    /** Values from the car (false: fading out). */
    @Volatile
    private var live = false

    /** Test sweep running (settings button): the RPM comes from it instead of the car. */
    @Volatile
    private var testUntil = 0L

    @Volatile
    private var thread: Thread? = null

    fun update(transform: (EngineSoundSettings) -> EngineSoundSettings) {
        val s = transform(_settings.value)
        prefs
            .edit()
            .putBoolean(K_ENABLED, s.enabled)
            .putString(K_PROFILE, s.profile.name)
            .putInt(K_VOLUME, s.volume)
            .putInt(K_TURBO, s.turbo)
            .putBoolean(K_POPS, s.pops)
            .putInt(K_REDLINE, s.redline)
            .apply()
        _settings.value = s
        if (s.enabled) start() else stop()
    }

    /** Follows the OBD values while enabled; the synthesis runs only while the car gives an RPM. */
    fun start() {
        if (!_settings.value.enabled || collect?.isActive == true) return
        collect =
            scope.launch {
                ObdManager.get(app).state.collect { st ->
                    val rpm = st.values[ObdPid.RPM.name]
                    live = st.connection == ObdConnection.CONNECTED && rpm != null && rpm > 200
                    if (rpm != null) targetRpm = rpm
                    targetLoad = ((st.values[ObdPid.THROTTLE.name] ?: st.values[ObdPid.ENGINE_LOAD.name] ?: 20.0) / 100.0).coerceIn(0.0, 1.0)
                    if (live) ensureThread()
                }
            }
    }

    fun stop() {
        collect?.cancel()
        collect = null
        live = false
    }

    /** 8 s sweep idle → near the redline → idle, to hear the settings without the car. */
    fun test() {
        testUntil = System.currentTimeMillis() + TEST_MS
        ensureThread()
    }

    private fun testing() = System.currentTimeMillis() < testUntil

    @Synchronized
    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::play, "engine-sound").apply { start() }
    }

    private fun play() {
        val bufferFrames = max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) / 2, RATE / 20)
        val track =
            runCatching {
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    ).setBufferSizeInBytes(bufferFrames * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrElse {
                Log.w(TAG, "AudioTrack: ${it.message}")
                return
            }
        val synth = Synth()
        val block = ShortArray(RATE / 100) // 10 ms
        var gain = 0.0
        try {
            track.play()
            while (true) {
                val s = _settings.value
                val t = testing()
                val on = (live && s.enabled) || t
                // Fades in and out over about half a second, then the thread ends.
                gain = if (on) min(1.0, gain + 0.02) else gain - 0.02
                if (gain <= 0 && !on) break
                val (rpm, load) =
                    if (t) {
                        val x = ((System.currentTimeMillis() - (testUntil - TEST_MS)).toDouble() / TEST_MS).coerceIn(0.0, 1.0)
                        val up = if (x < 0.55) x / 0.55 else (1 - x) / 0.45
                        Pair(850 + up * (s.redline * 0.9 - 850), if (x < 0.55) 0.9 else 0.0)
                    } else {
                        Pair(min(targetRpm, s.redline.toDouble()), targetLoad)
                    }
                synth.render(block, rpm, load, s, gain * s.volume / 100.0)
                track.write(block, 0, block.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "engine sound: ${e.message}")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** The synthesis (audio thread only). */
    private class Synth {
        private var rpm = 850.0
        private var load = 0.2
        private var lastLoad = 0.2
        private var phase = 0.0
        private var cylinder = 0
        private var pulse = 0.0
        private var crackle = 0.0
        private var blowOff = 0.0
        private var turboPhase = 0.0
        private var spool = 0.0
        private val low = Biquad()
        private val high = Biquad()
        private val muffler = Biquad()
        private val blowFilter = Biquad()
        private val rnd = Random(1)

        fun render(
            out: ShortArray,
            targetRpm: Double,
            targetLoad: Double,
            s: EngineSoundSettings,
            gain: Double,
        ) {
            val p = s.profile
            val dt = out.size.toDouble() / RATE
            // The OBD gives a few values a second: the RPM glides to it (about 80 ms), the load a little slower.
            rpm += (targetRpm - rpm) * (1 - exp(-dt / 0.08))
            load += (targetLoad - load) * (1 - exp(-dt / 0.15))
            val falling = targetRpm < rpm - 50
            // Blow-off: the throttle closes suddenly at speed with a turbo.
            if (s.turbo > 0 && lastLoad - load > 0.25 && rpm > 2500 && spool > 0.4) blowOff = 1.0
            lastLoad = load
            spool += ((if (s.turbo > 0) load * min(1.0, rpm / 3500) else 0.0) - spool) * (1 - exp(-dt / 0.6))
            low.bandPass(p.resonanceLow * (0.8 + rpm / 8000), 1.2)
            high.bandPass(p.resonanceHigh * (0.8 + rpm / 6000), 2.0)
            muffler.lowPass(700.0 + 2600.0 * load + rpm / 4, 0.7)
            blowFilter.bandPass(2800.0, 1.5)
            val firing = rpm / 60.0 * p.cylinders / 2.0
            val decay = exp(-1.0 / (RATE * 0.004))
            val overrun = s.pops && load < 0.08 && rpm > 2200 && falling
            for (i in out.indices) {
                phase += firing / RATE
                if (phase >= 1) {
                    phase -= 1
                    cylinder = (cylinder + 1) % p.cylinders
                    // Uneven firing (cross-plane V8): every other pulse louder.
                    val accent = if (cylinder % 2 == 0) 1 + p.uneven else 1 - p.uneven
                    pulse = accent * (0.6 + 0.4 * load) * (0.9 + 0.2 * rnd.nextDouble())
                    if (overrun && rnd.nextDouble() < 0.05) crackle = 1.0
                }
                val noise = rnd.nextDouble() * 2 - 1
                val excite = pulse * (1 + 0.35 * noise)
                pulse *= decay
                var x = low.process(excite) * 1.4 + high.process(excite) * (0.4 + 0.6 * load)
                x = muffler.process(x + noise * 0.03 * (0.3 + load))
                if (crackle > 0.01) {
                    x += crackle * noise * 1.6
                    crackle *= 0.9985
                }
                if (s.turbo > 0 && spool > 0.05) {
                    turboPhase += (1800 + 5200 * spool) / RATE
                    if (turboPhase > 1) turboPhase -= 1
                    x += sin(2 * PI * turboPhase) * 0.05 * spool * s.turbo
                }
                if (blowOff > 0.01) {
                    x += blowFilter.process(noise) * blowOff * 0.5 * s.turbo
                    blowOff *= 0.99993
                }
                out[i] = (softClip(x * gain * 0.9) * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private fun softClip(x: Double) = if (x > 1) 1.0 else if (x < -1) -1.0 else x * (1.5 - 0.5 * x * x)
    }

    /** Second-order filter (RBJ audio EQ cookbook formulas). */
    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun bandPass(
            f: Double,
            q: Double,
        ) {
            val w = 2 * PI * min(f, RATE * 0.45) / RATE
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            b0 = alpha / a0
            b1 = 0.0
            b2 = -alpha / a0
            a1 = -2 * kotlin.math.cos(w) / a0
            a2 = (1 - alpha) / a0
        }

        fun lowPass(
            f: Double,
            q: Double,
        ) {
            val w = 2 * PI * min(f, RATE * 0.45) / RATE
            val alpha = sin(w) / (2 * q)
            val c = kotlin.math.cos(w)
            val a0 = 1 + alpha
            b0 = (1 - c) / 2 / a0
            b1 = (1 - c) / a0
            b2 = b0
            a1 = -2 * c / a0
            a2 = (1 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private fun load(): EngineSoundSettings {
        val d = EngineSoundSettings()
        return EngineSoundSettings(
            enabled = prefs.getBoolean(K_ENABLED, d.enabled),
            profile = runCatching { EngineProfile.valueOf(prefs.getString(K_PROFILE, null)!!) }.getOrDefault(d.profile),
            volume = prefs.getInt(K_VOLUME, d.volume),
            turbo = prefs.getInt(K_TURBO, d.turbo),
            pops = prefs.getBoolean(K_POPS, d.pops),
            redline = prefs.getInt(K_REDLINE, d.redline),
        )
    }

    companion object {
        private const val TAG = "LibreHU-Engine"
        private const val RATE = 44100
        private const val TEST_MS = 8000L
        private const val K_ENABLED = "engine_sound"
        private const val K_PROFILE = "engine_profile"
        private const val K_VOLUME = "engine_volume"
        private const val K_TURBO = "engine_turbo"
        private const val K_POPS = "engine_pops"
        private const val K_REDLINE = "engine_redline"

        @Volatile
        private var instance: EngineSound? = null

        fun get(context: Context): EngineSound = instance ?: synchronized(this) { instance ?: EngineSound(context).also { instance = it } }
    }
}
