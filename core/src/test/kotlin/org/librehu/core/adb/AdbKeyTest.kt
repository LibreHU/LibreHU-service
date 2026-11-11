package org.librehu.core.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/** The key against adb's own tools: the public key line of `adb keygen`, the signature of OpenSSL (README.md). */
class AdbKeyTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream(name)!!.use { it.readBytes() }

    private val key = AdbKey.fromPkcs8(Base64.getDecoder().decode(String(resource("adbkey.pkcs8.b64")).trim()))

    @Test
    fun publicKeyIsTheOneOfAdbKeygen() {
        val expected = String(resource("adbkey.pub")).trim()
        assertEquals(expected, key.adbPublicKey("test@librehu"))
    }

    @Test
    fun signatureIsTheOneOfRsaSignSha1() {
        // PKCS#1 v1.5 is deterministic: the same bytes as OpenSSL's for the same key and token.
        assertArrayEquals(resource("token.sig"), key.sign(resource("token.bin")))
    }

    @Test
    fun storedKeyComesBack() {
        val k = AdbKey.generate()
        val back = AdbKey.fromPkcs8(k.encoded())
        assertEquals(k.adbPublicKey("a@b"), back.adbPublicKey("a@b"))
        assertEquals(4 + 4 + 256 + 256 + 4, AdbKey.encodePublicKey(k.modulus, k.publicExponent).size)
    }
}
