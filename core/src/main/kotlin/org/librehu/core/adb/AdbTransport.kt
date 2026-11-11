package org.librehu.core.adb

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** The link to adbd: USB (the head unit's side, in the app) or a byte stream (TCP, tests). */
interface AdbTransport : Closeable {
    /** The next whole message; blocks; IOException when the link is gone. Called by one thread at a time. */
    fun read(): AdbMessage

    /** Writes [message]; called by one thread at a time (AdbConnection serialises them). */
    fun write(message: AdbMessage)
}

/** adb over a byte stream: TCP (adbd's `adb tcpip` port) or anything else that keeps the bytes in order. */
class StreamTransport(
    input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {},
) : AdbTransport {
    private val input = DataInputStream(input)

    override fun read(): AdbMessage {
        val head = ByteArray(AdbMessage.HEADER_SIZE)
        input.readFully(head)
        val h = AdbMessage.parseHeader(head) ?: throw IOException("bad adb header")
        if (h.length > AdbMessage.MAX_PAYLOAD) throw IOException("adb payload too large: ${h.length}")
        val payload = ByteArray(h.length)
        input.readFully(payload)
        return AdbMessage(h.command, h.arg0, h.arg1, payload)
    }

    override fun write(message: AdbMessage) {
        output.write(message.header())
        if (message.payload.isNotEmpty()) output.write(message.payload)
        output.flush()
    }

    override fun close() = onClose()
}
