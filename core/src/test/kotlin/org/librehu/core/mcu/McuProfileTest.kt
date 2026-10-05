package org.librehu.core.mcu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McuProfileTest {
    private val generic = ProfileProtocol(McuProfiles.JANCAR_JAC_V1)

    private fun hex(s: String) = McuProfiles.parseHex(s).map { it.toByte() }.toByteArray()

    @Test
    fun genericEngineEncodesLikeTheNativeJancarProtocol() {
        val frames =
            listOf(
                Mcu.pcReady(),
                Mcu.mute(true),
                Mcu.date(2026, 10, 5),
                Mcu.time(12, 34, 56),
                Mcu.canData(ByteArray(140) { it.toByte() }.copyOf(128)),
            )
        for (f in frames) assertArrayEquals(f.toString(), JacProtocol.encode(f), generic.encode(f))
        // EE FA 02 1F 01, checksum = (EE + FA + 02 + 1F + 01) & FF = 0A.
        assertArrayEquals(hex("EE FA 02 1F 01 0A"), generic.encode(Mcu.pcReady()))
    }

    @Test
    fun genericEngineParsesLikeTheNativeJancarProtocol() {
        val stream =
            hex("01 02 03 04") + JacFrame.encode(0x00, hex("01")) + hex("EE EE") + JacFrame.encode(0x0A, "V1.2 ".toByteArray()) +
                hex("EE FA 02 1F 01 FF")
        val native = mutableListOf<McuFrame>()
        val viaProfile = mutableListOf<McuFrame>()
        JacProtocol.newParser { native += it }.feed(stream)
        val p = generic.newParser { viaProfile += it }
        p.feed(stream)
        assertEquals(native, viaProfile)
        assertEquals(2, viaProfile.size)
        assertEquals(1, p.errors)
    }

    @Test
    fun genericEngineDecodesEvents() {
        assertEquals(McuEvent.Acc(true), generic.decode(McuFrame.of(0x00, 1)))
        assertEquals(McuEvent.Headlight(false), generic.decode(McuFrame.of(0x0B, 0)))
        assertEquals(McuEvent.Date(2026, 10, 5), generic.decode(McuFrame.of(0x09, 0, 20, 26, 10, 5)))
        assertEquals(McuEvent.Time(12, 34, 56), generic.decode(McuFrame.of(0x09, 1, 12, 34, 56)))
        assertEquals(McuEvent.Version("V1.2"), generic.decode(McuFrame(0x0A, "V1.2 ".toByteArray())))
        val ack = generic.decode(McuFrame.of(0xC0, 0x1F, 1, 0))
        assertTrue(ack is McuEvent.Ack && ack.cmd == 0x1F)
        assertTrue(generic.needsAck(Mcu.mute(true)))
        val key = generic.decode(McuFrame.of(0x20, 5, 0x12, 0x34)) as McuEvent.Key
        assertEquals(5, key.channel)
        assertArrayEquals(intArrayOf(0x12, 0x34), key.values)
        assertTrue(generic.decode(McuFrame.of(0x77, 1)) is McuEvent.Other)
    }

    @Test
    fun jsonRoundTrip() {
        val text = McuProfiles.toJson(McuProfiles.JANCAR_JAC_V1)
        assertTrue(text.contains("\"format\": \"librehu-mcu-profile\""))
        assertEquals(McuProfiles.JANCAR_JAC_V1, McuProfiles.fromJson(text))
    }

    /** Hiworld-like framing: `5A A5 LEN CMD D.. CS`, LEN = data count, CS = sum(LEN..data) - 1, no ACK. */
    @Test
    fun otherFramingFromJson() {
        val p =
            McuProfiles.fromJson(
                """
                {
                  "format": "librehu-mcu-profile", "version": 1,
                  "id": "test.hiworld-like", "name": "Test",
                  "serial": { "port": "/dev/ttyS3", "baud": 38400 },
                  "frame": { "header": "5A A5", "lengthOffset": 2, "lengthCounts": "data", "cmdOffset": 3,
                             "checksum": "sum8", "checksumFrom": 2, "checksumAdjust": -1, "maxData": 64 },
                  "inputs": [
                    { "signal": "acc", "cmd": "11", "byte": 0, "mask": "01" },
                    { "signal": "reverse", "cmd": "11", "byte": 0, "mask": "04" }
                  ],
                  "outputs": { "muteOn": "08 01", "time": "09 {hour} {minute}" },
                  "board": { "audioChip": null }
                }
                """.trimIndent(),
            )
        assertEquals(38400, p.serial.baud)
        assertNull(p.board.reverseGpio)
        val proto = McuProfiles.protocolFor(p)
        // 5A A5 01 08 01 CS, CS = (01 + 08 + 01) - 1 = 09
        assertArrayEquals(hex("5A A5 01 08 01 09"), proto.encode(proto.mute(true)!!))
        assertNull(proto.pcReady())
        assertArrayEquals(hex("09 0C 22"), byteArrayOf(proto.time(12, 34, 0)!!.cmd.toByte()) + proto.time(12, 34, 0)!!.data)
        val got = mutableListOf<McuFrame>()
        proto.newParser { got += it }.feed(hex("00 5A A5 01 11 05 16"))
        assertEquals(listOf(McuFrame.of(0x11, 5)), got)
        // First matching input wins: ACC (bit 0).
        assertEquals(McuEvent.Acc(true), proto.decode(got[0]))
    }

    @Test
    fun invalidProfilesAreRejectedWithAReason() {
        fun fails(
            text: String,
            hint: String,
        ) {
            try {
                McuProfiles.fromJson(text)
                fail("accepted: $text")
            } catch (e: ProfileException) {
                assertTrue("${e.message} should mention $hint", e.message!!.contains(hint))
            }
        }
        fails("not json", "Not JSON")
        fails("""{"format":"other"}""", "Not a LibreHU MCU profile")
        fails("""{"format":"librehu-mcu-profile","version":1,"id":"Bad Id"}""", "Invalid id")
        fails("""{"format":"librehu-mcu-profile","version":1,"id":"ok-id"}""", "frame")
        val base = McuProfiles.toJson(McuProfiles.JANCAR_JAC_V1).replace("\"id\": \"jancar-jac-v1\"", "\"id\": \"copy\"")
        fails(base.replace("\"muteOn\"", "\"explode\""), "Unknown output")
        fails(base.replace("\"checksum\": \"sum8\"", "\"checksum\": \"crc32\""), "frame.checksum")
    }
}
