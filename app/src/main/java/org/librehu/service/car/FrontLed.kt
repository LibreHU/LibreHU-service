package org.librehu.service.car

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.librehu.core.mcu.Mcu
import org.librehu.core.mcu.McuFrame
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell

/** Front panel LED mode of the MCU firmware (mcu_firmware.md §9). */
enum class LedMode(
    val code: Int,
) {
    /** Colour cycle, always on. */
    AUTO(1),

    /** Fixed colour, on with the headlights (ILL) only. */
    MANUAL(2),

    /** Colour cycle with the headlights only. */
    SEMI_AUTO(3),
}

data class FrontLedSettings(
    /** False: LibreHU never sends LED frames (the MCU keeps its own setting). */
    val managed: Boolean = false,
    val mode: LedMode = LedMode.MANUAL,
    val red: Int = 0,
    val green: Int = 40,
    val blue: Int = 99,
    val blinkOnNotification: Boolean = false,
    val blinkRed: Int = 99,
    val blinkGreen: Int = 99,
    val blinkBlue: Int = 99,
    val blinkCount: Int = 3,
)

/**
 * Front panel (button) LEDs through the MCU (`0F 04`), and their blinking when a notification arrives
 * ([NotificationBlinkService]). The MCU only lights them in manual mode while the headlights are on: a blink in the
 * daytime is not visible.
 */
class FrontLed private constructor(
    private val context: Context,
) {
    private val prefs = LibreHuService.prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FrontLedSettings> = _settings

    /** Sends a frame to the MCU; set by the service while the Jancar link runs. */
    @Volatile
    var sender: ((McuFrame) -> Unit)? = null

    @Volatile
    private var blinking = false

    fun update(transform: (FrontLedSettings) -> FrontLedSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit().putString(PREF, toJson(s)).apply()
        apply()
    }

    /** Sends the configured colour and mode (at each start of the link and after a change). */
    fun apply() {
        val s = _settings.value
        if (!s.managed || blinking) return
        sender?.invoke(Mcu.frontLed(s.red, s.green, s.blue, s.mode.code))
    }

    /** Blinks [count] times in the blink colour, then puts the configured colour back. */
    fun blink(count: Int = _settings.value.blinkCount) {
        val s = _settings.value
        val send = sender ?: return
        if (blinking) return
        blinking = true
        val on = Mcu.frontLed(s.blinkRed, s.blinkGreen, s.blinkBlue, LedMode.MANUAL.code)
        val off = Mcu.frontLed(0, 0, 0, LedMode.MANUAL.code)
        for (i in 0 until count.coerceIn(1, 10)) {
            main.postDelayed({ send(on) }, i * 2L * STEP_MS)
            main.postDelayed({ send(off) }, (i * 2L + 1) * STEP_MS)
        }
        main.postDelayed({
            blinking = false
            if (s.managed) apply() else send(Mcu.frontLed(s.red, s.green, s.blue, s.mode.code))
        }, count.coerceIn(1, 10) * 2L * STEP_MS)
    }

    /** True when Android lets [NotificationBlinkService] read the notifications. */
    fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
    }

    /** Grants the notification access as root (`cmd notification allow_listener`, Android 9). */
    fun grantNotificationAccessAsRoot(): Boolean {
        val component = ComponentName(context, NotificationBlinkService::class.java).flattenToString()
        return RootShell.isAvailable() && RootShell.run("cmd notification allow_listener $component") != null
    }

    private fun load(): FrontLedSettings =
        prefs.getString(PREF, null)?.let {
            runCatching {
                val o = JSONObject(it)
                FrontLedSettings(
                    managed = o.optBoolean("managed", false),
                    mode = LedMode.entries.firstOrNull { m -> m.name == o.optString("mode") } ?: LedMode.MANUAL,
                    red = o.optInt("r", 0),
                    green = o.optInt("g", 40),
                    blue = o.optInt("b", 99),
                    blinkOnNotification = o.optBoolean("blink", false),
                    blinkRed = o.optInt("br", 99),
                    blinkGreen = o.optInt("bg", 99),
                    blinkBlue = o.optInt("bb", 99),
                    blinkCount = o.optInt("bn", 3),
                )
            }.getOrNull()
        } ?: FrontLedSettings()

    private fun toJson(s: FrontLedSettings) =
        JSONObject()
            .put("managed", s.managed)
            .put("mode", s.mode.name)
            .put("r", s.red)
            .put("g", s.green)
            .put("b", s.blue)
            .put("blink", s.blinkOnNotification)
            .put("br", s.blinkRed)
            .put("bg", s.blinkGreen)
            .put("bb", s.blinkBlue)
            .put("bn", s.blinkCount)
            .toString()

    companion object {
        private const val PREF = "front_led"
        private const val STEP_MS = 350L

        /** Colour presets (R, G, B on the MCU's 0..99 scale). */
        val PRESETS =
            listOf(
                Triple(99, 99, 99),
                Triple(0, 40, 99),
                Triple(0, 99, 99),
                Triple(0, 99, 0),
                Triple(99, 99, 0),
                Triple(99, 40, 0),
                Triple(99, 0, 0),
                Triple(99, 0, 99),
            )

        @Volatile
        private var instance: FrontLed? = null

        fun get(context: Context): FrontLed =
            instance ?: synchronized(this) {
                instance ?: FrontLed(context.applicationContext).also { instance = it }
            }
    }
}
