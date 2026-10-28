package org.librehu.service.power

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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

    private val _testing = MutableStateFlow(false)

    /** A suspend test runs ([testSuspend]). */
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** Every step written down, kept across a cut of the SoC (see [StandbyJournal]). */
    val journal = StandbyJournal.get(app)

    /** MCU sleep timer, PC_READY again: given by the service once the link runs. */
    var sleepTimer: (Int) -> Unit = {}
    var handshake: () -> Unit = {}

    private var acc: Boolean? = null
    private var wasPlaying = false

    /** The ACC wire as the MCU reports it (before the engine-start delay), and when it went off. */
    private var accWire: Boolean? = null
    private var accWireOffAt = 0L

    /** Standby entered at (elapsedRealtime), and how long the SoC has been suspended since. */
    private var standbyAt = 0L
    private var suspendedMs = 0L
    private var nextBeatAt = 0L
    private var lockSnapshotDone = false

    private val prepare = Runnable { enter() }

    init {
        Thread({
            journal.boot()
            // Standby entered in the previous run and never left: the MCU cut the SoC (or it rebooted) while asleep.
            prefs.getLong(KEY_ASLEEP_SINCE, 0L).takeIf { it > 0L }?.let { since ->
                note(
                    "Previous standby (entered ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(since))}) " +
                        "never woke up: the SoC was cut or rebooted, this is a cold boot",
                )
                prefs.edit().remove(KEY_ASLEEP_SINCE).apply()
            }
        }, "standby-journal").start()
        // Radios cut by a standby the head unit did not wake up from (the MCU cut the SoC, a reboot, the service
        // restarted): airplane mode stayed on and GPS off. Put back once the service runs again.
        if (prefs.getBoolean(KEY_AIRPLANE_SET, false) || prefs.getBoolean(KEY_GPS_SET, false)) {
            Thread({
                Thread.sleep(START_RESTORE_DELAY_MS)
                if (acc != false && !_asleep.value) restoreRadios("service start")
            }, "standby-restore").start()
        }
    }

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
        if (old != null) {
            note(if (on) "Powered on" else "Powered off after the engine-start delay (${since()}), mode ${_settings.value.mode}")
        }
        main.removeCallbacks(prepare)
        if (on) {
            if (old != null) exit("ignition on")
            // Also when this run of the service did not put the head unit to sleep itself (see init)
            if (!_asleep.value && hasCutRadios()) Thread({ restoreRadios("ignition on") }, "standby-restore").start()
        } else if (old == true && _settings.value.mode != StandbyMode.OFF) {
            main.postDelayed(prepare, PREPARE_DELAY_MS)
        }
    }

    /** The ACC wire as the MCU reports it, at once (written down only: [onAcc] acts after the engine-start delay). */
    fun onAccWire(on: Boolean) {
        val old = accWire
        accWire = on
        if (old == on) return
        if (!on) accWireOffAt = SystemClock.elapsedRealtime()
        if (old != null || !on) note(if (on) "Ignition on (MCU)" else "Ignition off (MCU): the MCU cuts the SoC 15 s later unless F1 is set")
    }

    /** Time since the ignition went off, "t+4.6 s" (the MCU cuts the SoC at t+15 s). */
    private fun since(): String =
        if (accWireOffAt == 0L) "t=?" else "t+%.1f s".format(java.util.Locale.ROOT, (SystemClock.elapsedRealtime() - accWireOffAt) / 1000f)

    private fun note(line: String) {
        Log.i(TAG, line)
        journal.add(line)
    }

    private fun enter() {
        if (acc != false || _asleep.value) return
        val s = _settings.value
        note("Standby: ${s.mode}${if (s.mode == StandbyMode.SLEEP) ", MCU timer ${s.mcuUnits} × 7 h" else ""} (${since()})")
        prefs.edit().putLong(KEY_ASLEEP_SINCE, System.currentTimeMillis()).commit()
        standbyAt = SystemClock.elapsedRealtime()
        suspendedMs = 0L
        lockSnapshotDone = false
        nextBeatAt = standbyAt + FIRST_BEAT_MS
        _asleep.value = true
        val audio = app.getSystemService(AudioManager::class.java)
        wasPlaying = s.resumeMedia && audio?.isMusicActive == true
        mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        runCatching {
            app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        note("Media paused${if (wasPlaying) " (was playing)" else ""}, home screen (${since()})")
        Thread({
            if (s.mode == StandbyMode.SLEEP && s.radiosOff) {
                radiosOff()
                note("Radios off (${since()})")
            }
            runCatching { ProcessBuilder("sync").start().waitFor() }
            sleepTimer(s.mcuUnits)
            note("MCU sleep timer F1 ${s.mcuUnits} sent, storage written (${since()})")
            runCatching { ProcessBuilder("sync").start().waitFor() }
            screen(false)
            note("Screen off (${since()}): preparation done${if (accWireOffAt > 0L && SystemClock.elapsedRealtime() - accWireOffAt > MCU_CUT_MS) ", LATE: after the MCU's 15 s" else ""}")
            if (s.mode == StandbyMode.SLEEP) main.post { watchResume() }
        }, "standby").start()
    }

    private fun exit(why: String) {
        main.removeCallbacks(resumeWatch)
        if (!_asleep.value) return
        _asleep.value = false
        prefs.edit().remove(KEY_ASLEEP_SINCE).apply()
        val total = (SystemClock.elapsedRealtime() - standbyAt) / 1000
        note("Wake up ($why) after $total s of standby, SoC suspended ${suspendedMs / 1000} s of it")
        handshake()
        val play = wasPlaying
        Thread({
            screen(true)
            restoreRadios(why)
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
                if (suspended > 0) suspendedMs += suspended
                if (suspended > RESUME_JUMP_MS && _asleep.value) {
                    note("SoC resumed after ${suspended / 1000} s suspended: PC_READY")
                    handshake()
                    Thread({ journal.kernelPm("Resume") }, "standby-journal").start()
                }
                // Not suspended: Android stays awake, something holds it (written down at the first beat).
                if (_asleep.value && e >= nextBeatAt) {
                    val awake = (e - standbyAt) / 1000
                    note("Still running $awake s after standby, SoC suspended ${suspendedMs / 1000} s in total")
                    if (!lockSnapshotDone && suspendedMs < (e - standbyAt) / 2) {
                        lockSnapshotDone = true
                        Thread({ journal.wakelocks("Not suspending") }, "standby-journal").start()
                    }
                    nextBeatAt = e + if (e - standbyAt < SLOW_BEATS_AFTER_MS) FAST_BEAT_MS else SLOW_BEAT_MS
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

    /**
     * Suspend test with the ignition on: screen off as in a standby (the MCU is not told: no `F1`, nothing cut), an
     * alarm wakes the SoC [seconds] later, then the journal says how long it was really suspended, with the kernel's
     * lines. Not suspended at all: the wakelocks holding it are written down. False when one runs or in standby.
     */
    fun testSuspend(seconds: Int): Boolean {
        if (_asleep.value || _testing.value) return false
        _testing.value = true
        note("TEST: suspend for $seconds s, ignition on (the MCU is not told)")
        val e0 = SystemClock.elapsedRealtime()
        val u0 = SystemClock.uptimeMillis()
        Thread({
            journal.wakelocks("TEST before")
            screen(false)
            note("TEST: screen off")
            main.post {
                val alarms = app.getSystemService(AlarmManager::class.java)
                val done =
                    AlarmManager.OnAlarmListener {
                        val e = SystemClock.elapsedRealtime() - e0
                        val suspended = e - (SystemClock.uptimeMillis() - u0)
                        val verdict =
                            when {
                                suspended >= e / 2 -> "the SoC suspended and woke up"
                                suspended > RESUME_JUMP_MS -> "the SoC suspended only part of the time"
                                else -> "the SoC did NOT suspend (see the wakelocks)"
                            }
                        note("TEST: alarm after ${e / 1000} s, suspended ${suspended / 1000} s: $verdict")
                        Thread({
                            screen(true)
                            if (suspended < e / 2) journal.wakelocks("TEST after")
                            journal.kernelPm("TEST")
                            _testing.value = false
                        }, "standby-test").start()
                    }
                if (alarms == null) {
                    note("TEST: no AlarmManager")
                    _testing.value = false
                } else {
                    alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + seconds * 1000L, "librehu:standby-test", done, main)
                }
            }
        }, "standby-test").start()
        return true
    }

    /**
     * Airplane mode on and GPS off for the night, only when the user had them otherwise, and written down
     * ([KEY_AIRPLANE_SET], [KEY_GPS_SET], committed before anything else: the SoC may be cut any time) so that
     * [restoreRadios] puts them back even after a reboot.
     */
    private fun radiosOff() {
        val airplaneOn = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        @Suppress("DEPRECATION")
        val providers = Settings.Secure.getString(app.contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED).orEmpty()
        val gpsOn = "gps" in providers
        prefs.edit().putBoolean(KEY_AIRPLANE_SET, !airplaneOn).putBoolean(KEY_GPS_SET, gpsOn).commit()
        val airplaneDone = airplaneOn || setAirplane(true)
        val gpsDone = !gpsOn || RootShell.run("settings put secure location_providers_allowed -gps") != null
        if (!airplaneDone || !gpsDone) {
            prefs.edit().putBoolean(KEY_AIRPLANE_SET, !airplaneOn && airplaneDone).putBoolean(KEY_GPS_SET, gpsOn && gpsDone).commit()
        }
    }

    private fun hasCutRadios() = prefs.getBoolean(KEY_AIRPLANE_SET, false) || prefs.getBoolean(KEY_GPS_SET, false)

    /** Airplane mode off and GPS on again if a standby changed them; the marks are cleared once done. */
    private fun restoreRadios(why: String) {
        val airplane = prefs.getBoolean(KEY_AIRPLANE_SET, false)
        val gps = prefs.getBoolean(KEY_GPS_SET, false)
        if (!airplane && !gps) return
        note("Radios back ($why):${if (airplane) " airplane mode off" else ""}${if (gps) " GPS on" else ""}")
        val airplaneDone = !airplane || setAirplane(false)
        val gpsDone = !gps || RootShell.run("settings put secure location_providers_allowed +gps") != null
        prefs.edit().putBoolean(KEY_AIRPLANE_SET, !airplaneDone).putBoolean(KEY_GPS_SET, !gpsDone).apply()
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
        if (!done) {
            val root = RootShell.run("input keyevent ${if (on) KeyEvent.KEYCODE_WAKEUP else KeyEvent.KEYCODE_SLEEP}") != null
            note("Screen ${if (on) "on" else "off"} by a key (root)${if (root) "" else " FAILED: no PowerManager access nor root"}")
        }
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
        private const val KEY_AIRPLANE_SET = "airplane_set_by_standby"
        private const val KEY_GPS_SET = "gps_cut_by_standby"
        private const val START_RESTORE_DELAY_MS = 4000L
        private const val KEY_ASLEEP_SINCE = "journal_asleep_since"
        private const val TAG = "LibreHU-Standby"

        /** The MCU cuts the SoC this long after the ignition when no sleep timer is set. */
        private const val MCU_CUT_MS = 15_000L
        private const val FIRST_BEAT_MS = 30_000L
        private const val FAST_BEAT_MS = 60_000L
        private const val SLOW_BEAT_MS = 600_000L
        private const val SLOW_BEATS_AFTER_MS = 600_000L

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
