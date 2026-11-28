package org.librehu.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Bd37534Test {
    private val writes = mutableListOf<Pair<Int, Int>>()
    private val chip =
        Bd37534({ r, v ->
            writes += r to v
            true
        }, sleep = {})

    @Test
    fun encodings() {
        assertEquals(0x80, Bd37534.attenuator(0))
        assertEquals(0xCF, Bd37534.attenuator(-79))
        assertEquals(0x71, Bd37534.attenuator(15))
        assertEquals(0x71, Bd37534.attenuator(40)) // clamped
        assertEquals(0x00, Bd37534.tone(10))
        assertEquals(0x94, Bd37534.tone(0)) // -20 dB
        assertEquals(0x14, Bd37534.tone(20)) // +20 dB
        assertEquals(41, Bd37534.VOLUME_CURVE.size)
    }

    @Test
    fun initFollowsJancarSequence() {
        chip.init()
        val first = writes.take(9)
        assertEquals(
            listOf(
                0x01 to 0xB7,
                0x02 to 0x00,
                0x03 to 0x11,
                0x41 to 0x11,
                0x44 to 0x21,
                0x47 to 0x20,
                0x30 to 0xFF,
                0x2C to 0x00,
                0x02 to 0x00,
            ),
            first,
        )
        // Android input selected, then soft mute on.
        assertEquals(0x8B, chip.shadow[Bd37534.INPUT_SELECT])
        assertEquals(0x80, chip.shadow[Bd37534.INPUT_GAIN])
    }

    @Test
    fun volumeUsesCurve() {
        chip.setVolume(0)
        assertEquals(0x20 to 0xCF, writes.last())
        chip.setVolume(40)
        assertEquals(0x20 to 0x7B, writes.last()) // +5 dB
    }

    @Test
    fun balanceAndFade() {
        assertArrayEquals(intArrayOf(1, 1, 1, 1), Bd37534.speakerLevels(30, 30))
        // Full left: right speakers cut.
        assertArrayEquals(intArrayOf(1, -79, 1, -79), Bd37534.speakerLevels(0, 30))
        // Full front: rear speakers cut.
        assertArrayEquals(intArrayOf(1, 1, -79, -79), Bd37534.speakerLevels(30, 0))
        // 10 steps right: left speakers at index 20 = -9 dB.
        assertArrayEquals(intArrayOf(-9, 1, -9, 1), Bd37534.speakerLevels(40, 30))
    }

    @Test
    fun subwooferOnOff() {
        chip.setSubwoofer(true, 6)
        assertEquals(listOf(0x02 to 0x03, 0x2C to 0x7F), writes) // 120 Hz, -5 + 6 = +1 dB
        writes.clear()
        chip.setSubwoofer(false, 6)
        assertEquals(listOf(0x2C to 0x00, 0x02 to 0x00), writes)
    }

    @Test
    fun subwooferCutoffPhaseAndOutput() {
        assertEquals(0, Bd37534.subFcIndex(0))
        assertEquals(1, Bd37534.subFcIndex(55))
        assertEquals(4, Bd37534.subFcIndex(160))
        assertEquals(0, Bd37534.subFcIndex(100)) // not a cut-off of the chip: filter off
        // Datasheet: phase bit 7, output select bits 5..4, fc bits 2..0.
        assertEquals(0x92, Bd37534.setup2(Bd37534.subFcIndex(85), phase = true, output = Bd37534.SUB_OUTPUT_FRONT))
        assertEquals(0x24, Bd37534.setup2(Bd37534.subFcIndex(160), output = Bd37534.SUB_OUTPUT_REAR))
        chip.setSubwoofer(true, 6, SubConfig(cutoffHz = 85, phase180 = true))
        assertEquals(listOf(0x02 to 0x82, 0x2C to 0x7F), writes)
    }

    @Test
    fun toneInDbAndSetup() {
        assertEquals(0x94, Bd37534.toneDb(-20))
        assertEquals(0x81, Bd37534.toneDb(-1))
        assertEquals(0x07, Bd37534.toneDb(7))
        assertEquals(Bd37534.tone(14), Bd37534.toneDb(8))
        // Jancar's setup = the defaults.
        chip.setToneSetup(ToneSetup())
        assertEquals(listOf(0x41 to 0x11, 0x44 to 0x21, 0x47 to 0x20), writes)
        writes.clear()
        chip.setToneSetup(ToneSetup(120, 200, 2500, 150, 15000, 125))
        assertEquals(listOf(0x41 to 0x33, 0x44 to 0x33, 0x47 to 0x31), writes)
        writes.clear()
        chip.setToneSetup(ToneSetup(70, 30, 900, 1, 9000, 0)) // unknown values: Jancar's
        assertEquals(listOf(0x41 to 0x11, 0x44 to 0x21, 0x47 to 0x20), writes)
    }

    @Test
    fun loudnessFrequencyAndHighCut() {
        assertEquals(0x01, Bd37534.setup3(250))
        assertEquals(0x09, Bd37534.setup3(400))
        assertEquals(0x11, Bd37534.setup3(800)) // the init value
        assertEquals(0x74, Bd37534.loudness(20, 4))
        assertEquals(0x05, Bd37534.loudness(5, 1))
        chip.setLoudness(12, 400, 3)
        assertEquals(listOf(0x03 to 0x09, 0x75 to 0x4C), writes)
    }

    @Test
    fun speakerTrims() {
        // Centre: every speaker at +1 dB (Jancar's table), then its trim; a cut speaker stays cut.
        chip.setBalanceFade(30, 30, intArrayOf(-3, 0, 2, 0))
        assertEquals(listOf(0x28 to 0x82, 0x29 to 0x7F, 0x2A to 0x7D, 0x2B to 0x7F), writes)
        writes.clear()
        chip.setBalanceFade(0, 30, intArrayOf(0, 6, 0, 6))
        assertEquals(0x29 to Bd37534.attenuator(-79), writes[1])
    }
}
