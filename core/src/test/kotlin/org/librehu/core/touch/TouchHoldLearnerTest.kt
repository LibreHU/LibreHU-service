package org.librehu.core.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchHoldLearnerTest {
    private fun s(
        t: Long,
        x: Int,
        y: Int,
        down: Boolean = true,
    ) = TouchSample(down, x, y, t)

    @Test
    fun ignoresTheTapOnTheScreenAndLearnsTheHeldKey() {
        val l = TouchHoldLearner(startMs = 1000, holdMs = 2000)
        // The tap on the "learn" button, before the prompt: ignored.
        l.feed(s(900, 300, 200))
        l.feed(s(950, 300, 200, down = false))
        // Held key outside the screen, with a little jitter.
        for (t in 1500L..3400L step 100) l.feed(s(t, 1030 + (t / 100 % 3).toInt(), 610))
        assertNull(l.result)
        l.feed(s(3500, 1031, 611))
        assertEquals(1031 to 610, l.result)
    }

    @Test
    fun liftingOrSlidingStartsOver() {
        val l = TouchHoldLearner(startMs = 0, holdMs = 1000)
        l.feed(s(0, 100, 100))
        l.feed(s(900, 100, 100, down = false))
        l.feed(s(1000, 100, 100))
        l.feed(s(1500, 400, 100)) // slid to another key
        l.feed(s(2400, 400, 100))
        assertNull(l.result)
        l.feed(s(2500, 401, 100))
        assertEquals(400 to 100, l.result)
    }
}
