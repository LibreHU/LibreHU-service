package org.librehu.service.touch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.touch.JancarTouchKeys
import org.librehu.core.touch.TouchAction
import org.librehu.core.touch.TouchSample
import org.librehu.core.touch.TouchZone
import org.librehu.core.touch.TouchZoneEngine
import org.librehu.core.touch.ZoneAction
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell

/**
 * Front panel "buttons" that are really the touch panel outside the LCD (ivi-services: `/jancar/config/touch_key.xml`):
 * zones learnt in the Touch tab, each with a click action and a long-press (or repeat) action.
 *
 * Volume goes through the service's audio chip when it drives it ([volumeHook]), else Android's media volume.
 */
class TouchKeys private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("touch", Context.MODE_PRIVATE)
    private val panel = TouchPanel.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)

    /** First start: the factory buttons of the unit (touch_key.xml), so that the front panel works out of the box. */
    private val _zones =
        MutableStateFlow(
            prefs.getString(KEY_ZONES, null)?.let(TouchZoneEngine::fromJson) ?: JancarTouchKeys.parse(JancarTouchKeys.UJC201),
        )
    val zones: StateFlow<List<TouchZone>> = _zones.asStateFlow()

    /** On by default once ivi-services is disabled (it handles the same zones: two actions per press otherwise). */
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, !LibreHuService.isIviServicesEnabled(app)))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _lastAction = MutableStateFlow("")
    val lastAction: StateFlow<String> = _lastAction.asStateFlow()

    /** Service volume (+1 / -1 step, 0 = mute toggle); returns false when the service does not drive the chip. */
    @Volatile
    var volumeHook: ((Int) -> Boolean)? = null

    /** Play / pause of the phone's music when it is the source (set by the Bluetooth module); true when handled. */
    @Volatile
    var playPauseHook: (() -> Boolean)? = null

    /** While learning, zones do not fire. */
    @Volatile
    var learning = false

    private val engine =
        TouchZoneEngine(_zones.value) { zone, action ->
            if (!learning) main.post { run(zone, action) }
        }

    private val tick =
        object : Runnable {
            override fun run() {
                synchronized(engine) { engine.onTick(SystemClock.uptimeMillis()) }
                if (engine.pressed) main.postDelayed(this, TICK_MS)
            }
        }

    /** Raw samples carry kernel time; the engine works on uptime so that ticks and samples agree. */
    private val listener: (TouchSample) -> Unit = { s ->
        synchronized(engine) { engine.onSample(s.copy(timeMs = SystemClock.uptimeMillis())) }
        if (engine.pressed) {
            main.removeCallbacks(tick)
            main.postDelayed(tick, TICK_MS)
        }
    }

    private var started = false

    fun start() {
        if (_enabled.value && !started) {
            started = true
            panel.addListener(listener)
        }
    }

    fun stop() {
        if (started) panel.removeListener(listener)
        started = false
        main.removeCallbacks(tick)
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (on) start() else stop()
    }

    fun save(zones: List<TouchZone>) {
        prefs.edit().putString(KEY_ZONES, TouchZoneEngine.toJson(zones)).apply()
        _zones.value = zones
        engine.zones = zones
    }

    fun upsert(zone: TouchZone) {
        val list = _zones.value.filter { it.id != zone.id } + zone
        save(list.sortedBy { it.id })
    }

    fun delete(id: Int) = save(_zones.value.filter { it.id != id })

    /**
     * Factory mapping: the unit's touch_key.xml (read as root), else the UJC201 one. Replaces the zones; returns
     * them.
     */
    fun loadFactory(): List<TouchZone> {
        val zones =
            RootShell.read(JancarTouchKeys.PATH)?.let(JancarTouchKeys::parse)?.takeIf { it.isNotEmpty() }
                ?: JancarTouchKeys.parse(JancarTouchKeys.UJC201)
        save(zones)
        return zones
    }

    fun nextId(): Int = (_zones.value.maxOfOrNull { it.id } ?: 0) + 1

    /** Runs [action] now (also used by the "test" button of a zone). */
    fun run(
        zone: TouchZone?,
        action: ZoneAction,
    ) {
        _lastAction.value = (zone?.name ?: "") + " → " + action.action.name
        when (action.action) {
            TouchAction.NONE -> {
                Unit
            }

            TouchAction.HOME -> {
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            TouchAction.BACK -> {
                inject(KeyEvent.KEYCODE_BACK)
            }

            TouchAction.RECENTS -> {
                inject(KeyEvent.KEYCODE_APP_SWITCH)
            }

            TouchAction.SCREEN_OFF -> {
                inject(KeyEvent.KEYCODE_SLEEP)
            }

            TouchAction.VOLUME_UP -> {
                volume(+1)
            }

            TouchAction.VOLUME_DOWN -> {
                volume(-1)
            }

            TouchAction.MUTE -> {
                volume(0)
            }

            TouchAction.PLAY_PAUSE -> {
                if (playPauseHook?.invoke() != true) media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }

            TouchAction.NEXT -> {
                media(KeyEvent.KEYCODE_MEDIA_NEXT)
            }

            TouchAction.PREVIOUS -> {
                media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }

            TouchAction.LAUNCH_APP -> {
                app.packageManager
                    .getLaunchIntentForPackage(
                        action.arg,
                    )?.let { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }

            TouchAction.KEYCODE -> {
                action.arg.toIntOrNull()?.let(::inject)
            }

            TouchAction.BRIGHTNESS_UP -> {
                brightness(+1)
            }

            TouchAction.BRIGHTNESS_DOWN -> {
                brightness(-1)
            }

            TouchAction.POWER_MENU -> {
                launcher(ACTION_POWER_MENU)
            }

            TouchAction.ALL_APPS -> {
                launcher(ACTION_ALL_APPS)
            }

            TouchAction.LOCK -> {
                launcher(ACTION_LOCK)
            }

            TouchAction.STANDBY_CLOCK -> {
                launcher(ACTION_STANDBY_CLOCK)
            }
        }
    }

    /** Screens of LibreHU Launcher, by action (the launcher declares them). */
    private fun launcher(action: String) {
        try {
            app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            _lastAction.value += " (LibreHU Launcher?)"
        }
    }

    /** Android brightness by steps of 1/10 (auto brightness turned off), WRITE_SETTINGS or root. */
    private fun brightness(dir: Int) {
        val cr = app.contentResolver
        val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val v = (now + dir * BRIGHTNESS_STEP).coerceIn(BRIGHTNESS_MIN, 255)
        val ok =
            Settings.System.canWrite(app) &&
                runCatching {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
                }.isSuccess
        if (!ok) Thread { RootShell.run("settings put system screen_brightness_mode 0; settings put system screen_brightness $v") }.start()
        _lastAction.value += " $v"
    }

    private fun volume(step: Int) {
        if (volumeHook?.invoke(step) == true) return
        val dir =
            when {
                step > 0 -> AudioManager.ADJUST_RAISE
                step < 0 -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_TOGGLE_MUTE
            }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    /** Media keys reach the active player without any permission. */
    private fun media(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Other keys need injection: `input keyevent` as root (INJECT_EVENTS is a platform signature permission). */
    private fun inject(code: Int) {
        Thread { RootShell.run("input keyevent $code") }.start()
    }

    companion object {
        private const val KEY_ZONES = "zones"
        private const val KEY_ENABLED = "zones_enabled"
        private const val TICK_MS = 50L
        private const val BRIGHTNESS_STEP = 25
        private const val BRIGHTNESS_MIN = 10
        const val ACTION_POWER_MENU = "org.librehu.action.POWER_MENU"
        const val ACTION_ALL_APPS = "org.librehu.action.ALL_APPS"
        const val ACTION_LOCK = "org.librehu.action.LOCK"
        const val ACTION_STANDBY_CLOCK = "org.librehu.action.STANDBY_CLOCK"

        @Volatile
        private var instance: TouchKeys? = null

        fun get(context: Context): TouchKeys = instance ?: synchronized(this) { instance ?: TouchKeys(context).also { instance = it } }
    }
}
