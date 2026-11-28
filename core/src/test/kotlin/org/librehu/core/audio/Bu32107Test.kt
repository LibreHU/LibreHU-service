package org.librehu.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bu32107Test {
    private val writes = mutableListOf<Pair<Int, List<Int>>>()
    private val chip =
        Bu32107({ address, data ->
            writes += address to data.map { it.toInt() and 0xFF }
            true
        }, sleep = {})

    private fun reg(address: Int) = chip.shadow[address]

    @Test
    fun datasheetExamples() {
        // "DVol(Att)=-23.5dB, Send data(hex)=6F", "DVol(Boost)=36dB, Send data=38", "Fader Volume=-39dB, 47 (+ bit 7)".
        assertEquals(0x6F, Bu32107.dvolAtt(-23.5))
        assertEquals(0x38, Bu32107.dvolBoost(36))
        assertEquals(0x80 or 0x47, Bu32107.fader(-39))
        // "AVol=-23dB, Send data(hex)=37" (+ bit 7), "Time Alignment Time=2.5ms, Send data(hex)=78".
        assertEquals(0x80 or 0x37, Bu32107.avol(-23))
        assertEquals(0x78, Bu32107.delayData(25))
        // Ends of the ranges: 0 dB, -95.5 dB, -inf.
        assertEquals(0x40, Bu32107.dvolAtt(0.0))
        assertEquals(0xFF, Bu32107.dvolAtt(-95.5))
        assertEquals(0x00, Bu32107.dvolAtt(Double.NEGATIVE_INFINITY))
        assertEquals(0xA0, Bu32107.fader(0))
        assertEquals(0xEF, Bu32107.fader(-79))
        assertEquals(0x80, Bu32107.fader(-80))
        assertEquals(0x3FF, Bu32107.delayData(Bu32107.DELAY_MAX_TENTHS))
    }

    @Test
    fun initFollowsTheDatasheetOrder() {
        assertTrue(chip.init())
        // Reset first, RAM clear set then released, faders last and muted.
        assertEquals(Bu32107.SYSTEM_RESET to listOf(0x81), writes.first())
        val setup1 = writes.filter { it.first == 0x0001 }.map { it.second.first() }
        assertEquals(listOf(0xCC, 0x0C), setup1)
        assertEquals(Bu32107.FADER, writes.last().first)
        assertEquals(List(6) { 0x80 }, writes.last().second)
        assertTrue(chip.isMuted)
        // Values the datasheet imposes (Test A..F) and the board's routing (Android on Digital Input1 → DSP).
        assertEquals(listOf(0x00, 0xC0, 0x00, 0xC0, 0x34, 0x05), (0x001D..0x0022).map { reg(it) })
        assertEquals(0x08, reg(0x0202))
        assertEquals(0x80, reg(Bu32107.DSP_SELECTOR1))
        // Table mode everywhere: no "Direct Coef Set" bit, no coefficient address written.
        assertEquals(0, (reg(0x0703) ?: 0) and 0x80)
        assertTrue(writes.none { it.first in 0x1000..0x14FF })
    }

    @Test
    fun volumeMuteAndSpeakers() {
        chip.init()
        chip.setVolume(40, 0)
        assertEquals(0x40, reg(Bu32107.DVOL_ATT))
        chip.setVolume(0, 0)
        assertEquals(0x00, reg(Bu32107.DVOL_ATT + 5))
        chip.setBalanceFade(30, 30, intArrayOf(-3, 0, 4, 0))
        chip.setMute(false)
        assertEquals(Bu32107.fader(-3), reg(Bu32107.FADER))
        assertEquals(0xA0, reg(Bu32107.FADER + 1))
        // A speaker raised above 0 dB: fader at 0 dB, the rest by its digital boost.
        assertEquals(0xA0, reg(Bu32107.FADER + 2))
        assertEquals(Bu32107.dvolBoost(4), reg(Bu32107.DVOL_BOOST + 2))
        chip.setMute(true)
        assertEquals(List(6) { 0x80 }, (0..5).map { reg(Bu32107.FADER + it) })
    }

    @Test
    fun subwooferOnSlAndSr() {
        chip.init()
        chip.setMute(false)
        chip.setSubwoofer(true, 12, SubConfig(phase180 = true))
        // Level 12 = +7 dB: faders at 0 dB, +7 dB of boost; low-pass phase 180°.
        assertEquals(0xA0, reg(Bu32107.FADER + 4))
        assertEquals(0xA0, reg(Bu32107.FADER + 5))
        assertEquals(Bu32107.dvolBoost(7), reg(Bu32107.DVOL_BOOST + 4))
        assertEquals(0x40, (reg(0x0707) ?: 0) and 0x40)
        chip.setSubwoofer(false, 12, SubConfig())
        assertEquals(0x80, reg(Bu32107.FADER + 4))
    }

    @Test
    fun auxAndMixing() {
        chip.init()
        chip.selectAux()
        assertEquals(0x04, reg(Bu32107.ANALOG_INPUT))
        assertEquals(5, reg(Bu32107.INPUT_GAIN))
        assertEquals(0x00, reg(Bu32107.DSP_SELECTOR1))
        chip.setMixing(40, 0)
        assertEquals(0x80, reg(0x0202))
        assertEquals(0xA0, reg(0x010A))
        chip.selectAndroid(0)
        chip.setMixing(null, 0)
        assertEquals(0x08, reg(0x0202))
        assertEquals(0x80, reg(0x010A))
    }

    @Test
    fun eqToneAndFilters() {
        chip.init()
        chip.applySettings(
            Bu32107Settings(
                eqFront = listOf(24, -2) + List(11) { 0 },
                eqNarrow = true,
                hpfFront = 80,
                hpfFrontSteep = true,
                subLpf = 63,
                p2BassFrontDb = 6,
                p2BassFrontHz = 86,
                delays = listOf(25, 0, 0, 0, 0),
            ),
        )
        // +24 dB: gain 12 steps, Q 4.7, front and rear together; -2 dB: cut bit.
        assertEquals(0x4C, reg(Bu32107.EQ_FRONT))
        assertEquals(0x51, reg(Bu32107.EQ_FRONT + 1))
        // 80 Hz = index 6, 4th order.
        assertEquals(0x26, reg(0x0703))
        assertEquals(0x80 or (3 shl 4) or 6, reg(0x0705))
        assertEquals(0x10 or 5, reg(0x0707))
        assertEquals(0x78, reg(0x0401))
        chip.setToneDb(-12, 0, 6)
        // Bass -12 dB at 100 Hz (index 2): cut, 6 steps, common bit.
        assertEquals(0x40 or (2 shl 4) or 0x08 or 6, reg(Bu32107.TONE_FRONT))
    }

    @Test
    fun separateRearEqWritesRearRegisters() {
        chip.init()
        chip.applySettings(Bu32107Settings(eqRearSame = false, eqRear = List(13) { 4 }))
        assertEquals(0, (reg(Bu32107.EQ_FRONT) ?: 0) and 0x40)
        assertEquals(0x22, reg(Bu32107.EQ_REAR))
    }

    @Test
    fun settingsRoundTrip() {
        val s = Bu32107Settings(eqFront = List(13) { 2 * (it - 6) }, hpfRear = 100, delays = listOf(1, 2, 3, 4, 213))
        assertEquals(s.normalized(), Bu32107Settings.decode(s.encode()))
        assertEquals(Bu32107Settings(), Bu32107Settings.decode("garbage"))
    }
}
