package org.librehu.core.mcu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Fake MCU: records written frames and acknowledges them after [ignore] tries. */
private class FakeMcu(
    private val ignore: Int,
) : SerialChannel {
    val written = CopyOnWriteArrayList<McuFrame>()
    private val toSoc = LinkedBlockingQueue<ByteArray>()
    private val parser =
        JacParser { f ->
            written += f
            if (written.count { it == f } > ignore) toSoc.put(McuFrame.of(0xC0, f.cmd, f.u(0)).encode())
        }

    fun push(frame: McuFrame) = toSoc.put(frame.encode())

    override fun read(
        buffer: ByteArray,
        timeoutMs: Int,
    ): Int {
        val b = toSoc.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        b.copyInto(buffer)
        return b.size
    }

    override fun write(bytes: ByteArray) = parser.feed(bytes)

    override fun close() {}
}

class McuTransportTest {
    @Test
    fun resendsUntilAcknowledged() {
        val mcu = FakeMcu(ignore = 2)
        val acked = CountDownLatch(1)
        val t =
            McuTransport(
                mcu,
                object : McuTransport.Listener {
                    override fun onFrame(frame: McuFrame) {
                        if (frame.cmd == 0xC0) acked.countDown()
                    }
                },
                ackTimeoutMs = 50,
            )
        t.start()
        t.send(Mcu.pcReady())
        assertEquals(true, acked.await(2, TimeUnit.SECONDS))
        t.close()
        assertEquals(3, mcu.written.count { it == Mcu.pcReady() })
    }

    @Test
    fun sendsOtherFramesOnceAndDeliversMcuFrames() {
        val mcu = FakeMcu(ignore = 100)
        val got = LinkedBlockingQueue<McuFrame>()
        val t =
            McuTransport(
                mcu,
                object : McuTransport.Listener {
                    override fun onFrame(frame: McuFrame) {
                        got.put(frame)
                    }
                },
                ackTimeoutMs = 50,
            )
        t.start()
        t.send(Mcu.externalAmp(true))
        mcu.push(McuFrame.of(0x00, 1))
        assertEquals(McuFrame.of(0x00, 1), got.poll(2, TimeUnit.SECONDS))
        Thread.sleep(200)
        t.close()
        assertEquals(1, mcu.written.count { it == Mcu.externalAmp(true) })
    }
}
