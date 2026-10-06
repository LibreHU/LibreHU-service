package org.librehu.core.unit

/**
 * Turn signal input (SoC GPIO 7 / 6, active low), filtered like ivi-services' `TurnLightDetect`: a level must be
 * read twice in a row (100 ms polling), and "off" is reported only 3 s after the lamp went dark, so that a blinking
 * signal reads as steadily on.
 *
 * ivi-services only reads these inputs when the 360° camera is enabled; on most installs they are not wired and a
 * floating pin can read 0 ("on") for good. A real indicator blinks: low for longer than [stuckMs] without a single
 * edge means "not wired" → off (and [stuck] true) until the pin moves again.
 */
class TurnSignalFilter(
    private val offDelayMs: Long = 3_000,
    private val stuckMs: Long = 15_000,
) {
    /** Filtered state. */
    var on = false
        private set

    /** The input stays low without blinking: treated as not wired. */
    var stuck = false
        private set

    private var candidate: Boolean? = null
    private var stable = false
    private var lastLitAt = 0L
    private var lowSince = -1L

    /** [lit]: raw level (true = lamp on), null when the read failed. Returns the filtered state. */
    fun update(
        lit: Boolean?,
        nowMs: Long,
    ): Boolean {
        if (lit == null) return on
        // Two equal reads in a row.
        if (lit != stable) {
            if (candidate == lit) {
                stable = lit
                candidate = null
            } else {
                candidate = lit
            }
        } else {
            candidate = null
        }
        if (stable) {
            if (lowSince < 0) lowSince = nowMs
            lastLitAt = nowMs
        } else {
            lowSince = -1
            stuck = false
        }
        if (stable && nowMs - lowSince > stuckMs) stuck = true
        on =
            when {
                stuck -> false
                stable -> true
                else -> lastLitAt > 0 && nowMs - lastLitAt < offDelayMs
            }
        return on
    }
}
