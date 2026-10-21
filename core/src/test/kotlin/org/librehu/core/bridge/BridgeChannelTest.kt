package org.librehu.core.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class BridgeChannelTest {
    private val static = BridgeCrypto.newKeyPair()

    private fun pair(pinned: String?): Pair<BridgeChannel, BridgeChannel> {
        val listen = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var server: BridgeChannel? = null
        var error: Throwable? = null
        val t =
            thread {
                runCatching {
                    val s = listen.accept()
                    server = BridgeChannel.accept(s.getInputStream(), s.getOutputStream(), static)
                }.onFailure { error = it }
            }
        val socket = Socket("127.0.0.1", listen.localPort)
        val client = BridgeChannel.connect(socket.getInputStream(), socket.getOutputStream(), pinned)
        t.join(5000)
        listen.close()
        error?.let { throw it }
        return client to server!!
    }

    @Test
    fun encryptedBothWays() {
        val (client, server) = pair(BridgeCrypto.fingerprint(static.public))
        assertTrue(client.transcript.contentEquals(server.transcript))
        client.send(buildJsonObject { put("m", "hello") })
        assertEquals(JsonPrimitive("hello"), server.receive()["m"])
        repeat(3) { i -> server.send(buildJsonObject { put("i", i) }) }
        repeat(3) { i -> assertEquals(JsonPrimitive(i), client.receive()["i"]) }
    }

    @Test
    fun wrongUnitRefused() {
        val other = BridgeCrypto.fingerprint(BridgeCrypto.newKeyPair().public)
        try {
            pair(other)
            fail("connected to an unknown unit")
        } catch (e: BridgeException) {
            assertTrue(e.message!!.contains("paired"))
        }
    }

    @Test
    fun proofsAndHkdf() {
        // RFC 5869 test case 1.
        val okm =
            BridgeCrypto.hkdf(
                ByteArray(22) { 0x0b },
                ByteArray(13) { it.toByte() },
                ByteArray(10) { (0xf0 + it).toByte() },
                42,
            )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }
}
