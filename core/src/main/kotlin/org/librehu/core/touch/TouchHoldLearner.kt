package org.librehu.core.touch

/**
 * Learns where a front panel key is: the user keeps a finger on it for [holdMs]. Only a press that starts after
 * [startMs] counts (not the tap on the screen button that opened the prompt), and it must stay within [maxSpread]
 * driver units; the result is the median position. Moving away or lifting the finger starts over.
 */
class TouchHoldLearner(
    private val startMs: Long,
    private val holdMs: Long = 2000,
    private val maxSpread: Int = 40,
) {
    private val xs = ArrayList<Int>()
    private val ys = ArrayList<Int>()
    private var pressAt = -1L

    /** 0..1 while held, 1 when done. */
    var progress = 0f
        private set

    /** Learnt position, once held long enough. */
    var result: Pair<Int, Int>? = null
        private set

    fun feed(s: TouchSample) {
        if (result != null) return
        if (!s.down || s.timeMs < startMs) {
            reset()
            return
        }
        if (xs.isNotEmpty() && (kotlin.math.abs(s.x - median(xs)) > maxSpread || kotlin.math.abs(s.y - median(ys)) > maxSpread)) {
            // Finger slid away from where it was held: this sample starts a new press.
            reset()
        }
        if (pressAt < 0) pressAt = s.timeMs
        xs += s.x
        ys += s.y
        progress = ((s.timeMs - pressAt).toFloat() / holdMs).coerceIn(0f, 1f)
        if (progress >= 1f) result = median(xs) to median(ys)
    }

    private fun reset() {
        xs.clear()
        ys.clear()
        pressAt = -1L
        progress = 0f
    }

    private fun median(v: List<Int>): Int = v.sorted()[v.size / 2]
}
