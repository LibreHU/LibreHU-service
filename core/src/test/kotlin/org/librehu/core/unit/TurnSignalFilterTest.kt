package org.librehu.core.unit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSignalFilterTest {
    private fun run(
        f: TurnSignalFilter,
        from: Long,
        to: Long,
        level: (Long) -> Boolean,
    ): List<Boolean> = (from until to step 100).map { f.update(level(it), it) }

    @Test
    fun blinkingReadsAsSteadilyOn() {
        val f = TurnSignalFilter()
        // 1.5 Hz indicator: 330 ms lit, 330 ms dark.
        val states = run(f, 0, 6_000) { (it / 330) % 2 == 0L }
        assertTrue(states.drop(3).all { it })
        // Stops: off 3 s after the last flash.
        val after = run(f, 6_000, 10_000) { false }
        assertTrue(after.first())
        assertFalse(after.last())
    }

    @Test
    fun singleGlitchIgnored() {
        val f = TurnSignalFilter()
        run(f, 0, 1_000) { false }
        assertFalse(f.update(true, 1_000))
        assertFalse(f.update(false, 1_100))
        assertFalse(f.on)
    }

    @Test
    fun floatingLowInputIsStuck() {
        val f = TurnSignalFilter()
        val states = run(f, 0, 20_000) { true }
        assertTrue(states[10])
        assertFalse(states.last())
        assertTrue(f.stuck)
        // The pin moves again: back to normal.
        run(f, 20_000, 20_500) { false }
        assertFalse(f.stuck)
    }
}
