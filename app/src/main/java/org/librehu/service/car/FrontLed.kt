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
    /**
     * Button backlight brightness following the headlights (on by default): the two PWM brightness outputs of the
     * MCU (`0F 02`, `0F 03`), which light the white button LEDs of the panels without colour LEDs.
     */
    val backlight: Boolean = true,
    val dayPwm1: Int = 100,
    val nightPwm1: Int = 35,
    val dayPwm2: Int = 100,
    val nightPwm2: Int = 35,
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

    /** Sends the configured colour and mode (at each start of the link and after a change), and the backlight. */
    fun apply() {
        val s = _settings.value
        backlight(headlights, force = false)
        if (!s.managed || blinking) return
        sender?.invoke(Mcu.frontLed(s.red, s.green, s.blue, s.mode.code))
    }

    @Volatile
    private var headlights: Boolean? = null

    /** Headlights changed (DisplayController): the button backlight takes the night or day brightness. */
    fun onHeadlights(on: Boolean?) {
        headlights = on
        backlight(on, force = false)
    }

    /**
     * The brightness for [night] on both PWM outputs. The MCU writes them to its flash: only a value that differs
     * from the last one sent goes out (unless [force]).
     */
    private fun backlight(
        night: Boolean?,
        force: Boolean,
    ) {
        val s = _settings.value
        val send = sender ?: return
        if (!s.backlight || night == null) return
        val p1 = if (night) s.nightPwm1 else s.dayPwm1
        val p2 = if (night) s.nightPwm2 else s.dayPwm2
        val last = prefs.getString(PREF_PWM_SENT, null)
        val now = "$p1,$p2"
        if (!force && last == now) return
        send(Mcu.pwmBrightness(1, p1))
        main.postDelayed({ send(Mcu.pwmBrightness(2, p2)) }, PWM_GAP_MS)
        prefs.edit().putString(PREF_PWM_SENT, now).apply()
    }

    /** Settings screen: the level of one output now, to see which LEDs it drives. */
    fun testPwm(
        channel: Int,
        percent: Int,
    ) {
        sender?.invoke(Mcu.pwmBrightness(channel, percent))
        prefs.edit().remove(PREF_PWM_SENT).apply()
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
                    backlight = o.optBoolean("bl", true),
                    dayPwm1 = o.optInt("d1", 100),
                    nightPwm1 = o.optInt("n1", 35),
                    dayPwm2 = o.optInt("d2", 100),
                    nightPwm2 = o.optInt("n2", 35),
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
            .put("bl", s.backlight)
            .put("d1", s.dayPwm1)
            .put("n1", s.nightPwm1)
            .put("d2", s.dayPwm2)
            .put("n2", s.nightPwm2)
            .toString()

    companion object {
        private const val PREF = "front_led"
        private const val PREF_PWM_SENT = "front_led_pwm_sent"
        private const val PWM_GAP_MS = 150L
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
