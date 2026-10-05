package org.librehu.core.obd

import kotlin.math.roundToInt

/**
 * OBD-II values read in mode 01 (SAE J1979). [decode] turns the data bytes A, B… of the answer into the value.
 * Formulas: SAE J1979 / ISO 15031-5 (summarised at https://en.wikipedia.org/wiki/OBD-II_PIDs).
 */
enum class ObdPid(
    val pid: Int,
    val unit: String,
    val bytes: Int,
    /** Polled at every round (values that move fast), the others every few rounds. */
    val fast: Boolean,
    val decode: (IntArray) -> Double,
) {
    ENGINE_LOAD(0x04, "%", 1, false, { it[0] * 100.0 / 255 }),
    COOLANT_TEMP(0x05, "°C", 1, false, { it[0] - 40.0 }),
    INTAKE_PRESSURE(0x0B, "kPa", 1, true, { it[0].toDouble() }),
    RPM(0x0C, "rpm", 2, true, { (it[0] * 256 + it[1]) / 4.0 }),
    SPEED(0x0D, "km/h", 1, true, { it[0].toDouble() }),
    TIMING_ADVANCE(0x0E, "°", 1, false, { it[0] / 2.0 - 64 }),
    INTAKE_TEMP(0x0F, "°C", 1, false, { it[0] - 40.0 }),
    MAF(0x10, "g/s", 2, true, { (it[0] * 256 + it[1]) / 100.0 }),
    THROTTLE(0x11, "%", 1, true, { it[0] * 100.0 / 255 }),
    RUN_TIME(0x1F, "s", 2, false, { (it[0] * 256 + it[1]).toDouble() }),
    DISTANCE_MIL(0x21, "km", 2, false, { (it[0] * 256 + it[1]).toDouble() }),
    FUEL_LEVEL(0x2F, "%", 1, false, { it[0] * 100.0 / 255 }),
    DISTANCE_SINCE_CLEAR(0x31, "km", 2, false, { (it[0] * 256 + it[1]).toDouble() }),
    BAROMETRIC(0x33, "kPa", 1, false, { it[0].toDouble() }),
    MODULE_VOLTAGE(0x42, "V", 2, false, { (it[0] * 256 + it[1]) / 1000.0 }),
    AMBIENT_TEMP(0x46, "°C", 1, false, { it[0] - 40.0 }),
    OIL_TEMP(0x5C, "°C", 1, false, { it[0] - 40.0 }),
    FUEL_RATE(0x5E, "L/h", 2, true, { (it[0] * 256 + it[1]) / 20.0 }),
    ;

    /** Request sent to the adapter, e.g. `010C`. */
    val command: String get() = "01%02X".format(pid)

    companion object {
        fun of(pid: Int): ObdPid? = entries.firstOrNull { it.pid == pid }

        fun byName(name: String): ObdPid? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** A stored or pending trouble code, e.g. `P0133`. */
@JvmInline
value class Dtc(
    val code: String,
)

/**
 * ELM327 adapter protocol (AT commands, text answers ending with the `>` prompt). Reference: ELM327 data sheet
 * (Elm Electronics, ELM327DS). Works the same over USB serial and Bluetooth SPP.
 */
object Elm327 {
    /** Reset, no echo, no line feeds, no spaces, no headers, adaptive timing, automatic protocol. */
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")

    const val PROMPT = '>'
    const val READ_VOLTAGE = "ATRV"
    const val DESCRIBE_PROTOCOL = "ATDPN"
    const val READ_DTC = "03"
    const val CLEAR_DTC = "04"

    /** PIDs 01-20, 21-40, 41-60: each answer is a bitmap of the 32 following PIDs. */
    val SUPPORT_QUERIES = listOf(0x00, 0x20, 0x40)

    private val ERRORS =
        listOf(
            "NO DATA",
            "ERROR",
            "UNABLE TO CONNECT",
            "STOPPED",
            "?",
            "BUS BUSY",
            "BUS INIT",
            "CAN ERROR",
            "DATA ERROR",
            "BUFFER FULL",
            "FB ERROR",
            "LV RESET",
            "ACT ALERT",
        )

    /** Answer lines without the prompt, the echo, `SEARCHING...` and blank lines. */
    fun lines(
        raw: String,
        command: String? = null,
    ): List<String> =
        raw
            .replace(PROMPT, '\n')
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && (command == null || !it.equals(command, ignoreCase = true)) }

    fun isError(raw: String): Boolean {
        val l = lines(raw)
        return l.isEmpty() || l.all { line -> ERRORS.any { line.uppercase().startsWith(it) } }
    }

    /** Hex bytes of one answer line (spaces allowed), or null when it is not hex. */
    fun hexBytes(line: String): IntArray? {
        val s = line.replace(" ", "").let { if (it.length > 2 && it[1] == ':') it.substring(2) else it }
        if (s.isEmpty() || s.length % 2 != 0 || !s.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Data bytes A, B… of a mode 01 answer for [pid] (`41 0C 1A F8` → `[1A, F8]`); first ECU answering wins. */
    fun mode01Data(
        raw: String,
        pid: Int,
    ): IntArray? {
        for (line in lines(raw)) {
            val b = hexBytes(line) ?: continue
            for (i in 0 until b.size - 1) {
                if (b[i] == 0x41 && b[i + 1] == pid) return b.copyOfRange(i + 2, b.size)
            }
        }
        return null
    }

    fun decode(
        raw: String,
        pid: ObdPid,
    ): Double? {
        val data = mode01Data(raw, pid.pid) ?: return null
        if (data.size < pid.bytes) return null
        return pid.decode(data)
    }

    /** PIDs announced by the answers to `0100`, `0120`, `0140` (bit 7 of A = first PID after the base). */
    fun supported(
        base: Int,
        raw: String,
    ): Set<Int> {
        val d = mode01Data(raw, base) ?: return emptySet()
        if (d.size < 4) return emptySet()
        val out = mutableSetOf<Int>()
        for (byte in 0 until 4) {
            for (bit in 0 until 8) {
                if (d[byte] and (0x80 shr bit) != 0) out += base + byte * 8 + bit + 1
            }
        }
        return out
    }

    /** `ATRV` answer (`12.6V`) → volts. Measured by the adapter on pin 16: works even with the engine off. */
    fun voltage(raw: String): Double? =
        lines(raw, READ_VOLTAGE)
            .firstNotNullOfOrNull { Regex("""(\d+(?:[.,]\d+)?)\s*V""", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues
            ?.get(1)
            ?.replace(',', '.')
            ?.toDoubleOrNull()

    /**
     * Mode 03 answer → trouble codes. CAN (ISO 15765) answers start with the number of codes (`43 02 01 33 02 44`),
     * older protocols send 3 codes per line padded with `0000` (`43 01 33 00 00 00 00`).
     */
    fun dtcs(raw: String): List<Dtc> {
        val out = mutableListOf<Dtc>()
        for (line in lines(raw, READ_DTC)) {
            val b = hexBytes(line) ?: continue
            val start = b.indexOf(0x43)
            if (start < 0) continue
            var data = b.copyOfRange(start + 1, b.size)
            if (data.size % 2 == 1) data = data.copyOfRange(1, data.size) // CAN: count byte first
            for (i in 0 until data.size - 1 step 2) {
                val hi = data[i]
                val lo = data[i + 1]
                if (hi == 0 && lo == 0) continue
                out += dtc(hi, lo)
            }
        }
        return out.distinct()
    }

    /** Two bytes → `P0133`: bits 7-6 system (P, C, B, U), then four hex digits. */
    fun dtc(
        hi: Int,
        lo: Int,
    ): Dtc {
        val system = "PCBU"[(hi shr 6) and 3]
        val first = (hi shr 4) and 3
        return Dtc("%c%d%X%02X".format(system, first, hi and 0x0F, lo))
    }

    /** Value shown to the driver, rounded to what the unit needs. */
    fun format(
        pid: ObdPid,
        value: Double,
    ): String =
        when (pid) {
            ObdPid.MODULE_VOLTAGE, ObdPid.FUEL_RATE, ObdPid.MAF -> "%.1f %s".format(value, pid.unit)
            else -> "${value.roundToInt()} ${pid.unit}"
        }
}
