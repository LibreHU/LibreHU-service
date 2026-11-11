package org.librehu.core.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

/**
 * The head unit's RSA 2048 key as an adb host. adbd sends a random token; the host signs it like adb does
 * (`RSA_sign(NID_sha1, token)`: PKCS#1 v1.5 over a SHA-1 DigestInfo holding the 20-byte token itself, packages/modules/adb
 * client/auth.cpp); a key adbd does not know yet goes as its public key, in Android's format
 * (`android_pubkey_encode`, system/core libcrypto_utils), and the phone asks its user ("Allow USB debugging?").
 */
class AdbKey private constructor(
    private val private: RSAPrivateCrtKey,
) {
    val modulus: BigInteger get() = private.modulus
    val publicExponent: BigInteger get() = private.publicExponent

    /** PKCS#8: what to store. */
    fun encoded(): ByteArray = private.encoded

    /** The token signed as adb does. */
    fun sign(token: ByteArray): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(private)
            update(SHA1_DIGEST_INFO)
            update(token)
            sign()
        }

    /** adb's public key line: base64 of the RSAPublicKey structure, a space, a name (`user@host`). */
    fun adbPublicKey(name: String): String = Base64.getEncoder().encodeToString(encodePublicKey(modulus, publicExponent)) + " " + name

    companion object {
        /** ASN.1 DigestInfo prefix of SHA-1 (RFC 8017, section 9.2): what RSA_sign(NID_sha1, …) puts before the digest. */
        val SHA1_DIGEST_INFO =
            byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /** ANDROID_PUBKEY_MODULUS_SIZE: 2048 bits. */
        private const val MODULUS_BYTES = 256

        fun generate(): AdbKey {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(RSAKeyGenParameterSpec(MODULUS_BYTES * 8, RSAKeyGenParameterSpec.F4))
            return AdbKey(g.generateKeyPair().private as RSAPrivateCrtKey)
        }

        fun fromPkcs8(bytes: ByteArray): AdbKey = AdbKey(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)) as RSAPrivateCrtKey)

        /** The PEM `PRIVATE KEY` (PKCS#8) adb writes in ~/.android/adbkey. */
        fun fromPem(pem: String): AdbKey =
            fromPkcs8(
                Base64.getMimeDecoder().decode(
                    pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").trim(),
                ),
            )

        /**
         * struct RSAPublicKey of android_pubkey.cpp, little-endian: modulus size in 32-bit words, n0inv = -1 / n mod 2^32,
         * the modulus, rr = (2^2048)^2 mod n, the exponent.
         */
        fun encodePublicKey(
            n: BigInteger,
            e: BigInteger,
        ): ByteArray {
            require(n.bitLength() <= MODULUS_BYTES * 8) { "RSA 2048 only" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
            val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.valueOf(2), n)
            return ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN).run {
                putInt(MODULUS_BYTES / 4)
                putInt(n0inv.toLong().toInt())
                put(littleEndian(n))
                put(littleEndian(rr))
                putInt(e.toInt())
                array()
            }
        }

        private fun littleEndian(v: BigInteger): ByteArray {
            val big = v.toByteArray().let { if (it.size > MODULUS_BYTES) it.copyOfRange(it.size - MODULUS_BYTES, it.size) else it }
            val out = ByteArray(MODULUS_BYTES)
            for (i in big.indices) out[i] = big[big.size - 1 - i]
            return out
        }
    }
}
