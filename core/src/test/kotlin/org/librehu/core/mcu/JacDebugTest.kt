package org.librehu.core.mcu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JacDebugTest {
    @Test
    fun audioChipFromBoardId() {
        assertEquals("ROHM BD37534", JacDebug.audioChip("XYZA01"))
        assertEquals("AKM AK7604", JacDebug.audioChip("A1xD"))
        assertEquals("ROHM BU32107", JacDebug.audioChip("B2xD"))
        assertNull(JacDebug.audioChip("AB"))
        assertNull(JacDebug.audioChip(null))
    }

    @Test
    fun risks() {
        assertEquals(JacDebug.Risk.BLOCKED, JacDebug.risk(0x80))
        assertEquals(JacDebug.Risk.CONFIRM, JacDebug.risk(0x01))
        assertEquals(JacDebug.Risk.CONFIRM, JacDebug.risk(0x0E))
        assertEquals(JacDebug.Risk.CONFIRM, JacDebug.risk(0xF1))
        assertEquals(JacDebug.Risk.NONE, JacDebug.risk(0x08))
    }

    @Test
    fun describesFrames() {
        assertEquals("QUERY  ? version", JacDebug.describe(McuFrame.of(0xF0, 0x0A, 0), false))
        assertEquals("CONFIG  high threshold = 16.5 V", JacDebug.describe(JacDebug.highVoltage(1), false))
        assertEquals("CONFIG  USART1 baud = 38400", JacDebug.describe(JacDebug.canBaud(2), false))
        assertEquals("CONFIG  sub-command 0C (ignored)", JacDebug.describe(McuFrame.of(0x0F, 0x0C, 1), false))
        assertEquals("VERSION  '${JacDebug.SIM_VERSION}'", JacDebug.describe(JacDebug.simVersion(), true))
        assertEquals("RTC  time 12:34:56", JacDebug.describe(McuFrame.of(0x09, 1, 12, 34, 56), true))
        assertEquals("KEY  channel 5  adc 30 30 30", JacDebug.describe(JacDebug.simWheelKey(5, true), true))
        assertEquals("KEY  channel 6  released", JacDebug.describe(JacDebug.simWheelKey(6, false), true))
        assertEquals("ACK  of 1F", JacDebug.describe(McuFrame.of(0xC0, 0x1F, 1, 0), true))
        assertEquals("LIGHTS  1", JacDebug.describe(McuFrame.of(0x0B, 1), true))
        assertEquals("LEARN_WHEEL  start", JacDebug.describe(JacDebug.learn(panel = false, start = true), false))
    }

    @Test
    fun commandsMatchMcuTools() {
        assertEquals(McuFrame.of(0x0F, 0x04, 1, 10, 20, 99, 2), JacDebug.led(10, 20, 150, JacDebug.LED_MANUAL))
        assertEquals(McuFrame.of(0x0F, 0x03, 5), JacDebug.pwm(2, 0))
        assertEquals(McuFrame.of(0x21, 3), JacDebug.learn(panel = true, start = false))
        val all = JacDebug.queryAll()
        assertEquals(9, all.size)
        assertEquals(McuFrame.of(0xF0, 0x0F, 0x07), all.last())
    }

    @Test
    fun debugStateFollowsFrames() {
        var s = McuDebugState()

        fun rx(
            f: McuFrame,
            sim: Boolean = false,
        ) {
            s = s.onReceived(f, McuEvent.decode(f), jancar = true, simulated = sim)
        }
        rx(McuFrame.of(0x00, 1))
        rx(McuFrame.of(0x08, 1))
        rx(McuFrame.of(0x43, 0))
        rx(McuFrame.of(0x0F, 0x07, 3))
        rx(McuFrame.of(0x09, 0, 20, 26, 10, 6))
        rx(McuFrame.of(0xC0, 0x08, 1, 0))
        rx(JacDebug.simWheelKey(5, true), sim = true)
        assertEquals(true, s.acc)
        assertEquals(true, s.mute)
        assertEquals(false, s.antenna)
        assertEquals(3, s.option)
        assertEquals("2026-10-06", s.date)
        assertEquals(1, s.acks)
        assertEquals(6, s.received)
        assertEquals(1, s.simulated)
        assertTrue(s.lastKey.contains("channel 5"))
        assertNull(s.handbrake)
    }
}
