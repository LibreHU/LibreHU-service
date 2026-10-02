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
}
