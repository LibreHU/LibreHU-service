package org.librehu.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test {
    @Test
    fun rpmAndSpeed() {
        // 0x1AF8 / 4 = 1726 rpm
        assertEquals(1726.0, Elm327.decode("41 0C 1A F8\r\r>", ObdPid.RPM)!!, 0.01)
        assertEquals(1726.0, Elm327.decode("410C1AF8\r>", ObdPid.RPM)!!, 0.01)
        assertEquals(87.0, Elm327.decode("SEARCHING...\r410D57\r\r>", ObdPid.SPEED)!!, 0.01)
    }

    @Test
    fun temperaturesAndVoltage() {
        assertEquals(90.0, Elm327.decode("41 05 82", ObdPid.COOLANT_TEMP)!!, 0.01)
        assertEquals(13.9, Elm327.decode("41 42 36 4C", ObdPid.MODULE_VOLTAGE)!!, 0.01)
        assertEquals(50.2, Elm327.decode("41 2F 80", ObdPid.FUEL_LEVEL)!!, 0.1)
    }

    @Test
    fun firstEcuWinsAndEchoIgnored() {
        val raw = "010C\r41 0C 0F A0\r41 0C 0F 00\r>"
        assertEquals(1000.0, Elm327.decode(raw, ObdPid.RPM)!!, 0.01)
    }

    @Test
    fun errors() {
        assertTrue(Elm327.isError("NO DATA\r\r>"))
        assertTrue(Elm327.isError("UNABLE TO CONNECT\r>"))
        assertTrue(Elm327.isError("?\r>"))
        assertTrue(Elm327.isError(">"))
        assertFalse(Elm327.isError("41 0C 1A F8\r>"))
        assertNull(Elm327.decode("NO DATA\r>", ObdPid.RPM))
        // Answer too short for a 2-byte PID.
        assertNull(Elm327.decode("41 0C 1A", ObdPid.RPM))
    }

    @Test
    fun supportedPids() {
        // BE 1F A8 13: classic example answer to 0100.
        val s = Elm327.supported(0x00, "41 00 BE 1F A8 13\r>")
        assertTrue(0x01 in s)
        assertFalse(0x02 in s)
        assertTrue(0x05 in s)
        assertTrue(0x0C in s)
        assertTrue(0x0D in s)
        assertTrue(0x20 in s)
        assertEquals(emptySet<Int>(), Elm327.supported(0x20, "NO DATA\r>"))
    }

    @Test
    fun voltageFromAtrv() {
        assertEquals(12.6, Elm327.voltage("ATRV\r12.6V\r\r>")!!, 0.001)
        assertEquals(14.1, Elm327.voltage("14,1V\r>")!!, 0.001)
        assertNull(Elm327.voltage("?\r>"))
    }

    @Test
    fun dtcLegacyAndCan() {
        assertEquals(listOf(Dtc("P0133")), Elm327.dtcs("43 01 33 00 00 00 00\r>"))
        assertEquals(listOf(Dtc("P0133"), Dtc("P0244")), Elm327.dtcs("43 02 01 33 02 44\r>"))
        assertEquals(Dtc("C1234"), Elm327.dtc(0x52, 0x34))
        assertEquals(Dtc("U0100"), Elm327.dtc(0xC1, 0x00))
        assertEquals(emptyList<Dtc>(), Elm327.dtcs("43 00\r>"))
        assertEquals(emptyList<Dtc>(), Elm327.dtcs("NO DATA\r>"))
    }

    @Test
    fun formatting() {
        assertEquals("1726 rpm", Elm327.format(ObdPid.RPM, 1726.4))
        assertEquals("13.9 V", Elm327.format(ObdPid.MODULE_VOLTAGE, 13.94).replace(',', '.'))
        assertEquals("010C", ObdPid.RPM.command)
    }
}
