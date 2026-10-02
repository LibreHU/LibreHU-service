package org.librehu.core.mcu

/**
 * Frame of the Jancar MCU serial protocol (`JAC_V1`), identical in both directions:
 *
 * ```
 * EE FA LEN CMD D0 .. Dn-1 CS     LEN = n + 1     CS = (EE + FA + LEN + CMD + D0 + .. + Dn-1) & 0xFF
 * ```
 *
 * The MCU acknowledges every valid frame with `C0 <cmd> <d0> <d1>`. LEN is unsigned: CAN frames (`10`) carry up to
 * 128 data bytes, so LEN can exceed 0x7F (ivi-services crashes on those because it reads LEN as a signed byte).
 * Reference: LibreHU/MCU-tools-app docs/mcu_firmware.md §6.
 */
class McuFrame(
    val cmd: Int,
    val data: ByteArray = ByteArray(0),
) {
    /** Unsigned data byte. */
    fun u(index: Int): Int = data[index].toInt() and 0xFF

    fun encode(): ByteArray = JacFrame.encode(cmd, data)

    override fun equals(other: Any?): Boolean = other is McuFrame && other.cmd == cmd && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * cmd + data.contentHashCode()

    override fun toString(): String = (byteArrayOf(cmd.toByte()) + data).toHex()

    companion object {
        fun of(
            cmd: Int,
            vararg data: Int,
        ): McuFrame = McuFrame(cmd, ByteArray(data.size) { data[it].toByte() })
    }
}

object JacFrame {
    const val SYNC1 = 0xEE
    const val SYNC2 = 0xFA
    const val CMD_ACK = 0xC0

    /** Largest data block the MCU accepts (its receive buffer is 130 bytes). */
    const val MAX_DATA = 130

    fun encode(
        cmd: Int,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        require(data.size <= MAX_DATA) { "MCU frame data too long: ${data.size}" }
        val out = ByteArray(data.size + 5)
        out[0] = SYNC1.toByte()
        out[1] = SYNC2.toByte()
        out[2] = (data.size + 1).toByte()
        out[3] = cmd.toByte()
        data.copyInto(out, 4)
        out[out.size - 1] = checksum(out, out.size - 1).toByte()
        return out
    }

    /** 8-bit sum of the first [length] bytes. */
    fun checksum(
        bytes: ByteArray,
        length: Int,
    ): Int {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }
}

/**
 * Byte-stream decoder for [McuFrame]s. Bytes outside frames (the MCU also sends raw debug bytes such as `01 02 03 04`
 * at boot or `11` before sleep) and frames with a bad checksum are dropped; decoding then resynchronises on `EE FA`.
 */
class JacParser(
    private val onFrame: (McuFrame) -> Unit,
) {
    private val buf = ByteArray(JacFrame.MAX_DATA + 5)
    private var pos = 0
    private var expected = 0

    /** Number of frames dropped because of a bad checksum or length (for diagnostics). */
    var errors = 0
        private set

    fun reset() {
        pos = 0
        expected = 0
    }

    fun feed(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ) {
        for (i in offset until offset + length) push(bytes[i].toInt() and 0xFF)
    }

    private fun push(b: Int) {
        when (pos) {
            0 -> {
                if (b == JacFrame.SYNC1) store(b)
            }

            1 -> {
                when (b) {
                    JacFrame.SYNC2 -> store(b)

                    JacFrame.SYNC1 -> pos = 1

                    // EE EE FA: keep the second EE as start
                    else -> reset()
                }
            }

            2 -> {
                // LEN = data + 1 (CMD); 0 or too long cannot be a valid frame.
                if (b == 0 || b - 1 > JacFrame.MAX_DATA) {
                    errors++
                    reset()
                    if (b == JacFrame.SYNC1) store(b)
                    return
                }
                store(b)
                expected = b + 4 // EE FA LEN + (CMD + data) + CS
            }

            else -> {
                store(b)
                if (pos == expected) complete()
            }
        }
    }

    private fun store(b: Int) {
        buf[pos++] = b.toByte()
    }

    private fun complete() {
        val len = pos
        val cs = buf[len - 1].toInt() and 0xFF
        if (cs == JacFrame.checksum(buf, len - 1)) {
            onFrame(McuFrame(buf[3].toInt() and 0xFF, buf.copyOfRange(4, len - 1)))
            reset()
        } else {
            errors++
            // Resynchronise inside the rejected bytes in case a real frame started there.
            val rest = buf.copyOfRange(1, len)
            reset()
            for (x in rest) push(x.toInt() and 0xFF)
        }
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
