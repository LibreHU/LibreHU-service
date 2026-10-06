package org.librehu.service.power

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.root.RootShell

/** What happens once the ignition has been off for a while (ivi-services' "ACC long off" and "Smart ACC off"). */
enum class StandbyMode {
    /** Nothing more than the screen, sound and amplifier cut (the MCU cuts the SoC by itself). */
    OFF,

    /** Android asleep, the MCU keeps the SoC suspended up to [StandbySettings.maxHours], then cuts it. */
    SLEEP,

    /** Storage written, the MCU cuts the SoC (next start is a cold boot). */
    SHUTDOWN,
}

data class StandbySettings(
    /** Ignition off ignored for this long (engine start), 0..10 s. */
    val accOffDelaySec: Int = 3,
    val mode: StandbyMode = StandbyMode.OFF,
    /** SLEEP: the MCU cuts the SoC after this many hours (in its steps of 7 h). */
    val maxHours: Int = 56,
    /** SLEEP: airplane mode and GPS off while asleep, back on wake up (root). */
    val radiosOff: Boolean = true,
    /** Media paused at standby and resumed on wake up if it was playing. */
    val resumeMedia: Boolean = true,
) {
    /** Value of the MCU's sleep timer (`F1 n`, n × 420 min). */
    val mcuUnits: Int
        get() = if (mode == StandbyMode.SLEEP) ((maxHours * 60 + MCU_UNIT_MIN - 1) / MCU_UNIT_MIN).coerceIn(1, 255) else 0

    companion object {
        /** Minutes per unit of `F1` on firmware 2024.08.09 (Android's code assumed 6 h). */
        const val MCU_UNIT_MIN = 420
        const val MAX_ACC_OFF_DELAY_SEC = 10
        val HOURS_CHOICES = listOf(7, 14, 28, 56, 112, 168, 336)
    }
}

/**
 * Standby after the ignition is cut, like ivi-services' `onAccLongOff` (docs/ivi-services/04-power-acc.md):
 * once the ACC is reported off (after the engine-start delay of HeadUnit), and [PREPARE_DELAY_MS] later if it is still
 * off: media paused, home screen, airplane mode and GPS off, storage written, MCU sleep timer (`F1`), screen off.
 * Everything must happen before the MCU cuts the SoC, 15 s after the ignition.
 *
 * On wake up (ACC back, or the SoC resumed by the MCU): screen on, PC_READY again, radios and media back.
 */
class Standby private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("standby", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<StandbySettings> = _settings.asStateFlow()

    private val _asleep = MutableStateFlow(false)

    /** Standby done, waiting for the ignition. */
    val asleep: StateFlow<Boolean> = _asleep.asStateFlow()

    /** MCU sleep timer, PC_READY again: given by the service once the link runs. */
    var sleepTimer: (Int) -> Unit = {}
    var handshake: () -> Unit = {}
    var log: (String) -> Unit = {}

    private var acc: Boolean? = null
    private var wasPlaying = false
    private var airplaneSet = false
    private var gpsSet = false

    private val prepare = Runnable { enter() }

    fun update(change: (StandbySettings) -> StandbySettings) {
        val s =
            change(
                _settings.value,
            ).let { it.copy(accOffDelaySec = it.accOffDelaySec.coerceIn(0, StandbySettings.MAX_ACC_OFF_DELAY_SEC)) }
        prefs
            .edit()
            .putInt(KEY_DELAY, s.accOffDelaySec)
            .putString(KEY_MODE, s.mode.name)
            .putInt(KEY_HOURS, s.maxHours)
            .putBoolean(KEY_RADIOS, s.radiosOff)
            .putBoolean(KEY_MEDIA, s.resumeMedia)
            .apply()
        _settings.value = s
    }

    /** Engine-start delay for HeadUnit (ms). */
    fun accOffDelayMs(): Long = _settings.value.accOffDelaySec * 1000L

    /** ACC as reported by HeadUnit (already past the engine-start delay). */
    fun onAcc(on: Boolean) {
        val old = acc
        acc = on
        if (old == on) return
        main.removeCallbacks(prepare)
        if (on) {
            if (old != null) exit("ignition on")
        } else if (old == true && _settings.value.mode != StandbyMode.OFF) {
            main.postDelayed(prepare, PREPARE_DELAY_MS)
        }
    }

    private fun enter() {
        if (acc != false || _asleep.value) return
        val s = _settings.value
        log("Standby: ${s.mode}${if (s.mode == StandbyMode.SLEEP) ", MCU timer ${s.mcuUnits} × 7 h" else ""}")
        _asleep.value = true
        val audio = app.getSystemService(AudioManager::class.java)
        wasPlaying = s.resumeMedia && audio?.isMusicActive == true
        mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        runCatching {
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Thread({
            if (s.mode == StandbyMode.SLEEP && s.radiosOff) radiosOff()
            runCatching { ProcessBuilder("sync").start().waitFor() }
            sleepTimer(s.mcuUnits)
            runCatching { ProcessBuilder("sync").start().waitFor() }
            screen(false)
            if (s.mode == StandbyMode.SLEEP) main.post { watchResume() }
        }, "standby").start()
    }

    private fun exit(why: String) {
        main.removeCallbacks(resumeWatch)
        if (!_asleep.value) return
        _asleep.value = false
        log("Wake up ($why)")
        handshake()
        val play = wasPlaying
        val airplane = airplaneSet
        val gps = gpsSet
        airplaneSet = false
        gpsSet = false
        Thread({
            screen(true)
            if (airplane) setAirplane(false)
            if (gps) RootShell.run("settings put secure location_providers_allowed +gps")
            if (play) {
                // The Bluetooth phone reconnects first (airplane mode off).
                Thread.sleep(RESUME_MEDIA_DELAY_MS)
                if (acc == true) mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }, "wake-up").start()
    }

    // The MCU wakes the SoC (power pulse) when the ignition comes back and cuts it 10 s later without PC_READY. The
    // screen may stay off and the ACC frame only comes after PC_READY: a jump of the wall clock against the uptime
    // (which stops while suspended) shows the resume.
    private var lastElapsed = 0L
    private var lastUptime = 0L

    private val resumeWatch =
        object : Runnable {
            override fun run() {
                val e = SystemClock.elapsedRealtime()
                val u = SystemClock.uptimeMillis()
                val suspended = (e - lastElapsed) - (u - lastUptime)
                lastElapsed = e
                lastUptime = u
                if (suspended > RESUME_JUMP_MS && _asleep.value) {
                    log("SoC resumed after ${suspended / 1000} s asleep: PC_READY")
                    handshake()
                }
                if (_asleep.value) main.postDelayed(this, RESUME_WATCH_MS)
            }
        }

    private fun watchResume() {
        lastElapsed = SystemClock.elapsedRealtime()
        lastUptime = SystemClock.uptimeMillis()
        main.removeCallbacks(resumeWatch)
        main.postDelayed(resumeWatch, RESUME_WATCH_MS)
    }

    private fun radiosOff() {
        val airplaneOn = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        if (!airplaneOn) airplaneSet = setAirplane(true)
        @Suppress("DEPRECATION")
        val providers = Settings.Secure.getString(app.contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED).orEmpty()
        if ("gps" in providers) gpsSet = RootShell.run("settings put secure location_providers_allowed -gps") != null
    }

    /** Android 9: the setting plus the broadcast the system sends itself (both need root here). */
    private fun setAirplane(on: Boolean): Boolean =
        RootShell.run(
            "settings put global airplane_mode_on ${if (on) 1 else 0}; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on",
        ) != null

    /** Screen off / on: hidden PowerManager.goToSleep / wakeUp (privileged app), else a key from root. */
    private fun screen(on: Boolean) {
        val pm = app.getSystemService(PowerManager::class.java)
        val done =
            try {
                PowerManager::class.java
                    .getMethod(
                        if (on) "wakeUp" else "goToSleep",
                        Long::class.javaPrimitiveType,
                    ).invoke(pm, SystemClock.uptimeMillis())
                true
            } catch (_: Throwable) {
                false
            }
        if (!done) RootShell.run("input keyevent ${if (on) KeyEvent.KEYCODE_WAKEUP else KeyEvent.KEYCODE_SLEEP}")
    }

    private fun mediaKey(code: Int) {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun load(): StandbySettings {
        val d = StandbySettings()
        return StandbySettings(
            accOffDelaySec = prefs.getInt(KEY_DELAY, d.accOffDelaySec),
            mode = runCatching { StandbyMode.valueOf(prefs.getString(KEY_MODE, null)!!) }.getOrDefault(d.mode),
            maxHours = prefs.getInt(KEY_HOURS, d.maxHours),
            radiosOff = prefs.getBoolean(KEY_RADIOS, d.radiosOff),
            resumeMedia = prefs.getBoolean(KEY_MEDIA, d.resumeMedia),
        )
    }

    companion object {
        private const val KEY_DELAY = "acc_off_delay"
        private const val KEY_MODE = "mode"
        private const val KEY_HOURS = "max_hours"
        private const val KEY_RADIOS = "radios_off"
        private const val KEY_MEDIA = "resume_media"

        /** After the ACC is reported off: the MCU cuts the SoC 15 s after the ignition (engine delay included). */
        const val PREPARE_DELAY_MS = 1500L
        private const val RESUME_WATCH_MS = 1000L
        private const val RESUME_JUMP_MS = 3000L
        private const val RESUME_MEDIA_DELAY_MS = 4000L

        @Volatile
        private var instance: Standby? = null

        fun get(context: Context): Standby = instance ?: synchronized(this) { instance ?: Standby(context).also { instance = it } }
    }
}
