package org.librehu.core.mcu

/**
 * Debug helpers for the Jancar `JAC_V1` MCU, ported from LibreHU/MCU-tools-app (`Frame.java`): readable frame
 * descriptions, risky commands, the commands of its "Commands" tab and the frames of its "Simulation" tab.
 * Reference: MCU-tools-app docs/mcu_firmware.md §6.
 */
object JacDebug {
    /** What sending a command to the MCU can do. */
    enum class Risk {
        NONE,

        /** Turns off / restarts the head unit: ask first. */
        CONFIRM,

        /** Bootloader (`80`): can leave the MCU unusable, never sent from the app. */
        BLOCKED,
    }

    const val CMD_LEARN_WHEEL = 0x11
    const val CMD_LEARN_PANEL = 0x21
    const val CMD_BOOTLOADER = 0x80

    /** Config (`0F`) sub-commands. */
    const val CFG_CAN_BAUD = 0x00
    const val CFG_PWM1 = 0x02
    const val CFG_PWM2 = 0x03
    const val CFG_LED = 0x04
    const val CFG_OPTION = 0x07
    const val CFG_HIGH_VOLTAGE = 0x0A
    const val CFG_LOW_VOLTAGE = 0x0B

    /** Baud rates of the CAN box link (USART1), indexed by the `0F 00` value. */
    val CAN_BAUDS = listOf(9600, 19200, 38400, 57600, 115200, 230400, 460800)

    /** Battery protection thresholds (V), indexed by the `0F 0A` / `0F 0B` value. */
    val HIGH_VOLTS = listOf("16", "16.5", "17", "18", "19", "20")
    val LOW_VOLTS = listOf("9", "9.5", "10")

    /** Front LED modes of `0F 04`. */
    const val LED_AUTO = 1
    const val LED_MANUAL = 2
    const val LED_SEMI_AUTO = 3

    /** States the MCU answers to `F0 xx 00`. */
    val QUERIES =
        listOf(
            Mcu.CMD_ACC,
            Mcu.CMD_HANDBRAKE,
            Mcu.CMD_MUTE,
            Mcu.CMD_HEADLIGHT,
            Mcu.CMD_BACKLIGHT,
            Mcu.CMD_ANTENNA,
            Mcu.CMD_VERSION,
            Mcu.CMD_DATE_TIME,
        )

    fun risk(cmd: Int): Risk =
        when (cmd) {
            CMD_BOOTLOADER -> Risk.BLOCKED
            Mcu.CMD_POWER_OFF, Mcu.CMD_RESET_SOC, Mcu.CMD_SLEEP_TIMER -> Risk.CONFIRM
            else -> Risk.NONE
        }

    // --- Commands ------------------------------------------------------------------------------------------------

    /** Every state of [QUERIES], then the `+0x11` option. */
    fun queryAll(): List<McuFrame> = QUERIES.map(Mcu::query) + McuFrame.of(Mcu.CMD_QUERY, Mcu.CMD_CONFIG, CFG_OPTION)

    /** PWM output 1 or 2, 5..100 %. Config frames are written to the MCU flash 3 s later. */
    fun pwm(
        channel: Int,
        percent: Int,
    ) = McuFrame.of(Mcu.CMD_CONFIG, if (channel == 2) CFG_PWM2 else CFG_PWM1, percent.coerceIn(5, 100))

    /** Front panel LED: red, green, blue 0..99 and [LED_AUTO] / [LED_MANUAL] / [LED_SEMI_AUTO]. */
    fun led(
        red: Int,
        green: Int,
        blue: Int,
        mode: Int,
    ) = McuFrame.of(Mcu.CMD_CONFIG, CFG_LED, 1, red.coerceIn(0, 99), green.coerceIn(0, 99), blue.coerceIn(0, 99), mode)

    fun highVoltage(index: Int) = McuFrame.of(Mcu.CMD_CONFIG, CFG_HIGH_VOLTAGE, index.coerceIn(0, HIGH_VOLTS.size - 1))

    fun lowVoltage(index: Int) = McuFrame.of(Mcu.CMD_CONFIG, CFG_LOW_VOLTAGE, index.coerceIn(0, LOW_VOLTS.size - 1))

    fun canBaud(index: Int) = McuFrame.of(Mcu.CMD_CONFIG, CFG_CAN_BAUD, index.coerceIn(0, CAN_BAUDS.size - 1))

    /** Key learning of the steering wheel (`11`) or the front panel / knob (`21`): start or end. */
    fun learn(
        panel: Boolean,
        start: Boolean,
    ) = McuFrame.of(if (panel) CMD_LEARN_PANEL else CMD_LEARN_WHEEL, if (start) 2 else 3)

    // --- Simulation (frames the MCU would send) ------------------------------------------------------------------

    fun simState(
        cmd: Int,
        on: Boolean,
    ) = McuFrame.of(cmd, if (on) 1 else 0)

    /** Wheel key press / release on channel 5 (KEY1) or 6 (KEY2), with a typical ADC reading. */
    fun simWheelKey(
        channel: Int,
        pressed: Boolean,
    ): McuFrame {
        val adc = if (channel == 6) 0x28 else 0x30
        return if (pressed) {
            McuFrame.of(Mcu.CMD_KEY, channel, 0xAA, adc, adc, adc)
        } else {
            McuFrame.of(Mcu.CMD_KEY, channel, 0xAA, 0xFF, 0xFF, 0xFF)
        }
    }

    const val SIM_VERSION = "JCST_AC8257_8T7-2024.08.09_12:59"

    fun simVersion() = McuFrame(Mcu.CMD_VERSION, SIM_VERSION.toByteArray(Charsets.US_ASCII))

    /** Node ivi-services reads to pick its audio driver (MCU-tools-app docs/ivi_audio.md §2). */
    const val BOARD_ID_NODE = "/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id"

    /** Audio chip of a board id, with ivi-services' rule: 4th character A / B / C, else A1 = AK7604, other = BU32107. */
    fun audioChip(boardId: String?): String? {
        val id = boardId?.trim()
        if (id == null || id.length < 4) return null
        return when (id[3]) {
            'A' -> "ROHM BD37534"
            'B' -> "ROHM BU32107"
            'C' -> "AKM AK7604"
            else -> if (id.startsWith("A1")) "AKM AK7604" else "ROHM BU32107"
        }
    }

    // --- Descriptions --------------------------------------------------------------------------------------------

    fun nameToMcu(cmd: Int): String =
        when (cmd) {
            0x01 -> "POWER_OFF"
            0x08 -> "MUTE"
            0x09 -> "SET_RTC"
            0x0E -> "RESET_SOC"
            0x0F -> "CONFIG"
            0x10 -> "CAN_TX"
            0x11 -> "LEARN_WHEEL"
            0x1F -> "PC_READY"
            0x21 -> "LEARN_PANEL"
            0x31 -> "IR (ignored)"
            0x33 -> "WHEEL_SCALE (ignored)"
            0x43 -> "ANTENNA"
            0x44 -> "REM_AMP"
            0x45 -> "ROTATION (ignored)"
            0x80 -> "BOOTLOADER"
            0xF0 -> "QUERY"
            0xF1 -> "SLEEP_TIME"
            else -> "CMD_%02X".format(cmd)
        }

    fun nameFromMcu(cmd: Int): String =
        when (cmd) {
            0x00 -> "ACC"
            0x04 -> "HANDBRAKE"
            0x08 -> "MUTE"
            0x09 -> "RTC"
            0x0A -> "VERSION"
            0x0B -> "LIGHTS"
            0x0D -> "BACKLIGHT"
            0x0F -> "CONFIG"
            0x10 -> "CAN_RX"
            0x20 -> "KEY"
            0x30 -> "KEY_LEARN"
            0x43 -> "ANTENNA"
            0xC0 -> "ACK"
            else -> "CMD_%02X".format(cmd)
        }

    private fun queryName(what: Int): String? =
        when (what) {
            0x00 -> "ACC"
            0x04 -> "handbrake"
            0x08 -> "mute"
            0x09 -> "date+time"
            0x0A -> "version"
            0x0B -> "lights/ILL"
            0x0D -> "backlight"
            0x0F -> "option +0x11"
            0x43 -> "radio antenna"
            else -> null
        }

    private fun configName(sub: Int): String? =
        when (sub) {
            CFG_CAN_BAUD -> "USART1 baud"
            CFG_PWM1 -> "PWM CH1 %"
            CFG_PWM2 -> "PWM CH2 %"
            CFG_LED -> "front LED"
            0x06 -> "knob"
            CFG_OPTION -> "option +0x11"
            0x08 -> "front panel type"
            CFG_HIGH_VOLTAGE -> "high threshold"
            CFG_LOW_VOLTAGE -> "low threshold"
            else -> null
        }

    /** `NAME  details`, for the traffic log. [fromMcu] = MCU → SoC. */
    fun describe(
        f: McuFrame,
        fromMcu: Boolean,
    ): String {
        val d = f.data
        val n = d.size
        val name = if (fromMcu) nameFromMcu(f.cmd) else nameToMcu(f.cmd)
        val info =
            when {
                !fromMcu && f.cmd == Mcu.CMD_QUERY && n > 0 -> {
                    "? " + (queryName(f.u(0)) ?: "%02X".format(f.u(0)))
                }

                !fromMcu && f.cmd == Mcu.CMD_CONFIG && n > 0 -> {
                    describeConfig(f)
                }

                fromMcu && f.cmd == Mcu.CMD_VERSION && n > 2 -> {
                    "'" + ascii(d) + "'"
                }

                f.cmd == Mcu.CMD_DATE_TIME && n >= 5 && f.u(0) == 0 -> {
                    "date %02d%02d-%02d-%02d".format(f.u(1), f.u(2), f.u(3), f.u(4))
                }

                f.cmd == Mcu.CMD_DATE_TIME && n >= 4 && f.u(0) == 1 -> {
                    "time %02d:%02d:%02d".format(f.u(1), f.u(2), f.u(3))
                }

                fromMcu && (f.cmd == Mcu.CMD_KEY || f.cmd == Mcu.CMD_KEY_LEARN) && n >= 5 -> {
                    keyText(f)
                }

                fromMcu && f.cmd == JacFrame.CMD_ACK && n > 0 -> {
                    "of %02X".format(f.u(0))
                }

                f.cmd == Mcu.CMD_CAN -> {
                    "$n bytes CAN box"
                }

                !fromMcu && f.cmd == Mcu.CMD_SLEEP_TIMER && n > 0 -> {
                    "${f.u(0)} x 420 min"
                }

                !fromMcu && (f.cmd == CMD_LEARN_WHEEL || f.cmd == CMD_LEARN_PANEL) && n > 0 -> {
                    if (f.u(0) == 2) "start" else "end"
                }

                n == 1 -> {
                    f.u(0).toString()
                }

                else -> {
                    d.toHex()
                }
            }
        return if (info.isEmpty()) name else "$name  $info"
    }

    /** Key frame: `channel 5  adc 30 30 30` or `channel 5  released`. */
    fun keyText(f: McuFrame): String {
        if (f.data.size < 5) return f.data.toHex()
        val released = f.u(2) == 0xFF && f.u(3) == 0xFF && f.u(4) == 0xFF
        return if (released) {
            "channel ${f.u(0)}  released"
        } else {
            "channel %d  adc %02X %02X %02X".format(f.u(0), f.u(2), f.u(3), f.u(4))
        }
    }

    private fun describeConfig(f: McuFrame): String {
        val sub = f.u(0)
        val a = if (f.data.size > 1) f.u(1) else -1
        val base = configName(sub) ?: return "sub-command %02X (ignored)".format(sub)
        return when {
            sub == CFG_CAN_BAUD && a in CAN_BAUDS.indices -> "$base = ${CAN_BAUDS[a]}"
            sub == CFG_HIGH_VOLTAGE && a in HIGH_VOLTS.indices -> "$base = ${HIGH_VOLTS[a]} V"
            sub == CFG_LOW_VOLTAGE && a in LOW_VOLTS.indices -> "$base = ${LOW_VOLTS[a]} V"
            sub == CFG_LED && f.data.size >= 6 -> "$base type=${f.u(1)} R=${f.u(2)} G=${f.u(3)} B=${f.u(4)} mode=${f.u(5)}"
            a >= 0 -> "$base = $a"
            else -> base
        }
    }

    fun ascii(d: ByteArray): String =
        d
            .map {
                val v = it.toInt() and 0xFF
                if (v in 32..126) v.toChar() else '.'
            }.joinToString("")
            .trim()
}
