package org.librehu.core.mcu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The built-in KSW profile against VincentZ4's « KSW API – Commands » v2.12 (its framed examples), McuCommunicator
 * (KswCarProject) and KSW's own centre service (KswCarProject/KswCenterService): frames, events, heartbeat, clock.
 */
class KswProfileTest {
    private val ksw = McuProfiles.protocolFor(McuProfiles.KSW_PX6) as ProfileProtocol

    private fun hex(s: String) = McuProfiles.parseHex(s).map { it.toByte() }.toByteArray()

    @Test
    fun encodesTheExamplesOfTheKswApi() {
        // « Shut off the HU Screen »: checksum 0x86 = (00 + 6A + 02 + 0D + 00) xor FF.
        assertArrayEquals(hex("F2 00 6A 02 0D 00 86"), ksw.encode(McuFrame.of(0x6A, 0x0D, 0x00)))
        // A/C temperature +1 and -1.
        assertArrayEquals(hex("F2 00 60 01 01 9D"), ksw.encode(McuFrame.of(0x60, 0x01)))
        assertArrayEquals(hex("F2 00 61 02 01 81 1A"), ksw.encode(McuFrame.of(0x61, 0x01, 0x81)))
        assertArrayEquals(hex("F2 00 60 01 01 9D"), ksw.encode(ksw.command("A/C temperature +")!!))
        assertArrayEquals(hex("F2 00 61 02 01 81 1A"), ksw.encode(ksw.command("A/C temperature -")!!))
    }

    @Test
    fun parsesTheFramesOfTheMcu() {
        val version = "615043bALS-CIC-HW7-200504 B1280".toByteArray() + ByteArray(9)
        val stream =
            hex("00 F2 F2") + ksw.encode(McuFrame.of(0x10, 1)) + hex("F2 00 A1 02 10 08 00") +
                ksw.encode(McuFrame(0x12, version)) + ksw.encode(McuFrame.of(0xA1, 0x17, 0x05, 0x01))
        val got = mutableListOf<McuFrame>()
        val parser = ksw.newParser { got += it }
        parser.feed(stream)
        assertEquals(listOf(McuFrame.of(0x10, 1), McuFrame(0x12, version), McuFrame.of(0xA1, 0x17, 0x05, 0x01)), got)
        assertEquals(1, parser.errors) // the A1 10 frame with a wrong checksum
    }

    @Test
    fun decodesTheEventsOfTheMcu() {
        assertEquals(McuEvent.Acc(true), ksw.decode(McuFrame.of(0x10, 1)))
        assertEquals(McuEvent.Acc(false), ksw.decode(McuFrame.of(0x10, 0)))
        assertEquals(McuEvent.Acc(true), ksw.decode(McuFrame.of(0x11, 0x05, 1)))
        assertEquals(McuEvent.Reverse(true), ksw.decode(McuFrame.of(0x11, 0x01, 1)))
        assertEquals(McuEvent.Reverse(false), ksw.decode(McuFrame.of(0x11, 0x01, 0)))
        assertTrue(ksw.decode(McuFrame.of(0x11, 0x03, 1)) is McuEvent.Other) // parking radar view
        val version = "615043bALS-CIC-HW7-200504 B1280".toByteArray() + ByteArray(9)
        assertEquals(McuEvent.Version("615043bALS-CIC-HW7-200504 B1280"), ksw.decode(McuFrame(0x12, version)))
        // A1 10: handbrake (D1 bit 3) read here, and the whole frame copied to the CAN engine.
        val brake = McuFrame.of(0xA1, 0x10, 0x08, 0x00)
        assertEquals(McuEvent.Handbrake(true), ksw.decode(brake))
        assertArrayEquals(ksw.encode(brake), ksw.canCopy(brake))
        val key = McuFrame.of(0xA1, 0x17, 0x05, 0x01)
        assertTrue(ksw.decode(key) is McuEvent.Other)
        assertArrayEquals(hex("F2 00 A1 03 17 05 01 3E"), ksw.canCopy(key))
        assertNull(ksw.canCopy(McuFrame.of(0x10, 1)))
    }

    @Test
    fun heartbeatHandshakeAndClock() {
        assertEquals(McuFrame.of(0x68, 0x08, 0x00), ksw.beat())
        assertEquals(1000L, ksw.beatMs)
        assertEquals(McuFrame.of(0x61, 0x00), ksw.pcReady())
        assertNull(ksw.queryClock())
        assertTrue(!ksw.readsClock && JacProtocol.readsClock && ProfileProtocol(McuProfiles.JANCAR_JAC_V1).readsClock)
        // No date frame: the date given goes into the time frame, 64 YY MM DD hh mm ss 00 (24 h).
        assertNull(ksw.date(2026, 11, 8))
        assertEquals(McuFrame.of(0x64, 26, 11, 8, 13, 45, 30, 0), ksw.time(13, 45, 30))
        assertNull(ksw.mute(true))
        assertNull(ksw.canData(hex("01 02")))
    }

    @Test
    fun commandsAndJson() {
        assertEquals(McuFrame.of(0x69, 0x12, 0x02), ksw.command("Screen: car (OEM)"))
        assertEquals(McuFrame.of(0x69, 0x12, 0x01), ksw.command("Screen: Android"))
        assertEquals(McuFrame.of(0x69, 0x13, 0x01, 0x00), ksw.command("Voice control on"))
        assertNull(ksw.command("Upgrade mode"))
        val text = McuProfiles.toJson(McuProfiles.KSW_PX6)
        assertTrue(text.contains("\"byte\": -1") && text.contains("\"beatMs\": 1000") && text.contains("\"Screen on\": \"6C 02 01\""))
        assertEquals(McuProfiles.KSW_PX6, McuProfiles.fromJson(text))
        assertTrue(McuProfiles.BUILT_IN.any { it.id == "ksw-px6" })
    }

    @Test
    fun wholeFrameOnlyForCanInputsAndBeatNeedsAnOutput() {
        val base = McuProfiles.toJson(McuProfiles.KSW_PX6).replace("\"id\": \"ksw-px6\"", "\"id\": \"copy\"")
        fun fails(
            text: String,
            hint: String,
        ) {
            try {
                McuProfiles.fromJson(text)
                fail("accepted")
            } catch (e: ProfileException) {
                assertTrue("${e.message} should mention $hint", e.message!!.contains(hint))
            }
        }
        fails(base.replace("\"signal\": \"version\",", "\"signal\": \"version\", \"byte\": -1,"), "inputs.byte")
        fails(base.replace("\"beat\": \"68 08 00\"", "\"muteOn\": \"08 01\""), "beatMs")
        fails(base.replace("\"beatMs\": 1000", "\"beatMs\": 5"), "beatMs")
        fails(base.replace("\"Screen on\": \"6C 02 01\"", "\"Screen on\": \"6C 0G\""), "Bad command")
    }
}
