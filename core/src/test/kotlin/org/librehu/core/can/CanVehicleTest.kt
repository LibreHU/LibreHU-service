package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CanVehicleTest {
    private val clio = CanVehicles.RENAULT_CLIO3_HIWORLD

    private fun frame(
        cmd: Int,
        vararg d: Int,
    ) = CanboxFrame(cmd, ByteArray(d.size) { d[it].toByte() }, true)

    @Test
    fun clioStateMessage() {
        // ACC + handbrake, wheel key 2 (volume -) pressed, steering -12.5° (0xFF83).
        val v = clio.decode(frame(0x11, 0x09, 0, 0x22, 1, 0, 0, 0xFF, 0x83)).associateBy { it.key }
        assertEquals(true, v["ACC"]!!.flag)
        assertEquals(false, v["lights"]!!.flag)
        assertEquals(true, v["handbrake"]!!.flag)
        assertEquals("volume -", v["wheel_key"]!!.text)
        assertEquals(-12.5, v["steering_angle"]!!.number!!, 1e-9)
        assertEquals("-12.5 °", v["steering_angle"]!!.display())
    }

    @Test
    fun outsideTemperatureAndRadar() {
        val clim = clio.decode(frame(0x31, *IntArray(11), 100)).single()
        assertEquals(10.0, clim.number!!, 1e-9)
        val radar = clio.decode(frame(0x41, 0, 1, 4, 7, 4, 4, 4, 4))
        // 7 is out of range (4 - 7 < 0): dropped, like CliOS' min_value.
        assertEquals(7, radar.size)
        assertEquals(4.0, radar.first().number!!, 1e-9)
    }

    @Test
    fun unknownCommandAndShortFrames() {
        assertNull(clio.describe(frame(0x99, 1)))
        assertTrue(clio.decode(frame(0x31, 1, 2)).isEmpty())
    }

    @Test
    fun cliosSignalSyntax() {
        // Signals copied from CliOS' Clio 3 dictionary (0x5FD odometer: mask + shift, 0x181 rpm: factor).
        val v =
            CanVehicles.fromJson(
                """
                {"format": "librehu-can-vehicle", "version": 1, "id": "test-car", "name": "Test",
                 "messages": {"0x70": {"name": "Engine", "signals": {
                   "odometer": { "start_byte": 0, "size": 3, "endian": "big", "shift": 4, "mask": "0xFFFFF0" },
                   "rpm": { "start_byte": 3, "size": 2, "endian": "big", "factor": 0.125 },
                   "temp_le": { "start_byte": 5, "size": 2, "endian": "little", "offset": -40 }
                 }}}}
                """.trimIndent(),
            )
        val got = v.decode(frame(0x70, 0x01, 0x23, 0x4F, 0x1F, 0x40, 0x50, 0x00)).associateBy { it.key }
        assertEquals(0x01234.toDouble(), got["odometer"]!!.number!!, 1e-9)
        assertEquals(1000.0, got["rpm"]!!.number!!, 1e-9)
        assertEquals(40.0, got["temp_le"]!!.number!!, 1e-9)
        // Round trip.
        assertEquals(v, CanVehicles.fromJson(CanVehicles.toJson(v)))
        assertEquals(clio, CanVehicles.fromJson(CanVehicles.toJson(clio)))
    }

    @Test
    fun rejectsRawCliosDictionary() {
        try {
            CanVehicles.fromJson("""{"schema_version": 1, "0x181": {"name": "ENGINE_DATA", "signals": {}}}""")
            fail()
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("CliOS"))
        }
    }
}
