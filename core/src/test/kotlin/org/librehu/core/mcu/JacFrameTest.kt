package org.librehu.core.mcu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JacFrameTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun encodesDocumentedFrames() {
        // Examples of mcu_firmware.md §6.1.
        assertEquals("EE FA 02 1F 01 0A", Mcu.pcReady().encode().toHex())
        assertEquals("EE FA 03 F0 00 00 DB", Mcu.query(Mcu.CMD_ACC).encode().toHex())
        assertEquals("EE FA 03 F0 0B 00 E6", Mcu.query(Mcu.CMD_HEADLIGHT).encode().toHex())
    }

    @Test
    fun parsesFramesAcrossGarbageAndSplitReads() {
        val got = mutableListOf<McuFrame>()
        val p = JacParser { got += it }
        // Raw boot bytes, then ACC on, then an ACK split in two reads.
        p.feed(hex("01 02 03 04"))
        p.feed(McuFrame.of(0x00, 1).encode())
        val ack = McuFrame.of(0xC0, 0x1F, 0x01).encode()
        p.feed(ack, 0, 3)
        p.feed(ack, 3, ack.size - 3)
        assertEquals(listOf(McuFrame.of(0x00, 1), McuFrame.of(0xC0, 0x1F, 0x01)), got)
        assertEquals(0, p.errors)
    }

    @Test
    fun dropsBadChecksumAndResynchronises() {
        val got = mutableListOf<McuFrame>()
        val p = JacParser { got += it }
        val bad = McuFrame.of(0x04, 1).encode().also { it[it.size - 1] = 0 }
        p.feed(bad + McuFrame.of(0x0B, 1).encode())
        assertEquals(listOf(McuFrame.of(0x0B, 1)), got)
        assertEquals(1, p.errors)
    }

    @Test
    fun acceptsLongCanFrames() {
        // 128 data bytes: LEN = 0x81, above the signed byte range that crashes ivi-services.
        val payload = ByteArray(128) { (it * 7).toByte() }
        val got = mutableListOf<McuFrame>()
        JacParser { got += it }.feed(McuFrame(Mcu.CMD_CAN, payload).encode())
        assertEquals(1, got.size)
        assertTrue(got[0].data.contentEquals(payload))
    }

    @Test
    fun decodesEvents() {
        assertEquals(McuEvent.Acc(true), McuEvent.decode(McuFrame.of(0x00, 1)))
        assertEquals(McuEvent.Handbrake(false), McuEvent.decode(McuFrame.of(0x04, 0)))
        assertEquals(McuEvent.Date(2025, 7, 18), McuEvent.decode(McuFrame.of(0x09, 0, 20, 25, 7, 18)))
        assertEquals(McuEvent.Time(4, 29, 5), McuEvent.decode(McuFrame.of(0x09, 1, 4, 29, 5)))
        val version = "JCST_AC8257_8T7-2024.08.09_12:59".padEnd(32)
        assertEquals(
            McuEvent.Version("JCST_AC8257_8T7-2024.08.09_12:59"),
            McuEvent.decode(McuFrame(0x0A, version.toByteArray())),
        )
        val press = McuEvent.decode(McuFrame.of(0x20, 5, 0xAA, 0x30, 0x60, 0x90)) as McuEvent.Key
        assertEquals(5, press.channel)
        assertTrue(!press.released)
        val release = McuEvent.decode(McuFrame.of(0x20, 5, 0xAA, 0xFF, 0xFF, 0xFF)) as McuEvent.Key
        assertTrue(release.released)
    }

    @Test
    fun encodesClockAndSleepTimer() {
        assertEquals("09 00 14 19 0C 1F", Mcu.date(2025, 12, 31).toString())
        assertEquals("09 01 17 3B 00", Mcu.time(23, 59, 0).toString())
        assertEquals("44 01", Mcu.externalAmp(true).toString())
    }
}
