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

/** Messages built from the head unit's state ([CanTemplate]) and commands. */
class CanTemplateTest {
    private class State(
        val n: MutableMap<String, Double> = mutableMapOf(),
        val t: MutableMap<String, String> = mutableMapOf(),
    ) : CanState {
        override fun number(name: String) = n[name]

        override fun text(name: String) = t[name]
    }

    private fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02X".format(it) }

    @Test
    fun buildsBytesFromTheState() {
        val st = State(CanState.clock(java.time.LocalDateTime.of(2026, 11, 5, 14, 7, 30)).toMutableMap(), mutableMapOf("title" to "Él Song"))
        assertEquals("1A 0B 05 0E 07 04", hex(CanTemplate.parse("{year2} {month} {day} {hour} {minute} {weekday}", "t").build(st)))
        assertEquals("07 EA EA 07", hex(CanTemplate.parse("{year:2be} {year:2le}", "t").build(st)))
        // Text: ASCII padded with spaces (or 0x00), unknown characters as "?"; UTF-16.
        assertEquals("0A 3F 6C 20 53 6F 6E 67 20 20", hex(CanTemplate.parse("0A {text:title:9}", "t").build(st)))
        assertEquals("3F 6C 00", hex(CanTemplate.parse("{text:x:3:00}", "t").build(State(t = mutableMapOf("x" to "él")))))
        assertEquals("C9 00 6C 00", hex(CanTemplate.parse("{utf16le:title:2}", "t").build(st)))
        // A command's value.
        assertEquals("01 2B", hex(CanTemplate.parse("01 {value * 2}", "t").build(st, 21.5)))
        // Unknown: nothing.
        assertNull(CanTemplate.parse("{text:artist:8}", "t").build(st))
        assertNull(CanTemplate.parse("{volume}", "t").build(st))
        assertEquals(listOf(0x81, 0x01), CanTemplate.parse("81 01", "t").fixed)
        for (bad in listOf("{text:x:99}", "GG", "{1 +}", "00 ".repeat(130))) {
            try {
                CanTemplate.parse(bad, "t")
                fail("accepted: $bad")
            } catch (_: CanVehicleException) {
            }
        }
    }

    @Test
    fun linkSendsBuiltMessagesAndCommands() {
        val p =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":2,"id":"clock-car","box":"raise","link":{"ack":true,
                   "send":[{"name":"time","cmd":"0xCB","data":"{hour} {minute}","first_ms":1000,"every_ms":0,"on":"change"}],
                   "commands":{"temp left":{"cmd":"0x8A","data":"01 {value * 2}"}}}}""",
            )
        val st = State(mutableMapOf("hour" to 14.0, "minute" to 7.0))
        val out = mutableListOf<String>()
        val l = CanBoxLink(p.family, p.link, { b, w -> out += "$w ${hex(b)}" }, state = st)
        l.start(0)
        l.tick(500)
        assertTrue(out.isEmpty())
        l.tick(1_000)
        assertEquals(listOf("time 2E CB 02 0E 07 1D"), out)
        // Same time: nothing; a new minute: sent at once.
        l.tick(2_000)
        assertEquals(1, out.size)
        st.n["minute"] = 8.0
        l.tick(2_100)
        assertEquals("time 2E CB 02 0E 08 1C", out.last())
        assertTrue(l.command("temp left", 21.5))
        assertEquals("command temp left 2E 8A 02 01 2B 47", out.last())
        assertTrue(!l.command("no such command", 1.0))
        // Written back as read; TWRP leaves the built message to the service.
        assertEquals(p.link, CanVehicles.fromJson(CanVehicles.toJson(p)).link)
        assertTrue(TwrpCanConf.of(p).contains("# time: built by the service"))
    }
}

/** More key sources (format 2 `"keys"`): media keys, knobs. */
class CanKeySourcesTest {
    private fun frame(
        cmd: Int,
        vararg d: Int,
    ) = CanboxFrame(cmd, ByteArray(d.size) { d[it].toByte() }, true)

    private val p =
        CanVehicles.fromJson(
            """{"format":"librehu-can-vehicle","version":2,"id":"keys-car","box":"raise",
               "wheel_keys":{"cmd":"0x20","code_byte":0,"state_byte":1,"pressed":[1],"names":{"1":"volume +"}},
               "keys":[{"cmd":"0x2F","code_byte":0,"state_byte":1,"pressed":[1],"names":{"1":"play / pause"}},
                       {"cmd":"0x22","code_byte":0,"steps_byte":1,"names":{"1":"volume","2":"tune"}}]}""",
        )

    @Test
    fun sourcesHaveTheirOwnCodes() {
        assertEquals(3, p.allKeys.size)
        assertEquals(listOf(0, 256, 512), p.allKeys.map { it.codeBase })
        val pressed = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val ds = p.allKeys.map { s -> HiworldWheelKeys(onRelease = { released += it }, spec = s) { pressed += it } }
        fun feed(f: CanboxFrame) = ds.forEach { it.onFrame(f) }
        feed(frame(0x20, 1, 1))
        feed(frame(0x20, 0, 0))
        feed(frame(0x2F, 1, 1))
        feed(frame(0x2F, 0, 0))
        // The volume knob turned 3 steps up, then 1 down (0xFF = -1).
        feed(frame(0x22, 1, 3))
        feed(frame(0x22, 1, 0xFF))
        assertEquals(listOf(1, 257, 514, 514, 514, 515), pressed)
        assertEquals(pressed, released)
        assertEquals("volume +", p.allKeys.first { it.owns(1) }.name(1))
        assertEquals("play / pause", p.allKeys.first { it.owns(257) }.name(257))
        assertEquals("volume +", p.allKeys.first { it.owns(514) }.name(514))
        assertEquals("volume −", p.allKeys.first { it.owns(515) }.name(515))
        assertEquals("tune +", p.allKeys.first { it.owns(516) }.name(516))
        assertEquals(p.allKeys, CanVehicles.fromJson(CanVehicles.toJson(p)).allKeys)
    }

    @Test
    fun aKnobTakesTenStepsAtMost() {
        val got = mutableListOf<Int>()
        HiworldWheelKeys(spec = p.moreKeys[1]) { got += it }.onFrame(frame(0x22, 1, 100))
        assertEquals(10, got.size)
    }

    @Test
    fun extraSourcesStayAboveTheWheelCodes() {
        try {
            CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"k2","keys":[{"cmd":"0x2F","code_byte":0,"code_base":10}]}""")
            fail("code_base 10 accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("code_base"))
        }
    }
}

/** Version of the engine (Semantic Versioning) and what a profile needs. */
class CanEngineVersionTest {
    @Test
    fun requirements() {
        val v = CanEngine.Version(2, 5, 0)
        assertTrue(CanEngine.satisfies(">=2.1", v))
        assertTrue(CanEngine.satisfies(">=1", v))
        assertTrue(!CanEngine.satisfies(">=2.6", v))
        assertTrue(CanEngine.satisfies("^2.3", v))
        assertTrue(!CanEngine.satisfies("^3.0", v))
        assertTrue(!CanEngine.satisfies("^1.0", v))
        assertTrue(CanEngine.satisfies("2.5.0", v))
        assertTrue(CanEngine.satisfies(">=2.1", CanEngine.Version(3, 0, 0)))
        assertTrue(!CanEngine.isRequirement("latest"))
        assertEquals(CanEngine.VERSION, CanEngine.HISTORY.last().first)
    }

    @Test
    fun aProfileTooNewIsRefusedClearly() {
        try {
            CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"future-car","engine":">=9.0"}""")
            fail("accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!, e.message!!.contains("update LibreHU service"))
        }
        CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"now-car","engine":">=2.0"}""")
    }

    @Test
    fun smallestEngineOfEachProfile() {
        assertEquals(">=2.1", CanEngine.requiredBy(CanVehicles.VW_PQ_HIWORLD))
        assertEquals(">=2.0", CanEngine.requiredBy(CanVehicles.RENAULT_CLIO3_HIWORLD))
        assertEquals(">=1.0", CanEngine.requiredBy(CanVehicles.GENERIC_HIWORLD))
        // Written in the JSON, and read back.
        val json = CanVehicles.toJson(CanVehicles.VW_PQ_HIWORLD)
        assertTrue(json.contains("\"engine\": \">=2.1\""))
        assertEquals(CanVehicles.VW_PQ_HIWORLD.messages, CanVehicles.fromJson(json).messages)
        for (p in CanVehicles.BUILT_IN) assertTrue(p.id, CanEngine.satisfies(CanEngine.requiredBy(p)))
    }
}

/** The car's values from the CAN box and an OBD adapter at once ([CarData]). */
class CarDataTest {
    private fun v(
        role: String,
        n: Double,
    ) = CanValue(role, role, "test", n, role = role)

    @Test
    fun boxFirstObdFillsIn() {
        val d = CarData()
        d.put(CarData.CANBOX, mapOf("vehicle.speed" to v("vehicle.speed", 50.0), "door.front_left" to v("door.front_left", 1.0)), 0)
        d.put(CarData.OBD, CarData.obdValues(mapOf("SPEED" to 49.0, "RPM" to 2000.0, "COOLANT_TEMP" to 88.0)), 0)
        val m = d.merged()
        assertEquals(50.0, m["vehicle.speed"]!!.value.number!!, 0.01)
        assertEquals(CarData.CANBOX, m["vehicle.speed"]!!.source)
        assertEquals(2000.0, m["engine.rpm"]!!.value.number!!, 0.01)
        assertEquals(CarData.OBD, m["engine.rpm"]!!.source)
        assertEquals(88.0, m["engine.coolant_temp"]!!.value.number!!, 0.01)
        // The box gone: the adapter's speed.
        d.clear(CarData.CANBOX)
        assertEquals(49.0, d.merged()["vehicle.speed"]!!.value.number!!, 0.01)
        assertTrue(d.merged()["door.front_left"] == null)
        // Another priority.
        val obdFirst = CarData(listOf(CarData.OBD, CarData.CANBOX))
        obdFirst.put(CarData.CANBOX, mapOf("vehicle.speed" to v("vehicle.speed", 50.0)), 0)
        obdFirst.put(CarData.OBD, CarData.obdValues(mapOf("SPEED" to 49.0)), 0)
        assertEquals(CarData.OBD, obdFirst.merged()["vehicle.speed"]!!.source)
    }

    @Test
    fun obdRolesExistAndBatteryPrefersThePid() {
        for (role in CarData.OBD_ROLES.values) assertTrue(role, CanRoles.byId(role) != null)
        val m = CarData.obdValues(mapOf("BATTERY" to 12.1, "MODULE_VOLTAGE" to 12.4))
        assertEquals(12.4, m["battery.voltage"]!!.number!!, 0.01)
        assertEquals("V", m["battery.voltage"]!!.unit)
    }
}

/** The command-line tool of the profiles repository ([CarTool]). */
class CarToolTest {
    @Test
    fun checksProfilesAndTheirEngine() {
        val dir = kotlin.io.path.createTempDirectory("cartool").toFile()
        java.io.File(dir, "good.json").writeText(
            """{"format":"librehu-can-vehicle","version":2,"engine":">=2.0","id":"good-car","messages":{"0x19":{"signals":{"rpm":{"start_byte":0,"size":2,"role":"engine.rpm"}}}},
               "examples":[{"cmd":"0x19","data":"03 20","expect":{"engine.rpm":800}}]}""",
        )
        val out = mutableListOf<String>()
        assertEquals(0, CarTool.check(listOf(dir.path), { out += it }))
        // Too low an engine for a formula, a wrong example: refused, with the reasons.
        java.io.File(dir, "bad.json").writeText(
            """{"format":"librehu-can-vehicle","version":2,"engine":">=2.0","id":"bad-car","messages":{"0x19":{"signals":{"rpm":{"expr":"u16be(0)","role":"engine.rpm"}}}},
               "examples":[{"cmd":"0x19","data":"03 20","expect":{"engine.rpm":900}}]}""",
        )
        out.clear()
        assertEquals(1, CarTool.check(listOf(dir.path), { out += it }))
        assertTrue(out.joinToString("\n"), out.any { it.contains("write \"engine\": \">=2.1\"") })
        assertTrue(out.any { it.contains("expected 900") })
        dir.deleteRecursively()
    }
}

/** Packs of car profiles by make ([CanPacks]). */
class CanPacksTest {
    private fun pack(
        profiles: String,
        engine: String = ">=2.0",
    ) = """{"format":"librehu-car-pack","version":1,"id":"testmake","name":"Test make","revision":7,"engine":"$engine","profiles":[$profiles]}""".toByteArray()

    private val good =
        """{"format":"librehu-can-vehicle","version":2,"engine":">=2.0","id":"test-car","messages":{"0x19":{"signals":{"rpm":{"start_byte":0,"size":2,"role":"engine.rpm"}}}},
           "examples":[{"cmd":"0x19","data":"03 20","expect":{"engine.rpm":800}}]}"""

    @Test
    fun readsAPack() {
        val bytes = pack(good)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val p = CanPacks.read(bytes, sha)
        assertEquals("testmake", p.id)
        assertEquals(7, p.revision)
        assertEquals(listOf("test-car"), p.profiles.map { it.id })
    }

    @Test
    fun refusesWholePacks() {
        for ((bytes, why) in listOf(
            pack(good) to "0000",
            pack(good, ">=9.0") to null,
            pack(good.replace("800", "900")) to null,
            pack("$good,$good") to null,
            """{"format":"something"}""".toByteArray() to null,
        )) {
            try {
                CanPacks.read(bytes, why)
                fail("accepted: ${String(bytes).take(60)}")
            } catch (_: CanVehicleException) {
            }
        }
    }
}
