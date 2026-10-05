package org.librehu.service.touch

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.touch.TouchAction
import org.librehu.core.touch.TouchSample
import org.librehu.core.touch.TouchZone
import org.librehu.core.touch.TouchZoneEngine
import org.librehu.core.touch.ZoneAction
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

    private val _zones = MutableStateFlow(TouchZoneEngine.fromJson(prefs.getString(KEY_ZONES, null)))
    val zones: StateFlow<List<TouchZone>> = _zones.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _lastAction = MutableStateFlow("")
    val lastAction: StateFlow<String> = _lastAction.asStateFlow()

    /** Service volume (+1 / -1 step, 0 = mute toggle); returns false when the service does not drive the chip. */
    @Volatile
    var volumeHook: ((Int) -> Boolean)? = null

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
                media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
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
        }
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

        @Volatile
        private var instance: TouchKeys? = null

        fun get(context: Context): TouchKeys = instance ?: synchronized(this) { instance ?: TouchKeys(context).also { instance = it } }
    }
}
