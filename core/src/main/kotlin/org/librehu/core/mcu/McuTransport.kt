package org.librehu.core.mcu

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Byte channel to the MCU (`/dev/ttyS1` on the device, a fake in tests). */
interface SerialChannel : Closeable {
    /** Reads available bytes, waiting at most [timeoutMs]. Returns 0 on timeout, -1 when closed. */
    fun read(
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int

    fun write(bytes: ByteArray)
}

/**
 * `JAC_V1` link: one reader thread decoding frames, one writer thread sending queued frames. Frames listed in
 * [Mcu.NEEDS_ACK] are resent until the MCU acknowledges them (`C0 cmd ..`), like ivi-services does.
 */
class McuTransport(
    private val channel: SerialChannel,
    private val listener: Listener,
    private val ackTimeoutMs: Long = 500,
    private val maxTries: Int = 5,
) : Closeable {
    interface Listener {
        /** Every valid frame received, acknowledgements included. */
        fun onFrame(frame: McuFrame)

        /** Every frame written, with the try number (1 = first). */
        fun onSent(
            frame: McuFrame,
            attempt: Int,
        ) {}

        /** A frame that needed an ACK was never acknowledged. */
        fun onAckTimeout(frame: McuFrame) {}

        fun onError(e: Exception) {}
    }

    private val queue = LinkedBlockingQueue<McuFrame>()
    private val ackLock = Object()
    private var waitingAckFor = -1
    private var acked = false

    @Volatile
    private var running = false
    private var reader: Thread? = null
    private var writer: Thread? = null

    private val parser =
        JacParser { frame ->
            if (frame.cmd == JacFrame.CMD_ACK && frame.data.isNotEmpty()) {
                synchronized(ackLock) {
                    if (frame.u(0) == waitingAckFor) {
                        acked = true
                        ackLock.notifyAll()
                    }
                }
            }
            listener.onFrame(frame)
        }

    val parseErrors: Int get() = parser.errors

    fun start() {
        if (running) return
        running = true
        reader = Thread(::readLoop, "mcu-rx").apply { isDaemon = true }.also { it.start() }
        writer = Thread(::writeLoop, "mcu-tx").apply { isDaemon = true }.also { it.start() }
    }

    fun send(frame: McuFrame) {
        queue.put(frame)
    }

    private fun readLoop() {
        val buf = ByteArray(512)
        var lastByte = System.nanoTime()
        while (running) {
            try {
                val n = channel.read(buf, 200)
                if (n < 0) break
                if (n == 0) continue
                val now = System.nanoTime()
                // The MCU drops a frame after 1 s without bytes; do the same so garbage never sticks.
                if (now - lastByte > 1_000_000_000L) parser.reset()
                lastByte = now
                parser.feed(buf, 0, n)
            } catch (e: IOException) {
                if (running) listener.onError(e)
                break
            } catch (e: RuntimeException) {
                listener.onError(e)
            }
        }
    }

    private fun writeLoop() {
        while (running) {
            val frame =
                try {
                    queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    break
                }
            val needAck = frame.cmd in Mcu.NEEDS_ACK
            val bytes = frame.encode()
            try {
                if (!needAck) {
                    channel.write(bytes)
                    listener.onSent(frame, 1)
                    continue
                }
                var ok = false
                for (attempt in 1..maxTries) {
                    synchronized(ackLock) {
                        waitingAckFor = frame.cmd
                        acked = false
                    }
                    channel.write(bytes)
                    listener.onSent(frame, attempt)
                    synchronized(ackLock) {
                        val deadline = System.currentTimeMillis() + ackTimeoutMs
                        while (!acked && running) {
                            val left = deadline - System.currentTimeMillis()
                            if (left <= 0) break
                            ackLock.wait(left)
                        }
                        ok = acked
                        waitingAckFor = -1
                    }
                    if (ok || !running) break
                }
                if (!ok && running) listener.onAckTimeout(frame)
            } catch (e: IOException) {
                if (running) listener.onError(e)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    override fun close() {
        running = false
        synchronized(ackLock) { ackLock.notifyAll() }
        writer?.interrupt()
        try {
            channel.close()
        } catch (_: IOException) {
        }
        reader?.join(1000)
        writer?.join(1000)
    }
}
