package org.librehu.core.adb

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An adb host connection (the head unit is the host, the phone runs adbd): CNXN, AUTH, then streams to adbd's services
 * (`exec:`, `sync:`, `tcp:<port>`…), multiplexed on the one link. Flow control of the protocol: one WRTE in flight per
 * stream, the next only after the other side's OKAY; a received WRTE is acknowledged when its reader takes it, so a slow
 * reader slows the phone down instead of filling the memory.
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val key: AdbKey,
    /** `user@host` shown on the phone with the key's fingerprint. */
    private val keyName: String,
) : Closeable {
    /** Largest payload of a message, the smaller of the two sides' (CNXN). */
    @Volatile
    var maxData = AdbMessage.MAX_PAYLOAD_V1
        private set

    /** The device's identity string (`device::ro.product.name=…;features=…`). */
    @Volatile
    var banner = ""
        private set

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    private val writeLock = Any()

    @Volatile
    private var closed = false
    private var reader: Thread? = null

    /** Called when the connection ends (link lost, closed), once. */
    @Volatile
    var onClosed: (() -> Unit)? = null

    /**
     * Connects: CNXN, then the token signed with [key]; a phone that does not know the key gets it and asks its user,
     * [onConfirm] is told (the answer can take long: the caller bounds it by closing the transport). Returns once the
     * phone answered CNXN.
     */
    fun connect(onConfirm: () -> Unit = {}) {
        send(AdbMessage(AdbMessage.A_CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray()))
        var signed = false
        var keySent = false
        while (true) {
            val m = transport.read()
            when (m.command) {
                AdbMessage.A_AUTH ->
                    if (m.arg0 == AdbMessage.AUTH_TOKEN) {
                        if (!signed) {
                            signed = true
                            send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_SIGNATURE, 0, key.sign(m.payload)))
                        } else if (!keySent) {
                            keySent = true
                            onConfirm()
                            send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_RSAPUBLICKEY, 0, (key.adbPublicKey(keyName) + "\u0000").toByteArray()))
                        }
                    }

                AdbMessage.A_CNXN -> {
                    maxData = minOf(AdbMessage.MAX_PAYLOAD, m.arg1).coerceAtLeast(AdbMessage.MAX_PAYLOAD_V1)
                    banner = String(m.payload).trimEnd('\u0000')
                    break
                }

                AdbMessage.A_STLS -> throw IOException("adb over TLS (wireless debugging) is not supported")
                // Anything before the device's CNXN is ignored (protocol.md).
            }
        }
        reader =
            Thread({ readLoop() }, "adb-reader").apply {
                isDaemon = true
                start()
            }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val m = transport.read()
                when (m.command) {
                    AdbMessage.A_OKAY -> streams[m.arg1]?.onOkay(m.arg0)
                    AdbMessage.A_WRTE -> streams[m.arg1]?.onWrite(m.payload)
                    AdbMessage.A_CLSE -> streams.remove(m.arg1)?.onRemoteClose()
                    // adbd restarted on the phone (or a new CNXN): the streams are gone.
                    AdbMessage.A_CNXN, AdbMessage.A_AUTH -> throw IOException("adb connection reset by the phone")
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    /**
     * Opens a stream to [destination] (`exec:…`, `sync:`, `tcp:<port>`); null when the phone refuses it (CLSE) or does
     * not answer within [timeoutMs].
     */
    fun open(
        destination: String,
        timeoutMs: Long = 10_000,
    ): AdbStream? {
        if (closed) throw IOException("adb connection closed")
        val id = nextId.getAndIncrement()
        val s = AdbStream(this, id, destination)
        streams[id] = s
        send(AdbMessage(AdbMessage.A_OPEN, id, 0, (destination + "\u0000").toByteArray()))
        if (!s.awaitOpen(timeoutMs)) {
            streams.remove(id)
            s.close()
            return null
        }
        return s
    }

    internal fun send(message: AdbMessage) {
        if (closed && message.command != AdbMessage.A_CLSE) throw IOException("adb connection closed")
        synchronized(writeLock) {
            try {
                transport.write(message)
            } catch (e: IOException) {
                close()
                throw e
            }
        }
    }

    internal fun forget(stream: AdbStream) {
        streams.remove(stream.localId)
    }

    val isOpen: Boolean get() = !closed

    override fun close() {
        if (closed) return
        closed = true
        try {
            transport.close()
        } catch (_: Exception) {
        }
        for (s in streams.values) s.onRemoteClose()
        streams.clear()
        onClosed?.invoke()
    }
}
