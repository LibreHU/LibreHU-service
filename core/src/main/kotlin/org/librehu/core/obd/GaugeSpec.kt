package org.librehu.core.obd

import kotlin.math.abs

/** Dial of a dashboard value: range, red zone (above [redAbove] or below [redBelow]), graduation. */
data class GaugeSpec(
    val min: Double,
    val max: Double,
    /** Distance between labelled graduations. */
    val major: Double,
    /** Small graduations per major one. */
    val minors: Int = 4,
    val redAbove: Double? = null,
    val redBelow: Double? = null,
    /** Labels divided by this (RPM shown ×1000). */
    val labelDivisor: Double = 1.0,
) {
    /** Position of [value] on the dial, 0..1. */
    fun fraction(value: Double): Double = ((value - min) / (max - min)).coerceIn(0.0, 1.0)

    fun inRed(value: Double): Boolean = (redAbove != null && value >= redAbove) || (redBelow != null && value <= redBelow)

    /** Labelled graduations, min to max. */
    fun majors(): List<Double> {
        val out = ArrayList<Double>()
        var v = min
        while (v <= max + major * 1e-6) {
            out += v
            v += major
        }
        return out
    }

    fun label(value: Double): String {
        val v = value / labelDivisor
        return if (abs(v - Math.round(v)) < 1e-6) Math.round(v).toString() else "%.1f".format(v)
    }

    companion object {
        /** Ranges of the values a car dashboard shows; null for the others (number only). */
        fun of(key: String): GaugeSpec? =
            when (key) {
                ObdPid.SPEED.name -> GaugeSpec(0.0, 200.0, 20.0, minors = 1)
                ObdPid.RPM.name -> GaugeSpec(0.0, 8000.0, 1000.0, minors = 1, redAbove = 6000.0, labelDivisor = 1000.0)
                ObdPid.COOLANT_TEMP.name -> GaugeSpec(40.0, 130.0, 30.0, redAbove = 110.0)
                ObdPid.OIL_TEMP.name -> GaugeSpec(40.0, 150.0, 30.0, redAbove = 130.0)
                ObdPid.INTAKE_TEMP.name, ObdPid.AMBIENT_TEMP.name -> GaugeSpec(-20.0, 60.0, 20.0)
                ObdPid.FUEL_LEVEL.name -> GaugeSpec(0.0, 100.0, 25.0, redBelow = 10.0)
                ObdPid.ENGINE_LOAD.name, ObdPid.THROTTLE.name -> GaugeSpec(0.0, 100.0, 25.0)
                ObdPid.MODULE_VOLTAGE.name, "BATTERY" -> GaugeSpec(10.0, 16.0, 2.0, redBelow = 11.8, redAbove = 15.0)
                ObdPid.INTAKE_PRESSURE.name, ObdPid.BAROMETRIC.name -> GaugeSpec(0.0, 250.0, 50.0)
                ObdPid.MAF.name -> GaugeSpec(0.0, 200.0, 50.0)
                ObdPid.FUEL_RATE.name -> GaugeSpec(0.0, 30.0, 10.0)
                ObdPid.TIMING_ADVANCE.name -> GaugeSpec(-20.0, 60.0, 20.0)
                else -> null
            }
    }
}
