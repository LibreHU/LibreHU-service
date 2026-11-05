package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Format 2 of the car profiles: roles, conditions, multiplexed messages, invalid values, labels, examples. */
class CanEngineV2Test {
    private fun frame(
        cmd: Int,
        vararg d: Int,
    ) = CanboxFrame(cmd, ByteArray(d.size) { d[it].toByte() }, true)

    private fun profile(body: String) =
        CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"test-car","box":"raise",$body}""")

    // A multiplexed message as Raise's Volkswagen 0x50 (ReMQB7Protocol.parseDrivingData): D0 says what follows.
    private val driving =
        profile(
            """"messages":{"0x50":{"name":"Driving data","mux":0,
                 "signals":{"kind":{"start_byte":0}},
                 "cases":{
                   "0x10":{"name":"Range","signals":{"range":{"start_byte":2,"size":2,"endian":"little","unit":"km","role":"fuel.range"}}},
                   "0x30":{"name":"Average consumption","signals":{"average":{"start_byte":2,"size":2,"endian":"little","factor":0.1,"role":"fuel.average"}}}}}},
               "examples":[{"cmd":"0x50","data":"10 00 2C 01","expect":{"fuel.range":300,"average":null}},
                           {"cmd":"0x50","data":"30 00 41 00","expect":{"average":6.5}}]""",
        )

    @Test
    fun multiplexedMessage() {
        val range = driving.decode(frame(0x50, 0x10, 0, 0x2C, 0x01))
        assertEquals(300.0, range.single { it.key == "range" }.number!!, 0.01)
        assertEquals("fuel.range", range.single { it.key == "range" }.role)
        assertTrue(range.none { it.key == "average" })
        assertEquals("Range", range.first().message)
        val avg = driving.decodeRoles(frame(0x50, 0x30, 0, 0x41, 0x00))
        assertEquals(6.5, avg["fuel.average"]!!.number!!, 0.01)
        // A code without a case: only the message's own signals.
        assertEquals(listOf("kind"), driving.decode(frame(0x50, 0x99, 0, 0, 0)).map { it.key })
        assertTrue(driving.selfTest().isEmpty())
    }

    @Test
    fun conditionOnLengthAndByte() {
        // RePQProtocol.parseVehicleGearInfo: the state is D1 when the message has 2 bytes, D0 otherwise.
        val p =
            profile(
                """"messages":{"0x24":{"name":"State","signals":{
                     "long":{"start_byte":1,"bits":{"reverse":{"bit":0,"role":"gear.reverse"}},"when":{"len":2}},
                     "short":{"start_byte":0,"bits":{"reverse0":{"bit":0,"role":"gear.reverse"}},"when":{"len":1}},
                     "tagged":{"start_byte":1,"when":{"byte":0,"is":["0x80","0x81"],"mask":"0xF0"}}}}}""",
            )
        assertEquals(true, p.decodeRoles(frame(0x24, 0x00, 0x01))["gear.reverse"]!!.flag)
        assertEquals(true, p.decodeRoles(frame(0x24, 0x01))["gear.reverse"]!!.flag)
        assertEquals(false, p.decodeRoles(frame(0x24, 0x01, 0x00))["gear.reverse"]!!.flag)
        assertEquals(7.0, p.decode(frame(0x24, 0x8F, 0x07)).single { it.key == "tagged" }.number!!, 0.01)
        assertTrue(p.decode(frame(0x24, 0x7F, 0x07)).none { it.key == "tagged" })
    }

    @Test
    fun invalidValuesBitFieldsAndLabels() {
        val p =
            profile(
                """"messages":{"0x12":{"name":"Car info","signals":{
                     "oil":{"start_byte":0,"invalid":["0xFF"],"unit":"°C","role":"engine.oil_temp","label":{"en":"oil","fr":"huile"}},
                     "mode":{"start_byte":1,"start_bit":4,"bit_length":3},
                     "doors":{"start_byte":2,"bits":{"fl":{"bit":7,"role":"door.front_left"},"trunk":3}}}}}""",
            )
        val v = p.decode(frame(0x12, 0xFF, 0b0101_0000, 0x80))
        assertTrue(v.none { it.key == "oil" })
        assertEquals(5.0, v.single { it.key == "mode" }.number!!, 0.01)
        assertEquals(true, v.single { it.key == "fl" }.flag)
        assertEquals("door.front_left", v.single { it.key == "fl" }.role)
        assertNull(v.single { it.key == "trunk" }.role)
        val oil = p.decode(frame(0x12, 90, 0, 0)).single { it.key == "oil" }
        assertEquals("huile", oil.labelIn("fr"))
        assertEquals("oil", oil.labelIn("en"))
        // No label: the role's, in French too.
        assertEquals("porte avant gauche ouverte", v.single { it.key == "fl" }.labelIn("fr"))
    }

    @Test
    fun examplesReportFailures() {
        val bad =
            profile(
                """"messages":{"0x19":{"name":"Engine","signals":{"rpm":{"start_byte":0,"size":2,"role":"engine.rpm"}}}},
                   "examples":[{"cmd":"0x19","data":"03 20","expect":{"engine.rpm":900,"coolant":85}}]""",
            )
        val errors = bad.selfTest()
        assertEquals(2, errors.size)
        assertTrue(errors[0], errors[0].contains("expected 900"))
        assertTrue(errors[1], errors[1].contains("no value"))
    }

    @Test
    fun unknownRoleRefused() {
        try {
            profile(""""messages":{"0x19":{"signals":{"rpm":{"start_byte":0,"role":"engine.rmp"}}}}""")
            fail("unknown role accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!, e.message!!.contains("engine.rmp"))
        }
        // A profile's own value: x.<name>.
        profile(""""messages":{"0x19":{"signals":{"boost":{"start_byte":0,"role":"x.turbo_boost"}}}}""")
    }

    @Test
    fun casesNeedMux() {
        try {
            profile(""""messages":{"0x50":{"cases":{"0x10":{"signals":{}}}}}""")
            fail("cases without mux accepted")
        } catch (e: CanVehicleException) {
            assertTrue(e.message!!.contains("mux"))
        }
    }

    @Test
    fun jsonRoundTripKeepsFormat2() {
        val again = CanVehicles.fromJson(CanVehicles.toJson(driving))
        assertEquals(driving.messages, again.messages)
        assertEquals(driving.examples.size, again.examples.size)
        assertTrue(again.selfTest().isEmpty())
        assertTrue(CanVehicles.toJson(driving).contains("\"version\": 2"))
    }

    @Test
    fun builtInProfilesPassTheirExamples() {
        for (v in CanVehicles.BUILT_IN) assertEquals(v.id, emptyList<String>(), v.selfTest())
    }

    @Test
    fun rolesHaveUniqueIdsAndVssPaths() {
        assertEquals(CanRoles.ALL.size, CanRoles.ALL.map { it.id }.toSet().size)
        for (r in CanRoles.ALL) {
            assertTrue(r.id, CanRoles.isValid(r.id))
            r.vss?.let { assertTrue(it, it.startsWith("Vehicle.")) }
        }
    }
}
