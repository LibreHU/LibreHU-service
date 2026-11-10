package org.librehu.core.can

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The link never calls out with its lock held. 10/11/2026 (service v151 to v196): the head unit's thread, holding the
 * CAN monitor's lock, called [CanBoxLink.onFrame]; the box's thread, inside [CanBoxLink.tick], sent a message and
 * waited for the monitor's lock to note it. Each waited for the other for good.
 */
class CanBoxLinkThreadsTest {
    private fun frame(
        cmd: Int,
        vararg data: Int,
    ) = CanboxFrame(cmd, ByteArray(data.size) { data[it].toByte() }, true)

    /** A profile whose message goes 1 s after the start, and every message of the box acknowledged. */
    private val carType =
        CanLink(ack = true, send = listOf(CanSend("car type", 0x24, listOf(0x00, 0x31), firstMs = 1_000, everyMs = 30_000)))

    @Test
    fun boxThreadSendingWhileTheMonitorHoldsItsLockAndCallsTheLink() {
        // The CAN monitor's lock: held by the head unit's thread while it hands a message to the link, taken by the
        // box's thread to note what it sent.
        val monitor = Any()
        val tickSending = CountDownLatch(1)
        val monitorHeld = CountDownLatch(1)
        val sent = Collections.synchronizedList(mutableListOf<String>())
        lateinit var boxThread: Thread
        val link =
            CanBoxLink(CanBoxFamilies.HIWORLD, carType, { _, what ->
                if (Thread.currentThread() === boxThread) {
                    tickSending.countDown()
                    monitorHeld.await(5, TimeUnit.SECONDS)
                }
                synchronized(monitor) { sent += what }
            })
        link.start(0)
        boxThread = Thread({ link.tick(1_000) }, "can-box").apply { isDaemon = true }
        val headUnitThread =
            Thread({
                tickSending.await(5, TimeUnit.SECONDS)
                synchronized(monitor) {
                    monitorHeld.countDown()
                    link.onFrame(frame(0x11, 0x61, 0, 0, 0, 8, 0, 0, 0, 2, 0x17), 1_000)
                }
            }, "headunit").apply { isDaemon = true }
        boxThread.start()
        headUnitThread.start()
        boxThread.join(5_000)
        headUnitThread.join(5_000)
        if (boxThread.isAlive || headUnitThread.isAlive) fail("deadlock: box thread ${boxThread.state}, head unit thread ${headUnitThread.state}")
        assertEquals(setOf("car type", "ack"), sent.toSet())
    }

    @Test
    fun callbacksRunWithoutTheLinksLock() {
        // Each callback has another thread take the link (a message with a bad checksum: noted, nothing sent back),
        // which it could not do while this one held it.
        lateinit var link: CanBoxLink
        val stuck = mutableListOf<String>()

        fun linkUsableFromAnotherThread(what: String) {
            val t = Thread { link.onFrame(CanboxFrame(0x12, byteArrayOf(1), false), 2_000) }.apply { isDaemon = true }
            t.start()
            t.join(2_000)
            if (t.isAlive) stuck += what
        }
        link =
            CanBoxLink(
                CanBoxFamilies.HIWORLD,
                carType.copy(commands = mapOf("lights" to CanCommand("lights", 0x9A, CanTemplate.parse("01 {value}", "test")))),
                { _, what -> linkUsableFromAnotherThread(what) },
                onAcked = { linkUsableFromAnotherThread("acked") },
            )
        link.start(0)
        link.tick(1_000)
        link.onFrame(frame(0xFF, 0x24), 1_100)
        assertTrue(link.command("lights", 1.0))
        assertEquals(emptyList<String>(), stuck)
    }

    @Test
    fun messagesOfOneTickGoInTheProfilesOrder() {
        val sent = mutableListOf<String>()
        val l =
            CanBoxLink(
                CanBoxFamilies.HIWORLD,
                CanLink(
                    ack = false,
                    send =
                        listOf(
                            CanSend("first", 0x24, listOf(1), firstMs = 100, everyMs = 0),
                            CanSend("second", 0x25, listOf(2), firstMs = 100, everyMs = 0),
                            CanSend("third", 0x26, listOf(3), firstMs = 100, everyMs = 0),
                        ),
                ),
                { _, what -> sent += what },
            )
        l.start(0)
        l.tick(100)
        l.tick(200)
        assertEquals(listOf("first", "second", "third"), sent)
    }
}
