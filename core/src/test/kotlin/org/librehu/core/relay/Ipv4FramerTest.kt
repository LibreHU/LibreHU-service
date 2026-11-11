package org.librehu.core.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class Ipv4FramerTest {
    private val a = Packets.udp(1, 2, 3, 4, "first".toByteArray())
    private val b = Packets.tcp(5, 6, 7, 8, 1, 2, Packets.ACK, ByteArray(3000) { it.toByte() })

    private fun cut(
        stream: ByteArray,
        sizes: List<Int>,
    ): List<ByteArray> {
        val f = Ipv4Framer()
        val out = ArrayList<ByteArray>()
        var off = 0
        for (n in sizes) {
            f.feed(stream.copyOfRange(off, off + n)) { p, o, l -> out += p.copyOfRange(o, o + l) }
            off += n
        }
        return out
    }

    @Test
    fun packetsSplitAndJoinedAnyhowComeOutWhole() {
        val stream = a + b + a
        for (sizes in listOf(listOf(stream.size), listOf(1, 19, 13, stream.size - 33), (0 until stream.size).map { 1 })) {
            val out = cut(stream, sizes)
            assertEquals(3, out.size)
            assertArrayEquals(a, out[0])
            assertArrayEquals(b, out[1])
            assertArrayEquals(a, out[2])
        }
    }

    @Test(expected = IOException::class)
    fun notIpv4IsAnError() {
        Ipv4Framer().feed(ByteArray(40) { 0x60 }) { _, _, _ -> }
    }
}
