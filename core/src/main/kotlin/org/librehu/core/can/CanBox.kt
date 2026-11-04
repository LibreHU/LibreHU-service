package org.librehu.core.can

/** Rebuilds the messages of a CAN box from the bytes relayed by the MCU (frames `10`, cut anywhere). */
interface CanBoxParser {
    fun feed(bytes: ByteArray)

    /** Bytes that did not belong to a message (other framing, noise). */
    val skipped: Long
}

/**
 * A family of CAN boxes (Hiworld, Raise…): how its messages are framed, how the head unit writes one, and how it
 * acknowledges the box's. The car's decoding is in the vehicle profile ([CanVehicle]), which names its family.
 */
interface CanBoxFamily {
    /** Name in the profiles (`"box"`). */
    val id: String
    val name: String

    /** Speed of the box's serial link (MCU `0F 00`), when the profile gives none. */
    val defaultBaud: Int

    fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser

    fun encode(
        cmd: Int,
        data: ByteArray,
    ): ByteArray

    /** The head unit's answer to [f] (good checksum only), or null: the box's own acknowledgements, no answer in this family. */
    fun ack(f: CanboxFrame): ByteArray?
}

/** The CAN box families LibreHU knows. */
object CanBoxFamilies {
    /**
     * Hiworld, `5A A5 LEN CMD D0..Dn-1 CS`, CS = ((LEN + CMD + ΣD) & 0xFF) - 1, 38400 bit/s. The head unit acknowledges
     * every message `5A A5 01 FF <cmd> CS`, except the box's own acknowledgements (commands 0xFF and 0xFE): Hiworld's
     * app "Car Info" 1.0.3991 (`HdProtocol.acknowledge`, `isAckPacket`, `shouldSendAck` = true, sent by
     * `JancarRx.onHandlerOkData` for each good message).
     */
    val HIWORLD =
        object : CanBoxFamily {
            override val id = CanVehicle.BOX_HIWORLD
            override val name = "Hiworld (5A A5)"
            override val defaultBaud = 38400

            override fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser = HiworldParser(onFrame)

            override fun encode(
                cmd: Int,
                data: ByteArray,
            ) = HiworldParser.encode(cmd, data)

            override fun ack(f: CanboxFrame): ByteArray? = if (f.cmd == 0xFF || f.cmd == 0xFE) null else encode(0xFF, byteArrayOf(f.cmd.toByte()))
        }

    val ALL: List<CanBoxFamily> = listOf(HIWORLD)

    fun byId(id: String): CanBoxFamily? = ALL.firstOrNull { it.id == id }
}

/**
 * The head unit's side of the link with the CAN box, as the box's own app does it: each message with a good checksum
 * gets the family's acknowledgement ([CanLink.ack]), and the profile's messages ([CanLink.send], the car type of a
 * Hiworld box) go [CanSend.firstMs] after the start, then every [CanSend.everyMs]. The box coming back after a
 * silence (switched off, reset) gets them again from the start. Without them a Hiworld box waits: it sends its
 * messages one a second in a loop and never a steering wheel key (CAN logs of 02/11 and 04/11/2026).
 * Pure logic: [send] gets the bytes and what they are, [tick] is called regularly with the time (ms, monotonic).
 */
class CanBoxLink(
    private val family: CanBoxFamily,
    private val link: CanLink,
    private val send: (ByteArray, String) -> Unit,
    /** A command the box acknowledged (the car type…). */
    private val onAcked: (Int) -> Unit = {},
) {
    private val due = LongArray(link.send.size) { -1 }
    private var lastFrameAt = Long.MIN_VALUE

    /** Starts the schedule of the profile's messages. */
    @Synchronized
    fun start(now: Long) {
        for (i in due.indices) due[i] = now + link.send[i].firstMs
    }

    @Synchronized
    fun onFrame(
        f: CanboxFrame,
        now: Long,
    ) {
        if (lastFrameAt != Long.MIN_VALUE && now - lastFrameAt > SILENCE_MS) start(now)
        lastFrameAt = now
        if (!f.checksumOk) return
        if ((f.cmd == 0xFF || f.cmd == 0xFE) && f.data.isNotEmpty()) onAcked(f.data[0].toInt() and 0xFF)
        if (link.ack) family.ack(f)?.let { send(it, "ack") }
    }

    @Synchronized
    fun tick(now: Long) {
        for ((i, s) in link.send.withIndex()) {
            if (due[i] < 0 || now < due[i]) continue
            send(family.encode(s.cmd, ByteArray(s.data.size) { s.data[it].toByte() }), s.name)
            due[i] = if (s.everyMs > 0) now + s.everyMs else -1
        }
    }

    companion object {
        /** A box silent this long, then talking again, has restarted. */
        const val SILENCE_MS = 5_000L
    }
}
