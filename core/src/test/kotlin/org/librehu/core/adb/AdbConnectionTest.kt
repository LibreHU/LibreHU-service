package org.librehu.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The connection against a small adbd written from protocol.md and sync.md: authentication (known key, new key), streams
 * with their flow control (one WRTE in flight each way), exec, sync push.
 */
class AdbConnectionTest {
    /** The phone's side: reads the head unit's messages, answers like adbd. */
    private class FakeAdbd(
        val knownKey: ByteArray?,
    ) {
        // A real socket pair on 127.0.0.1: the head unit's end and the phone's end.
        private val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        private val hostSocket = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
        private val phoneSocket = server.accept().also { server.close() }
        val hostIn = hostSocket.getInputStream()
        val hostOut = hostSocket.getOutputStream()
        private val phone = StreamTransport(phoneSocket.getInputStream(), phoneSocket.getOutputStream())

        /** The phone goes away (cable unplugged). */
        fun hangUp() = phoneSocket.close()
        val received = LinkedBlockingQueue<AdbMessage>()
        var keyOffered: String? = null
        val token = ByteArray(20) { it.toByte() }
        val files = HashMap<String, ByteArray>()

        fun send(m: AdbMessage) = phone.write(m)

        fun verify(
            signature: ByteArray,
            publicKey: ByteArray,
        ): Boolean {
            // Decode Android's RSAPublicKey: words, n0inv, modulus LE, rr LE, exponent.
            val b = ByteBuffer.wrap(publicKey).order(ByteOrder.LITTLE_ENDIAN)
            b.int
            b.int
            val n = ByteArray(256).also { b.get(it) }.reversedArray()
            b.position(b.position() + 256)
            val e = b.int
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(java.math.BigInteger(1, n), java.math.BigInteger.valueOf(e.toLong())))
            return Signature.getInstance("NONEwithRSA").run {
                initVerify(pub)
                update(AdbKey.SHA1_DIGEST_INFO)
                update(token)
                verify(signature)
            }
        }

        fun run(onExec: (String, AdbMessage) -> Unit) =
            thread(isDaemon = true) {
                try {
                    var authed = false
                    while (true) {
                        val m = phone.read()
                        received.add(m)
                        when (m.command) {
                            AdbMessage.A_CNXN -> send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_TOKEN, 0, token))
                            AdbMessage.A_AUTH ->
                                when (m.arg0) {
                                    AdbMessage.AUTH_SIGNATURE ->
                                        if (knownKey != null && verify(m.payload, knownKey)) {
                                            authed = true
                                            send(AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, 256 * 1024, "device::features=cmd".toByteArray()))
                                        } else {
                                            send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_TOKEN, 0, token))
                                        }
                                    AdbMessage.AUTH_RSAPUBLICKEY -> {
                                        keyOffered = String(m.payload).trimEnd('\u0000')
                                        authed = true
                                        // The user taps "Allow".
                                        send(AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, 4096, "device::".toByteArray()))
                                    }
                                }
                            AdbMessage.A_OPEN -> {
                                check(authed)
                                onExec(String(m.payload).trimEnd('\u0000'), m)
                            }
                            else -> onExec("", m)
                        }
                    }
                } catch (_: Exception) {
                }
            }
    }

    private val key = AdbKey.generate()

    private fun pubStruct(k: AdbKey) = AdbKey.encodePublicKey(k.modulus, k.publicExponent)

    @Test
    fun knownKeyConnectsWithoutAsking() {
        val adbd = FakeAdbd(pubStruct(key))
        adbd.run { _, _ -> }
        val c = AdbConnection(StreamTransport(adbd.hostIn, adbd.hostOut), key, "hu@test")
        var asked = false
        c.connect { asked = true }
        assertEquals(false, asked)
        assertEquals(256 * 1024, c.maxData)
        assertEquals("device::features=cmd", c.banner)
        c.close()
    }

    @Test
    fun newKeyIsOfferedAndTheUserIsAsked() {
        val adbd = FakeAdbd(null)
        adbd.run { _, _ -> }
        val c = AdbConnection(StreamTransport(adbd.hostIn, adbd.hostOut), key, "hu@test")
        var asked = false
        c.connect { asked = true }
        assertTrue(asked)
        assertEquals(key.adbPublicKey("hu@test"), adbd.keyOffered)
        // A 4096-byte device: never less than that.
        assertEquals(4096, c.maxData)
        c.close()
    }

    @Test
    fun streamsKeepOneWriteInFlightAndAcknowledgeWhatIsRead() {
        val adbd = FakeAdbd(pubStruct(key))
        val hostWrites = LinkedBlockingQueue<AdbMessage>()
        lateinit var c: AdbConnection
        adbd.run { dest, m ->
            when {
                dest == "exec:echo" -> {
                    adbd.send(AdbMessage(AdbMessage.A_OKAY, 77, m.arg0))
                    // Two writes of the phone: the second only after the host's OKAY of the first.
                    adbd.send(AdbMessage(AdbMessage.A_WRTE, 77, m.arg0, "hello ".toByteArray()))
                }
                m.command == AdbMessage.A_OKAY && m.arg1 == 77 && hostWrites.none { it.command == AdbMessage.A_OKAY } -> {
                    hostWrites.add(m)
                    adbd.send(AdbMessage(AdbMessage.A_WRTE, 77, m.arg0, "world".toByteArray()))
                }
                m.command == AdbMessage.A_OKAY && m.arg1 == 77 -> {
                    hostWrites.add(m)
                    adbd.send(AdbMessage(AdbMessage.A_CLSE, 77, m.arg0))
                }
                m.command == AdbMessage.A_WRTE -> {
                    hostWrites.add(m)
                    adbd.send(AdbMessage(AdbMessage.A_OKAY, 77, m.arg0))
                }
                dest == "exec:refused" -> adbd.send(AdbMessage(AdbMessage.A_CLSE, 0, m.arg0))
            }
        }
        c = AdbConnection(StreamTransport(adbd.hostIn, adbd.hostOut), key, "hu@test")
        c.connect()
        assertNull(c.open("exec:refused", 2000))
        val s = c.open("exec:echo")!!
        assertEquals(77, s.remoteId)
        // 600 KiB in messages of at most maxData (256 KiB): three WRTE, each after an OKAY.
        val big = ByteArray(600 * 1024) { (it % 251).toByte() }
        s.write(big)
        assertEquals("hello world", String(s.readAll()))
        val writes = hostWrites.filter { it.command == AdbMessage.A_WRTE }
        assertEquals(3, writes.size)
        assertArrayEquals(big, writes.fold(ByteArrayOutputStream()) { o, m -> o.apply { write(m.payload) } }.toByteArray())
        c.close()
    }

    @Test
    fun pushSendsTheFileWithSync() {
        val adbd = FakeAdbd(pubStruct(key))
        val sync = ByteArrayOutputStream()
        adbd.run { dest, m ->
            when {
                dest == "sync:" -> adbd.send(AdbMessage(AdbMessage.A_OKAY, 5, m.arg0))
                m.command == AdbMessage.A_WRTE -> {
                    sync.write(m.payload)
                    adbd.send(AdbMessage(AdbMessage.A_OKAY, 5, m.arg0))
                    val b = sync.toByteArray()
                    // After DONE (8 bytes at the end), answer OKAY.
                    if (b.size >= 8 && String(b, b.size - 8, 4) == "DONE") {
                        adbd.send(AdbMessage(AdbMessage.A_WRTE, 5, m.arg0, "OKAY".toByteArray() + ByteArray(4)))
                    }
                }
            }
        }
        val c = AdbConnection(StreamTransport(adbd.hostIn, adbd.hostOut), key, "hu@test")
        c.connect()
        val file = ByteArray(150_000) { (it * 7).toByte() }
        AdbServices.push(c, ByteArrayInputStream(file), "/data/local/tmp/x.apk", mtimeS = 1234)
        // SEND <len> "path,33188", DATA 65536, DATA 65536, DATA 18928, DONE 1234
        val b = ByteBuffer.wrap(sync.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        fun id() = String(ByteArray(4).also { b.get(it) })
        assertEquals("SEND", id())
        val specLen = b.int
        assertEquals("/data/local/tmp/x.apk,33188", String(ByteArray(specLen).also { b.get(it) }))
        val body = ByteArrayOutputStream()
        while (true) {
            val i = id()
            val n = b.int
            if (i == "DONE") {
                assertEquals(1234, n)
                break
            }
            assertEquals("DATA", i)
            assertTrue(n <= 64 * 1024)
            body.write(ByteArray(n).also { b.get(it) })
        }
        assertArrayEquals(file, body.toByteArray())
        c.close()
    }

    @Test
    fun closedLinkEndsTheStreams() {
        val adbd = FakeAdbd(pubStruct(key))
        adbd.run { dest, m -> if (dest == "tcp:1") adbd.send(AdbMessage(AdbMessage.A_OKAY, 9, m.arg0)) }
        val c = AdbConnection(StreamTransport(adbd.hostIn, adbd.hostOut), key, "hu@test")
        var ended = false
        c.onClosed = { ended = true }
        c.connect()
        val s = c.open("tcp:1")!!
        val reader = thread { assertNull(s.readChunk()) }
        adbd.hangUp()
        reader.join(TimeUnit.SECONDS.toMillis(5))
        assertTrue(ended)
        assertTrue(s.closed)
    }
}
