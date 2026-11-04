package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanBoxLinkTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private class Sent(
        val bytes: String,
        val what: String,
    )

    private fun link(
        l: CanLink,
        out: MutableList<Sent>,
        acked: MutableList<Int> = mutableListOf(),
    ) = CanBoxLink(CanBoxFamilies.HIWORLD, l, { b, w -> out += Sent(hex(b), w) }, { acked += it })

    private fun frame(
        cmd: Int,
        vararg data: Int,
        ok: Boolean = true,
    ) = CanboxFrame(cmd, ByteArray(data.size) { data[it].toByte() }, ok)

    @Test
    fun hiworldAcknowledgesEveryMessageButAcks() {
        val out = mutableListOf<Sent>()
        val acked = mutableListOf<Int>()
        val l = link(CanLink(), out, acked)
        l.onFrame(frame(0x11, 0x61, 0, 0, 0, 8, 0, 0, 0, 2, 0x17), 0)
        // As Hiworld's app: 5A A5 01 FF <cmd> CS, CS = ((1 + 0xFF + cmd) & 0xFF) - 1.
        assertEquals("5A A5 01 FF 11 10", out.single().bytes)
        l.onFrame(frame(0xFF, 0x24), 10)
        l.onFrame(frame(0xFE, 0x24), 20)
        l.onFrame(frame(0x12, 0, ok = false), 30)
        assertEquals(1, out.size)
        assertEquals(listOf(0x24, 0x24), acked)
    }

    @Test
    fun clio3CarTypeAtOneSecondThenEveryThirty() {
        val out = mutableListOf<Sent>()
        val l = link(CanVehicles.RENAULT_CLIO3_HIWORLD.link.copy(ack = false), out)
        l.start(0)
        l.tick(999)
        assertTrue(out.isEmpty())
        l.tick(1_000)
        // HdRenaultProtocolLNP002.buildCarTypePacket: getPacket(0x24, [car code 0, 0x31]).
        assertEquals("5A A5 02 24 00 31 56", out.single().bytes)
        l.tick(30_999)
        assertEquals(1, out.size)
        l.tick(31_000)
        assertEquals(2, out.size)
    }

    @Test
    fun boxBackAfterSilenceGetsTheMessagesAgain() {
        val out = mutableListOf<Sent>()
        val once = CanLink(ack = false, send = listOf(CanSend("init", 0x24, listOf(0, 0x31), firstMs = 100, everyMs = 0)))
        val l = link(once, out)
        l.start(0)
        l.onFrame(frame(0x11, 0), 50)
        l.tick(100)
        l.tick(10_000)
        assertEquals(1, out.size)
        // Six seconds without a message, then the box talks again: restarted.
        l.onFrame(frame(0x11, 0), 6_200)
        l.tick(6_299)
        assertEquals(1, out.size)
        l.tick(6_300)
        assertEquals(2, out.size)
    }

    @Test
    fun linkSurvivesTheJsonRoundTrip() {
        val v = CanVehicles.RENAULT_CLIO3_HIWORLD
        val again = CanVehicles.fromJson(CanVehicles.toJson(v))
        assertEquals(v.link, again.link)
        assertEquals(listOf(0x00, 0x31), again.link.send.single().data)
        assertEquals(30_000L, again.link.send.single().everyMs)
    }

    @Test(expected = CanVehicleException::class)
    fun unknownBoxRefused() {
        CanVehicles.fromJson(
            """{"format":"librehu-can-vehicle","version":1,"id":"x-car","box":"nope","messages":{}}""",
        )
    }
}

class CanBoxFamiliesTest {
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun roundTrip(
        family: CanBoxFamily,
        cmd: Int,
        data: ByteArray,
    ): CanboxFrame {
        val got = mutableListOf<CanboxFrame>()
        val p = family.parser { got += it }
        val bytes = family.encode(cmd, data)
        // Noise first, then the message cut in two chunks, as the MCU relays it.
        p.feed(byteArrayOf(0x00, 0x13))
        p.feed(bytes.copyOfRange(0, 3))
        p.feed(bytes.copyOfRange(3, bytes.size))
        return got.single()
    }

    @Test
    fun raiseFraming() {
        // 2E CMD LEN D.. CS, CS = (CMD + LEN + ΣD) XOR 0xFF (ReBaseProtocol.getPacket / calculateCheckSum).
        assertEquals("2E 20 02 01 01 DB", hex(CanBoxFamilies.RAISE.encode(0x20, byteArrayOf(1, 1))))
        val f = roundTrip(CanBoxFamilies.RAISE, 0x20, byteArrayOf(1, 1))
        assertEquals(0x20, f.cmd)
        assertTrue(f.checksumOk)
        assertEquals("FF", hex(CanBoxFamilies.RAISE.ack(f)!!))
        // The box's single-byte answers are not messages.
        val got = mutableListOf<CanboxFrame>()
        val p = RaiseParser { got += it }
        p.feed(byteArrayOf(0xFF.toByte(), 0xF0.toByte()))
        assertTrue(got.isEmpty())
        assertEquals(2L, p.answers)
        assertEquals(0L, p.skipped)
    }

    @Test
    fun hiworldAa55AndFdFramings() {
        assertEquals("AA 55 02 24 00 31 56", hex(CanBoxFamilies.HIWORLD_AA55.encode(0x24, byteArrayOf(0, 0x31))))
        assertTrue(roundTrip(CanBoxFamilies.HIWORLD_AA55, 0x24, byteArrayOf(0, 0x31)).checksumOk)
        assertEquals(null, CanBoxFamilies.HIWORLD_AA55.ack(CanboxFrame(0x11, byteArrayOf(0), true)))
        // FD LEN CMD D.. CS: LEN = n + 3, CS = (LEN + CMD + ΣD) & 0xFF (ReFDProtocol).
        assertEquals("FD 05 10 01 02 18", hex(CanBoxFamilies.RAISE_FD.encode(0x10, byteArrayOf(1, 2))))
        assertTrue(roundTrip(CanBoxFamilies.RAISE_FD, 0x10, byteArrayOf(1, 2)).checksumOk)
        // FD LEN CMD D.. CSH CSL: LEN = n + 4, 16-bit sum (ReFD2Protocol).
        assertEquals("FD 06 10 01 02 00 19", hex(CanBoxFamilies.RAISE_FD2.encode(0x10, byteArrayOf(1, 2))))
        val f = roundTrip(CanBoxFamilies.RAISE_FD2, 0x10, byteArrayOf(1, 2))
        assertTrue(f.checksumOk)
        assertEquals(listOf(1, 2), f.data.map { it.toInt() })
    }

    @Test
    fun makersNameTheirFraming() {
        assertTrue(CanBoxFamilies.byId("simple") === CanBoxFamilies.RAISE)
        assertTrue(CanBoxFamilies.byId("hiworld") === CanBoxFamilies.HIWORLD)
        assertEquals(null, CanBoxFamilies.byId("klyde"))
    }

    @Test
    fun wheelKeysFromTheProfile() {
        val v =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":1,"id":"raise-car","box":"raise",
                   "wheel_keys":{"cmd":"0x20","code_byte":0,"state_byte":1,"pressed":[1,2],"names":{"1":"volume +"}},"messages":{}}""",
            )
        val spec = v.keys!!
        assertEquals("volume +", spec.name(1))
        val got = mutableListOf<Int>()
        val k = HiworldWheelKeys(spec = spec) { got += it }
        listOf(byteArrayOf(1, 1), byteArrayOf(1, 2), byteArrayOf(1, 0), byteArrayOf(0, 0), byteArrayOf(3, 1)).forEach {
            k.onFrame(CanboxFrame(0x20, it, true))
        }
        assertEquals(listOf(1, 3), got)
        // Written back as it was read.
        assertEquals(spec, CanVehicles.fromJson(CanVehicles.toJson(v)).keys)
        // The Clio 3 profile states its keys; an old Hiworld profile with only the 0x11 wheel_key signal gets LNP002's.
        assertEquals(0x08, CanVehicles.RENAULT_CLIO3_HIWORLD.keys!!.eventMask)
    }
}
