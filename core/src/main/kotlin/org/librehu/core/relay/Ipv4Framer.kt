package org.librehu.core.relay

import java.io.IOException

/** IPv4 packets out of a byte stream: each packet carries its total length (bytes 2 and 3 of its header). */
class Ipv4Framer {
    private var buf = ByteArray(1 shl 17)
    private var len = 0

    fun feed(
        data: ByteArray,
        emit: (ByteArray, Int, Int) -> Unit,
    ) {
        if (len + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + data.size))
        System.arraycopy(data, 0, buf, len, data.size)
        len += data.size
        var off = 0
        while (len - off >= 20) {
            if ((buf[off].toInt() shr 4) and 0x0f != 4) throw IOException("not an IPv4 packet in the tunnel")
            val total = ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)
            if (total < 20) throw IOException("bad IPv4 length $total")
            if (len - off < total) break
            emit(buf, off, total)
            off += total
        }
        if (off > 0) {
            System.arraycopy(buf, off, buf, 0, len - off)
            len -= off
        }
    }
}
