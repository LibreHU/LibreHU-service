package org.librehu.core.adb

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The adbd services the head unit uses: `exec:` (a command, raw binary stream) and `sync:` SEND (packages/modules/adb docs/dev). */
object AdbServices {
    /** sync.md: each DATA chunk is at most 64 KiB. */
    private const val SYNC_DATA_MAX = 64 * 1024

    /** Runs [command] on the phone (`exec:`: no terminal, the bytes as they are) and returns its output; null if refused. */
    fun run(
        connection: AdbConnection,
        command: String,
        limit: Int = 1 shl 20,
    ): String? = connection.open("exec:$command")?.use { String(it.readAll(limit)) }

    /**
     * Writes [data] ([size] bytes) to [remotePath] on the phone with the mode [mode] (`sync:` SEND, then DATA chunks, then
     * DONE with the modification time; the phone answers OKAY or FAIL). IOException on failure.
     */
    fun push(
        connection: AdbConnection,
        data: InputStream,
        remotePath: String,
        mode: Int = "644".toInt(8),
        mtimeS: Int = (System.currentTimeMillis() / 1000).toInt(),
    ) {
        val s = connection.open("sync:") ?: throw IOException("sync refused by the phone")
        s.use {
            // S_IFREG | mode, written in decimal after the last comma.
            val spec = "$remotePath,${"100000".toInt(8) or mode}".toByteArray()
            s.write(request("SEND", spec.size) + spec)
            val buf = ByteArray(SYNC_DATA_MAX)
            while (true) {
                var n = 0
                while (n < buf.size) {
                    val r = data.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n > 0) s.write(request("DATA", n) + buf.copyOf(n))
                if (n < buf.size) break
            }
            s.write(request("DONE", mtimeS))
            val reply = readExactly(s, 8)
            val id = String(reply, 0, 4)
            val len = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            when (id) {
                "OKAY" -> {}
                "FAIL" -> throw IOException("push $remotePath: " + String(readExactly(s, len.coerceIn(0, 4096))))
                else -> throw IOException("push $remotePath: unexpected answer $id")
            }
            s.write(request("QUIT", 0))
        }
    }

    private fun request(
        id: String,
        length: Int,
    ): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(length).array()

    private fun readExactly(
        s: AdbStream,
        n: Int,
    ): ByteArray {
        val out = ByteArray(n)
        var got = 0
        val input = s.inputStream
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) throw IOException("adb stream ended")
            got += r
        }
        return out
    }
}
