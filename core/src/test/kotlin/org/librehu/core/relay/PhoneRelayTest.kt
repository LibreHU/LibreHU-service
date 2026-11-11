package org.librehu.core.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The relay as the head unit sees it: the secret, the client id, then raw IPv4 packets of the VPN in both directions.
 * UDP and TCP to servers of this machine (127.0.0.1), what the phone would do with the Internet.
 */
class PhoneRelayTest {
    private val secret = ByteArray(PhoneRelay.SECRET_SIZE) { (it * 13 + 1).toByte() }
    private var gone = false
    private val loop = RelayLoop(secret) { gone = true }
    private val relayThread = thread(isDaemon = true) { loop.run() }

    @After
    fun stop() {
        loop.stop()
        relayThread.join(2000)
    }

    private val vpn = ip(10, 0, 0, 2)
    private val local = ip(127, 0, 0, 1)

    private fun ip(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) = (a shl 24) or (b shl 16) or (c shl 8) or d

    private fun connect(withSecret: ByteArray = secret): Pair<Socket, DataInputStream> {
        val s = Socket(InetAddress.getLoopbackAddress(), loop.port)
        s.soTimeout = 10_000
        s.getOutputStream().write(withSecret)
        return s to DataInputStream(s.getInputStream())
    }

    @Test
    fun wrongSecretIsRefused() {
        val (s, input) = connect(ByteArray(PhoneRelay.SECRET_SIZE))
        assertEquals(-1, input.read())
        s.close()
        assertEquals(false, loop.served)
    }

    @Test
    fun udpGoesThroughAndComesBack() {
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            echo.receive(p)
            echo.send(DatagramPacket(("pong:" + String(p.data, 0, p.length)).toByteArray(), p.length + 5, p.socketAddress))
        }
        val (s, input) = connect()
        input.readInt() // client id
        assertTrue(loop.served)
        s.getOutputStream().write(Packets.udp(vpn, 40000, local, echo.localPort, "ping".toByteArray()))
        val back = Packets.read(input)
        assertEquals(17, back.protocol)
        assertEquals(local, back.src)
        assertEquals(vpn, back.dst)
        assertEquals(echo.localPort, back.srcPort)
        assertEquals(40000, back.dstPort)
        assertEquals("pong:ping", String(back.payload))
        s.close()
        echo.close()
    }

    @Test
    fun tcpConnectsSendsAndReceives() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.accept().use { c ->
                val got = ByteArray(5)
                DataInputStream(c.getInputStream()).readFully(got)
                c.getOutputStream().write("echo:".toByteArray() + got)
                c.getOutputStream().flush()
                Thread.sleep(500)
            }
        }
        val (s, input) = connect()
        input.readInt()
        val out = s.getOutputStream()
        out.write(Packets.tcp(vpn, 41000, local, server.localPort, seq = 1000, ack = 0, flags = Packets.SYN))
        val synAck = Packets.read(input)
        assertEquals(6, synAck.protocol)
        assertEquals(Packets.SYN or Packets.ACK, synAck.flags and (Packets.SYN or Packets.ACK))
        assertEquals(1001L, synAck.ack)
        var theirSeq = synAck.seq + 1
        out.write(Packets.tcp(vpn, 41000, local, server.localPort, seq = 1001, ack = theirSeq, flags = Packets.ACK))
        out.write(Packets.tcp(vpn, 41000, local, server.localPort, seq = 1001, ack = theirSeq, flags = Packets.ACK or Packets.PSH, data = "hello".toByteArray()))
        val received = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (received.length < "echo:hello".length && System.nanoTime() < deadline) {
            val p = Packets.read(input)
            if (p.payload.isNotEmpty()) {
                received.append(String(p.payload))
                theirSeq = p.seq + p.payload.size
                out.write(Packets.tcp(vpn, 41000, local, server.localPort, seq = 1006, ack = theirSeq, flags = Packets.ACK))
            }
        }
        assertEquals("echo:hello", received.toString())
        s.close()
        server.close()
    }

    @Test
    fun clientLeavingIsTold() {
        val (s, input) = connect()
        input.readInt()
        s.close()
        val end = System.currentTimeMillis() + 5000
        while (!gone && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(gone)
    }
}

/** IPv4 packets of the tests (RFC 791, 768, 793), checksums included. */
object Packets {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    class Parsed(
        val protocol: Int,
        val src: Int,
        val dst: Int,
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val payload: ByteArray,
    )

    private fun sum(
        b: ByteArray,
        off: Int,
        len: Int,
        start: Long = 0,
    ): Long {
        var s = start
        var i = off
        while (i + 1 < off + len) {
            s += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < off + len) s += (b[i].toInt() and 0xff) shl 8
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    private fun ipv4(
        src: Int,
        dst: Int,
        protocol: Int,
        segment: ByteArray,
    ): ByteArray {
        val total = 20 + segment.size
        val b = ByteBuffer.allocate(total)
        b.put(0x45.toByte()).put(0).putShort(total.toShort()).putShort(0).putShort(0x4000.toShort())
        b.put(64).put(protocol.toByte()).putShort(0).putInt(src).putInt(dst).put(segment)
        val a = b.array()
        val c = fold(sum(a, 0, 20))
        a[10] = (c shr 8).toByte()
        a[11] = c.toByte()
        return a
    }

    private fun pseudo(
        src: Int,
        dst: Int,
        protocol: Int,
        length: Int,
    ): Long {
        val p = ByteBuffer.allocate(12).putInt(src).putInt(dst).put(0).put(protocol.toByte()).putShort(length.toShort()).array()
        return sum(p, 0, 12)
    }

    fun udp(
        src: Int,
        srcPort: Int,
        dst: Int,
        dstPort: Int,
        data: ByteArray,
    ): ByteArray {
        val seg = ByteBuffer.allocate(8 + data.size).putShort(srcPort.toShort()).putShort(dstPort.toShort())
            .putShort((8 + data.size).toShort()).putShort(0).put(data).array()
        val c = fold(sum(seg, 0, seg.size, pseudo(src, dst, 17, seg.size)))
        seg[6] = (c shr 8).toByte()
        seg[7] = c.toByte()
        return ipv4(src, dst, 17, seg)
    }

    fun tcp(
        src: Int,
        srcPort: Int,
        dst: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        data: ByteArray = ByteArray(0),
    ): ByteArray {
        val seg = ByteBuffer.allocate(20 + data.size).putShort(srcPort.toShort()).putShort(dstPort.toShort())
            .putInt(seq.toInt()).putInt(ack.toInt()).put((5 shl 4).toByte()).put(flags.toByte())
            .putShort(0xffff.toShort()).putShort(0).putShort(0).put(data).array()
        val c = fold(sum(seg, 0, seg.size, pseudo(src, dst, 6, seg.size)))
        seg[16] = (c shr 8).toByte()
        seg[17] = c.toByte()
        return ipv4(src, dst, 6, seg)
    }

    /** The next IPv4 packet of [input] (framed by its total length). */
    fun read(input: InputStream): Parsed {
        val d = if (input is DataInputStream) input else DataInputStream(input)
        val head = ByteArray(20)
        d.readFully(head)
        val total = ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
        val rest = ByteArray(total - 20)
        d.readFully(rest)
        val b = ByteBuffer.wrap(head + rest)
        val ihl = (b.get(0).toInt() and 0x0f) * 4
        val protocol = b.get(9).toInt() and 0xff
        val src = b.getInt(12)
        val dst = b.getInt(16)
        val srcPort = b.getShort(ihl).toInt() and 0xffff
        val dstPort = b.getShort(ihl + 2).toInt() and 0xffff
        return if (protocol == 6) {
            val off = ((b.get(ihl + 12).toInt() and 0xf0) shr 4) * 4
            Parsed(
                protocol, src, dst, srcPort, dstPort,
                b.getInt(ihl + 4).toLong() and 0xffffffffL, b.getInt(ihl + 8).toLong() and 0xffffffffL,
                b.get(ihl + 13).toInt() and 0xff, (head + rest).copyOfRange(ihl + off, total),
            )
        } else {
            Parsed(protocol, src, dst, srcPort, dstPort, 0, 0, 0, (head + rest).copyOfRange(ihl + 8, total))
        }
    }
}
