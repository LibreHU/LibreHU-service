package org.librehu.core.adb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A stream of an [AdbConnection]: its writes become WRTE messages (one in flight, protocol.md), what the phone sends is
 * read through [readChunk] or [inputStream]. Thread safety: one reader thread and one writer thread may use it at once.
 */
class AdbStream internal constructor(
    private val connection: AdbConnection,
    val localId: Int,
    val destination: String,
) : Closeable {
    private val lock = Object()

    @Volatile
    var remoteId = 0
        private set
    private var opened = false
    private var ready = false
    private val inbox = ArrayDeque<ByteArray>()

    @Volatile
    var closed = false
        private set

    internal fun onOkay(remote: Int) =
        synchronized(lock) {
            if (!opened) {
                remoteId = remote
                opened = true
            }
            ready = true
            lock.notifyAll()
        }

    internal fun onWrite(data: ByteArray) =
        synchronized(lock) {
            inbox.addLast(data)
            lock.notifyAll()
        }

    internal fun onRemoteClose() =
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }

    internal fun awaitOpen(timeoutMs: Long): Boolean =
        synchronized(lock) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!opened && !closed) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
            opened && !closed
        }

    /** Sends [len] bytes of [data], in messages of at most maxData bytes, each after the phone's OKAY of the previous. */
    fun write(
        data: ByteArray,
        off: Int = 0,
        len: Int = data.size,
    ) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, connection.maxData)
            synchronized(lock) {
                while (!ready && !closed) lock.wait()
                if (closed) throw IOException("adb stream $destination closed")
                ready = false
            }
            connection.send(AdbMessage(AdbMessage.A_WRTE, localId, remoteId, data.copyOfRange(o, o + n)))
            o += n
            left -= n
        }
    }

    /** The next bytes from the phone (acknowledged now: the phone sends more only then), or null once the stream ended. */
    fun readChunk(): ByteArray? {
        val data =
            synchronized(lock) {
                while (inbox.isEmpty() && !closed) lock.wait()
                inbox.removeFirstOrNull()
            } ?: return null
        if (!closed) {
            try {
                connection.send(AdbMessage(AdbMessage.A_OKAY, localId, remoteId))
            } catch (_: IOException) {
            }
        }
        return data
    }

    /** Reads until the stream ends (for a command's output). */
    fun readAll(limit: Int = 1 shl 20): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < limit) out.write(readChunk() ?: break)
        return out.toByteArray()
    }

    val inputStream: InputStream =
        object : InputStream() {
            private var chunk: ByteArray? = null
            private var pos = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (len == 0) return 0
                var c = chunk
                while (c == null || pos >= c.size) {
                    c = readChunk() ?: return -1
                    chunk = c
                    pos = 0
                }
                val n = minOf(len, c.size - pos)
                System.arraycopy(c, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() = this@AdbStream.close()
        }

    val outputStream: OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = this@AdbStream.write(b, off, len)

            override fun close() = this@AdbStream.close()
        }

    override fun close() {
        val wasOpen: Boolean
        synchronized(lock) {
            wasOpen = !closed && opened
            closed = true
            lock.notifyAll()
        }
        connection.forget(this)
        if (wasOpen) {
            try {
                connection.send(AdbMessage(AdbMessage.A_CLSE, localId, remoteId))
            } catch (_: IOException) {
            }
        }
    }
}
