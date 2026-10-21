package org.librehu.core.obd

import kotlin.math.roundToInt

enum class BatteryStatus { ENGINE_OFF, CHARGING, NOT_CHARGING, OVERVOLTAGE }

enum class BatteryAlarm { LOW, NOT_CHARGING, OVERVOLTAGE }

/** Car battery from the adapter's voltage: [percent] only engine off (the voltage then follows the charge). */
data class BatteryReading(
    val voltage: Double,
    val percent: Int?,
    val status: BatteryStatus,
)

/** Alarm thresholds (volts) and the alarms wanted. */
data class BatteryLimits(
    val low: Double = 12.0,
    val chargeMin: Double = 13.0,
    val overMax: Double = 15.0,
    val alarmLow: Boolean = true,
    val alarmCharge: Boolean = true,
    val alarmOver: Boolean = true,
)

/**
 * 12 V lead-acid car battery read from the OBD socket's voltage (ELM327 `ATRV`). Engine off, the voltage gives the
 * charge (open-circuit table of the Battery Council International: 12.6 V = 100 %, 12.4 = 75, 12.2 = 50, 12.0 = 25,
 * 11.7 = 0, interpolated; with the head unit on the battery is under a small load, so it reads a little low, and just
 * after driving a little high: surface charge). Engine running (RPM above [ENGINE_RPM], or without RPM a voltage
 * above the charge threshold), the alternator holds about 13.5–14.7 V: below [BatteryLimits.chargeMin] it does not
 * charge, above [BatteryLimits.overMax] it overcharges.
 */
object CarBattery {
    private val OCV = listOf(11.7 to 0, 12.0 to 25, 12.2 to 50, 12.4 to 75, 12.6 to 100)
    const val ENGINE_RPM = 300.0

    fun percent(volts: Double): Int {
        if (volts <= OCV.first().first) return 0
        if (volts >= OCV.last().first) return 100
        val i = OCV.indexOfFirst { it.first >= volts }
        val (v0, p0) = OCV[i - 1]
        val (v1, p1) = OCV[i]
        return (p0 + (volts - v0) / (v1 - v0) * (p1 - p0)).roundToInt()
    }

    fun read(
        volts: Double,
        rpm: Double?,
        limits: BatteryLimits = BatteryLimits(),
    ): BatteryReading {
        val engine = if (rpm != null) rpm > ENGINE_RPM else volts >= limits.chargeMin
        val status =
            when {
                !engine -> BatteryStatus.ENGINE_OFF
                volts > limits.overMax -> BatteryStatus.OVERVOLTAGE
                volts >= limits.chargeMin -> BatteryStatus.CHARGING
                else -> BatteryStatus.NOT_CHARGING
            }
        return BatteryReading(volts, if (engine) null else percent(volts), status)
    }
}

/**
 * Raises each alarm once, after its condition lasted (low battery 30 s, no charge 60 s, overvoltage 10 s: a start or
 * a load step does not count), and again only after it cleared (0.2 V above the low threshold for the low battery).
 */
class BatteryAlarms {
    private val since = HashMap<BatteryAlarm, Long>()
    private val raised = HashSet<BatteryAlarm>()

    /** Alarms that start now. */
    fun update(
        r: BatteryReading,
        limits: BatteryLimits,
        now: Long,
    ): List<BatteryAlarm> {
        val active =
            mapOf(
                BatteryAlarm.LOW to (limits.alarmLow && r.status == BatteryStatus.ENGINE_OFF && r.voltage < limits.low),
                BatteryAlarm.NOT_CHARGING to (limits.alarmCharge && r.status == BatteryStatus.NOT_CHARGING),
                BatteryAlarm.OVERVOLTAGE to (limits.alarmOver && r.status == BatteryStatus.OVERVOLTAGE),
            )
        val cleared =
            mapOf(
                BatteryAlarm.LOW to (r.status != BatteryStatus.ENGINE_OFF || r.voltage >= limits.low + 0.2),
                BatteryAlarm.NOT_CHARGING to (r.status != BatteryStatus.NOT_CHARGING),
                BatteryAlarm.OVERVOLTAGE to (r.status != BatteryStatus.OVERVOLTAGE),
            )
        val out = ArrayList<BatteryAlarm>()
        for (a in BatteryAlarm.entries) {
            if (active.getValue(a)) {
                val start = since.getOrPut(a) { now }
                if (a !in raised && now - start >= delay(a)) {
                    raised += a
                    out += a
                }
            } else {
                since.remove(a)
                if (cleared.getValue(a)) raised -= a
            }
        }
        return out
    }

    fun reset() {
        since.clear()
        raised.clear()
    }

    private fun delay(a: BatteryAlarm): Long =
        when (a) {
            BatteryAlarm.LOW -> 30_000
            BatteryAlarm.NOT_CHARGING -> 60_000
            BatteryAlarm.OVERVOLTAGE -> 10_000
        }
}
