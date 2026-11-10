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
 * MCU link for any [McuProtocol] (default: Jancar `JAC_V1`): one reader thread decoding frames, one writer thread
 * sending queued frames. Frames the protocol marks as needing an acknowledgement are resent until the MCU
 * acknowledges them (`C0 cmd ..` on Jancar), like ivi-services does.
 */
class McuTransport(
    private val channel: SerialChannel,
    private val listener: Listener,
    private val protocol: McuProtocol = JacProtocol,
    private val ackTimeoutMs: Long = protocol.ackTimeoutMs,
    private val maxTries: Int = protocol.maxTries,
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

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var writer: Thread? = null

    /**
     * [System.nanoTime] of the last turn of the reader's loop and of the writer's: each turn waits 200 ms at most, the
     * writer's 200 ms plus the ACK tries ([ackTimeoutMs] × [maxTries]). An old one: that thread is stuck in a call
     * (the service's watchdog reads them).
     */
    @Volatile
    var readerBeat = 0L
        private set

    @Volatile
    var writerBeat = 0L
        private set

    /** Started, not closed, and both threads still running (false once one has ended: port closed, I/O error). */
    val alive: Boolean get() = running && reader?.isAlive == true && writer?.isAlive == true

    private val parser =
        protocol.newParser { frame ->
            val ackedCmd = protocol.ackedCommand(frame)
            if (ackedCmd != null) {
                synchronized(ackLock) {
                    if (ackedCmd == waitingAckFor) {
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
        readerBeat = System.nanoTime()
        writerBeat = readerBeat
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
            readerBeat = System.nanoTime()
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
            writerBeat = System.nanoTime()
            val frame =
                try {
                    queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                } catch (_: InterruptedException) {
                    break
                }
            val needAck = protocol.needsAck(frame)
            val bytes = protocol.encode(frame)
            try {
                if (!needAck) {
                    channel.write(bytes)
                    listener.onSent(frame, 1)
                    continue
                }
                var ok = false
                for (attempt in 1..maxTries) {
                    writerBeat = System.nanoTime()
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
        // The threads first (a read or a poll of the queue waits 200 ms at most), then the port: a port closed under a
        // thread still reading or writing could be another file by then (a closed descriptor's number goes to the next
        // file opened), and the MCU's bytes would go to it.
        reader?.join(1000)
        writer?.join(1000)
        try {
            channel.close()
        } catch (_: IOException) {
        }
    }
}
