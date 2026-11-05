package org.librehu.core.can

/**
 * A box family described in the profile (`"framing"`, format 2) instead of in code, for a box maker LibreHU has no
 * family for: `HEAD… [LEN CMD | CMD LEN] D0..Dn-1 SUM`. LEN = n + [lenOffset]; SUM over [sumOver] (the fields
 * `len`, `cmd`, `data`, `head`), `sum` (added) or `xor` (xored) on [sumSize] bytes (big-endian), then + [sumAdd] and
 * xor [sumXor]. The head unit acknowledges each good message with [ackCmd] (data: the message's command) framed the
 * same way, or the raw bytes [ackRaw], except the commands [noAckFor]; the single bytes [boxAnswers] the box sends
 * between messages are its own acknowledgements.
 *
 * ```json
 * "framing": { "head": "5A A5", "order": "len,cmd", "len_offset": 0,
 *   "checksum": { "algo": "sum", "over": "len,cmd,data", "size": 1, "add": -1, "xor": 0 },
 *   "ack": { "cmd": "0xFF" }, "no_ack_for": ["0xFF", "0xFE"], "box_answers": [] }
 * ```
 */
data class CanFraming(
    val head: List<Int>,
    val lenFirst: Boolean = true,
    val lenOffset: Int = 0,
    val sumAlgo: String = "sum",
    val sumOver: Set<String> = setOf("len", "cmd", "data"),
    val sumSize: Int = 1,
    val sumAdd: Int = 0,
    val sumXor: Int = 0,
    val ackCmd: Int? = null,
    val ackRaw: List<Int>? = null,
    val noAckFor: Set<Int> = emptySet(),
    val boxAnswers: Set<Int> = emptySet(),
    val baud: Int = 38400,
) {
    fun checksum(
        len: Int,
        cmd: Int,
        data: ByteArray,
    ): Int {
        val bytes = ArrayList<Int>()
        if ("head" in sumOver) bytes += head
        // In the order of the frame.
        val fields = if (lenFirst) listOf("len" to len, "cmd" to cmd) else listOf("cmd" to cmd, "len" to len)
        for ((name, v) in fields) if (name in sumOver) bytes += v and 0xFF
        if ("data" in sumOver) data.forEach { bytes += it.toInt() and 0xFF }
        val raw = if (sumAlgo == "xor") bytes.fold(0) { a, b -> a xor b } else bytes.sum()
        val mask = if (sumSize == 2) 0xFFFF else 0xFF
        return (((raw + sumAdd) and mask) xor sumXor) and mask
    }

    fun encode(
        cmd: Int,
        data: ByteArray,
    ): ByteArray {
        val len = data.size + lenOffset
        val out = ArrayList<Byte>()
        head.forEach { out += it.toByte() }
        if (lenFirst) {
            out += len.toByte()
            out += cmd.toByte()
        } else {
            out += cmd.toByte()
            out += len.toByte()
        }
        data.forEach { out += it }
        val s = checksum(len, cmd, data)
        if (sumSize == 2) out += (s shr 8).toByte()
        out += s.toByte()
        return out.toByteArray()
    }

    /** As a family (`"box"` of the profile), named [id]. */
    fun family(id: String): CanBoxFamily =
        object : CanBoxFamily {
            override val id = id
            override val name = "Profile framing (" + head.joinToString(" ") { "%02X".format(it) } + ")"
            override val defaultBaud = baud

            override fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser = FramingParser(this@CanFraming, onFrame)

            override fun encode(
                cmd: Int,
                data: ByteArray,
            ) = this@CanFraming.encode(cmd, data)

            override fun ack(f: CanboxFrame): ByteArray? =
                when {
                    f.cmd in noAckFor -> null
                    ackRaw != null -> ByteArray(ackRaw.size) { ackRaw[it].toByte() }
                    ackCmd != null -> encode(ackCmd, byteArrayOf(f.cmd.toByte()))
                    else -> null
                }

            override fun equals(other: Any?) = other is CanBoxFamily && other.id == id && other.name == name

            override fun hashCode() = id.hashCode()
        }
}

/** Rebuilds messages of a [CanFraming]. */
class FramingParser(
    private val f: CanFraming,
    private val onFrame: (CanboxFrame) -> Unit,
) : CanBoxParser {
    private var state = 0
    private var headPos = 0
    private var len = 0
    private var cmd = 0
    private var buf = ByteArray(0)
    private var pos = 0
    private var sum = 0
    private var sumGot = 0

    override var skipped = 0L
        private set

    /** The box's single-byte acknowledgements. */
    var answers = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun startData() {
        val n = len - f.lenOffset
        if (n < 0 || n > 255) {
            skipped++
            state = 0
            return
        }
        buf = ByteArray(n)
        pos = 0
        sum = 0
        sumGot = 0
        state = if (n == 0) 5 else 4
    }

    private fun feed(v: Int) {
        when (state) {
            // Head bytes, one by one.
            0 -> {
                if (v == f.head[headPos]) {
                    headPos++
                } else {
                    if (headPos == 0 && v in f.boxAnswers) answers++ else skipped++
                    headPos = if (v == f.head[0]) 1 else 0
                }
                if (headPos == f.head.size) {
                    headPos = 0
                    state = 2
                }
            }
            2 -> {
                if (f.lenFirst) len = v else cmd = v
                state = 3
            }
            3 -> {
                if (f.lenFirst) cmd = v else len = v
                startData()
            }
            4 -> {
                buf[pos++] = v.toByte()
                if (pos == buf.size) state = 5
            }
            5 -> {
                sum = (sum shl 8) or v
                if (++sumGot == f.sumSize) {
                    onFrame(CanboxFrame(cmd, buf, sum == f.checksum(len, cmd, buf)))
                    state = 0
                }
            }
        }
    }
}
