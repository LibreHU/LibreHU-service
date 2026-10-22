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

/** Which LEDs light the buttons of this head unit: told by the user, after the test of [FrontLed.testColour] / [FrontLed.testWhite]. */
enum class PanelType {
    /** Not told yet: both kinds are driven. */
    UNKNOWN,

    /** Colour (RGB) LEDs: `0F 04`. */
    COLOUR,

    /** White LEDs on the MCU's two PWM brightness outputs: `0F 02`, `0F 03`. */
    WHITE,
}

/** When the colour LEDs light, in the user's words (the MCU's modes). */
enum class LedWhen(
    val mode: LedMode?,
) {
    /** LibreHU sends nothing: the MCU keeps its own setting. */
    MCU(null),

    /** A fixed colour, lit with the headlights only (the firmware does not light a fixed colour in the daytime). */
    LIGHTS_FIXED(LedMode.MANUAL),

    /** The colour cycle, always lit. */
    ALWAYS_CYCLE(LedMode.AUTO),

    /** The colour cycle, with the headlights only. */
    LIGHTS_CYCLE(LedMode.SEMI_AUTO),
}

data class FrontLedSettings(
    val panel: PanelType = PanelType.UNKNOWN,
    val ledWhen: LedWhen = LedWhen.MCU,
    val red: Int = 0,
    val green: Int = 40,
    val blue: Int = 99,
    /** Intensity of the fixed colour, % (scales R, G, B). */
    val ledBrightness: Int = 100,
    val blinkOnNotification: Boolean = false,
    val blinkRed: Int = 99,
    val blinkGreen: Int = 99,
    val blinkBlue: Int = 99,
    val blinkCount: Int = 3,
    /** White backlight brightness following the headlights (on by default). */
    val backlight: Boolean = true,
    /** The two PWM outputs set apart (else output 2 takes output 1's levels). */
    val separateOutputs: Boolean = false,
    val dayPwm1: Int = 100,
    val nightPwm1: Int = 35,
    val dayPwm2: Int = 100,
    val nightPwm2: Int = 35,
) {
    val drivesColour: Boolean get() = panel != PanelType.WHITE && ledWhen != LedWhen.MCU
    val drivesWhite: Boolean get() = panel != PanelType.COLOUR && backlight
    val day2: Int get() = if (separateOutputs) dayPwm2 else dayPwm1
    val night2: Int get() = if (separateOutputs) nightPwm2 else nightPwm1

    /** The fixed colour at its intensity, on the MCU's 0..99 scale. */
    fun scaled(c: Int) = (c * ledBrightness.coerceIn(0, 100) + 50) / 100
}

/**
 * Button LEDs through the MCU: colour LEDs (`0F 04`, colour and when they light) or white LEDs (the two PWM brightness
 * outputs `0F 02` / `0F 03`, a level with the headlights off and one with them on), tests to tell which ones the head
 * unit has, and a blink when a notification arrives ([NotificationBlinkService]): colour LEDs only light at night for
 * the firmware (fixed colour), white ones blink their brightness, visible in the daytime too.
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

    /** Sends the colour and mode (at each start of the link and after a change), and the white backlight. */
    fun apply() {
        val s = _settings.value
        if (blinking || testing) return
        backlight(force = false)
        val mode = s.ledWhen.mode
        if (s.drivesColour && mode != null) sender?.invoke(Mcu.frontLed(s.scaled(s.red), s.scaled(s.green), s.scaled(s.blue), mode.code))
    }

    @Volatile
    private var headlights: Boolean? = null

    private val _night = MutableStateFlow<Boolean?>(null)

    /** Headlights as last told (null = not known yet: the daytime level is used). */
    val night: StateFlow<Boolean?> = _night

    /** Headlights changed (DisplayController): the white backlight takes the night or day brightness. */
    fun onHeadlights(on: Boolean?) {
        headlights = on
        _night.value = on
        if (!blinking && !testing) backlight(force = false)
    }

    /**
     * The brightness for the headlights now on both PWM outputs (the daytime one while they are not known yet, so
     * that it is set from the start). The MCU writes them to its flash: only a value that differs from the last one
     * sent goes out (unless [force]).
     */
    private fun backlight(force: Boolean) {
        val s = _settings.value
        val send = sender ?: return
        if (!s.drivesWhite) return
        val night = headlights == true
        val p1 = if (night) s.nightPwm1 else s.dayPwm1
        val p2 = if (night) s.night2 else s.day2
        val now = "$p1,$p2"
        if (!force && prefs.getString(PREF_PWM_SENT, null) == now) return
        send(Mcu.pwmBrightness(1, p1))
        main.postDelayed({ send(Mcu.pwmBrightness(2, p2)) }, PWM_GAP_MS)
        prefs.edit().putString(PREF_PWM_SENT, now).apply()
    }

    @Volatile
    private var testing = false

    /** A level set with a slider, shown for a few seconds on its output(s), then the level for the headlights now. */
    fun preview(
        channel: Int?,
        percent: Int,
    ) {
        val send = sender ?: return
        testing = true
        main.removeCallbacksAndMessages(TEST)
        if (channel == null || channel == 1) send(Mcu.pwmBrightness(1, percent))
        if (channel == null || channel == 2) main.postDelayed({ send(Mcu.pwmBrightness(2, percent)) }, PWM_GAP_MS)
        main.postAtTime({ endTest() }, TEST, android.os.SystemClock.uptimeMillis() + PREVIEW_MS)
    }

    /** Test of colour LEDs: the colour cycle, always lit, for a few seconds (visible in the daytime too). */
    fun testColour() {
        val send = sender ?: return
        testing = true
        main.removeCallbacksAndMessages(TEST)
        send(Mcu.frontLed(99, 99, 99, LedMode.AUTO.code))
        main.postAtTime({
            // Back to what LibreHU sets, or to the MCU's default mode (manual, lit with the headlights)
            val s = _settings.value
            if (!s.drivesColour) send(Mcu.frontLed(s.red, s.green, s.blue, LedMode.MANUAL.code))
            endTest()
        }, TEST, android.os.SystemClock.uptimeMillis() + TEST_COLOUR_MS)
    }

    /** Test of white LEDs: both PWM outputs between full and low brightness a few times. */
    fun testWhite() {
        val send = sender ?: return
        testing = true
        main.removeCallbacksAndMessages(TEST)
        val t0 = android.os.SystemClock.uptimeMillis()
        for (i in 0 until TEST_WHITE_STEPS) {
            val level = if (i % 2 == 0) 100 else 5
            main.postAtTime({ send(Mcu.pwmBrightness(1, level)) }, TEST, t0 + i * TEST_WHITE_STEP_MS)
            main.postAtTime({ send(Mcu.pwmBrightness(2, level)) }, TEST, t0 + i * TEST_WHITE_STEP_MS + PWM_GAP_MS)
        }
        main.postAtTime({ endTest() }, TEST, t0 + TEST_WHITE_STEPS * TEST_WHITE_STEP_MS + PWM_GAP_MS)
    }

    private fun endTest() {
        testing = false
        prefs.edit().remove(PREF_PWM_SENT).apply()
        backlight(force = true)
        apply()
    }

    /**
     * A notification: [count] blinks, in the blink colour on colour LEDs, in brightness on white ones; then the
     * settings again.
     */
    fun blink(count: Int = _settings.value.blinkCount) {
        val s = _settings.value
        val send = sender ?: return
        if (blinking) return
        blinking = true
        val n = count.coerceIn(1, 10)
        val colour = s.panel != PanelType.WHITE
        val white = s.panel != PanelType.COLOUR
        val on = Mcu.frontLed(s.blinkRed, s.blinkGreen, s.blinkBlue, LedMode.MANUAL.code)
        val off = Mcu.frontLed(0, 0, 0, LedMode.MANUAL.code)
        val lit = if (headlights == true) s.nightPwm1 else s.dayPwm1
        val low = if (lit > 50) 5 else 100
        for (i in 0 until n) {
            main.postDelayed({
                if (colour) send(on)
                if (white) send(Mcu.pwmBrightness(1, low))
            }, i * 2L * STEP_MS)
            main.postDelayed({
                if (colour) send(off)
                if (white) send(Mcu.pwmBrightness(1, lit))
            }, (i * 2L + 1) * STEP_MS)
        }
        main.postDelayed({
            blinking = false
            prefs.edit().remove(PREF_PWM_SENT).apply()
            if (colour && !s.drivesColour) send(Mcu.frontLed(s.red, s.green, s.blue, LedMode.MANUAL.code))
            backlight(force = true)
            apply()
        }, n * 2L * STEP_MS)
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
                // Before the panel / "when" settings: "managed" + "mode"
                val oldWhen =
                    if (!o.optBoolean("managed", false)) {
                        LedWhen.MCU
                    } else {
                        when (o.optString("mode")) {
                            "AUTO" -> LedWhen.ALWAYS_CYCLE
                            "SEMI_AUTO" -> LedWhen.LIGHTS_CYCLE
                            else -> LedWhen.LIGHTS_FIXED
                        }
                    }
                FrontLedSettings(
                    panel = PanelType.entries.firstOrNull { p -> p.name == o.optString("panel") } ?: PanelType.UNKNOWN,
                    ledWhen = LedWhen.entries.firstOrNull { w -> w.name == o.optString("when") } ?: oldWhen,
                    red = o.optInt("r", 0),
                    green = o.optInt("g", 40),
                    blue = o.optInt("b", 99),
                    ledBrightness = o.optInt("lb", 100),
                    blinkOnNotification = o.optBoolean("blink", false),
                    blinkRed = o.optInt("br", 99),
                    blinkGreen = o.optInt("bg", 99),
                    blinkBlue = o.optInt("bb", 99),
                    blinkCount = o.optInt("bn", 3),
                    backlight = o.optBoolean("bl", true),
                    separateOutputs = o.optBoolean("sep", false),
                    dayPwm1 = o.optInt("d1", 100),
                    nightPwm1 = o.optInt("n1", 35),
                    dayPwm2 = o.optInt("d2", 100),
                    nightPwm2 = o.optInt("n2", 35),
                )
            }.getOrNull()
        } ?: FrontLedSettings()

    private fun toJson(s: FrontLedSettings) =
        JSONObject()
            .put("panel", s.panel.name)
            .put("when", s.ledWhen.name)
            .put("r", s.red)
            .put("g", s.green)
            .put("b", s.blue)
            .put("lb", s.ledBrightness)
            .put("blink", s.blinkOnNotification)
            .put("br", s.blinkRed)
            .put("bg", s.blinkGreen)
            .put("bb", s.blinkBlue)
            .put("bn", s.blinkCount)
            .put("bl", s.backlight)
            .put("sep", s.separateOutputs)
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
        private const val PREVIEW_MS = 3000L
        private const val TEST_COLOUR_MS = 6000L
        private const val TEST_WHITE_STEPS = 6
        private const val TEST_WHITE_STEP_MS = 600L

        /** Token of the test / preview callbacks. */
        private val TEST = Any()

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
