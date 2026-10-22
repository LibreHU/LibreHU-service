package org.librehu.core.can

/**
 * Short or long press of a steering wheel key that has a long action, from its press / release frames and the time.
 *
 * A press starts at the first press frame and ends when no press frame came for [gapMs] after a release: a CAN box
 * may send a held key as one long press or as a train of short ones (~100 ms every ~380 ms on a Hiworld box), and a
 * tap as a few short ones. Still going [longMs] after it started → [Result.LONG] at once, else [Result.SHORT] when it
 * ends. The caller feeds [down], [up] and [tick] (at [deadline]), and runs what they return.
 */
class WheelHold(
    private val longMs: Long,
    private val gapMs: Long,
) {
    enum class Result { SHORT, LONG }

    data class Press(
        val code: Int,
        val result: Result,
        /** Frames of the press: "↓0 ↑100 ↓380 …", ms from the first. */
        val frames: String,
    )

    private var code = 0
    private var start = 0L
    private var releasedAt = -1L
    private var longDone = false
    private val log = StringBuilder()

    val active: Boolean get() = code != 0

    /** When [tick] has something to decide (long press or end of the press), or null. */
    val deadline: Long?
        get() =
            when {
                code == 0 -> null
                !longDone && releasedAt < 0 -> start + longMs
                !longDone -> minOf(start + longMs, releasedAt + gapMs)
                releasedAt >= 0 -> releasedAt + gapMs
                else -> null
            }

    /** A press frame of [key]; returns the end of another key's press it interrupts (its short press), if any. */
    fun down(
        key: Int,
        now: Long,
    ): Press? {
        if (key == code) {
            releasedAt = -1
            log.append(" ↓").append(now - start)
            return null
        }
        val ended = if (code != 0) finish() else null
        code = key
        start = now
        releasedAt = -1
        longDone = false
        log.setLength(0)
        log.append("↓0")
        return ended
    }

    fun up(
        key: Int,
        now: Long,
    ) {
        if (key != code) return
        releasedAt = now
        log.append(" ↑").append(now - start)
    }

    /** Time passing: the long press once held long enough, or the short press once let go. */
    fun tick(now: Long): Press? {
        if (code == 0) return null
        if (releasedAt >= 0 && now - releasedAt >= gapMs) return finish()
        if (!longDone && now - start >= longMs) {
            longDone = true
            log.append(" ⇒ long")
            return Press(code, Result.LONG, log.toString())
        }
        return null
    }

    /** Another key interrupts: the press ends now (its short press, unless its long press ran). */
    fun end(): Press? = if (code != 0) finish() else null

    /** The press ends: a short press unless its long press already ran (then null). */
    private fun finish(): Press? {
        val p = if (longDone) null else Press(code, Result.SHORT, log.append(" ⇒ short").toString())
        code = 0
        releasedAt = -1
        return p
    }
}
