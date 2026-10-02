package org.librehu.core.mcu

/** Commands understood by the MCU firmware of the UJC201 (HK32C030). Reference: mcu_firmware.md §6.2–6.4. */
object Mcu {
    const val CMD_ACC = 0x00
    const val CMD_POWER_OFF = 0x01
    const val CMD_HANDBRAKE = 0x04
    const val CMD_MUTE = 0x08
    const val CMD_DATE_TIME = 0x09
    const val CMD_VERSION = 0x0A
    const val CMD_HEADLIGHT = 0x0B
    const val CMD_BACKLIGHT = 0x0D
    const val CMD_RESET_SOC = 0x0E
    const val CMD_CONFIG = 0x0F
    const val CMD_CAN = 0x10
    const val CMD_PC_READY = 0x1F
    const val CMD_KEY = 0x20
    const val CMD_KEY_LEARN = 0x30
    const val CMD_ANTENNA = 0x43
    const val CMD_EXT_AMP = 0x44
    const val CMD_QUERY = 0xF0
    const val CMD_SLEEP_TIMER = 0xF1

    /** Commands ivi-services resends until acknowledged (500 ms, 5 tries). */
    val NEEDS_ACK = setOf(CMD_PC_READY, CMD_MUTE, CMD_POWER_OFF, CMD_SLEEP_TIMER, 0x80)

    /** "SoC ready": the MCU answers with ACC, then version (+200 ms) and date/time (+400 ms). */
    fun pcReady() = McuFrame.of(CMD_PC_READY, 0x01)

    /** Amplifier mute output of the MCU (PA0). */
    fun mute(on: Boolean) = McuFrame.of(CMD_MUTE, if (on) 1 else 0)

    fun date(
        year: Int,
        month: Int,
        day: Int,
    ) = McuFrame.of(CMD_DATE_TIME, 0, year / 100, year % 100, month, day)

    fun time(
        hour: Int,
        minute: Int,
        second: Int,
    ) = McuFrame.of(CMD_DATE_TIME, 1, hour, minute, second)

    /** Ask for a state: [CMD_ACC], [CMD_HANDBRAKE], [CMD_MUTE], [CMD_DATE_TIME], [CMD_VERSION], [CMD_HEADLIGHT]… */
    fun query(what: Int) = McuFrame.of(CMD_QUERY, what, 0)

    /** Remote (REM) output of an external amplifier (PB3). */
    fun externalAmp(on: Boolean) = McuFrame.of(CMD_EXT_AMP, if (on) 1 else 0)

    /** Radio antenna power (PF6). */
    fun antenna(on: Boolean) = McuFrame.of(CMD_ANTENNA, if (on) 1 else 0)

    /** Keep the SoC suspended after ACC off for `n × 420 min` (0 = cut power). ivi-services sends `minutes / 360`. */
    fun sleepTimer(units: Int) = McuFrame.of(CMD_SLEEP_TIMER, units.coerceIn(0, 255))

    /** Reset the SoC (power cycle of its rails). */
    fun resetSoc() = McuFrame.of(CMD_RESET_SOC, 0, 0, 0)

    /** Bytes copied as is to the CAN box (USART1 of the MCU). */
    fun canData(bytes: ByteArray) = McuFrame(CMD_CAN, bytes)
}

/** What a frame received from the MCU means. */
sealed interface McuEvent {
    data class Acc(
        val on: Boolean,
    ) : McuEvent

    data class Handbrake(
        val on: Boolean,
    ) : McuEvent

    data class Headlight(
        val on: Boolean,
    ) : McuEvent

    data class Backlight(
        val on: Boolean,
    ) : McuEvent

    data class Version(
        val text: String,
    ) : McuEvent

    data class Date(
        val year: Int,
        val month: Int,
        val day: Int,
    ) : McuEvent

    data class Time(
        val hour: Int,
        val minute: Int,
        val second: Int,
    ) : McuEvent

    /** Wheel / front panel key: channel 5-6 wheel KEY1/KEY2, 3-4 front panel, 2 knob. Values are ADC readings. */
    class Key(
        val channel: Int,
        val values: IntArray,
        val learning: Boolean,
    ) : McuEvent {
        /** Wheel keys release as `[AA, FF, FF, FF]`, front panel keys as `[FF, ..]`; the knob has no release. */
        val released: Boolean
            get() =
                when (channel) {
                    5, 6 -> values.size >= 4 && values.drop(1).all { it == 0xFF }
                    3, 4 -> values.firstOrNull() == 0xFF
                    else -> false
                }
    }

    /** Bytes received from the CAN box. */
    class Can(
        val bytes: ByteArray,
    ) : McuEvent

    class Ack(
        val cmd: Int,
    ) : McuEvent

    class Other(
        val frame: McuFrame,
    ) : McuEvent

    companion object {
        fun decode(f: McuFrame): McuEvent {
            val n = f.data.size
            return when (f.cmd) {
                JacFrame.CMD_ACK -> {
                    if (n >= 1) Ack(f.u(0)) else Other(f)
                }

                Mcu.CMD_ACC -> {
                    if (n >= 1) Acc(f.u(0) != 0) else Other(f)
                }

                Mcu.CMD_HANDBRAKE -> {
                    if (n >= 1) Handbrake(f.u(0) != 0) else Other(f)
                }

                Mcu.CMD_HEADLIGHT -> {
                    if (n >= 1) Headlight(f.u(0) != 0) else Other(f)
                }

                Mcu.CMD_BACKLIGHT -> {
                    if (n >= 1) Backlight(f.u(0) != 0) else Other(f)
                }

                Mcu.CMD_VERSION -> {
                    Version(String(f.data, Charsets.US_ASCII).trim { it <= ' ' })
                }

                Mcu.CMD_DATE_TIME -> {
                    when {
                        n >= 5 && f.u(0) == 0 -> Date(f.u(1) * 100 + f.u(2), f.u(3), f.u(4))
                        n >= 4 && f.u(0) == 1 -> Time(f.u(1), f.u(2), f.u(3))
                        else -> Other(f)
                    }
                }

                Mcu.CMD_KEY, Mcu.CMD_KEY_LEARN -> {
                    if (n >= 2) {
                        Key(f.u(0), IntArray(n - 1) { f.u(it + 1) }, f.cmd == Mcu.CMD_KEY_LEARN)
                    } else {
                        Other(f)
                    }
                }

                Mcu.CMD_CAN -> {
                    Can(f.data.copyOf())
                }

                else -> {
                    Other(f)
                }
            }
        }
    }
}
