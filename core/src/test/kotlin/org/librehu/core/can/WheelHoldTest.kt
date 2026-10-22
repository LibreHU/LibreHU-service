package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WheelHoldTest {
    /** Feeds frames ('d' press, 'u' release at a time) and ticks every 10 ms up to [until]; the presses made. */
    private fun run(
        frames: List<Pair<Long, Char>>,
        until: Long = 3000,
        key: Int = 5,
    ): List<WheelHold.Result> {
        val h = WheelHold(longMs = 900, gapMs = 450)
        val out = mutableListOf<WheelHold.Result>()
        val byTime = frames.groupBy({ it.first }, { it.second })
        var t = 0L
        while (t <= until) {
            byTime[t]?.forEach { c -> if (c == 'd') h.down(key, t)?.let { out += it.result } else h.up(key, t) }
            h.tick(t)?.let { out += it.result }
            t += 10
        }
        return out
    }

    @Test
    fun tapIsShort() = assertEquals(listOf(WheelHold.Result.SHORT), run(listOf(0L to 'd', 100L to 'u')))

    @Test
    fun tapSentAsSeveralPiecesIsShort() =
        assertEquals(
            listOf(WheelHold.Result.SHORT),
            run(listOf(0L to 'd', 100L to 'u', 150L to 'd', 250L to 'u', 300L to 'd', 350L to 'u')),
        )

    @Test
    fun heldAsATrainOfPressesIsLong() {
        val train = (0L..2000L step 380).flatMap { listOf(it to 'd', it + 100 to 'u') }
        assertEquals(listOf(WheelHold.Result.LONG), run(train))
    }

    @Test
    fun heldAsOnePressIsLong() = assertEquals(listOf(WheelHold.Result.LONG), run(listOf(0L to 'd', 1500L to 'u')))

    @Test
    fun longPressFiresWhileStillHeld() {
        val h = WheelHold(longMs = 900, gapMs = 450)
        h.down(5, 0)
        assertNull(h.tick(899))
        assertEquals(WheelHold.Result.LONG, h.tick(900)?.result)
        h.up(5, 2000)
        assertNull(h.tick(2500))
        assertEquals(false, h.active)
    }

    @Test
    fun anotherKeyEndsThePress() {
        val h = WheelHold(longMs = 900, gapMs = 450)
        h.down(5, 0)
        h.up(5, 100)
        assertEquals(WheelHold.Result.SHORT, h.down(6, 200)?.result)
    }
}
