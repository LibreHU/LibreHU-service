package org.librehu.service.car

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    /**
     * Not told yet: only the colour LEDs are driven. The two PWM outputs are left alone: Jancar's display app sends the
     * panel's AVDD and VCOM on the same `0F 02` / `0F 03` (`/jancar/config/screen_voltage.json`, 60 / 38 on two units),
     * so on some boards they may bias the screen, not light buttons.
     */
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

/** Which LEDs blink for a notification. */
enum class BlinkOutput {
    /** The LEDs of the panel type: the colour LEDs, or the white PWM output 1 once "White" is picked. */
    PANEL,

    /** Colour (RGB) LEDs: `0F 04`. */
    COLOUR,

    /** White PWM output 1: `0F 02`. */
    PWM1,

    /** White PWM output 2: `0F 03`. */
    PWM2,

    /** Both white PWM outputs. */
    PWM_BOTH,

    /** The colour LEDs and both white PWM outputs. */
    ALL,
}

/** Where a notification comes from. */
enum class NotificationSource {
    /** A notification of this head unit's Android ([NotificationBlinkService]). */
    ANDROID,

    /** Sent by Open Headunit LibreHU from the Android Auto phone ([ProjectionNotifications]). */
    ANDROID_AUTO,

    /** Sent by CarPlay (xcertplay LibreHU) from the iPhone ([ProjectionNotifications]). */
    CARPLAY,
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
    /** The LEDs that blink. */
    val blinkOutput: BlinkOutput = BlinkOutput.PANEL,
    /** The sources that blink. */
    val blinkAndroid: Boolean = true,
    val blinkAndroidAuto: Boolean = true,
    val blinkCarPlay: Boolean = true,
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
    val drivesWhite: Boolean get() = panel == PanelType.WHITE && backlight
    val day2: Int get() = if (separateOutputs) dayPwm2 else dayPwm1
    val night2: Int get() = if (separateOutputs) nightPwm2 else nightPwm1

    /** The fixed colour at its intensity, on the MCU's 0..99 scale. */
    fun scaled(c: Int) = (c * ledBrightness.coerceIn(0, 100) + 50) / 100

    fun blinks(source: NotificationSource) =
        when (source) {
            NotificationSource.ANDROID -> blinkAndroid
            NotificationSource.ANDROID_AUTO -> blinkAndroidAuto
            NotificationSource.CARPLAY -> blinkCarPlay
        }

    val blinksColour: Boolean
        get() =
            when (blinkOutput) {
                BlinkOutput.PANEL -> panel != PanelType.WHITE
                BlinkOutput.COLOUR, BlinkOutput.ALL -> true
                else -> false
            }
    val blinksPwm1: Boolean
        get() =
            when (blinkOutput) {
                BlinkOutput.PANEL -> panel == PanelType.WHITE
                BlinkOutput.PWM1, BlinkOutput.PWM_BOTH, BlinkOutput.ALL -> true
                else -> false
            }
    val blinksPwm2: Boolean get() = blinkOutput == BlinkOutput.PWM2 || blinkOutput == BlinkOutput.PWM_BOTH || blinkOutput == BlinkOutput.ALL
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

    @Volatile
    private var lastBlinkAt = 0L

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
     * A notification from [source]: a blink when the blink and that source are on, at most one every
     * [BLINK_MIN_GAP_MS] (the white PWM levels are kept in the MCU's flash: a burst of notifications must not
     * write it again and again).
     */
    fun onNotification(source: NotificationSource) {
        val s = _settings.value
        if (!s.blinkOnNotification || !s.blinks(source)) return
        val now = SystemClock.elapsedRealtime()
        if (lastBlinkAt != 0L && now - lastBlinkAt < BLINK_MIN_GAP_MS) return
        lastBlinkAt = now
        main.post { blink() }
    }

    /**
     * A notification: [count] blinks on the LEDs chosen in [FrontLedSettings.blinkOutput], in the blink colour on
     * colour LEDs, in brightness on white ones; then the settings again.
     */
    fun blink(count: Int = _settings.value.blinkCount) {
        val s = _settings.value
        val send = sender ?: return
        if (blinking) return
        blinking = true
        val n = count.coerceIn(1, 10)
        val colour = s.blinksColour
        // The PWM outputs only when chosen ("White" panel, or a white output picked for the blink): with "Not sure"
        // the blink sent 0F 02 (Jancar's AVDD of the screen on the UJC201) and left it at the backlight level.
        val pwm1 = s.blinksPwm1
        val pwm2 = s.blinksPwm2
        // The firmware lights a fixed colour with the headlights only: in the daytime the colour cycle (always lit)
        // is what blinks, at night the chosen colour.
        val night = headlights == true
        val onMode = if (night) LedMode.MANUAL else LedMode.AUTO
        val on = Mcu.frontLed(s.blinkRed, s.blinkGreen, s.blinkBlue, onMode.code)
        val off = Mcu.frontLed(0, 0, 0, LedMode.MANUAL.code)
        // At night the panel LEDs are already lit by the headlights: the loop runs the other way round, "off" first,
        // then lit again. Caveat (firmware JCST_AC8257_8T7 2024.08.09, disassembled 26/11/2026): the MCU powers the
        // white panel LEDs (PF7) from the headlights input in every mode but auto, where they are always on, so
        // "off" may not show on a panel without colour LEDs; kept as asked, to be checked on the unit.
        val first = if (night) off else on
        val second = if (night) on else off
        val lit1 = if (night) s.nightPwm1 else s.dayPwm1
        val lit2 = if (night) s.night2 else s.day2
        val low1 = if (lit1 > 50) 5 else 100
        val low2 = if (lit2 > 50) 5 else 100
        for (i in 0 until n) {
            main.postDelayed({
                if (colour) send(first)
                if (pwm1) send(Mcu.pwmBrightness(1, low1))
                if (pwm2) main.postDelayed({ send(Mcu.pwmBrightness(2, low2)) }, if (pwm1) PWM_GAP_MS else 0L)
            }, i * 2L * STEP_MS)
            main.postDelayed({
                if (colour) send(second)
                if (pwm1) send(Mcu.pwmBrightness(1, lit1))
                if (pwm2) main.postDelayed({ send(Mcu.pwmBrightness(2, lit2)) }, if (pwm1) PWM_GAP_MS else 0L)
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
                    blinkOutput = BlinkOutput.entries.firstOrNull { b -> b.name == o.optString("bo") } ?: BlinkOutput.PANEL,
                    blinkAndroid = o.optBoolean("bsa", true),
                    blinkAndroidAuto = o.optBoolean("bsaa", true),
                    blinkCarPlay = o.optBoolean("bscp", true),
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
            .put("bo", s.blinkOutput.name)
            .put("bsa", s.blinkAndroid)
            .put("bsaa", s.blinkAndroidAuto)
            .put("bscp", s.blinkCarPlay)
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

        /** One blink at most in this time, whatever the number of notifications. */
        const val BLINK_MIN_GAP_MS = 10_000L
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
