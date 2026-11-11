package org.librehu.service.net.cable

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import org.librehu.core.adb.AdbMessage
import org.librehu.core.adb.AdbTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * adb over the phone's USB debugging interface (class 0xff, subclass 0x42, protocol 1: the second interface of an
 * Android Auto phone, 0x18d1:0x2d01). Read like adb's own host does: the 24-byte header, then exactly the payload's
 * length (the device marks no boundaries); written header then payload, a zero-length packet after a payload whose
 * size is a multiple of the endpoint's packet size (docs/dev/zero_length_packet.md of adb). Reads are UsbRequests
 * (nothing lost on a timeout, cancelled on close); writes are bulk transfers with a timeout.
 */
class UsbAdbTransport(
    private val connection: UsbDeviceConnection,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val onClose: () -> Unit,
) : AdbTransport {
    private val request = UsbRequest().apply { if (!initialize(connection, input)) throw IOException("USB request refused") }
    private val buffer = ByteBuffer.allocateDirect(CHUNK)

    @Volatile
    private var closed = false

    override fun read(): AdbMessage {
        val head = readExactly(AdbMessage.HEADER_SIZE)
        val h = AdbMessage.parseHeader(head) ?: throw IOException("bad adb header from the phone")
        if (h.length > AdbMessage.MAX_PAYLOAD) throw IOException("adb payload too large: ${h.length}")
        return AdbMessage(h.command, h.arg0, h.arg1, if (h.length == 0) AdbMessage.EMPTY else readExactly(h.length))
    }

    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val want = minOf(n - got, CHUNK)
            buffer.clear()
            buffer.limit(want)
            if (!request.queue(buffer)) throw IOException("USB read refused")
            while (true) {
                if (closed) {
                    request.cancel()
                    throw IOException("closed")
                }
                val done =
                    try {
                        connection.requestWait(WAIT_MS)
                    } catch (_: TimeoutException) {
                        continue
                    } ?: throw IOException("USB read failed (phone unplugged?)")
                if (done === request) break
            }
            val len = buffer.position()
            if (len <= 0) throw IOException("USB read: nothing (phone unplugged?)")
            buffer.flip()
            buffer.get(out, got, len)
            got += len
        }
        return out
    }

    override fun write(message: AdbMessage) {
        transfer(message.header())
        val p = message.payload
        if (p.isNotEmpty()) {
            transfer(p)
            if (p.size % output.maxPacketSize == 0) zeroLengthPacket()
        }
    }

    private fun transfer(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(data.size - off, CHUNK)
            val sent = connection.bulkTransfer(output, data, off, n, WRITE_TIMEOUT_MS)
            if (sent <= 0) throw IOException("USB write failed ($sent)")
            off += sent
        }
    }

    private fun zeroLengthPacket() {
        if (connection.bulkTransfer(output, ByteArray(0), 0, 0, WRITE_TIMEOUT_MS) < 0) throw IOException("USB zero-length packet failed")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { request.cancel() }
        onClose()
    }

    private companion object {
        /** Largest bulk transfer of Android's USB host API before Android 9 (and a multiple of 512). */
        const val CHUNK = 16384
        const val WAIT_MS = 500L
        const val WRITE_TIMEOUT_MS = 5000
    }
}
