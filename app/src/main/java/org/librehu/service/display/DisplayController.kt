package org.librehu.service.display

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.LibreHuService
import org.librehu.service.ServiceState
import org.librehu.service.overlay.LevelOverlay
import org.librehu.service.root.RootShell

/** Android dark mode choice. */
enum class DarkMode { UNCHANGED, LIGHT, DARK, HEADLIGHTS }

data class DisplaySettings(
    val darkMode: DarkMode = DarkMode.HEADLIGHTS,
    /** Lower the screen brightness while the headlights are on. */
    val dimWithHeadlights: Boolean = false,
    /** Android brightness 0..255. */
    val dayBrightness: Int = 255,
    val nightBrightness: Int = 90,
)

/**
 * Night driving: Android's dark mode and the screen brightness follow the headlights. The headlight state comes from
 * the service's own MCU link, or from Jancar ivi-services (`ICar.getHeadLightStatus`, raw binder transaction 14) while
 * it still owns the MCU.
 *
 * Needs MODIFY_DAY_NIGHT_MODE (privileged install) for the dark mode and WRITE_SETTINGS (granted to privileged
 * apps, or by the user in Android's "Modify system settings") for the brightness. LibreHU Launcher can also switch
 * the dark mode: use one of the two.
 */
class DisplayController private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("display", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DisplaySettings> = _settings.asStateFlow()

    private val _headlights = MutableStateFlow<Boolean?>(null)

    /** Headlights on, null while unknown. */
    val headlights: StateFlow<Boolean?> = _headlights.asStateFlow()

    private val _darkModeAllowed = MutableStateFlow(true)
    val darkModeAllowed: StateFlow<Boolean> = _darkModeAllowed.asStateFlow()

    private val _brightnessAllowed = MutableStateFlow(true)
    val brightnessAllowed: StateFlow<Boolean> = _brightnessAllowed.asStateFlow()

    private var ivi: IviHeadlights? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (LibreHuService.isIviServicesEnabled(app)) ivi = IviHeadlights(app) { on -> main.post { setHeadlights(on, fromIvi = true) } }
        apply()
    }

    fun stop() {
        started = false
        ivi?.release()
        ivi = null
    }

    /** Headlight state from the service's MCU link. */
    fun onHeadlights(on: Boolean) = main.post { setHeadlights(on, fromIvi = false) }

    private fun setHeadlights(
        on: Boolean?,
        fromIvi: Boolean,
    ) {
        // The MCU link wins over ivi-services when both report.
        if (fromIvi && ServiceState.link.value.state == LibreHuService.Link.RUNNING) return
        if (_headlights.value == on) return
        _headlights.value = on
        apply()
    }

    fun update(transform: (DisplaySettings) -> DisplaySettings) {
        val s = transform(_settings.value)
        prefs
            .edit()
            .putString("dark_mode", s.darkMode.name)
            .putBoolean("dim", s.dimWithHeadlights)
            .putInt("day", s.dayBrightness)
            .putInt("night", s.nightBrightness)
            .apply()
        _settings.value = s
        apply()
    }

    fun canWriteSettings(): Boolean = Settings.System.canWrite(app)

    /** Screen to grant "Modify system settings" when the install is not privileged. */
    fun writeSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun apply() {
        val s = _settings.value
        val night = _headlights.value == true
        val dark =
            when (s.darkMode) {
                DarkMode.UNCHANGED -> null
                DarkMode.LIGHT -> false
                DarkMode.DARK -> true
                DarkMode.HEADLIGHTS -> _headlights.value
            }
        if (dark != null) setNightMode(dark)
        if (s.dimWithHeadlights && _headlights.value != null) setBrightness(if (night) s.nightBrightness else s.dayBrightness)
    }

    private fun setNightMode(dark: Boolean) {
        val ui = app.getSystemService(UiModeManager::class.java)
        val mode = if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        try {
            ui.nightMode = mode
            _darkModeAllowed.value = ui.nightMode == mode
        } catch (e: SecurityException) {
            _darkModeAllowed.value = false
            Log.i(TAG, "setNightMode: ${e.message}")
        }
    }

    /** Brightness chosen by the user (brightness panel): WRITE_SETTINGS, else root. */
    fun setUserBrightness(value: Int) {
        val v = value.coerceIn(MIN_BRIGHTNESS, 255)
        if (canWriteSettings()) {
            val r = app.contentResolver
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, v)
        } else {
            Thread { RootShell.run("settings put system screen_brightness_mode 0; settings put system screen_brightness $v") }.start()
        }
    }

    private fun setBrightness(value: Int) {
        // Automatic change (headlights): no brightness panel for it.
        LevelOverlay.brightness(app).quiet(1_500)
        if (!canWriteSettings()) {
            _brightnessAllowed.value = false
            return
        }
        _brightnessAllowed.value = true
        val r = app.contentResolver
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(MIN_BRIGHTNESS, 255))
    }

    private fun load(): DisplaySettings {
        val d = DisplaySettings()
        return DisplaySettings(
            darkMode = runCatching { DarkMode.valueOf(prefs.getString("dark_mode", null)!!) }.getOrDefault(d.darkMode),
            dimWithHeadlights = prefs.getBoolean("dim", d.dimWithHeadlights),
            dayBrightness = prefs.getInt("day", d.dayBrightness),
            nightBrightness = prefs.getInt("night", d.nightBrightness),
        )
    }

    /** Polls `ICar.getHeadLightStatus` of ivi-services every 2 s (raw binder call, no Jancar classes). */
    private class IviHeadlights(
        private val context: Context,
        private val onChange: (Boolean?) -> Unit,
    ) {
        private val thread = HandlerThread("ivi-headlights").apply { start() }
        private val worker = Handler(thread.looper)

        @Volatile
        private var binder: IBinder? = null
        private var bound = false

        private val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    binder = service
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    binder = null
                }
            }

        private val poll =
            object : Runnable {
                override fun run() {
                    onChange(read())
                    worker.postDelayed(this, POLL_MS)
                }
            }

        init {
            bound =
                try {
                    context.bindService(
                        Intent("com.jancar.services.action.car").setPackage("com.jancar.services"),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                } catch (e: SecurityException) {
                    false
                }
            worker.postDelayed(poll, 1000)
        }

        private fun read(): Boolean? {
            val b = binder ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken("com.jancar.services.car.ICar")
                b.transact(TX_HEADLIGHT, data, reply, 0)
                reply.readException()
                reply.readInt() != 0
            } catch (e: Exception) {
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun release() {
            worker.removeCallbacks(poll)
            thread.quitSafely()
            if (bound) {
                try {
                    context.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                }
            }
        }

        private companion object {
            const val POLL_MS = 2000L
            const val TX_HEADLIGHT = 14
        }
    }

    companion object {
        private const val TAG = "LibreHU-Display"
        private const val MIN_BRIGHTNESS = 10

        @Volatile
        private var instance: DisplayController? = null

        fun get(context: Context): DisplayController =
            instance ?: synchronized(this) { instance ?: DisplayController(context).also { instance = it } }
    }
}
