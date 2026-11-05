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

/** Box framing described in a profile ([CanFraming]): the same bytes as the families written in code. */
class CanFramingTest {
    private fun fam(json: String) =
        CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"frame-car","box":"newbox","framing":$json}""").family

    private val hiworld =
        fam("""{"head":"5A A5","order":"len,cmd","checksum":{"algo":"sum","over":"len,cmd,data","add":-1},"ack":{"cmd":"0xFF"},"no_ack_for":["0xFF","0xFE"]}""")
    private val raise =
        fam("""{"head":"2E","order":"cmd,len","checksum":{"algo":"sum","over":"cmd,len,data","xor":"0xFF"},"ack":{"raw":"FF"},"box_answers":["FF","F0","F3","FC"]}""")
    private val fd16 = fam("""{"head":"FD","order":"len,cmd","len_offset":4,"checksum":{"algo":"sum","over":"len,cmd,data","size":2}}""")

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun sameBytesAsTheCodedFamilies() {
        val samples = listOf(byteArrayOf(), byteArrayOf(0x00, 0x31), ByteArray(27) { (it * 37).toByte() })
        for (d in samples) {
            assertEquals(hex(CanBoxFamilies.HIWORLD.encode(0x24, d)), hex(hiworld.encode(0x24, d)))
            assertEquals(hex(CanBoxFamilies.RAISE.encode(0x81, d)), hex(raise.encode(0x81, d)))
            assertEquals(hex(CanBoxFamilies.RAISE_FD2.encode(0x40, d)), hex(fd16.encode(0x40, d)))
        }
        assertEquals("5A A5 02 24 00 31 56", hex(hiworld.encode(0x24, byteArrayOf(0x00, 0x31))))
        assertEquals("2E 81 01 01 7C", hex(raise.encode(0x81, byteArrayOf(1))))
    }

    @Test
    fun parsesAndAcknowledges() {
        for ((family, coded) in listOf(hiworld to CanBoxFamilies.HIWORLD, raise to CanBoxFamilies.RAISE, fd16 to CanBoxFamilies.RAISE_FD2)) {
            val got = mutableListOf<CanboxFrame>()
            val p = family.parser { got += it }
            val a = coded.encode(0x11, byteArrayOf(1, 2, 3))
            val b = coded.encode(0x12, byteArrayOf())
            val bad = coded.encode(0x13, byteArrayOf(9)).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            // Noise, a box answer, frames cut anywhere.
            val stream = byteArrayOf(0x00, 0x77) + a + byteArrayOf(0xFF.toByte()) + b + bad
            stream.toList().chunked(3).forEach { p.feed(it.toByteArray()) }
            assertEquals(family.id, listOf(0x11, 0x12, 0x13), got.map { it.cmd })
            assertEquals(listOf(true, true, false), got.map { it.checksumOk })
            assertEquals("01 02 03", hex(got[0].data))
            assertEquals(coded.ack(got[0])?.let { hex(it) }, family.ack(got[0])?.let { hex(it) })
        }
        // Hiworld: no acknowledgement of the box's own acknowledgements.
        assertNull(hiworld.ack(CanboxFrame(0xFF, byteArrayOf(0x24), true)))
    }

    @Test
    fun badFramingRefused() {
        for (bad in listOf("""{"head":""}""", """{"head":"2E","order":"x"}""", """{"head":"2E","checksum":{"algo":"crc99"}}""", """{"head":"2E","checksum":{"size":3}}""")) {
            try {
                fam(bad)
                fail("accepted: $bad")
            } catch (e: CanVehicleException) {
                assertTrue(e.message!!, e.message!!.contains("framing"))
            }
        }
    }
}
