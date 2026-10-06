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
        // ACC + handbrake, wheel key 2 (volume -) pressed, steering -12.5° (sign + magnitude: 0x807D).
        val v = clio.decode(frame(0x11, 0x09, 0, 0x22, 1, 0, 0, 0x80, 0x7D)).associateBy { it.key }
        assertEquals(true, v["ACC"]!!.flag)
        assertEquals(false, v["lights"]!!.flag)
        assertEquals(true, v["handbrake"]!!.flag)
        assertEquals("volume -", v["wheel_key"]!!.text)
        assertEquals(-12.5, v["steering_angle"]!!.number!!, 1e-9)
        assertEquals("-12.5 °", v["steering_angle"]!!.display())
    }

    @Test
    fun outsideTemperatureAndRadar() {
        val clim = clio.decode(frame(0x31, *IntArray(11), 100)).associateBy { it.key }
        assertEquals(10.0, clim["outside_temp"]!!.number!!, 1e-9)
        val radar = clio.decode(frame(0x41, 0, 1, 4, 7, 4, 4, 4, 4))
        // 7 is out of range (4 - 7 < 0): dropped, like CliOS' min_value.
        assertEquals(7, radar.size)
        assertEquals(4.0, radar.first().number!!, 1e-9)
    }

    @Test
    fun hiworldLnp002Values() {
        // Steering to the right: positive magnitude.
        val right = clio.decode(frame(0x11, 0, 0, 0, 0, 0, 0, 0x01, 0x2C)).associateBy { it.key }
        assertEquals(30.0, right["steering_angle"]!!.number!!, 1e-9)
        // Trip computer: 5.8 L/100 km, 47.3 km/h, 1234.5 km, 2 h 15 min, 71.6 L; 0xFFFF = not available.
        val trip =
            clio
                .decode(frame(0x14, 0, 58, 0x01, 0xD9, 0x00, 0x30, 0x39, 15, 0, 2, 0x02, 0xCC, 0xFF, 0xFF))
                .associateBy { it.key }
        assertEquals(5.8, trip["avg_consumption"]!!.number!!, 1e-9)
        assertEquals(47.3, trip["avg_speed"]!!.number!!, 1e-9)
        assertEquals(1234.5, trip["distance"]!!.number!!, 1e-9)
        assertEquals(15.0, trip["time_minutes"]!!.number!!, 1e-9)
        assertEquals(2.0, trip["time_hours"]!!.number!!, 1e-9)
        assertEquals(71.6, trip["fuel_used"]!!.number!!, 1e-9)
        assertNull(trip["green_distance"])
        // Tyres in 0.1 kPa from D2.
        val tpms = clio.decode(frame(0x48, 0, 0, 0x08, 0xFC, 0x08, 0xFC, 0x08, 0x98, 0x08, 0x98)).associateBy { it.key }
        assertEquals(230.0, tpms["front_left"]!!.number!!, 1e-9)
        assertEquals(220.0, tpms["rear_right"]!!.number!!, 1e-9)
        // Climate: 21.5 °C left, HI right.
        val clim = clio.decode(frame(0x31, 0x41, 0, 0, 0, 0, 3, 43, 255, 0, 0, 0, 100)).associateBy { it.key }
        assertEquals(true, clim["AC"]!!.flag)
        assertEquals(21.5, clim["temp_left"]!!.number!!, 1e-9)
        assertEquals("HI", clim["temp_right"]!!.text)
    }

    @Test
    fun unknownCommandAndShortFrames() {
        assertNull(clio.describe(frame(0x99, 1)))
        assertTrue(clio.decode(frame(0x48, 1, 2)).isEmpty())
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
