package org.librehu.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaugeSpecTest {
    @Test
    fun rpmDial() {
        val g = GaugeSpec.of("RPM")!!
        assertEquals(0.5, g.fraction(4000.0), 1e-9)
        assertEquals(1.0, g.fraction(9000.0), 1e-9)
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8"), g.majors().map(g::label))
        assertTrue(g.inRed(6500.0))
        assertFalse(g.inRed(5000.0))
    }

    @Test
    fun batteryBothRedZones() {
        val g = GaugeSpec.of("BATTERY")!!
        assertTrue(g.inRed(11.5))
        assertTrue(g.inRed(15.2))
        assertFalse(g.inRed(13.8))
        assertEquals(listOf(10.0, 12.0, 14.0, 16.0), g.majors())
    }

    @Test
    fun unknownValueHasNoDial() {
        assertNull(GaugeSpec.of("RUN_TIME"))
    }
}
