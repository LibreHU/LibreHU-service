package org.librehu.core.can

/** One message of the CAN box (Hiworld framing), [data] = D0..Dn-1. */
class CanboxFrame(
    val cmd: Int,
    val data: ByteArray,
    val checksumOk: Boolean,
) {
    fun u(i: Int): Int = if (i in data.indices) data[i].toInt() and 0xFF else 0

    fun hex(): String = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    override fun toString() = "%02X: %s%s".format(cmd, hex(), if (checksumOk) "" else " (bad checksum)")
}

/**
 * Rebuilds the CAN box messages from the bytes relayed by the MCU (frames `10`, cut every ~128 bytes / 20 ms, so a
 * message can span two chunks). Hiworld framing (ivi-canbus, docs/ivi-services/12-canbus.md):
 * `5A A5 LEN CMD D0..Dn-1 CS`, LEN = n, CS = ((LEN + CMD + ΣD) & 0xFF) - 1; the same with the header `AA 55` for
 * Hiworld's other family ([head1], [head2]).
 */
class HiworldParser(
    private val head1: Int = 0x5A,
    private val head2: Int = 0xA5,
    private val onFrame: (CanboxFrame) -> Unit,
) : CanBoxParser {
    private var state = 0
    private var len = 0
    private var cmd = 0
    private var buf = ByteArray(0)
    private var pos = 0

    override var skipped = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun feed(v: Int) {
        when (state) {
            0 -> {
                if (v == head1) state = 1 else skipped++
            }

            1 -> {
                state =
                    if (v == head2) {
                        2
                    } else if (v == head1) {
                        1
                    } else {
                        0.also { skipped += 2 }
                    }
            }

            2 -> {
                len = v
                state = 3
            }

            3 -> {
                cmd = v
                buf = ByteArray(len)
                pos = 0
                state = if (len == 0) 5 else 4
            }

            4 -> {
                buf[pos++] = v.toByte()
                if (pos == len) state = 5
            }

            5 -> {
                val sum = len + cmd + buf.sumOf { it.toInt() and 0xFF }
                val expected = ((sum and 0xFF) - 1) and 0xFF
                onFrame(CanboxFrame(cmd, buf, v == expected))
                state = 0
            }
        }
    }

    companion object {
        /** Builds a message (tests, sending to the box). */
        fun encode(
            cmd: Int,
            data: ByteArray,
            head1: Int = 0x5A,
            head2: Int = 0xA5,
        ): ByteArray {
            val sum = data.size + cmd + data.sumOf { it.toInt() and 0xFF }
            return byteArrayOf(head1.toByte(), head2.toByte(), data.size.toByte(), cmd.toByte()) + data + (((sum and 0xFF) - 1) and 0xFF).toByte()
        }
    }
}
