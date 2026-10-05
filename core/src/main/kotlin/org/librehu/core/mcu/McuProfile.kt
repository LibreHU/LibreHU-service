package org.librehu.core.mcu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the length byte counts. */
enum class LengthCounts { DATA, CMD_DATA, FRAME, AFTER_LENGTH }

/** One-byte checksum over bytes [from] .. before the checksum, then `+ adjust`. */
enum class ChecksumType { NONE, SUM8, XOR8, TWOS8 }

data class FrameSpec(
    val header: List<Int>,
    val lengthOffset: Int,
    val lengthCounts: LengthCounts,
    val lengthAdd: Int = 0,
    val cmdOffset: Int,
    val checksum: ChecksumType,
    val checksumFrom: Int = 0,
    val checksumAdjust: Int = 0,
    val maxData: Int = 255,
)

data class AckSpec(
    val cmd: Int,
    /** Data byte holding the acknowledged command. */
    val ackedByte: Int = 0,
    val needs: Set<Int> = emptySet(),
    val timeoutMs: Long = 500,
    val tries: Int = 5,
)

/** Signals a received frame can carry. */
enum class Signal { ACC, HANDBRAKE, HEADLIGHT, BACKLIGHT, REVERSE, VERSION, DATE, TIME, KEY, CAN }

/**
 * How frame [cmd] (whose data bytes match [match], index → value) gives [signal]. Booleans: data byte [byte] and
 * [mask] non zero (inverted by [invert]). VERSION: ASCII from [byte]. CAN: raw bytes from [byte]. KEY: channel at
 * [byte], values after. DATE / TIME: data indexes in [fields] (`year` or `yearHi` + `yearLo`, `month`, `day`;
 * `hour`, `minute`, `second`).
 */
data class InputSpec(
    val signal: Signal,
    val cmd: Int,
    val match: Map<Int, Int> = emptyMap(),
    val byte: Int = 0,
    val mask: Int = 0xFF,
    val invert: Boolean = false,
    val fields: Map<String, Int> = emptyMap(),
)

/** Board wiring around the MCU: SoC GPIOs (null = not wired / not used) and audio processor. */
data class BoardSpec(
    val reverseGpio: Int? = null,
    val turnLeftGpio: Int? = null,
    val turnRightGpio: Int? = null,
    val backlightGpio: Int? = null,
    val ampMuteGpio: Int? = null,
    val antennaGpio: Int? = null,
    /** `bd37534` or null when the audio processor is not driven by the service. */
    val audioChip: String? = null,
    val audioBus: Int = 6,
    val audioAddress: Int = 0x40,
)

/**
 * A head unit model's MCU protocol, stored as JSON ("librehu-mcu-profile", version 1) so that users can add other
 * head units without code: see docs/mcu-profiles.md. [outputs] are hex templates (`"08 01"`, first byte = command)
 * with placeholders `{yearHi} {yearLo} {month} {day} {hour} {minute} {second} {data}`.
 */
data class McuProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val serial: SerialConfig,
    val frame: FrameSpec,
    val ack: AckSpec? = null,
    val inputs: List<InputSpec> = emptyList(),
    val outputs: Map<String, String> = emptyMap(),
    val board: BoardSpec = BoardSpec(),
) {
    companion object {
        const val FORMAT = "librehu-mcu-profile"
        const val VERSION = 1
        val OUTPUT_KEYS =
            listOf("pcReady", "muteOn", "muteOff", "ampOn", "ampOff", "antennaOn", "antennaOff", "date", "time", "can", "queryClock")
    }
}

class ProfileException(
    message: String,
) : Exception(message)

/** JSON reading / writing of [McuProfile]s, and the built-in profiles. */
object McuProfiles {
    private val json = Json { prettyPrint = true }

    const val FILE_EXTENSION = "json"

    /** Jancar JAC_V1, as a profile: the reference for writing others (the service uses [JacProtocol] for it). */
    val JANCAR_JAC_V1 =
        McuProfile(
            id = JacProtocol.id,
            name = JacProtocol.name,
            description = "Jancar UJC201 / AC8257 (board A0_AN), MCU HK32C030. Native implementation.",
            serial = JacProtocol.serial,
            frame =
                FrameSpec(
                    header = listOf(0xEE, 0xFA),
                    lengthOffset = 2,
                    lengthCounts = LengthCounts.CMD_DATA,
                    cmdOffset = 3,
                    checksum = ChecksumType.SUM8,
                    checksumFrom = 0,
                    maxData = JacFrame.MAX_DATA,
                ),
            ack = AckSpec(cmd = JacFrame.CMD_ACK, ackedByte = 0, needs = Mcu.NEEDS_ACK, timeoutMs = 500, tries = 5),
            inputs =
                listOf(
                    InputSpec(Signal.ACC, Mcu.CMD_ACC),
                    InputSpec(Signal.HANDBRAKE, Mcu.CMD_HANDBRAKE),
                    InputSpec(Signal.HEADLIGHT, Mcu.CMD_HEADLIGHT),
                    InputSpec(Signal.BACKLIGHT, Mcu.CMD_BACKLIGHT),
                    InputSpec(Signal.VERSION, Mcu.CMD_VERSION),
                    InputSpec(
                        Signal.DATE,
                        Mcu.CMD_DATE_TIME,
                        match = mapOf(0 to 0),
                        fields =
                            mapOf(
                                "yearHi" to 1,
                                "yearLo" to 2,
                                "month" to 3,
                                "day" to 4,
                            ),
                    ),
                    InputSpec(
                        Signal.TIME,
                        Mcu.CMD_DATE_TIME,
                        match = mapOf(0 to 1),
                        fields =
                            mapOf(
                                "hour" to 1,
                                "minute" to 2,
                                "second" to 3,
                            ),
                    ),
                    InputSpec(Signal.KEY, Mcu.CMD_KEY),
                    InputSpec(Signal.CAN, Mcu.CMD_CAN),
                ),
            outputs =
                mapOf(
                    "pcReady" to "1F 01",
                    "muteOn" to "08 01",
                    "muteOff" to "08 00",
                    "ampOn" to "44 01",
                    "ampOff" to "44 00",
                    "antennaOn" to "43 01",
                    "antennaOff" to "43 00",
                    "date" to "09 00 {yearHi} {yearLo} {month} {day}",
                    "time" to "09 01 {hour} {minute} {second}",
                    "can" to "10 {data}",
                    "queryClock" to "F0 09 00",
                ),
            board =
                BoardSpec(
                    reverseGpio = 2,
                    turnLeftGpio = 7,
                    turnRightGpio = 6,
                    backlightGpio = 5,
                    ampMuteGpio = 166,
                    antennaGpio = 110,
                    audioChip = "bd37534",
                    audioBus = 6,
                    audioAddress = 0x40,
                ),
        )

    val BUILT_IN = listOf(JANCAR_JAC_V1)

    /** Protocol to run for [profile]: the native one for Jancar, the generic engine otherwise. */
    fun protocolFor(profile: McuProfile): McuProtocol = if (profile.id == JacProtocol.id) JacProtocol else ProfileProtocol(profile)

    // --- JSON ----------------------------------------------------------------------------------------------------

    fun toJson(p: McuProfile): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("format", McuProfile.FORMAT)
                put("version", McuProfile.VERSION)
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put(
                    "serial",
                    buildJsonObject {
                        put("port", p.serial.port)
                        put("baud", p.serial.baud)
                    },
                )
                put(
                    "frame",
                    buildJsonObject {
                        put("header", hex(p.frame.header))
                        put("lengthOffset", p.frame.lengthOffset)
                        put(
                            "lengthCounts",
                            p.frame.lengthCounts.name
                                .lowercase(),
                        )
                        put("lengthAdd", p.frame.lengthAdd)
                        put("cmdOffset", p.frame.cmdOffset)
                        put(
                            "checksum",
                            p.frame.checksum.name
                                .lowercase(),
                        )
                        put("checksumFrom", p.frame.checksumFrom)
                        put("checksumAdjust", p.frame.checksumAdjust)
                        put("maxData", p.frame.maxData)
                    },
                )
                p.ack?.let { a ->
                    put(
                        "ack",
                        buildJsonObject {
                            put("cmd", hex(a.cmd))
                            put("ackedByte", a.ackedByte)
                            put("needs", buildJsonArray { a.needs.sorted().forEach { add(JsonPrimitive(hex(it))) } })
                            put("timeoutMs", a.timeoutMs)
                            put("tries", a.tries)
                        },
                    )
                }
                put(
                    "inputs",
                    buildJsonArray {
                        for (i in p.inputs) {
                            add(
                                buildJsonObject {
                                    put("signal", i.signal.name.lowercase())
                                    put("cmd", hex(i.cmd))
                                    if (i.match.isNotEmpty()) {
                                        put("match", buildJsonObject { i.match.forEach { (k, v) -> put(k.toString(), hex(v)) } })
                                    }
                                    if (i.byte != 0) put("byte", i.byte)
                                    if (i.mask != 0xFF) put("mask", hex(i.mask))
                                    if (i.invert) put("invert", true)
                                    if (i.fields.isNotEmpty()) put("fields", buildJsonObject { i.fields.forEach { (k, v) -> put(k, v) } })
                                },
                            )
                        }
                    },
                )
                put("outputs", buildJsonObject { p.outputs.forEach { (k, v) -> put(k, v) } })
                put(
                    "board",
                    buildJsonObject {
                        putNullable("reverseGpio", p.board.reverseGpio)
                        putNullable("turnLeftGpio", p.board.turnLeftGpio)
                        putNullable("turnRightGpio", p.board.turnRightGpio)
                        putNullable("backlightGpio", p.board.backlightGpio)
                        putNullable("ampMuteGpio", p.board.ampMuteGpio)
                        putNullable("antennaGpio", p.board.antennaGpio)
                        if (p.board.audioChip == null) put("audioChip", JsonNull) else put("audioChip", p.board.audioChip)
                        put("audioBus", p.board.audioBus)
                        put("audioAddress", hex(p.board.audioAddress))
                    },
                )
            },
        )

    /** Reads a profile; throws [ProfileException] with a readable reason when the file is not valid. */
    fun fromJson(text: String): McuProfile {
        val root =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ProfileException("Not JSON: ${e.message}")
            }
        val format = root.str("format")
        if (format != McuProfile.FORMAT) throw ProfileException("Not a LibreHU MCU profile (format = $format)")
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
        if (version > McuProfile.VERSION) throw ProfileException("Profile version $version is newer than this service")
        val id = root.str("id")?.trim().orEmpty()
        if (!Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(id)) throw ProfileException("Invalid id \"$id\" (a-z, 0-9, . _ -)")
        val serial = root.obj("serial")
        val f = root.obj("frame") ?: throw ProfileException("Missing \"frame\"")
        val frame =
            FrameSpec(
                header = parseHex(f.str("header") ?: throw ProfileException("Missing frame.header")),
                lengthOffset = f.int("lengthOffset") ?: throw ProfileException("Missing frame.lengthOffset"),
                lengthCounts = enumOf<LengthCounts>(f.str("lengthCounts") ?: "cmd_data", "frame.lengthCounts"),
                lengthAdd = f.int("lengthAdd") ?: 0,
                cmdOffset = f.int("cmdOffset") ?: throw ProfileException("Missing frame.cmdOffset"),
                checksum = enumOf<ChecksumType>(f.str("checksum") ?: "sum8", "frame.checksum"),
                checksumFrom = f.int("checksumFrom") ?: 0,
                checksumAdjust = f.int("checksumAdjust") ?: 0,
                maxData = f.int("maxData") ?: 255,
            )
        validate(frame)
        val ack =
            root.obj("ack")?.let { a ->
                AckSpec(
                    cmd = hexByte(a.str("cmd") ?: throw ProfileException("Missing ack.cmd"), "ack.cmd"),
                    ackedByte = a.int("ackedByte") ?: 0,
                    needs =
                        a["needs"]
                            ?.jsonArray
                            ?.map { hexByte(it.jsonPrimitive.content, "ack.needs") }
                            ?.toSet()
                            .orEmpty(),
                    timeoutMs = a["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 500,
                    tries = (a.int("tries") ?: 5).coerceIn(1, 20),
                )
            }
        val inputs =
            root["inputs"]
                ?.jsonArray
                ?.map { e ->
                    val o = e.jsonObject
                    InputSpec(
                        signal = enumOf(o.str("signal") ?: throw ProfileException("Input without signal"), "inputs.signal"),
                        cmd = hexByte(o.str("cmd") ?: throw ProfileException("Input without cmd"), "inputs.cmd"),
                        match =
                            o
                                .obj("match")
                                ?.entries
                                ?.associate { (k, v) ->
                                    (k.toIntOrNull() ?: throw ProfileException("Bad match index $k")) to
                                        hexByte(v.jsonPrimitive.content, "inputs.match")
                                }.orEmpty(),
                        byte = o.int("byte") ?: 0,
                        mask = o.str("mask")?.let { hexByte(it, "inputs.mask") } ?: 0xFF,
                        invert = o["invert"]?.jsonPrimitive?.booleanOrNull ?: false,
                        fields =
                            o
                                .obj("fields")
                                ?.entries
                                ?.associate { (k, v) ->
                                    k to
                                        (v.jsonPrimitive.intOrNull ?: throw ProfileException("Bad field $k"))
                                }.orEmpty(),
                    )
                }.orEmpty()
        val outputs =
            root
                .obj("outputs")
                ?.entries
                ?.associate { (k, v) -> k to v.jsonPrimitive.content }
                .orEmpty()
        for ((k, v) in outputs) {
            if (k !in
                McuProfile.OUTPUT_KEYS
            ) {
                throw ProfileException("Unknown output \"$k\" (known: ${McuProfile.OUTPUT_KEYS.joinToString()})")
            }
            try {
                template(v, emptyMap(), ByteArray(0))
            } catch (e: Exception) {
                throw ProfileException("Bad output \"$k\": ${e.message}")
            }
        }
        val b = root.obj("board")
        val board =
            BoardSpec(
                reverseGpio = b?.int("reverseGpio"),
                turnLeftGpio = b?.int("turnLeftGpio"),
                turnRightGpio = b?.int("turnRightGpio"),
                backlightGpio = b?.int("backlightGpio"),
                ampMuteGpio = b?.int("ampMuteGpio"),
                antennaGpio = b?.int("antennaGpio"),
                audioChip = b?.str("audioChip")?.lowercase()?.takeIf { it.isNotBlank() },
                audioBus = b?.int("audioBus") ?: 6,
                audioAddress = b?.str("audioAddress")?.let { hexByte(it, "board.audioAddress") } ?: 0x40,
            )
        if (board.audioChip != null && board.audioChip != "bd37534") throw ProfileException("Unsupported audio chip ${board.audioChip}")
        return McuProfile(
            id = id,
            name = root.str("name")?.ifBlank { null } ?: id,
            description = root.str("description").orEmpty(),
            serial = SerialConfig(serial?.str("port") ?: "/dev/ttyS1", serial?.int("baud") ?: 115200),
            frame = frame,
            ack = ack,
            inputs = inputs,
            outputs = outputs,
            board = board,
        )
    }

    private fun validate(f: FrameSpec) {
        if (f.header.isEmpty()) throw ProfileException("frame.header is empty")
        if (f.lengthOffset < f.header.size ||
            f.cmdOffset < f.header.size
        ) {
            throw ProfileException("Length and command must come after the header")
        }
        if (f.lengthOffset == f.cmdOffset) throw ProfileException("Length and command at the same offset")
        if (f.maxData !in 1..1024) throw ProfileException("frame.maxData out of 1..1024")
    }

    /** `"EE FA"`, `"eefa"`, `"0xEE 0xFA"` → bytes. */
    fun parseHex(text: String): List<Int> {
        val clean = text.replace("0x", "", ignoreCase = true).replace(",", " ").replace(" ", "")
        if (clean.length % 2 != 0 ||
            !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            throw ProfileException("Bad hex \"$text\"")
        }
        return List(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /**
     * Template → frame. Tokens are hex bytes or placeholders; `{data}` expands to [data] bytes, the others to one
     * byte from [values]. The first byte is the command.
     */
    fun template(
        text: String,
        values: Map<String, Int>,
        data: ByteArray,
    ): McuFrame {
        val bytes = ArrayList<Int>()
        for (token in text.trim().split(Regex("\\s+"))) {
            when {
                token == "{data}" -> data.forEach { bytes += it.toInt() and 0xFF }
                token.startsWith("{") && token.endsWith("}") -> bytes += (values[token.substring(1, token.length - 1)] ?: 0) and 0xFF
                else -> bytes += parseHex(token).also { if (it.size != 1) throw ProfileException("Bad byte \"$token\"") }[0]
            }
        }
        if (bytes.isEmpty()) throw ProfileException("Empty template")
        return McuFrame(bytes[0], ByteArray(bytes.size - 1) { bytes[it + 1].toByte() })
    }

    private fun hex(v: Int) = "%02X".format(v and 0xFF)

    private fun hex(v: List<Int>) = v.joinToString(" ") { hex(it) }

    private fun hexByte(
        s: String,
        what: String,
    ): Int = parseHex(s).singleOrNull() ?: throw ProfileException("$what: one byte expected, got \"$s\"")

    private inline fun <reified E : Enum<E>> enumOf(
        s: String,
        what: String,
    ): E =
        enumValues<E>().firstOrNull { it.name.equals(s, ignoreCase = true) }
            ?: throw ProfileException("$what: unknown \"$s\" (${enumValues<E>().joinToString { it.name.lowercase() }})")

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        k: String,
        v: Int?,
    ) {
        if (v == null) put(k, JsonNull) else put(k, v)
    }
}

/** Generic engine running a [McuProfile]. */
class ProfileProtocol(
    val profile: McuProfile,
) : McuProtocol {
    override val id get() = profile.id
    override val name get() = profile.name
    override val serial get() = profile.serial
    override val ackTimeoutMs get() = profile.ack?.timeoutMs ?: 500
    override val maxTries get() = profile.ack?.tries ?: 1

    private val f = profile.frame

    /** Fixed bytes before the data: header, length and command. */
    private val prefix = maxOf(f.lengthOffset, f.cmdOffset) + 1

    override fun encode(frame: McuFrame): ByteArray {
        require(frame.data.size <= f.maxData) { "MCU frame data too long: ${frame.data.size}" }
        val total = prefix + frame.data.size + if (f.checksum == ChecksumType.NONE) 0 else 1
        val out = ByteArray(total)
        f.header.forEachIndexed { i, b -> out[i] = b.toByte() }
        out[f.lengthOffset] = (lengthValue(frame.data.size, total) + f.lengthAdd).toByte()
        out[f.cmdOffset] = frame.cmd.toByte()
        frame.data.copyInto(out, prefix)
        if (f.checksum != ChecksumType.NONE) out[total - 1] = checksum(out, total - 1).toByte()
        return out
    }

    private fun lengthValue(
        dataSize: Int,
        total: Int,
    ): Int =
        when (f.lengthCounts) {
            LengthCounts.DATA -> dataSize
            LengthCounts.CMD_DATA -> dataSize + 1
            LengthCounts.FRAME -> total
            LengthCounts.AFTER_LENGTH -> total - f.lengthOffset - 1
        }

    /** Total frame size announced by length byte [len], or -1 when impossible. */
    internal fun totalFromLength(len: Int): Int {
        val v = len - f.lengthAdd
        val cs = if (f.checksum == ChecksumType.NONE) 0 else 1
        val total =
            when (f.lengthCounts) {
                LengthCounts.DATA -> prefix + v + cs
                LengthCounts.CMD_DATA -> prefix + v - 1 + cs
                LengthCounts.FRAME -> v
                LengthCounts.AFTER_LENGTH -> v + f.lengthOffset + 1
            }
        val data = total - prefix - cs
        return if (data < 0 || data > f.maxData) -1 else total
    }

    internal fun checksum(
        bytes: ByteArray,
        end: Int,
    ): Int {
        var acc = 0
        for (i in f.checksumFrom until end) {
            val b = bytes[i].toInt() and 0xFF
            acc = if (f.checksum == ChecksumType.XOR8) acc xor b else acc + b
        }
        val base =
            when (f.checksum) {
                ChecksumType.TWOS8 -> -acc
                else -> acc
            }
        return (base + f.checksumAdjust) and 0xFF
    }

    override fun newParser(onFrame: (McuFrame) -> Unit): FrameParser = Parser(onFrame)

    private inner class Parser(
        private val onFrame: (McuFrame) -> Unit,
    ) : FrameParser {
        private val buf = ByteArray(prefix + f.maxData + 1)
        private var pos = 0
        private var expected = -1

        override var errors = 0
            private set

        override fun reset() {
            pos = 0
            expected = -1
        }

        override fun feed(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (i in offset until offset + length) push(bytes[i].toInt() and 0xFF)
        }

        private fun push(b: Int) {
            if (pos < f.header.size) {
                if (b == f.header[pos]) {
                    buf[pos++] = b.toByte()
                } else {
                    reset()
                    if (b == f.header[0]) buf[pos++] = b.toByte()
                }
                return
            }
            buf[pos++] = b.toByte()
            if (pos == f.lengthOffset + 1) {
                expected = totalFromLength(b)
                if (expected < prefix || expected > buf.size) {
                    errors++
                    resync()
                    return
                }
            }
            if (expected > 0 && pos == expected) complete()
        }

        private fun complete() {
            val len = pos
            val ok = f.checksum == ChecksumType.NONE || (buf[len - 1].toInt() and 0xFF) == checksum(buf, len - 1)
            if (ok) {
                val dataEnd = if (f.checksum == ChecksumType.NONE) len else len - 1
                onFrame(McuFrame(buf[f.cmdOffset].toInt() and 0xFF, buf.copyOfRange(prefix, dataEnd)))
                reset()
            } else {
                errors++
                resync()
            }
        }

        /** Looks for a new header inside the rejected bytes. */
        private fun resync() {
            val rest = buf.copyOfRange(1, pos)
            reset()
            for (x in rest) push(x.toInt() and 0xFF)
        }
    }

    override fun ackedCommand(frame: McuFrame): Int? {
        val a = profile.ack ?: return null
        if (frame.cmd != a.cmd || frame.data.size <= a.ackedByte) return null
        return frame.u(a.ackedByte)
    }

    override fun needsAck(frame: McuFrame) = profile.ack?.needs?.contains(frame.cmd) == true

    override fun decode(frame: McuFrame): McuEvent {
        if (ackedCommand(frame) != null) return McuEvent.Ack(ackedCommand(frame)!!)
        val n = frame.data.size
        for (i in profile.inputs) {
            if (i.cmd != frame.cmd) continue
            if (i.match.any { (idx, v) -> idx >= n || frame.u(idx) != v }) continue
            val event = toEvent(i, frame) ?: continue
            return event
        }
        return McuEvent.Other(frame)
    }

    private fun toEvent(
        i: InputSpec,
        frame: McuFrame,
    ): McuEvent? {
        val n = frame.data.size

        fun bool(): Boolean? = if (i.byte < n) ((frame.u(i.byte) and i.mask) != 0) != i.invert else null

        fun field(k: String): Int? = i.fields[k]?.let { if (it < n) frame.u(it) else null }
        return when (i.signal) {
            Signal.ACC -> {
                bool()?.let { McuEvent.Acc(it) }
            }

            Signal.HANDBRAKE -> {
                bool()?.let { McuEvent.Handbrake(it) }
            }

            Signal.HEADLIGHT -> {
                bool()?.let { McuEvent.Headlight(it) }
            }

            Signal.BACKLIGHT -> {
                bool()?.let { McuEvent.Backlight(it) }
            }

            Signal.REVERSE -> {
                bool()?.let { McuEvent.Reverse(it) }
            }

            Signal.VERSION -> {
                McuEvent.Version(String(frame.data.copyOfRange(i.byte.coerceAtMost(n), n), Charsets.US_ASCII).trim { it <= ' ' })
            }

            Signal.CAN -> {
                McuEvent.Can(frame.data.copyOfRange(i.byte.coerceAtMost(n), n))
            }

            Signal.KEY -> {
                if (i.byte + 1 <
                    n
                ) {
                    McuEvent.Key(frame.u(i.byte), IntArray(n - i.byte - 1) { frame.u(i.byte + 1 + it) }, false)
                } else {
                    null
                }
            }

            Signal.DATE -> {
                val year = field("year")?.let { 2000 + it } ?: field("yearHi")?.let { hi -> field("yearLo")?.let { hi * 100 + it } }
                val month = field("month")
                val day = field("day")
                if (year != null && month != null && day != null) McuEvent.Date(year, month, day) else null
            }

            Signal.TIME -> {
                val h = field("hour")
                val m = field("minute")
                val s = field("second") ?: 0
                if (h != null && m != null) McuEvent.Time(h, m, s) else null
            }
        }
    }

    private fun out(
        key: String,
        values: Map<String, Int> = emptyMap(),
        data: ByteArray = ByteArray(0),
    ): McuFrame? = profile.outputs[key]?.let { McuProfiles.template(it, values, data) }

    override fun pcReady() = out("pcReady")

    override fun mute(on: Boolean) = out(if (on) "muteOn" else "muteOff")

    override fun externalAmp(on: Boolean) = out(if (on) "ampOn" else "ampOff")

    override fun antenna(on: Boolean) = out(if (on) "antennaOn" else "antennaOff")

    override fun date(
        year: Int,
        month: Int,
        day: Int,
    ) = out("date", mapOf("yearHi" to year / 100, "yearLo" to year % 100, "month" to month, "day" to day))

    override fun time(
        hour: Int,
        minute: Int,
        second: Int,
    ) = out("time", mapOf("hour" to hour, "minute" to minute, "second" to second))

    override fun canData(bytes: ByteArray) = out("can", data = bytes)

    override fun queryClock() = out("queryClock")
}
