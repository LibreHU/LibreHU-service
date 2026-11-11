package org.librehu.core.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One message of the adb protocol (packages/modules/adb, docs/dev/protocol.md): a 24-byte header, six little-endian
 * 32-bit words (command, arg0, arg1, payload length, payload checksum, command xor 0xffffffff), then the payload.
 */
class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY,
) {
    fun header(): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).run {
            putInt(command)
            putInt(arg0)
            putInt(arg1)
            putInt(payload.size)
            putInt(checksum(payload))
            putInt(command.inv())
            array()
        }

    override fun toString() = "${name(command)}($arg0, $arg1, ${payload.size} B)"

    /** A header read from the link. */
    class Header(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val length: Int,
        val checksum: Int,
    )

    companion object {
        const val HEADER_SIZE = 24

        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_AUTH = 0x48545541
        const val A_STLS = 0x534C5453

        /** adb.h: A_VERSION (checksums no longer checked from 0x01000001 on, sent anyway for older devices). */
        const val VERSION = 0x01000001

        /** adb.h: MAX_PAYLOAD; the device answers with its own, the smaller one is used. */
        const val MAX_PAYLOAD = 1024 * 1024

        /** CNXN and AUTH sent before the device's CNXN: at most MAX_PAYLOAD_V1 (4096). */
        const val MAX_PAYLOAD_V1 = 4 * 1024

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        val EMPTY = ByteArray(0)

        fun checksum(payload: ByteArray): Int {
            var sum = 0
            for (b in payload) sum += b.toInt() and 0xff
            return sum
        }

        /** The header in [bytes] (24), or null when its magic does not match its command. */
        fun parseHeader(bytes: ByteArray): Header? {
            val b = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            val checksum = b.int
            val magic = b.int
            if (magic != command.inv() || length < 0) return null
            return Header(command, arg0, arg1, length, checksum)
        }

        fun name(command: Int): String =
            when (command) {
                A_SYNC -> "SYNC"
                A_CNXN -> "CNXN"
                A_OPEN -> "OPEN"
                A_OKAY -> "OKAY"
                A_CLSE -> "CLSE"
                A_WRTE -> "WRTE"
                A_AUTH -> "AUTH"
                A_STLS -> "STLS"
                else -> "0x%08x".format(command)
            }
    }
}
