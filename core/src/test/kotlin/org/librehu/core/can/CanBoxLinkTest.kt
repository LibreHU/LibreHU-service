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
