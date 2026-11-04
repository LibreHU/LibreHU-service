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

            override fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser = HiworldParser(onFrame = onFrame)

            override fun encode(
                cmd: Int,
                data: ByteArray,
            ) = HiworldParser.encode(cmd, data)

            override fun ack(f: CanboxFrame): ByteArray? = if (f.cmd == 0xFF || f.cmd == 0xFE) null else encode(0xFF, byteArrayOf(f.cmd.toByte()))
        }

    /** Hiworld's other framing, `AA 55 LEN CMD D0..Dn-1 CS` (same checksum); the head unit sends no acknowledgement (`HdAA55Protocol`). */
    val HIWORLD_AA55 =
        object : CanBoxFamily {
            override val id = "hiworld-aa55"
            override val name = "Hiworld (AA 55)"
            override val defaultBaud = 38400

            override fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser = HiworldParser(0xAA, 0x55, onFrame)

            override fun encode(
                cmd: Int,
                data: ByteArray,
            ) = HiworldParser.encode(cmd, data, 0xAA, 0x55)

            override fun ack(f: CanboxFrame): ByteArray? = null
        }

    /**
     * Raise (RZC) and most other makers (Simple, Bagoo, Binary, Oudi, Luzheng, Keyichuang, Xinbas, Daojun, BSJ, CYT…):
     * `2E CMD LEN D0..Dn-1 CS`, CS = (CMD + LEN + ΣD) XOR 0xFF; the head unit acknowledges each message with the single
     * byte `FF`, the box answers with single bytes too (FF, F0, F3, FC) (`ReBaseProtocol`, `BnrProtocol`, `LzProtocol`,
     * `KcyProtocol`: `acknowledge` = `[FF]`, `haveNoPackageAck`).
     */
    val RAISE =
        object : CanBoxFamily {
            override val id = "raise"
            override val name = "Raise (2E)"
            override val defaultBaud = 38400

            override fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser = RaiseParser(onFrame)

            override fun encode(
                cmd: Int,
                data: ByteArray,
            ) = RaiseParser.encode(cmd, data)

            override fun ack(f: CanboxFrame): ByteArray = byteArrayOf(0xFF.toByte())
        }

    /** Raise's `FD LEN CMD D0..Dn-1 CS` (LEN = n + 3, CS = (LEN + CMD + ΣD) & 0xFF), no acknowledgement (`ReFDProtocol`). */
    val RAISE_FD = fd("raise-fd", "Raise (FD, 8-bit sum)", 1)

    /** Raise's `FD LEN CMD D0..Dn-1 CSH CSL` (LEN = n + 4, 16-bit sum of LEN, CMD and D), no acknowledgement (`ReFD2Protocol`). */
    val RAISE_FD2 = fd("raise-fd2", "Raise (FD, 16-bit sum)", 2)

    private fun fd(
        id: String,
        name: String,
        sumBytes: Int,
    ) = object : CanBoxFamily {
        override val id = id
        override val name = name
        override val defaultBaud = 38400

        override fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser = FdParser(onFrame, sumBytes)

        override fun encode(
            cmd: Int,
            data: ByteArray,
        ) = FdParser.encode(cmd, data, sumBytes)

        override fun ack(f: CanboxFrame): ByteArray? = null
    }

    val ALL: List<CanBoxFamily> = listOf(HIWORLD, HIWORLD_AA55, RAISE, RAISE_FD, RAISE_FD2)

    /**
     * Box makers of Hiworld's app ("Car Info" `assets/canbus.db`, table `canbox`) → the framing most of their
     * protocols use (the base class their decoders extend). A profile may name the maker (`"box": "simple"`).
     */
    val MAKERS: Map<String, CanBoxFamily> =
        mapOf(
            "hiworld" to HIWORLD,
            "wy" to HIWORLD,
            "raise" to RAISE,
            "simple" to RAISE,
            "bagoo" to RAISE,
            "binary" to RAISE,
            "oudi" to RAISE,
            "luzheng" to RAISE,
            "keyichuang" to RAISE,
            "xinbas" to RAISE,
            "dogen" to RAISE,
            "hcy" to RAISE,
            "bsj" to RAISE,
            "cyt" to RAISE,
            "autoai" to RAISE,
            "rcw" to RAISE,
            "hechi" to RAISE,
            "xfy" to RAISE,
            "hangdao" to RAISE,
        )

    /** Speeds of the box's serial link the Jancar MCU knows (`0F 00 <index>`, MCU-tools-app docs/mcu_firmware.md). */
    val BAUDS: List<Int> get() = org.librehu.core.mcu.JacDebug.CAN_BAUDS

    fun byId(id: String): CanBoxFamily? = ALL.firstOrNull { it.id == id } ?: MAKERS[id]
}

/** Raise framing, `2E CMD LEN D0..Dn-1 CS`; the box's single-byte answers (FF, F0, F3, FC) are not messages. */
class RaiseParser(
    private val onFrame: (CanboxFrame) -> Unit,
) : CanBoxParser {
    private var state = 0
    private var cmd = 0
    private var len = 0
    private var buf = ByteArray(0)
    private var pos = 0

    override var skipped = 0L
        private set

    /** The box's single-byte answers (acknowledgements). */
    var answers = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun feed(v: Int) {
        when (state) {
            0 -> {
                when (v) {
                    HEAD -> state = 1
                    0xFF, 0xF0, 0xF3, 0xFC -> answers++
                    else -> skipped++
                }
            }
            1 -> {
                cmd = v
                state = 2
            }
            2 -> {
                len = v
                buf = ByteArray(len)
                pos = 0
                state = if (len == 0) 4 else 3
            }
            3 -> {
                buf[pos++] = v.toByte()
                if (pos == len) state = 4
            }
            4 -> {
                onFrame(CanboxFrame(cmd, buf, v == sum(cmd, buf)))
                state = 0
            }
        }
    }

    companion object {
        const val HEAD = 0x2E

        fun sum(
            cmd: Int,
            data: ByteArray,
        ): Int = ((cmd + data.size + data.sumOf { it.toInt() and 0xFF }) xor 0xFF) and 0xFF

        fun encode(
            cmd: Int,
            data: ByteArray,
        ): ByteArray = byteArrayOf(HEAD.toByte(), cmd.toByte(), data.size.toByte()) + data + sum(cmd, data).toByte()
    }
}

/** Raise's FD framing: `FD LEN CMD D0..Dn-1` then a sum of LEN, CMD and D on [sumBytes] bytes (big-endian). */
class FdParser(
    private val onFrame: (CanboxFrame) -> Unit,
    private val sumBytes: Int,
) : CanBoxParser {
    private var state = 0
    private var len = 0
    private var cmd = 0
    private var buf = ByteArray(0)
    private var pos = 0
    private var got = 0
    private var have = 0

    override var skipped = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun feed(v: Int) {
        when (state) {
            0 -> if (v == HEAD) state = 1 else skipped++
            1 -> {
                len = v
                // LEN counts itself, CMD, the data and the sum.
                val n = len - 2 - sumBytes
                if (n < 0) {
                    skipped += 2
                    state = 0
                } else {
                    buf = ByteArray(n)
                    pos = 0
                    state = 2
                }
            }
            2 -> {
                cmd = v
                got = 0
                have = 0
                state = if (buf.isEmpty()) 4 else 3
            }
            3 -> {
                buf[pos++] = v.toByte()
                if (pos == buf.size) state = 4
            }
            4 -> {
                have = (have shl 8) or v
                if (++got == sumBytes) {
                    onFrame(CanboxFrame(cmd, buf, have == sum(len, cmd, buf, sumBytes)))
                    state = 0
                }
            }
        }
    }

    companion object {
        const val HEAD = 0xFD

        fun sum(
            len: Int,
            cmd: Int,
            data: ByteArray,
            sumBytes: Int,
        ): Int {
            val s = len + cmd + data.sumOf { it.toInt() and 0xFF }
            return if (sumBytes == 1) s and 0xFF else s and 0xFFFF
        }

        fun encode(
            cmd: Int,
            data: ByteArray,
            sumBytes: Int,
        ): ByteArray {
            val len = data.size + 2 + sumBytes
            val s = sum(len, cmd, data, sumBytes)
            val tail = if (sumBytes == 1) byteArrayOf(s.toByte()) else byteArrayOf((s shr 8).toByte(), s.toByte())
            return byteArrayOf(HEAD.toByte(), len.toByte(), cmd.toByte()) + data + tail
        }
    }
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
