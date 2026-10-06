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
    fun ignoresOtherMessagesAndShortFrames() {
        val got = mutableListOf<Int>()
        val keys = HiworldWheelKeys { got += it }
        keys.onFrame(CanboxFrame(0x12, byteArrayOf(0, 8, 1, 1), true))
        keys.onFrame(CanboxFrame(0x11, byteArrayOf(0, 8), true))
        assertEquals(emptyList<Int>(), got)
    }
}
