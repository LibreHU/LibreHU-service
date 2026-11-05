package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Formulas of the car profiles ([CanExpr]), `extends` and the cars' patches (format 2). */
class CanExprTest {
    private fun eval(
        f: String,
        vararg data: Int,
        roles: Map<String, Double> = emptyMap(),
    ): Double? = CanExpr.parse(f).eval(CanExpr.Context(ByteArray(data.size) { data[it].toByte() }, roles = { roles[it] }))

    private fun frame(
        cmd: Int,
        vararg d: Int,
    ) = CanboxFrame(cmd, ByteArray(d.size) { d[it].toByte() }, true)

    @Test
    fun operatorsInCOrder() {
        assertEquals(7.0, eval("1 + 2 * 3"))
        assertEquals(9.0, eval("(1 + 2) * 3"))
        assertEquals(2.0, eval("10 % 4"))
        assertEquals(17.0, eval("0x10 | 1"))
        assertEquals(16.0, eval("1 << 4"))
        assertEquals(255.0, eval("~0 & 0xFF"))
        assertEquals(1.0, eval("3 > 2 && 2 >= 2 && 1 != 2 && !(1 == 2)"))
        assertEquals(1.0, eval("0 || 5"))
        assertEquals(-4.0, eval("-2 * 2"))
        assertEquals(1.5, eval("3 / 2"))
        assertEquals(12.0, eval("1 ? 2 ? 12 : 13 : 14"))
        // "<" does not swallow "<<" nor "<=", "&" not "&&".
        assertEquals(1.0, eval("1 << 2 <= 4 & 1 && 1"))
    }

    @Test
    fun bytesAndFunctions() {
        // VWP003 steering (HdMQB6Protocol.parseWheelInfo): D4 == 0 ? -D5 : D4.
        assertEquals(-70.0, eval("D4 == 0 ? -D5 : D4", 0, 0, 0, 0, 0, 70))
        assertEquals(35.0, eval("D4 == 0 ? -D5 : D4", 0, 0, 0, 0, 35, 0))
        assertEquals(6.0, eval("len", 1, 2, 3, 4, 5, 6))
        assertEquals(0.0, eval("D9", 1))
        assertEquals(0x0201.toDouble(), eval("u16le(0)", 1, 2))
        assertEquals(-2.0, eval("s16be(0)", 0xFF, 0xFE))
        assertEquals(-1.0, eval("s8(0)", 0xFF))
        assertEquals(0x030201.toDouble(), eval("u24le(0)", 1, 2, 3))
        assertEquals(5.0, eval("bits(D0, 4, 3)", 0b0101_0000))
        assertEquals(1.0, eval("bit(D0, 7)", 0x80))
        assertEquals(-1.0, eval("signed(0xFF, 8)"))
        assertEquals(59.0, eval("bcd(0x59)"))
        assertEquals(3.0, eval("clamp(7, 0, 3)"))
        assertEquals(2.0, eval("min(4, 2, 9)"))
        assertEquals(4.0, eval("round(3.6)"))
    }

    @Test
    fun rolesFromOtherMessages() {
        assertEquals(1.0, eval("has(\"vehicle.speed\")", roles = mapOf("vehicle.speed" to 50.0)))
        assertEquals(0.0, eval("has(\"vehicle.speed\")"))
        assertEquals(100.0, eval("role(\"vehicle.speed\") * 2", roles = mapOf("vehicle.speed" to 50.0)))
        // No value for the role: no result.
        assertNull(eval("role(\"vehicle.speed\") * 2"))
    }

    @Test
    fun failuresGiveNoValue() {
        assertNull(eval("1 / 0"))
        assertNull(eval("nothing + 1"))
        for (bad in listOf("1 +", "foo(1)", "D99", "(1", "1 ? 2", "min()", "\"text", "1 2")) {
            try {
                CanExpr.parse(bad)
                fail("accepted: $bad")
            } catch (e: CanVehicleException) {
                assertTrue(e.message!!, e.message!!.contains("formula"))
            }
        }
        try {
            CanExpr.parse("1+".repeat(400) + "1")
            fail("too long accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("longer"))
        }
    }

    @Test
    fun formulasInAProfile() {
        val p =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":2,"id":"expr-car","messages":{
                   "0x72":{"name":"State","signals":{
                     "steering":{"expr":"D4 == 0 ? -D5 : D4","factor":3.857143,"unit":"°","role":"steering.angle"},
                     "fast":{"expr":"D1 > 100","bits":{"over 100":0}},
                     "half":{"expr":"steering / 2","when":"len >= 6"},
                     "kmh":{"start_byte":1,"when":"role(\"x.unit\") != 1"}}}}}""",
            )
        val v = p.decode(frame(0x72, 0, 120, 0, 0, 0, 70)).associateBy { it.key }
        assertEquals(-270.0, v["steering"]!!.number!!, 0.01)
        assertEquals(true, v["over 100"]!!.flag)
        // A later signal reads an earlier one (its value, after factor).
        assertEquals(-135.0, v["half"]!!.number!!, 0.01)
        assertEquals(120.0, v["kmh"]!!.number!!, 0.01)
        // The condition reads a role from another message: miles, not km/h.
        assertTrue(p.decode(frame(0x72, 0, 120, 0, 0, 0, 70)) { if (it == "x.unit") 1.0 else null }.none { it.key == "kmh" })
        // Written back as read.
        assertEquals(p.messages, CanVehicles.fromJson(CanVehicles.toJson(p)).messages)
        // An unknown role in a formula is refused when the profile is read.
        try {
            CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"bad","messages":{"0x10":{"signals":{"a":{"expr":"role(\"speed\")"}}}}}""")
            fail("unknown role accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!, e.message!!.contains("speed"))
        }
    }

    @Test
    fun extendsABuiltInProfile() {
        val child =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":2,"id":"my-golf","extends":"vw-mqb-raise","name":"My Golf",
                   "messages":{
                     "0x23":null,
                     "0x29":{"signals":{"steering":{"factor":-0.03}}},
                     "0x50":{"name":"Driving data","mux":0,"cases":{"0x10":{"signals":{"range":{"start_byte":2,"size":2,"endian":"little","role":"fuel.range"}}}}}},
                   "examples":[{"cmd":"0x50","data":"10 00 2C 01","expect":{"fuel.range":300}}]}""",
            )
        val base = CanVehicles.VW_MQB_RAISE
        assertEquals("my-golf", child.id)
        assertEquals("My Golf", child.name)
        assertEquals(base.family, child.family)
        assertEquals(base.link, child.link)
        assertEquals(base.variants.size, child.variants.size)
        assertNull(child.messages[0x23])
        assertEquals(base.messages[0x24], child.messages[0x24])
        // The signal merged: its new factor, the rest of the parent's.
        val steering = child.messages[0x29]!!.signals.single()
        assertEquals(-0.03, steering.factor, 1e-9)
        assertEquals("steering.angle", steering.role)
        assertTrue(child.selfTest().isEmpty())
        try {
            CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"x-car","extends":"no-such-profile"}""")
            fail("unknown parent accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("no-such-profile"))
        }
        // A loop stops.
        val texts =
            mapOf(
                "a-car" to """{"format":"librehu-can-vehicle","version":2,"id":"a-car","extends":"b-car"}""",
                "b-car" to """{"format":"librehu-can-vehicle","version":2,"id":"b-car","extends":"a-car"}""",
            )
        try {
            CanVehicles.fromJson(texts["a-car"]!!) { texts[it] }
            fail("loop accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("levels"))
        }
    }

    @Test
    fun aCarPatchesItsProfile() {
        val p =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":2,"id":"patch-car","box":"raise",
                   "link":{"send":[{"name":"connect","cmd":"0x81","data":"01","every_ms":0}]},
                   "messages":{"0x29":{"name":"Steering","signals":{"steering":{"start_byte":0,"size":2,"endian":"little","signed":true,"factor":0.1,"role":"steering.angle"}}}},
                   "variants":[{"name":"Old car","send":{},"patch":{"messages":{"0x29":{"signals":{"steering":{"factor":1}}}},"baud":19200}},
                               {"name":"New car","send":{}}]}""",
            )
        val old = p.withVariant("Old car")
        assertEquals(100.0, old.decode(frame(0x29, 100, 0)).single().number!!, 0.01)
        assertEquals(19200, old.baud)
        assertEquals(p.variants, old.variants)
        assertEquals(10.0, p.withVariant("New car").decode(frame(0x29, 100, 0)).single().number!!, 0.01)
        assertEquals(10.0, p.decode(frame(0x29, 100, 0)).single().number!!, 0.01)
        try {
            CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"p2","variants":[{"name":"x","patch":{"id":"other"}}]}""")
            fail("patch of the id accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("patch"))
        }
    }
}
