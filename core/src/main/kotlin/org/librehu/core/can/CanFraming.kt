package org.librehu.core.can

/**
 * How a box type frames its messages (`"framing"`, format 2), described in the box type's generic profile (engine
 * 2.6, [CanBoxFamilies]) or in a car profile for a box of its own: `HEAD… [LEN CMD | CMD LEN] D0..Dn-1 SUM TAIL…`.
 * LEN = n + [lenOffset]; SUM over [sumOver] (the fields `len`, `cmd`, `data`, `head`), `sum` (added) or `xor` (xored)
 * on [sumSize] bytes (big-endian), then + [sumAdd] and xor [sumXor]. The head unit acknowledges each good message with
 * [ackCmd] (data: the message's command) framed the same way, or the raw bytes [ackRaw], except the commands
 * [noAckFor]; the single bytes [boxAnswers] the box sends between messages are its own acknowledgements.
 *
 * Engine 2.7, for the boxes whose frames lack a part (each read from the decoder of the stock app, its deframer):
 * - no CMD ([hasCmd] false, `"order": "len"`): the length is the message's command (Simple's Audi box, `AA 55 LEN D… SUM`);
 * - no LEN ([hasLen] false, `"order": "cmd"`): [dataLen] data bytes (CYT's BYD box, `55 D0..D5 AA`);
 * - no head: a frame starts with one of the commands [starts] (CYT's Ford box, `55|57 LEN D…`);
 * - [tail]: bytes after the checksum (CYT's BYD box: `AA`); `"algo": "none"`: no checksum;
 * - [out]: the framing of what the head unit sends, when it differs from what the box sends (Simple: `55 AA` out,
 *   `AA 55` in; Besturn X80: `FD CMD LEN` out, `FD LEN CMD` in).
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
    val hasLen: Boolean = true,
    val hasCmd: Boolean = true,
    val dataLen: Int = 0,
    val tail: List<Int> = emptyList(),
    val starts: Set<Int> = emptySet(),
    val out: CanFraming? = null,
) {
    /** Uses what engine 2.7 brought: older services and TWRP's reader (touchfix) cannot read it. */
    val needs27: Boolean get() = !hasLen || !hasCmd || head.isEmpty() || tail.isNotEmpty() || sumAlgo == "none" || out != null

    /** Bytes of the checksum (none: 0). */
    val sumBytes: Int get() = if (sumAlgo == "none") 0 else sumSize

    /** LEN and CMD as the frame has them, in its order. */
    internal val fields: List<String> =
        (if (lenFirst) listOf("len", "cmd") else listOf("cmd", "len")).filter { (it == "len" && hasLen) || (it == "cmd" && hasCmd) }

    fun checksum(
        len: Int,
        cmd: Int,
        data: ByteArray,
    ): Int {
        if (sumAlgo == "none") return 0
        val bytes = ArrayList<Int>()
        if ("head" in sumOver) bytes += head
        // In the order of the frame.
        for (name in fields) if (name in sumOver) bytes += (if (name == "len") len else cmd) and 0xFF
        if ("data" in sumOver) data.forEach { bytes += it.toInt() and 0xFF }
        val raw = if (sumAlgo == "xor") bytes.fold(0) { a, b -> a xor b } else bytes.sum()
        val mask = if (sumSize == 2) 0xFFFF else 0xFF
        return (((raw + sumAdd) and mask) xor sumXor) and mask
    }

    /** A frame the head unit sends ([out] when the box reads another framing). Without LEN: [dataLen] bytes of [data]. */
    fun encode(
        cmd: Int,
        data: ByteArray,
    ): ByteArray {
        out?.let { return it.encode(cmd, data) }
        val d = if (hasLen) data else ByteArray(dataLen) { if (it < data.size) data[it] else 0 }
        val len = d.size + lenOffset
        val bytes = ArrayList<Byte>()
        head.forEach { bytes += it.toByte() }
        for (name in fields) bytes += (if (name == "len") len else cmd).toByte()
        d.forEach { bytes += it }
        if (sumBytes > 0) {
            val s = checksum(len, cmd, d)
            if (sumSize == 2) bytes += (s shr 8).toByte()
            bytes += s.toByte()
        }
        tail.forEach { bytes += it.toByte() }
        return bytes.toByteArray()
    }

    /** As a family (`"box"` of the profile), named [id]; [name]: the box type's (`box_type.name`), else made from the head. */
    fun family(
        id: String,
        name: String? = null,
    ): CanBoxFamily =
        object : CanBoxFamily {
            override val id = id
            override val name =
                name ?: ("Profile framing (" + (head.ifEmpty { starts.sorted() }).joinToString(" ") { "%02X".format(it) } + ")")
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

/** Rebuilds messages of a [CanFraming]: head (or one of its first commands), LEN / CMD, data, checksum, tail. */
class FramingParser(
    private val f: CanFraming,
    private val onFrame: (CanboxFrame) -> Unit,
) : CanBoxParser {
    private enum class Part { HEAD, FIELDS, DATA, SUM, TAIL }

    private var part = Part.HEAD
    private var headPos = 0
    private var fieldPos = 0
    private var len = 0
    private var cmd = 0
    private var buf = ByteArray(0)
    private var pos = 0
    private var sum = 0
    private var sumGot = 0
    private var tailPos = 0
    private var tailOk = true

    override var skipped = 0L
        private set

    /** The box's single-byte acknowledgements. */
    var answers = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun restart() {
        part = Part.HEAD
        headPos = 0
    }

    private fun fields(from: Int) {
        fieldPos = from
        if (fieldPos >= f.fields.size) startData() else part = Part.FIELDS
    }

    private fun startData() {
        if (!f.hasCmd) cmd = len
        val n = if (f.hasLen) len - f.lenOffset else f.dataLen
        if (n < 0 || n > 255) {
            skipped++
            restart()
            return
        }
        buf = ByteArray(n)
        pos = 0
        sum = 0
        sumGot = 0
        tailPos = 0
        tailOk = true
        if (n == 0) dataDone() else part = Part.DATA
    }

    private fun dataDone() {
        if (f.sumBytes > 0) part = Part.SUM else sumDone()
    }

    private fun sumDone() {
        if (f.tail.isNotEmpty()) part = Part.TAIL else emit()
    }

    private fun emit() {
        val ok = (f.sumBytes == 0 || sum == f.checksum(len, cmd, buf)) && tailOk
        onFrame(CanboxFrame(cmd, buf, ok))
        restart()
    }

    private fun feed(v: Int) {
        when (part) {
            Part.HEAD ->
                if (f.head.isEmpty()) {
                    // No head: a frame starts with one of its commands (CMD comes first, checked when the profile is read).
                    if (v in f.starts) {
                        cmd = v
                        fields(1)
                    } else if (v in f.boxAnswers) {
                        answers++
                    } else {
                        skipped++
                    }
                } else {
                    if (v == f.head[headPos]) {
                        headPos++
                    } else {
                        if (headPos == 0 && v in f.boxAnswers) answers++ else skipped++
                        headPos = if (v == f.head[0]) 1 else 0
                    }
                    if (headPos == f.head.size) {
                        headPos = 0
                        fields(0)
                    }
                }
            Part.FIELDS -> {
                if (f.fields[fieldPos] == "len") len = v else cmd = v
                fields(fieldPos + 1)
            }
            Part.DATA -> {
                buf[pos++] = v.toByte()
                if (pos == buf.size) dataDone()
            }
            Part.SUM -> {
                sum = (sum shl 8) or v
                if (++sumGot == f.sumBytes) sumDone()
            }
            Part.TAIL -> {
                if (v != f.tail[tailPos]) tailOk = false
                if (++tailPos == f.tail.size) emit()
            }
        }
    }
}
