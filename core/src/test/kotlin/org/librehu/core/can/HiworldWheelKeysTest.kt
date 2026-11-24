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

    /** Raise box on a Clio 4 (capture 2026-11-24): 20 kk 01 press, 20 kk 02 held every ~240 ms, 20 kk 00 release. */
    @Test
    fun raiseClio4Repeat() {
        val spec =
            WheelKeySpec(
                cmd = 0x20,
                codeByte = 0,
                stateByte = 1,
                pressed = setOf(1, 2),
                repeat = mapOf(1 to setOf(2), 2 to setOf(2), 3 to setOf(1), 4 to setOf(1)),
            )
        val got = mutableListOf<Int>()
        val ups = mutableListOf<Int>()
        val keys = HiworldWheelKeys(null, { ups += it }, spec) { got += it }

        fun f(
            k: Int,
            s: Int,
        ) = CanboxFrame(0x20, byteArrayOf(k.toByte(), s.toByte()), true)
        listOf(
            // mute tap (one 02 in it: no repeat), wheel up x3 (no release), wheel down x1, volume + held, source held
            f(6, 1), f(6, 2), f(6, 0),
            f(4, 1), f(4, 1), f(4, 1),
            f(3, 1),
            f(1, 1), f(1, 2), f(1, 2), f(1, 2), f(1, 0),
            f(7, 1), f(7, 2), f(7, 2), f(7, 2), f(7, 0),
        ).forEach(keys::onFrame)
        assertEquals(listOf(6, 4, 4, 4, 3, 1, 1, 1, 1, 7), got)
        assertEquals(listOf(6, 4, 3, 1, 7), ups)
    }

    @Test
    fun repeatFromProfile() {
        val v =
            CanVehicles.fromJson(
                """{"format":"librehu-can-vehicle","version":2,"id":"test-raise","box":"raise",
                   "wheel_keys":{"cmd":"0x20","code_byte":0,"state_byte":1,"pressed":[1,2],"repeat":{"1":[2],"4":[1]}},"messages":{}}""",
            )
        assertEquals(mapOf(1 to setOf(2), 4 to setOf(1)), v.keys?.repeat)
    }
}
