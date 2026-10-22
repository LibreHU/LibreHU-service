package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Test

class HiworldWheelKeysTest {
    private fun state(
        d1: Int,
        key: Int,
        d3: Int,
    ) = CanboxFrame(0x11, byteArrayOf(0x61, d1.toByte(), key.toByte(), d3.toByte(), 8, 2, 0, 0, 0x22, 0x17), true)

    @Test
    fun clio3Captures() {
        val got = mutableListOf<Int>()
        val keys = HiworldWheelKeys { got += it }
        // Idle, one press of volume + (code 1), then volume - held (two repeats), then key 0x11.
        val seq =
            listOf(
                state(0, 0, 0),
                state(8, 1, 1),
                state(8, 1, 0),
                state(8, 0, 0),
                state(0, 0, 0),
                state(8, 2, 1),
                state(8, 2, 0),
                state(8, 0, 0),
                state(8, 2, 1),
                state(8, 2, 0),
                state(8, 0, 0),
                state(0, 0, 0),
                state(8, 0x11, 1),
                state(8, 0x11, 1),
                state(8, 0x11, 0),
                state(0, 0, 0),
            )
        seq.forEach(keys::onFrame)
        assertEquals(listOf(1, 2, 2, 0x11), got)
    }

    @Test
    fun wholeByteIsTheCode() {
        val got = mutableListOf<Int>()
        val raw = mutableListOf<Triple<Int, Int, Int>>()
        val keys = HiworldWheelKeys({ a, b, c -> raw += Triple(a, b, c) }) { got += it }
        // Two buttons whose codes only differ above 0x1F (were both 4 with the old mask)
        listOf(state(8, 0x04, 1), state(8, 0x04, 0), state(0, 0, 0), state(8, 0x24, 1), state(8, 0x24, 0), state(0, 0, 0))
            .forEach(keys::onFrame)
        assertEquals(listOf(0x04, 0x24), got)
        assertEquals(Triple(8, 0x24, 1), raw[3])
    }

    @Test
    fun ignoresOtherMessagesAndShortFrames() {
        val got = mutableListOf<Int>()
        val keys = HiworldWheelKeys { got += it }
        keys.onFrame(CanboxFrame(0x12, byteArrayOf(0, 8, 1, 1), true))
        keys.onFrame(CanboxFrame(0x11, byteArrayOf(0, 8), true))
        assertEquals(emptyList<Int>(), got)
    }

    @Test
    fun releaseFollowsEachPress() {
        val events = mutableListOf<String>()
        val keys = HiworldWheelKeys(null, { events += "up$it" }) { events += "down$it" }
        listOf(state(8, 1, 1), state(8, 1, 0), state(8, 0, 0), state(8, 2, 1), state(8, 3, 1), state(0, 0, 0)).forEach(keys::onFrame)
        assertEquals(listOf("down1", "up1", "down2", "up2", "down3", "up3"), events)
    }
}
