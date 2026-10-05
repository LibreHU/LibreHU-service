package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiworldTest {
    @Test
    fun framesAcrossChunks() {
        val got = mutableListOf<CanboxFrame>()
        val p = HiworldParser { got += it }
        val a = HiworldParser.encode(0x12, byteArrayOf(0, 0, 0x88.toByte(), 0))
        val b = HiworldParser.encode(0xF0, "H1N5".toByteArray())
        val stream = byteArrayOf(1, 2) + a + b
        p.feed(stream.copyOfRange(0, 5))
        p.feed(stream.copyOfRange(5, stream.size))
        assertEquals(2, got.size)
        assertTrue(got.all { it.checksumOk })
        assertEquals(0x12, got[0].cmd)
        assertEquals("portes ouvertes : AVG, coffre", HiworldRenault.describe(got[0]))
        assertEquals("version : H1N5", HiworldRenault.describe(got[1]))
        assertEquals(2, p.skipped)
    }

    @Test
    fun badChecksumIsFlagged() {
        val got = mutableListOf<CanboxFrame>()
        val bytes = HiworldParser.encode(0x31, ByteArray(12))
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        HiworldParser { got += it }.feed(bytes)
        assertEquals(false, got.single().checksumOk)
    }
}
