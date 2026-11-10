package org.librehu.core.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {
    @Test
    fun aThreadSilentForMoreThanAMinuteIsStuck() {
        val beats = mapOf("headunit" to 0L, "mcu-rx" to 50_000L, "mcu-tx" to 64_000L)
        assertEquals(emptyMap<String, Long>(), Watchdog.stalls(beats, 60_000))
        assertEquals(mapOf("headunit" to 60_001L), Watchdog.stalls(beats, 60_001))
        assertEquals(mapOf("headunit" to 120_000L, "mcu-rx" to 70_000L), Watchdog.stalls(beats, 120_000))
    }

    @Test
    fun threeRestartsInTenMinutesOfTheSameBootThenReportOnly() {
        val boot = "b1"
        var saved: String? = null
        for (at in listOf(1_000L, 100_000L, 200_000L)) {
            assertTrue(Watchdog.mayRestart(saved, boot, at))
            saved = Watchdog.withRestart(saved, boot, at)
        }
        assertFalse(Watchdog.mayRestart(saved, boot, 300_000))
        // Ten minutes after the first one, it no longer counts.
        assertTrue(Watchdog.mayRestart(saved, boot, 601_000))
        assertEquals(listOf(100_000L, 200_000L, 601_000L), Watchdog.recentRestarts(Watchdog.withRestart(saved, boot, 601_000), boot, 601_000))
    }

    @Test
    fun restartsOfAnotherBootOrUnreadableDoNotCount() {
        val saved = "b0 1000\nb0 2000\nb0 3000\ngarbage\nb1 x\nb1 5000\nb1 999999"
        assertEquals(listOf(5_000L), Watchdog.recentRestarts(saved, "b1", 10_000))
        assertTrue(Watchdog.mayRestart(saved, "b1", 10_000))
        assertEquals("b1 5000\nb1 10000", Watchdog.withRestart(saved, "b1", 10_000))
    }
}
