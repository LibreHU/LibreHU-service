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
 * [mask] non zero (inverted by [invert]). VERSION: ASCII from [byte]. CAN: raw bytes from [byte]; with [byte] -1, a
 * copy of the whole frame as received, besides what the other inputs read from it (KSW: the car data are the MCU's
 * own frames, read by a car profile). KEY: channel at [byte], values after. DATE / TIME: data indexes in [fields]
 * (`year` or `yearHi` + `yearLo`, `month`, `day`; `hour`, `minute`, `second`).
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
 * with placeholders `{yearHi} {yearLo} {month} {day} {hour} {minute} {second} {data}`; the clock ones work in the
 * date and the time outputs alike (KSW sets both in one frame).
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
    /** Period of the « beat » output (the MCU's heartbeat from the head unit, KSW: 68 08 00 every second); 0 = none. */
    val beatMs: Int = 0,
    /** Named frames (command, then data, as [outputs]) the MCU tab sends on demand: what the profile's MCU also does. */
    val commands: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "librehu-mcu-profile"
        const val VERSION = 1
        val OUTPUT_KEYS =
            listOf(
                "pcReady",
                "muteOn",
                "muteOff",
                "ampOn",
                "ampOff",
                "antennaOn",
                "antennaOff",
                "date",
                "time",
                "can",
                "queryClock",
                "watchdogOff",
                "resetSoc",
                "sleepTimer",
                "beat",
            )
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
                    "watchdogOff" to "1F 05",
                    "resetSoc" to "0E 00 00 00",
                    "sleepTimer" to "F1 {units}",
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

    /**
     * KSW, the MCU of the Android screens that sit in front of the car's own system (BMW, Mercedes-Benz…): frames
     * `F2 00 CMD LEN DATA SUM`, SUM = (bytes 1 to the last data byte) xor 0xFF, the same both ways. From VincentZ4's
     * « KSW API – Commands » v2.12 (PX6, Android 9, BMW F1x CIC: port, framing, commands 60-79), McuCommunicator
     * (KswCarProject, GPL-3.0: heartbeat, events) and KSW's own centre service as KswCarProject published it
     * (KswCenterService: 115200 baud, version request 61 00, ACC 10 / 11 05, reverse 11 01, screen and voice frames).
     * The car data (frames A1, sub-command in D0) go whole to the CAN engine, read by a car profile; the handbrake is
     * read here too. Left out: the commands that change what the MCU stores (car type, CAN protocol, cameras…), its
     * upgrade mode and the power off.
     */
    val KSW_PX6 =
        McuProfile(
            id = "ksw-px6",
            name = "KSW PX6",
            description =
                "KSW MCU (Android screens of BMW, Mercedes-Benz…), F2 00 frames. /dev/ttyS3 on PX6 (KSW API v2.12); Snapdragon " +
                    "units: /dev/ttyMSM1 (/dev/ttyHSL1 on MSM8937, /dev/ttyHS1 on M600 with Android 11+): export, change port " +
                    "and id, import. Not tried on a head unit yet.",
            serial = SerialConfig("/dev/ttyS3", 115200),
            frame =
                FrameSpec(
                    header = listOf(0xF2, 0x00),
                    lengthOffset = 3,
                    lengthCounts = LengthCounts.DATA,
                    cmdOffset = 2,
                    checksum = ChecksumType.TWOS8,
                    checksumFrom = 1,
                    checksumAdjust = -1,
                ),
            inputs =
                listOf(
                    InputSpec(Signal.ACC, 0x10, mask = 0x01),
                    InputSpec(Signal.ACC, 0x11, match = mapOf(0 to 0x05), byte = 1, mask = 0x01),
                    InputSpec(Signal.REVERSE, 0x11, match = mapOf(0 to 0x01), byte = 1, mask = 0x01),
                    InputSpec(Signal.VERSION, 0x12),
                    InputSpec(Signal.HANDBRAKE, 0xA1, match = mapOf(0 to 0x10), byte = 1, mask = 0x08),
                    InputSpec(Signal.CAN, 0xA1, byte = -1),
                ),
            outputs =
                mapOf(
                    "pcReady" to "61 00",
                    "time" to "64 {yearLo} {month} {day} {hour} {minute} {second} 00",
                    "beat" to "68 08 00",
                ),
            beatMs = 1000,
            commands =
                mapOf(
                    "Screen: Android" to "69 12 01",
                    "Screen: car (OEM)" to "69 12 02",
                    "Sound: car (OEM)" to "67 00",
                    "Sound: Android music" to "67 01",
                    "Screen on" to "6C 02 01",
                    "Screen off" to "6C 02 00",
                    "Voice control on" to "69 13 01 00",
                    "Voice control off" to "69 13 00 00",
                    "Playing" to "69 14 01",
                    "Not playing" to "69 14 00",
                    "A/C temperature +" to "60 01",
                    "A/C temperature -" to "61 01 81",
                    "Front camera on" to "66 01 01",
                    "Front camera off" to "66 00 01",
                ),
        )

    val BUILT_IN = listOf(JANCAR_JAC_V1, KSW_PX6)

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
                if (p.beatMs > 0) put("beatMs", p.beatMs)
                if (p.commands.isNotEmpty()) put("commands", buildJsonObject { p.commands.forEach { (k, v) -> put(k, v) } })
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
        for (i in inputs) {
            if (i.byte < -1 || (i.byte == -1 && i.signal != Signal.CAN)) {
                throw ProfileException("inputs.byte: 0 or more (-1, the whole frame: CAN inputs only)")
            }
        }
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
        val commands =
            root
                .obj("commands")
                ?.entries
                ?.associate { (k, v) -> k to v.jsonPrimitive.content }
                .orEmpty()
        for ((k, v) in commands) {
            if (k.isBlank() || k.length > 60) throw ProfileException("Bad command name \"$k\"")
            try {
                template(v, emptyMap(), ByteArray(0))
            } catch (e: Exception) {
                throw ProfileException("Bad command \"$k\": ${e.message}")
            }
        }
        val beatMs = root.int("beatMs") ?: 0
        if (beatMs != 0 && beatMs !in 100..60_000) throw ProfileException("beatMs: 100 to 60000 (or 0)")
        if (beatMs > 0 && "beat" !in outputs) throw ProfileException("beatMs without a \"beat\" output")
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
            beatMs = beatMs,
            commands = commands,
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
        for (i in profile.inputs) {
            if (!i.matches(frame) || (i.signal == Signal.CAN && i.byte < 0)) continue
            val event = toEvent(i, frame) ?: continue
            return event
        }
        return McuEvent.Other(frame)
    }

    private fun InputSpec.matches(frame: McuFrame): Boolean =
        cmd == frame.cmd && match.none { (idx, v) -> idx >= frame.data.size || frame.u(idx) != v }

    override fun canCopy(frame: McuFrame): ByteArray? =
        if (profile.inputs.any { it.signal == Signal.CAN && it.byte < 0 && it.matches(frame) }) encode(frame) else null

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

    /**
     * Every clock placeholder, from the system clock, for an output that sets the date and the time in one frame
     * (KSW): the values given to [date] and [time] replace them, the date given last is kept for [time].
     */
    private fun clock(): Map<String, Int> {
        val t = java.time.LocalDateTime.now()
        return mapOf(
            "yearHi" to t.year / 100,
            "yearLo" to t.year % 100,
            "month" to t.monthValue,
            "day" to t.dayOfMonth,
            "hour" to t.hour,
            "minute" to t.minute,
            "second" to t.second,
        )
    }

    @Volatile
    private var lastDate: Map<String, Int> = emptyMap()

    override fun date(
        year: Int,
        month: Int,
        day: Int,
    ): McuFrame? {
        lastDate = mapOf("yearHi" to year / 100, "yearLo" to year % 100, "month" to month, "day" to day)
        return out("date", clock() + lastDate)
    }

    override fun time(
        hour: Int,
        minute: Int,
        second: Int,
    ) = out("time", clock() + lastDate + mapOf("hour" to hour, "minute" to minute, "second" to second))

    override fun beat() = out("beat")

    override val beatMs: Long get() = profile.beatMs.toLong()

    /** The profile's command [name] as a frame, null when it has none. */
    fun command(name: String): McuFrame? = profile.commands[name]?.let { McuProfiles.template(it, emptyMap(), ByteArray(0)) }

    override fun canData(bytes: ByteArray) = out("can", data = bytes)

    override fun queryClock() = out("queryClock")

    override val readsClock: Boolean get() = "queryClock" in profile.outputs || profile.inputs.any { it.signal == Signal.TIME }

    override fun watchdogOff() = out("watchdogOff")

    override fun resetSoc() = out("resetSoc")

    override fun sleepTimer(units: Int) = out("sleepTimer", mapOf("units" to units.coerceIn(0, 255)))
}
