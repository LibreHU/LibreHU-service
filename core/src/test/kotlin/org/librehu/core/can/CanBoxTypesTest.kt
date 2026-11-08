package org.librehu.core.can

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Box types described as data (engine 2.6): the generic profiles built in, and those of a `boxes` pack. */
class CanBoxTypesTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @After
    fun builtInOnly() = CanBoxFamilies.register(emptyList())

    @Test
    fun builtInGenericProfilesAreTheBoxTypes() {
        assertEquals(CanVehicles.BUILT_IN_IDS, CanVehicles.BUILT_IN.map { it.id })
        for (v in CanVehicles.BUILT_IN) {
            assertNotNull(v.id, v.boxType)
            assertNotNull(v.id, v.framing)
            assertTrue(v.id, v.messages.isEmpty())
            assertEquals(v.id, ">=2.6", CanEngine.requiredBy(v))
        }
        assertEquals(listOf("hiworld", "hiworld-aa55", "raise", "raise-fd", "raise-fd2"), CanBoxFamilies.ALL.map { it.id })
        assertEquals("Hiworld (5A A5)", CanBoxFamilies.HIWORLD.name)
        assertEquals(CanVehicles.DEFAULT_ID, CanVehicles.GENERIC_HIWORLD.id)
    }

    /** The data box types write and read the very bytes of the framings coded before engine 2.6. */
    @Test
    fun sameBytesAsTheFormerCodedFamilies() {
        val samples = listOf(byteArrayOf(), byteArrayOf(0x00, 0x31), ByteArray(40) { (it * 37).toByte() })
        for (d in samples) {
            assertEquals(hex(HiworldParser.encode(0x24, d)), hex(CanBoxFamilies.HIWORLD.encode(0x24, d)))
            assertEquals(hex(HiworldParser.encode(0x24, d, 0xAA, 0x55)), hex(CanBoxFamilies.HIWORLD_AA55.encode(0x24, d)))
            assertEquals(hex(RaiseParser.encode(0x81, d)), hex(CanBoxFamilies.RAISE.encode(0x81, d)))
            assertEquals(hex(FdParser.encode(0x10, d, 1)), hex(CanBoxFamilies.RAISE_FD.encode(0x10, d)))
            assertEquals(hex(FdParser.encode(0x10, d, 2)), hex(CanBoxFamilies.RAISE_FD2.encode(0x10, d)))
        }
        val coded: Map<CanBoxFamily, (Int, ByteArray) -> ByteArray> =
            mapOf(
                CanBoxFamilies.HIWORLD to { c, d -> HiworldParser.encode(c, d) },
                CanBoxFamilies.HIWORLD_AA55 to { c, d -> HiworldParser.encode(c, d, 0xAA, 0x55) },
                CanBoxFamilies.RAISE to { c, d -> RaiseParser.encode(c, d) },
                CanBoxFamilies.RAISE_FD to { c, d -> FdParser.encode(c, d, 1) },
                CanBoxFamilies.RAISE_FD2 to { c, d -> FdParser.encode(c, d, 2) },
            )
        for ((family, encode) in coded) {
            val got = mutableListOf<CanboxFrame>()
            val p = family.parser { got += it }
            val stream = byteArrayOf(0x00) + encode(0x11, byteArrayOf(1, 2, 3)) + encode(0x12, byteArrayOf()) + encode(0x13, ByteArray(30) { it.toByte() })
            stream.toList().chunked(5).forEach { p.feed(it.toByteArray()) }
            assertEquals(family.id, listOf(0x11, 0x12, 0x13), got.map { it.cmd })
            assertTrue(family.id, got.all { it.checksumOk })
            assertEquals(family.id, 30, got[2].data.size)
        }
    }

    @Test
    fun acknowledgementsOfEachType() {
        val f = CanboxFrame(0x11, byteArrayOf(8), true)
        assertEquals("5A A5 01 FF 11 10", hex(CanBoxFamilies.HIWORLD.ack(f)!!))
        assertNull(CanBoxFamilies.HIWORLD.ack(CanboxFrame(0xFF, byteArrayOf(0x11), true)))
        assertEquals("FF", hex(CanBoxFamilies.RAISE.ack(f)!!))
        assertNull(CanBoxFamilies.HIWORLD_AA55.ack(f))
        assertNull(CanBoxFamilies.RAISE_FD.ack(f))
        assertNull(CanBoxFamilies.RAISE_FD2.ack(f))
    }

    @Test
    fun makersAndGenericProfiles() {
        assertTrue(CanBoxFamilies.byId("Simple") === CanBoxFamilies.RAISE)
        assertTrue(CanBoxFamilies.byId("wy") === CanBoxFamilies.HIWORLD)
        assertEquals("raise-generic", CanVehicles.genericFor("bagoo").id)
        assertEquals("raise-fd-generic", CanVehicles.genericFor("raise-fd").id)
        assertEquals("hiworld-generic", CanVehicles.genericFor(null).id)
        assertEquals("hiworld-generic", CanVehicles.genericFor("klyde").id)
        // The cars built in up to versionCode 183 fall back to their own box type until their pack is installed.
        for (v in TestProfiles.ALL) assertEquals(v.id, v.family.id, CanBoxFamilies.byId(CanVehicles.FORMER_BUILT_IN_BOX.getValue(v.id))!!.id)
    }

    /** A new box type in a pack: known at once, no new service; its makers too. */
    @Test
    fun boxTypeFromAPack() {
        val car = """{"format":"librehu-can-vehicle","version":2,"id":"klyde-car","box":"klyde","messages":{}}"""
        try {
            CanVehicles.fromJson(car)
            throw AssertionError("unknown box read")
        } catch (e: CanVehicleException) {
            assertTrue(e.message, e.message!!.contains("Unknown box \"klyde\""))
        }
        val type =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":2,"engine":">=2.6","id":"klyde-generic","name":"Klyde: any car","box":"klyde",
                "box_type":{"name":"Klyde (A5 5A)","aliases":["klyde-pro"]},
                "framing":{"head":"A5 5A","order":"cmd,len","checksum":{"algo":"xor","over":"cmd,len,data"},"ack":{"raw":"F5"}},"messages":{}}""",
            )
        CanBoxFamilies.register(listOf(type))
        val v = CanVehicles.fromJson(car)
        assertEquals("Klyde (A5 5A)", v.family.name)
        assertEquals("A5 5A 20 01 07 26", hex(v.family.encode(0x20, byteArrayOf(7))))
        assertEquals("F5", hex(v.family.ack(CanboxFrame(0x20, byteArrayOf(7), true))!!))
        assertTrue(CanBoxFamilies.byId("klyde-pro") === v.family)
        assertEquals("klyde-generic", CanVehicles.genericFor("klyde").id)
        assertEquals("A5 5A", TwrpCanConf.framing(v)!!.head.joinToString(" ") { "%02X".format(it) })
        // The built-in types stay.
        assertEquals("Hiworld (5A A5)", CanBoxFamilies.HIWORLD.name)
        CanBoxFamilies.register(emptyList())
        assertNull(CanBoxFamilies.byId("klyde"))
    }

    @Test
    fun aBoxTypeNeedsItsFraming() {
        try {
            CanVehicles.fromJson("""{"format":"librehu-can-vehicle","version":2,"id":"x-generic","box":"x","box_type":{"name":"X"},"messages":{}}""")
            throw AssertionError("box type without framing read")
        } catch (e: CanVehicleException) {
            assertTrue(e.message, e.message!!.contains("framing"))
        }
    }

    @Test
    fun boxTypeWrittenBackToJson() {
        val v = CanVehicles.fromJson(CanVehicles.toJson(CanVehicles.BUILT_IN[2]))
        assertEquals("raise", v.box)
        assertEquals(CanVehicles.BUILT_IN[2].boxType, v.boxType)
        assertEquals(CanVehicles.BUILT_IN[2].framing, v.framing)
    }
}
