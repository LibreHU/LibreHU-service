package org.librehu.core.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LibreHU bridge protocol, version 1: the phone app (LibreHU-bridge) and LibreHU-service talk over any byte stream
 * (TCP on Wi-Fi, Bluetooth RFCOMM) through an authenticated, encrypted channel. Same file in both apps.
 *
 * Handshake (frames = 4-byte big-endian length + UTF-8 JSON):
 * 1. phone → unit `{"v":1,"e":<ephemeral P-256 key>,"n":<32 random bytes>}`
 * 2. unit → phone `{"v":1,"e":…,"n":…,"s":<static P-256 key>,"sig":ECDSA-SHA256(static, H)}`, H = SHA-256 of
 *    "LibreHU-bridge-1" and the four keys and nonces. The phone checks the static key against the fingerprint it
 *    knows (QR code, or saved at pairing): no one in the middle can sign for the unit.
 * 3. Both derive keys from ECDH(ephemeral keys) with HKDF-SHA256 (RFC 5869, salt H); from then on every frame is
 *    AES-256-GCM, one key per direction, nonce = direction + counter (a replayed or reordered frame fails).
 * 4. phone → unit (encrypted) the authentication: `{"mode":"qr","proof":HMAC(QR token, H)}` (first pairing, token
 *    shown in the QR code for a few minutes), `{"mode":"cred","id":…,"proof":HMAC(credential, H)}` (paired phone),
 *    or `{"mode":"bt"}` (phone paired in Bluetooth: the unit asks its user). The unit answers `{"ok":true,…}`, with
 *    a new `id` and `credential` on a pairing.
 * Keys: base64url of their X.509 (public) / PKCS#8 (private) encodings.
 */
object BridgeCrypto {
    const val VERSION = 1
    private const val LABEL = "LibreHU-bridge-1"
    private val random = SecureRandom()

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun unb64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    fun publicKey(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun privateKey(encoded: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    /** What the QR code and the phone keep of the unit's static key. */
    fun fingerprint(publicKey: PublicKey): String = b64(sha256(publicKey.encoded))

    fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    fun transcript(
        clientEphemeral: ByteArray,
        clientNonce: ByteArray,
        serverEphemeral: ByteArray,
        serverNonce: ByteArray,
        serverStatic: ByteArray,
    ): ByteArray = sha256(LABEL.toByteArray(), clientEphemeral, clientNonce, serverEphemeral, serverNonce, serverStatic)

    fun sign(
        key: PrivateKey,
        data: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }

    fun verify(
        key: PublicKey,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        }.getOrDefault(false)

    fun agree(
        private: PrivateKey,
        public: PublicKey,
    ): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(public, true)
            generateSecret()
        }

    /** Constant time comparison (proofs). */
    fun same(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)
}

class BridgeException(
    message: String,
) : IOException(message)

/**
 * The encrypted channel once the handshake is done: [send] / [receive] JSON objects. Thread-safe for one sender and
 * one receiver at a time.
 */
class BridgeChannel private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    sendKey: ByteArray,
    receiveKey: ByteArray,
    private val sendTag: Int,
    private val receiveTag: Int,
    /** Transcript hash of the handshake (authentication proofs). */
    val transcript: ByteArray,
    /** The unit's static key fingerprint (as checked by the phone). */
    val serverFingerprint: String,
) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sendCounter = 0L
    private var receiveCounter = 0L
    private val sendLock = Any()

    fun send(message: JsonObject) {
        val plain = JSON.encodeToString(JsonObject.serializer(), message).toByteArray()
        synchronized(sendLock) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, nonce(sendTag, sendCounter++)))
            writeFrame(output, c.doFinal(plain))
        }
    }

    fun receive(): JsonObject {
        val frame = readFrame(input)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, nonce(receiveTag, receiveCounter++)))
        val plain =
            try {
                c.doFinal(frame)
            } catch (_: Exception) {
                throw BridgeException("frame not authentic")
            }
        return parse(plain)
    }

    private fun nonce(
        tag: Int,
        counter: Long,
    ): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 4) n[i] = (tag shr (24 - 8 * i)).toByte()
        for (i in 0 until 8) n[4 + i] = (counter shr (56 - 8 * i)).toByte()
        return n
    }

    companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
        private const val TAG_CLIENT = 0x50484F4E // "PHON"
        private const val TAG_SERVER = 0x554E4954 // "UNIT"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun writeFrame(
            out: DataOutputStream,
            data: ByteArray,
        ) {
            out.writeInt(data.size)
            out.write(data)
            out.flush()
        }

        fun readFrame(input: DataInputStream): ByteArray {
            val n = input.readInt()
            if (n < 0 || n > MAX_FRAME) throw BridgeException("frame of $n bytes")
            return ByteArray(n).also { input.readFully(it) }
        }

        fun parse(b: ByteArray): JsonObject =
            try {
                JSON.parseToJsonElement(b.toString(Charsets.UTF_8)).jsonObject
            } catch (_: Exception) {
                throw BridgeException("not a JSON object")
            }

        private fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: throw BridgeException("missing $k")

        private fun keys(
            shared: ByteArray,
            transcript: ByteArray,
        ): Pair<ByteArray, ByteArray> {
            val k = BridgeCrypto.hkdf(shared, transcript, "LibreHU bridge keys".toByteArray(), 64)
            return k.copyOfRange(0, 32) to k.copyOfRange(32, 64)
        }

        /**
         * Phone side: handshake with the unit; [pinned] = the fingerprint from the QR code or saved at pairing (null
         * only for a Bluetooth pairing, where the Bluetooth link vouches for the unit).
         */
        fun connect(
            input: InputStream,
            output: OutputStream,
            pinned: String?,
        ): BridgeChannel {
            val din = DataInputStream(input)
            val dout = DataOutputStream(output)
            val eph = BridgeCrypto.newKeyPair()
            val nonce = BridgeCrypto.randomBytes(32)
            writeFrame(
                dout,
                JSON
                    .encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("v", BridgeCrypto.VERSION)
                            put("e", BridgeCrypto.b64(eph.public.encoded))
                            put("n", BridgeCrypto.b64(nonce))
                        },
                    ).toByteArray(),
            )
            val reply = parse(readFrame(din))
            val serverEph = BridgeCrypto.unb64(reply.str("e"))
            val serverNonce = BridgeCrypto.unb64(reply.str("n"))
            val serverStatic = BridgeCrypto.unb64(reply.str("s"))
            val staticKey = BridgeCrypto.publicKey(serverStatic)
            val fingerprint = BridgeCrypto.fingerprint(staticKey)
            if (pinned != null && fingerprint != pinned) throw BridgeException("this is not the paired head unit")
            val th = BridgeCrypto.transcript(eph.public.encoded, nonce, serverEph, serverNonce, serverStatic)
            if (!BridgeCrypto.verify(staticKey, th, BridgeCrypto.unb64(reply.str("sig")))) throw BridgeException("bad signature")
            val shared = BridgeCrypto.agree(eph.private, BridgeCrypto.publicKey(serverEph))
            val (c2s, s2c) = keys(shared, th)
            return BridgeChannel(din, dout, c2s, s2c, TAG_CLIENT, TAG_SERVER, th, fingerprint)
        }

        /** Unit side: answers the handshake with its static key pair. */
        fun accept(
            input: InputStream,
            output: OutputStream,
            static: KeyPair,
        ): BridgeChannel {
            val din = DataInputStream(input)
            val dout = DataOutputStream(output)
            val hello = parse(readFrame(din))
            if ((hello["v"] as? JsonPrimitive)?.contentOrNull != BridgeCrypto.VERSION.toString()) throw BridgeException("protocol version")
            val clientEph = BridgeCrypto.unb64(hello.str("e"))
            val clientNonce = BridgeCrypto.unb64(hello.str("n"))
            if (clientNonce.size != 32) throw BridgeException("nonce")
            val eph = BridgeCrypto.newKeyPair()
            val nonce = BridgeCrypto.randomBytes(32)
            val th = BridgeCrypto.transcript(clientEph, clientNonce, eph.public.encoded, nonce, static.public.encoded)
            writeFrame(
                dout,
                JSON
                    .encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("v", BridgeCrypto.VERSION)
                            put("e", BridgeCrypto.b64(eph.public.encoded))
                            put("n", BridgeCrypto.b64(nonce))
                            put("s", BridgeCrypto.b64(static.public.encoded))
                            put("sig", BridgeCrypto.b64(BridgeCrypto.sign(static.private, th)))
                        },
                    ).toByteArray(),
            )
            val shared = BridgeCrypto.agree(eph.private, BridgeCrypto.publicKey(clientEph))
            val (c2s, s2c) = keys(shared, th)
            return BridgeChannel(din, dout, s2c, c2s, TAG_SERVER, TAG_CLIENT, th, BridgeCrypto.fingerprint(static.public))
        }
    }
}
