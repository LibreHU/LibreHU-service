package org.librehu.core.can

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One value inside a CAN box message, with the signal syntax of CliOS' CAN dictionaries
 * (github.com/Tanchouteur/CliOS, `data/can/` dictionaries, `src/signal_processor.py`): [startByte] / [size] / [endian] read
 * an unsigned integer, then [mask], [shift], [signed] ([bitLength]), `value = raw × factor + offset`, dropped outside
 * [min]..[max]. With [bits], each named bit of the integer is a flag instead. Extensions: [label], [values] (names of
 * codes), [ascii] (text from [startByte] to the end), [signMagnitude] (top bit of [bitLength] = minus sign, the
 * rest = magnitude: Hiworld's steering angle). [startByte] counts from D0, the first data byte.
 *
 * Format 2: [role] (what the value means for LibreHU's features, [CanRoles]), [bitRoles] (the role of each named bit),
 * [invalid] (raw values meaning "no value", as 0xFF often does), [labels] (label per language, `{"en": …, "fr": …}`),
 * [condition] (decoded only when the message matches it: a code in a byte, a length).
 * [expr] (a formula, [CanExpr], giving the number instead of the bytes read; factor, offset, invalid, values and bits
 * apply to it) and [whenExpr] (a formula condition, `"when": "len == 2 && bit(D0, 1)"`).
 */
data class CanSignal(
    val name: String,
    val startByte: Int = 0,
    val size: Int = 1,
    val bigEndian: Boolean = true,
    val factor: Double = 1.0,
    val offset: Double = 0.0,
    val mask: Long? = null,
    val shift: Int = 0,
    val signed: Boolean = false,
    val signMagnitude: Boolean = false,
    val bitLength: Int = size * 8,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String = "",
    val label: String = "",
    val bits: Map<String, Int> = emptyMap(),
    val values: Map<Int, String> = emptyMap(),
    val ascii: Boolean = false,
    val role: String? = null,
    val bitRoles: Map<String, String> = emptyMap(),
    val invalid: Set<Long> = emptySet(),
    val labels: Map<String, String> = emptyMap(),
    val condition: CanCondition? = null,
    val expr: CanExpr? = null,
    val whenExpr: CanExpr? = null,
)

/**
 * When a signal (or a case of a message) applies, from the message itself: the byte [byte] (from D0, masked by [mask])
 * is one of [values], and the message has [len] data bytes, or [minLen]..[maxLen]. Null fields are not checked.
 * `"when": { "byte": 0, "is": ["0x10", "0x11"], "mask": "0x7F", "len": 2, "min_len": 2, "max_len": 8 }`.
 */
data class CanCondition(
    val byte: Int? = null,
    val values: Set<Int> = emptySet(),
    val mask: Int = 0xFF,
    val len: Int? = null,
    val minLen: Int? = null,
    val maxLen: Int? = null,
) {
    fun matches(data: ByteArray): Boolean {
        if (len != null && data.size != len) return false
        if (minLen != null && data.size < minLen) return false
        if (maxLen != null && data.size > maxLen) return false
        if (byte != null) {
            if (byte >= data.size) return false
            if (values.isNotEmpty() && (data[byte].toInt() and mask) !in values) return false
        }
        return true
    }
}

/**
 * A message of the CAN box: command [cmd], its [signals], and, when its layout depends on a code in one of its bytes
 * (a multiplexed message: the box puts several kinds of data under one command), the byte [mux] (from D0) and the
 * signals of each code ([cases]).
 */
data class CanMessage(
    val cmd: Int,
    val name: String,
    val signals: List<CanSignal>,
    val mux: Int? = null,
    val cases: Map<Int, CanCase> = emptyMap(),
) {
    /** The signals that apply to [data]: the message's, then those of its case. */
    fun signalsFor(data: ByteArray): List<CanSignal> {
        val case = mux?.takeIf { it < data.size }?.let { cases[data[it].toInt() and 0xFF] }
        val all = if (case == null) signals else signals + case.signals
        return all.filter { it.condition?.matches(data) ?: true }
    }

    /** Name for the viewer: the case's when it has one. */
    fun nameFor(data: ByteArray): String = mux?.takeIf { it < data.size }?.let { cases[data[it].toInt() and 0xFF]?.name } ?: name
}

/** The signals of one code of a multiplexed message ([CanMessage.mux]). */
data class CanCase(
    val name: String?,
    val signals: List<CanSignal>,
)

/**
 * A self-test of a profile (`"examples"`): message [cmd] with [data] must give each value of [expect], keyed by a
 * signal name, a bit name or a role (numbers within 0.01, flags, texts); a key mapped to null must give no value.
 */
data class CanExample(
    val cmd: Int,
    val data: List<Int>,
    val expect: Map<String, Any?>,
)

/** A decoded value: a number, a flag of a `bits` signal, or a text. */
data class CanValue(
    val key: String,
    val label: String,
    val message: String,
    val number: Double? = null,
    val flag: Boolean? = null,
    val text: String? = null,
    val unit: String = "",
    /** What the value means ([CanRoles]), when the profile says. */
    val role: String? = null,
    /** The label in other languages (`fr`…), [label] being the default. */
    val labels: Map<String, String> = emptyMap(),
) {
    /** The label in [lang] (a language code), else the role's, else the default. */
    fun labelIn(lang: String): String {
        labels[lang]?.let { return it }
        val r = role?.let { CanRoles.byId(it) }
        if (r != null && label == key) return if (lang == "fr") r.fr else r.en
        return label
    }

    fun display(): String =
        when {
            text != null -> text
            flag != null -> if (flag) "1" else "0"
            number == null -> "—"
            number == Math.floor(number) && kotlin.math.abs(number) < 1e9 -> number.toLong().toString()
            else -> "%.2f".format(number).trimEnd('0').trimEnd('.', ',')
        } + if (unit.isNotEmpty() && text == null && flag == null) " $unit" else ""
}

/**
 * A message the head unit sends to the box ([CanLink]): [firstMs] after the link starts (or after the box comes back
 * from a silence), then every [everyMs] (0: once). [data] = D0..Dn-1, framed by the box's family; empty: given by the
 * car chosen among the profile's variants, nothing is sent before (a box must not get another car's type).
 */
data class CanSend(
    val name: String,
    val cmd: Int,
    val data: List<Int>,
    val firstMs: Long = 1_000,
    val everyMs: Long = 0,
    /** Data built from the head unit's state (format 2: `{hour}`…, [CanTemplate]); [data] is then empty. */
    val template: CanTemplate? = null,
    /** `start`: [firstMs] then every [everyMs]; `change`: also as soon as the built data changes (clock, media…). */
    val on: String = "start",
)

/** A message sent on demand (`"link": { "commands": { "<name>": { "cmd", "data" } } }`), its data a [CanTemplate] reading `value`. */
data class CanCommand(
    val name: String,
    val cmd: Int,
    val template: CanTemplate,
)

/**
 * One car among those a profile covers (a box protocol serves many models): its [send] overrides the data of the
 * profile's messages of the same name (the car type the box needs for this model).
 */
data class CanVariant(
    val name: String,
    val send: Map<String, List<Int>>,
    /** Changes to the profile for this car (format 2, `"patch"`): any part of a profile, merged as `extends` does. */
    val patch: JsonObject? = null,
)

/**
 * The head unit's side of the link: [ack] each message of the box (as its family does: Hiworld acknowledges every
 * message), and the profile's [send] messages (a Hiworld box waits for its car type, command 0x24).
 */
data class CanLink(
    val ack: Boolean = true,
    val send: List<CanSend> = emptyList(),
    /** Messages sent on demand (a setting of the car, the climate…), by name. */
    val commands: Map<String, CanCommand> = emptyMap(),
)

/**
 * How to read the messages of one car's CAN box. The box (Hiworld here) is set for a car model and translates its
 * CAN bus into its own messages, so the decoding depends on the car: one profile per car, selectable and importable.
 */
data class CanVehicle(
    val id: String,
    val name: String,
    val description: String = "",
    val box: String = BOX_HIWORLD,
    val messages: Map<Int, CanMessage> = emptyMap(),
    /** What the head unit sends to the box (acknowledgements, car type). */
    val link: CanLink = CanLink(),
    /** Where the box puts the steering wheel keys; null: see [keys]. */
    val wheelKeys: WheelKeySpec? = null,
    /** Speed of the box's serial link, set on the MCU when the link starts (as the box's app does); null: left as it is. */
    val baud: Int? = null,
    /** The cars of this profile, chosen in the CAN tab; none chosen: [link] as it is. */
    val variants: List<CanVariant> = emptyList(),
    /** Self-tests of the decoding (format 2), checked by [selfTest]. */
    val examples: List<CanExample> = emptyList(),
    /** The whole profile as read, `extends` resolved (for the cars' [CanVariant.patch]); not part of equality. */
    val source: CanSource = CanSource(null),
    /** The box's framing, described in the profile (format 2): replaces the family named by [box]. */
    val framing: CanFraming? = null,
    /** More key sources (format 2, `"keys"`): media keys, front panel, knobs ([WheelKeySpec.codeBase]). */
    val moreKeys: List<WheelKeySpec> = emptyList(),
) {
    /** Every key source: the steering wheel's ([keys]) then [moreKeys]. */
    val allKeys: List<WheelKeySpec> get() = listOfNotNull(keys) + moreKeys

    /** This profile for the car [name] (one of [variants]); unknown or null: unchanged. */
    fun withVariant(name: String?): CanVehicle {
        val v = variants.firstOrNull { it.name == name } ?: return this
        val src = source.json
        val base = if (v.patch != null && src != null) CanVehicles.fromSource(CanVehicles.merge(src, v.patch)).copy(variants = variants, source = source) else this
        return base.copy(link = base.link.copy(send = base.link.send.map { s -> v.send[s.name]?.let { s.copy(data = it, template = null) } ?: s }))
    }

    /** The box family named by [box] (checked when the profile is read). */
    val family: CanBoxFamily get() = framingFamily ?: CanBoxFamilies.byId(box) ?: CanBoxFamilies.HIWORLD

    private val framingFamily: CanBoxFamily? by lazy { framing?.family(box) }

    /**
     * The steering wheel keys of this profile: its [wheelKeys], else Hiworld LNP002's when the profile names a
     * `wheel_key` signal in `0x11` (profiles written before `wheel_keys`), else none.
     */
    val keys: WheelKeySpec?
        get() =
            wheelKeys
                ?: WheelKeySpec.HIWORLD_LNP002.takeIf {
                    family === CanBoxFamilies.HIWORLD && messages[HiworldWheelKeys.CMD]?.signals?.any { s -> s.name == "wheel_key" } == true
                }

    /** Every value of [f] this profile knows; empty when the command is unknown. */
    fun decode(
        f: CanboxFrame,
        /** Last value of a role, from earlier messages (formulas' `role("…")`). */
        roles: (String) -> Double? = { null },
    ): List<CanValue> {
        val m = messages[f.cmd] ?: return emptyList()
        val name = m.nameFor(f.data)
        val known = HashMap<String, Double>()
        val out = ArrayList<CanValue>()
        for (s in m.signalsFor(f.data)) {
            if (s.whenExpr != null && !s.whenExpr.test(CanExpr.Context(f.data, signals = known, roles = roles))) continue
            for (v in decodeSignal(name, s, f.data, known, roles)) {
                out += v
                (v.number ?: v.flag?.let { if (it) 1.0 else 0.0 })?.let { known[v.key] = it }
            }
        }
        return out
    }

    /** The values of [f] by role (those whose signal names one). */
    fun decodeRoles(
        f: CanboxFrame,
        roles: (String) -> Double? = { null },
    ): Map<String, CanValue> = decode(f, roles).filter { it.role != null }.associateBy { it.role!! }

    /** The failures of this profile's [examples] (empty: all pass, or none). */
    fun selfTest(): List<String> =
        examples.flatMapIndexed { i, e ->
            val values = decode(CanboxFrame(e.cmd, ByteArray(e.data.size) { e.data[it].toByte() }, true))
            e.expect.mapNotNull { (k, want) ->
                val got = values.firstOrNull { it.key == k } ?: values.firstOrNull { it.role == k }
                val where = "examples[$i] 0x%02X %s".format(e.cmd, k)
                when {
                    want == null -> if (got != null) "$where: expected no value, got ${got.display()}" else null
                    got == null -> "$where: no value"
                    want is Boolean -> if (got.flag != want) "$where: expected $want, got ${got.display()}" else null
                    want is Number -> {
                        val n = got.number
                        if (n == null || kotlin.math.abs(n - want.toDouble()) > 0.01) "$where: expected $want, got ${got.display()}" else null
                    }
                    else -> if ((got.text ?: got.display()) != want.toString()) "$where: expected \"$want\", got \"${got.text ?: got.display()}\"" else null
                }
            }
        }

    /** One line for the CAN viewer, or null when the command is unknown. */
    fun describe(f: CanboxFrame): String? {
        val m = messages[f.cmd] ?: return null
        val values = decode(f)
        val name = m.nameFor(f.data)
        val flags = values.filter { it.flag == true }.joinToString(", ") { it.label }
        val others = values.filter { it.flag == null }.joinToString(" · ") { "${it.label} ${it.display()}" }
        return listOf(name, flags, others).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    companion object {
        const val BOX_HIWORLD = "hiworld"
    }
}

private fun decodeSignal(
    message: String,
    s: CanSignal,
    data: ByteArray,
    known: Map<String, Double> = emptyMap(),
    roles: (String) -> Double? = { null },
): List<CanValue> {
    val label = s.label.ifEmpty { s.name }
    if (s.ascii) {
        if (s.startByte >= data.size) return emptyList()
        val text = String(data, s.startByte, data.size - s.startByte, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        return listOf(CanValue(s.name, label, message, text = text, role = s.role, labels = s.labels))
    }
    val fits = s.startByte >= 0 && s.startByte + s.size <= data.size
    if (!fits && s.expr == null) return emptyList()
    var raw = 0L
    if (fits) {
        for (i in 0 until s.size) {
            val b = data[s.startByte + if (s.bigEndian) i else s.size - 1 - i].toLong() and 0xFF
            raw = (raw shl 8) or b
        }
    }
    if (s.expr != null) {
        // A formula: its result is the number; bits, invalid, factor, offset, bounds and names apply to it.
        val r = s.expr.eval(CanExpr.Context(data, raw, known, roles)) ?: return emptyList()
        if (s.bits.isNotEmpty()) {
            val bits = r.toLong()
            if (bits in s.invalid) return emptyList()
            return s.bits.map { (bit, pos) -> CanValue(bit, bit, message, flag = (bits shr pos) and 1L == 1L, role = s.bitRoles[bit]) }
        }
        if (r == kotlin.math.floor(r) && r.toLong() in s.invalid) return emptyList()
        val value = r * s.factor + s.offset
        if (s.min != null && value < s.min) return emptyList()
        if (s.max != null && value > s.max) return emptyList()
        val named = if (r == kotlin.math.floor(r)) s.values[r.toInt()] else null
        return listOf(
            if (named != null) {
                CanValue(s.name, label, message, number = value, text = named, role = s.role, labels = s.labels)
            } else {
                CanValue(s.name, label, message, value, unit = s.unit, role = s.role, labels = s.labels)
            },
        )
    }
    if (s.bits.isNotEmpty()) {
        if (raw in s.invalid) return emptyList()
        return s.bits.map { (bit, pos) -> CanValue(bit, bit, message, flag = (raw shr pos) and 1L == 1L, role = s.bitRoles[bit]) }
    }
    if (s.mask != null) raw = raw and s.mask
    if (s.shift > 0) raw = raw shr s.shift
    if (raw in s.invalid) return emptyList()
    if (s.signMagnitude && s.bitLength in 1..63) {
        val sign = 1L shl (s.bitLength - 1)
        if (raw and sign != 0L) raw = -(raw and (sign - 1))
    } else if (s.signed && s.bitLength in 1..63 && raw and (1L shl (s.bitLength - 1)) != 0L) {
        raw -= 1L shl s.bitLength
    }
    val value = raw * s.factor + s.offset
    if (s.min != null && value < s.min) return emptyList()
    if (s.max != null && value > s.max) return emptyList()
    val named = s.values[raw.toInt()]
    return listOf(
        if (named !=
            null
        ) {
            CanValue(s.name, label, message, number = value, text = named, role = s.role, labels = s.labels)
        } else {
            CanValue(s.name, label, message, value, unit = s.unit, role = s.role, labels = s.labels)
        },
    )
}

/** The JSON a profile was read from: carried along, never compared (two equal profiles may be written differently). */
class CanSource(
    val json: JsonObject?,
) {
    override fun equals(other: Any?) = other is CanSource

    override fun hashCode() = 0

    override fun toString() = "CanSource"
}

class CanVehicleException(
    message: String,
) : Exception(message)

/** Built-in car profiles and the JSON format of the importable ones (docs/can-vehicles.md). */
object CanVehicles {
    const val FORMAT = "librehu-can-vehicle"
    /** 2: roles, conditions, multiplexed messages, invalid values, labels per language, examples (1 is still read). */
    const val VERSION = 2

    private val json = Json { prettyPrint = true }

    /** A built-in profile, a JSON file next to this class (`vehicles/<id>.json`, generated from Hiworld's app: docs/can-vehicles.md). */
    private fun builtIn(id: String): CanVehicle = fromJson(builtInText(id) ?: throw CanVehicleException("Built-in profile $id missing"))

    /** Renault, Dacia, LADA, Samsung, Nissan, Mercedes, Hiworld protocol LNP002 (docs/ivi-services/12-canbus.md §12.4); Clio 3 by default. */
    val RENAULT_CLIO3_HIWORLD: CanVehicle by lazy { builtIn("renault-clio3-hiworld") }

    /** Hyundai, Kia, Hiworld protocol HYP001. */
    val HYUNDAI_KIA_HIWORLD: CanVehicle by lazy { builtIn("hyundai-kia-hiworld") }

    /** Toyota, Lexus ES, Hiworld protocol TYP001. */
    val TOYOTA_HIWORLD: CanVehicle by lazy { builtIn("toyota-hiworld") }

    /** Honda, Acura, Hiworld protocol HDP004. */
    val HONDA_HIWORLD: CanVehicle by lazy { builtIn("honda-hiworld") }

    /** Volkswagen, Skoda (MQB), Hiworld protocol VWP004: no car type, engine data (coolant, oil, engine speed). */
    val VW_MQB_HIWORLD: CanVehicle by lazy { builtIn("vw-mqb-hiworld") }

    /** Volkswagen, Skoda (older PQ platforms), older Mercedes, Hiworld protocol VWP003: no car type, keys without a state byte. */
    val VW_PQ_HIWORLD: CanVehicle by lazy { builtIn("vw-pq-hiworld") }

    /** Volkswagen, Skoda (MQB), Raise or Oudi box (Raise framing): hello 0x81 01, keys in 0x20, doors, outside temperature, radar. */
    val VW_MQB_RAISE: CanVehicle by lazy { builtIn("vw-mqb-raise") }

    /** Volkswagen, Skoda (older PQ platforms), Raise or Oudi box: hello 0x81 01, keys in 0x20, speed, lights / reverse, radar. */
    val VW_PQ_RAISE: CanVehicle by lazy { builtIn("vw-pq-raise") }

    /** Any car: no decoding, the CAN viewer shows the raw messages. */
    val GENERIC_HIWORLD =
        CanVehicle(
            id = "hiworld-generic",
            name = "Hiworld (raw messages only)",
            description = "No decoding: raw messages, to find the fields of a new car.",
        )

    val BUILT_IN: List<CanVehicle> by lazy { listOf(RENAULT_CLIO3_HIWORLD, HYUNDAI_KIA_HIWORLD, TOYOTA_HIWORLD, HONDA_HIWORLD, VW_MQB_HIWORLD, VW_PQ_HIWORLD, VW_MQB_RAISE, VW_PQ_RAISE, GENERIC_HIWORLD) }

    val DEFAULT_ID = "renault-clio3-hiworld"

    /** The text of a built-in profile, or null. */
    fun builtInText(id: String): String? = CanVehicles::class.java.getResourceAsStream("vehicles/$id.json")?.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * Reads a profile. `"extends": "<id>"` (format 2) starts from that profile ([resolve]: built-in ones by default),
     * then merges this one over it ([merge]).
     */
    fun fromJson(
        text: String,
        resolve: (String) -> String? = ::builtInText,
    ): CanVehicle = fromSource(resolveExtends(parseObject(text), resolve, 0))

    private fun parseObject(text: String): JsonObject =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw CanVehicleException("Not JSON: ${e.message}")
        }

    private fun resolveExtends(
        root: JsonObject,
        resolve: (String) -> String?,
        depth: Int,
    ): JsonObject {
        val parent = root.str("extends") ?: return root
        if (depth >= 8) throw CanVehicleException("extends: more than 8 levels (a loop?)")
        val text = resolve(parent) ?: throw CanVehicleException("extends: unknown profile \"$parent\"")
        val base = resolveExtends(parseObject(text), resolve, depth + 1)
        return JsonObject(merge(base, root) - "extends")
    }

    /**
     * [over] merged over [base]: objects key by key (messages, signals…), anything else replaced; a key set to null
     * removes it (a message, a signal of the parent profile).
     */
    fun merge(
        base: JsonObject,
        over: JsonObject,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((k, v) in over) {
            val b = out[k]
            when {
                v is kotlinx.serialization.json.JsonNull -> out.remove(k)
                v is JsonObject && b is JsonObject -> out[k] = merge(b, v)
                else -> out[k] = v
            }
        }
        return JsonObject(out)
    }

    /** A profile from its JSON object, `extends` already resolved. */
    fun fromSource(root: JsonObject): CanVehicle {
        val format = root.str("format")
        if (format != FORMAT) {
            if (root.containsKey("schema_version") && root.keys.any { it.startsWith("0x") }) {
                throw CanVehicleException(
                    "Raw CAN dictionary (CliOS): its frame ids are those of the car's bus, which the Hiworld box does not relay. " +
                        "Copy its signals into a \"$FORMAT\" profile, keyed by Hiworld commands.",
                )
            }
            throw CanVehicleException("Not a LibreHU CAN vehicle profile (format = $format)")
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > VERSION) throw CanVehicleException("Profile version $version is newer than this service")
        val id = root.str("id")?.trim().orEmpty()
        if (!Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(id)) throw CanVehicleException("Invalid id \"$id\" (a-z, 0-9, . _ -)")
        val box = root.str("box") ?: CanVehicle.BOX_HIWORLD
        val framing = (root["framing"] as? JsonObject)?.let(::framing)
        if (framing == null && CanBoxFamilies.byId(box) == null) {
            throw CanVehicleException(
                "Unknown box \"$box\" (supported: ${(CanBoxFamilies.ALL.map { it.id } + CanBoxFamilies.MAKERS.keys).distinct().joinToString()})",
            )
        }
        val messages =
            (root["messages"] as? JsonObject).orEmpty().map { (key, e) ->
                val cmd = hexKey(key)
                val o = e as? JsonObject ?: throw CanVehicleException("Message $key is not an object")
                val mux = o.int("mux")?.also { if (it !in 0..63) throw CanVehicleException("Message $key: mux 0..63") }
                val cases =
                    (o["cases"] as? JsonObject).orEmpty().map { (ck, ce) ->
                        val code = hexKey(ck)
                        val co = ce as? JsonObject ?: throw CanVehicleException("Message $key case $ck is not an object")
                        code to CanCase(co.str("name"), signals(co, "$key.$ck"))
                    }.toMap()
                if (cases.isNotEmpty() && mux == null) throw CanVehicleException("Message $key: cases without mux (the byte holding their code)")
                cmd to CanMessage(cmd, o.str("name") ?: "%02X".format(cmd), signals(o, key), mux, cases)
            }
        return CanVehicle(
            id = id,
            name = root.str("name")?.ifBlank { null } ?: id,
            description = root.str("description").orEmpty(),
            box = box,
            messages = messages.toMap(),
            link = (root["link"] as? JsonObject)?.let(::link) ?: CanLink(),
            wheelKeys = (root["wheel_keys"] as? JsonObject)?.let { wheelKeys(it) },
            moreKeys =
                ((root["keys"] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).mapIndexed { i, e ->
                    wheelKeys(e as? JsonObject ?: throw CanVehicleException("keys[$i] is not an object"), 256 * (i + 1), "keys[$i]")
                },
            variants =
                ((root["variants"] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).mapIndexed { i, e ->
                    val o = e as? JsonObject ?: throw CanVehicleException("variants[$i] is not an object")
                    val patch = o["patch"] as? JsonObject
                    if (patch != null && (patch.containsKey("id") || patch.containsKey("variants") || patch.containsKey("extends"))) {
                        throw CanVehicleException("variants[$i].patch: id, variants and extends cannot change")
                    }
                    CanVariant(
                        o.str("name")?.ifBlank { null } ?: throw CanVehicleException("variants[$i]: name missing"),
                        (o["send"] as? JsonObject).orEmpty().map { (k, v) -> k to bytes((v as? JsonPrimitive)?.contentOrNull.orEmpty(), "variants[$i].$k") }.toMap(),
                        patch,
                    )
                },
            baud =
                root.int("baud")?.also {
                    if (it !in CanBoxFamilies.BAUDS) throw CanVehicleException("baud $it (one of ${CanBoxFamilies.BAUDS.joinToString()})")
                },
            source = CanSource(root),
            framing = framing,
            examples =
                ((root["examples"] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).mapIndexed { i, e ->
                    val o = e as? JsonObject ?: throw CanVehicleException("examples[$i] is not an object")
                    CanExample(
                        o.str("cmd")?.let(::hexKey) ?: throw CanVehicleException("examples[$i]: cmd missing"),
                        bytes(o.str("data").orEmpty(), "examples[$i]"),
                        (o["expect"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                            val pv = v as? JsonPrimitive
                            when {
                                pv == null || v is kotlinx.serialization.json.JsonNull -> null
                                pv.isString -> pv.content
                                pv.booleanOrNull != null -> pv.booleanOrNull
                                else -> pv.doubleOrNull ?: throw CanVehicleException("examples[$i]: bad value $v")
                            }
                        },
                    )
                },
        )
    }

    private fun signals(
        o: JsonObject,
        key: String,
    ): List<CanSignal> =
        (o["signals"] as? JsonObject).orEmpty().map { (name, se) ->
            signal(name, se as? JsonObject ?: throw CanVehicleException("Signal $key.$name is not an object"), key)
        }

    /** `"when": { "byte", "is", "mask", "len", "min_len", "max_len" }`. */
    private fun condition(
        o: JsonObject,
        where: String,
    ): CanCondition {
        fun small(k: String) = o.int(k)?.also { if (it !in 0..255) throw CanVehicleException("$where: $k 0..255") }
        val values =
            when (val v = o["is"]) {
                null -> emptyList()
                is kotlinx.serialization.json.JsonArray -> v.map { code(it, where) }
                else -> listOf(code(v, where))
            }
        val byte = o.int("byte")?.also { if (it !in 0..63) throw CanVehicleException("$where: byte 0..63") }
        if (values.isNotEmpty() && byte == null) throw CanVehicleException("$where: \"is\" without \"byte\"")
        return CanCondition(byte, values.toSet(), o.str("mask")?.let { code(JsonPrimitive(it), where) } ?: 0xFF, small("len"), small("min_len"), small("max_len"))
    }

    /** A byte value: a number or a hex string ("0x10"). */
    private fun code(
        e: JsonElement,
        where: String,
    ): Int {
        val p = e as? JsonPrimitive ?: throw CanVehicleException("$where: bad byte value $e")
        val v = if (p.isString) p.content.removePrefix("0x").removePrefix("0X").toIntOrNull(16) else p.intOrNull
        return v?.takeIf { it in 0..0xFF } ?: throw CanVehicleException("$where: bad byte value $e")
    }

    /** `"framing": { "head", "order", "len_offset", "checksum": { "algo", "over", "size", "add", "xor" }, "ack", "no_ack_for", "box_answers", "baud" }`. */
    private fun framing(o: JsonObject): CanFraming {
        val w = "framing"
        val head = bytes(o.str("head").orEmpty(), "$w.head")
        if (head.isEmpty() || head.size > 4) throw CanVehicleException("$w.head: 1 to 4 bytes")
        val order = o.str("order") ?: "len,cmd"
        if (order != "len,cmd" && order != "cmd,len") throw CanVehicleException("$w.order: \"len,cmd\" or \"cmd,len\"")
        val c = o["checksum"] as? JsonObject ?: JsonObject(emptyMap())
        val algo = c.str("algo") ?: "sum"
        if (algo != "sum" && algo != "xor") throw CanVehicleException("$w.checksum.algo: sum or xor")
        val over = (c.str("over") ?: "len,cmd,data").split(",").map { it.trim() }.toSet()
        if (!over.all { it in setOf("head", "len", "cmd", "data") }) throw CanVehicleException("$w.checksum.over: head, len, cmd, data")
        val size = c.int("size") ?: 1
        if (size !in 1..2) throw CanVehicleException("$w.checksum.size: 1 or 2")
        val ack = o["ack"] as? JsonObject
        fun codes(k: String) = ((o[k] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).map { code(it, "$w.$k") }.toSet()
        return CanFraming(
            head = head,
            lenFirst = order == "len,cmd",
            lenOffset = (o.int("len_offset") ?: 0).also { if (it !in -2..8) throw CanVehicleException("$w.len_offset: -2..8") },
            sumAlgo = algo,
            sumOver = over,
            sumSize = size,
            sumAdd = c.int("add") ?: 0,
            sumXor = c.str("xor")?.let { code(JsonPrimitive(it), "$w.checksum.xor") } ?: c.int("xor") ?: 0,
            ackCmd = ack?.str("cmd")?.let(::hexKey),
            ackRaw = ack?.str("raw")?.let { bytes(it, "$w.ack.raw") },
            noAckFor = codes("no_ack_for"),
            boxAnswers = codes("box_answers"),
            baud = o.int("baud") ?: 38400,
        )
    }

    /** `"wheel_keys": { "cmd": "0x11", "code_byte": 2, "state_byte": 3, "pressed": [1], "event_byte": 1, "event_mask": "0x08", "names": {…} }`. */
    private fun wheelKeys(
        o: JsonObject,
        defaultBase: Int = 0,
        w: String = "wheel_keys",
    ): WheelKeySpec {
        val cmd = o.str("cmd")?.let(::hexKey) ?: throw CanVehicleException("$w: cmd missing")
        val code = o.int("code_byte") ?: throw CanVehicleException("$w: code_byte missing")
        fun byte(k: String): Int? = o.int(k)?.also { if (it !in 0..63) throw CanVehicleException("$w: $k 0..63") }
        fun mask(k: String): Int? =
            o.str(k)?.let { m ->
                m.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 1..0xFF } ?: throw CanVehicleException("$w: bad $k $m")
            }
        if (code !in 0..63) throw CanVehicleException("$w: code_byte 0..63")
        val base = (o.int("code_base") ?: defaultBase).also { if (it < 0 || it > 0xFFFF || (w != "wheel_keys" && it < 256)) throw CanVehicleException("$w: code_base 256..65535 (codes under 256 are the steering wheel's)") }
        return WheelKeySpec(
            cmd = cmd,
            codeByte = code,
            codeMask = mask("code_mask") ?: 0xFF,
            stateByte = byte("state_byte"),
            pressed =
                ((o["pressed"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet())
                    ?.takeIf { it.isNotEmpty() } ?: setOf(1),
            eventByte = byte("event_byte"),
            eventMask = mask("event_mask") ?: 0xFF,
            names =
                (o["names"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to ((v as? JsonPrimitive)?.contentOrNull ?: "") } }
                    ?.toMap()
                    .orEmpty(),
            codeBase = if (w == "wheel_keys") 0 else base,
            stepsByte = byte("steps_byte"),
            stepsSigned = (o["steps_signed"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    private fun wheelKeysJson(w: WheelKeySpec) =
        buildJsonObject {
            put("cmd", "0x%02X".format(w.cmd))
            put("code_byte", w.codeByte)
            if (w.codeMask != 0xFF) put("code_mask", "0x%02X".format(w.codeMask))
            w.stateByte?.let { put("state_byte", it) }
            put("pressed", kotlinx.serialization.json.JsonArray(w.pressed.sorted().map { JsonPrimitive(it) }))
            w.eventByte?.let { put("event_byte", it) }
            if (w.eventMask != 0xFF) put("event_mask", "0x%02X".format(w.eventMask))
            if (w.names.isNotEmpty()) put("names", buildJsonObject { w.names.toSortedMap().forEach { (k, v) -> put(k.toString(), v) } })
            if (w.codeBase != 0) put("code_base", w.codeBase)
            w.stepsByte?.let { put("steps_byte", it) }
            if (!w.stepsSigned) put("steps_signed", false)
        }

    /** `"link": { "ack": true, "send": [ { "name", "cmd": "0x24", "data": "00 31", "first_ms", "every_ms" } ] }`. */
    private fun link(o: JsonObject): CanLink {
        val sends =
            ((o["send"] as? kotlinx.serialization.json.JsonArray) ?: emptyList()).mapIndexed { i, e ->
                val m = e as? JsonObject ?: throw CanVehicleException("link.send[$i] is not an object")
                val cmd = m.str("cmd")?.let(::hexKey) ?: throw CanVehicleException("link.send[$i]: cmd missing")
                val text = m.str("data").orEmpty()
                val template = if ('{' in text) CanTemplate.parse(text, "link.send[$i]") else null
                val data = if (template == null) bytes(text, "link.send[$i]") else emptyList()
                val on = m.str("on") ?: "start"
                if (on != "start" && on != "change") throw CanVehicleException("link.send[$i]: on is start or change")
                val first = m.long("first_ms") ?: 1_000
                val every = m.long("every_ms") ?: 0
                if (first !in 0..60_000) throw CanVehicleException("link.send[$i]: first_ms 0..60000")
                if (every != 0L && every !in MIN_EVERY_MS..3_600_000) throw CanVehicleException("link.send[$i]: every_ms 0 or $MIN_EVERY_MS..3600000")
                CanSend(m.str("name")?.ifBlank { null } ?: "0x%02X".format(cmd), cmd, data, first, every, template, on)
            }
        val commands =
            (o["commands"] as? JsonObject).orEmpty().map { (name, e) ->
                val c = e as? JsonObject ?: throw CanVehicleException("link.commands.$name is not an object")
                val cmd = c.str("cmd")?.let(::hexKey) ?: throw CanVehicleException("link.commands.$name: cmd missing")
                name to CanCommand(name, cmd, CanTemplate.parse(c.str("data").orEmpty(), "link.commands.$name"))
            }.toMap()
        return CanLink(ack = (o["ack"] as? JsonPrimitive)?.booleanOrNull ?: true, send = sends, commands = commands)
    }

    /** Hex bytes "00 31" (spaces or commas). */
    private fun bytes(
        text: String,
        where: String,
    ): List<Int> {
        val data =
            text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.map { b ->
                b.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFF }
                    ?: throw CanVehicleException("$where: bad data byte \"$b\"")
            }
        if (data.size > MAX_SEND_BYTES) throw CanVehicleException("$where: more than $MAX_SEND_BYTES data bytes")
        return data
    }

    private fun linkJson(l: CanLink) =
        buildJsonObject {
            put("ack", l.ack)
            put(
                "send",
                kotlinx.serialization.json.JsonArray(
                    l.send.map { s ->
                        buildJsonObject {
                            put("name", s.name)
                            put("cmd", "0x%02X".format(s.cmd))
                            put("data", s.template?.source ?: s.data.joinToString(" ") { "%02X".format(it) })
                            put("first_ms", s.firstMs)
                            put("every_ms", s.everyMs)
                            if (s.on != "start") put("on", s.on)
                        }
                    },
                ),
            )
            if (l.commands.isNotEmpty()) {
                put(
                    "commands",
                    buildJsonObject {
                        l.commands.forEach { (n, c) ->
                            put(
                                n,
                                buildJsonObject {
                                    put("cmd", "0x%02X".format(c.cmd))
                                    put("data", c.template.source)
                                },
                            )
                        }
                    },
                )
            }
        }

    /** Bytes of one message sent to the box, and its shortest interval (a box is not flooded). */
    private const val MAX_SEND_BYTES = 64
    private const val MIN_EVERY_MS = 500L

    fun toJson(v: CanVehicle): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("id", v.id)
                put("name", v.name)
                put("description", v.description)
                put("box", v.box)
                v.source.json?.get("framing")?.let { put("framing", it) }
                v.baud?.let { put("baud", it) }
                put("link", linkJson(v.link))
                v.wheelKeys?.let { put("wheel_keys", wheelKeysJson(it)) }
                if (v.moreKeys.isNotEmpty()) put("keys", kotlinx.serialization.json.JsonArray(v.moreKeys.map { wheelKeysJson(it) }))
                if (v.variants.isNotEmpty()) {
                    put(
                        "variants",
                        kotlinx.serialization.json.JsonArray(
                            v.variants.map { c ->
                                buildJsonObject {
                                    put("name", c.name)
                                    put("send", buildJsonObject { c.send.forEach { (k, d) -> put(k, d.joinToString(" ") { "%02X".format(it) }) } })
                                    c.patch?.let { put("patch", it) }
                                }
                            },
                        ),
                    )
                }
                put(
                    "messages",
                    buildJsonObject {
                        for (m in v.messages.values.sortedBy { it.cmd }) {
                            put(
                                "0x%02X".format(m.cmd),
                                buildJsonObject {
                                    put("name", m.name)
                                    m.mux?.let { put("mux", it) }
                                    put("signals", buildJsonObject { m.signals.forEach { put(it.name, signalJson(it)) } })
                                    if (m.cases.isNotEmpty()) {
                                        put(
                                            "cases",
                                            buildJsonObject {
                                                for ((code, c) in m.cases.toSortedMap()) {
                                                    put(
                                                        "0x%02X".format(code),
                                                        buildJsonObject {
                                                            c.name?.let { put("name", it) }
                                                            put("signals", buildJsonObject { c.signals.forEach { put(it.name, signalJson(it)) } })
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    },
                )
                if (v.examples.isNotEmpty()) {
                    put(
                        "examples",
                        kotlinx.serialization.json.JsonArray(
                            v.examples.map { e ->
                                buildJsonObject {
                                    put("cmd", "0x%02X".format(e.cmd))
                                    put("data", e.data.joinToString(" ") { "%02X".format(it) })
                                    put(
                                        "expect",
                                        buildJsonObject {
                                            e.expect.forEach { (k, x) ->
                                                when (x) {
                                                    null -> put(k, kotlinx.serialization.json.JsonNull)
                                                    is Boolean -> put(k, x)
                                                    is Number -> put(k, x.toDouble())
                                                    else -> put(k, x.toString())
                                                }
                                            }
                                        },
                                    )
                                }
                            },
                        ),
                    )
                }
            },
        )

    private fun signal(
        name: String,
        o: JsonObject,
        key: String,
    ): CanSignal {
        val size = o.int("size") ?: 1
        val start = o.int("start_byte") ?: 0
        val where = "Signal $key.$name"
        if (size !in 1..8 || start < 0) throw CanVehicleException("$where: bad start_byte / size")
        val endian = o.str("endian") ?: "big"
        if (endian != "big" && endian != "little") throw CanVehicleException("Signal $key.$name: endian must be big or little")
        val bitRoles = mutableMapOf<String, String>()
        val bits =
            (o["bits"] as? JsonObject)?.mapValues { (bit, v) ->
                // A bit is its position, or { "bit": position, "role": … }.
                val pos = (v as? JsonPrimitive)?.intOrNull ?: (v as? JsonObject)?.int("bit")
                if (pos == null || pos !in 0 until size * 8) throw CanVehicleException("$where: bad bit $bit")
                (v as? JsonObject)?.str("role")?.let { bitRoles[bit] = role(it, "$where.$bit") }
                pos
            }
        val startBit = o.int("start_bit")
        val bitLen = o.int("bit_length")
        var mask =
            o.str("mask")?.let {
                it.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                    ?: throw CanVehicleException("$where: bad mask $it")
            }
        var shift = o.int("shift") ?: 0
        if (startBit != null) {
            // DBC style: the field is bit_length bits from start_bit (bit 0 = lowest bit of the bytes read).
            val len = bitLen ?: 1
            if (startBit < 0 || len < 1 || startBit + len > size * 8) throw CanVehicleException("$where: start_bit / bit_length outside the bytes read")
            mask = ((1L shl len) - 1) shl startBit
            shift = startBit
        }
        val labelEl = o["label"]
        val labels =
            (labelEl as? JsonObject)?.mapNotNull { (lang, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { lang to it } }?.toMap().orEmpty()
        return CanSignal(
            name = name,
            startByte = start,
            size = size,
            bigEndian = endian == "big",
            factor = o.dbl("factor") ?: 1.0,
            offset = o.dbl("offset") ?: 0.0,
            mask = mask,
            shift = shift,
            signed = (o["signed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            signMagnitude = (o["sign_magnitude"] as? JsonPrimitive)?.booleanOrNull ?: false,
            bitLength = bitLen ?: size * 8,
            min = o.dbl("min_value"),
            max = o.dbl("max_value"),
            unit = o.str("unit").orEmpty(),
            label = (labelEl as? JsonPrimitive)?.contentOrNull ?: labels["en"] ?: labels.values.firstOrNull().orEmpty(),
            labels = labels,
            role = o.str("role")?.let { role(it, where) },
            bitRoles = bitRoles,
            invalid =
                when (val v = o["invalid"]) {
                    null -> emptySet()
                    is kotlinx.serialization.json.JsonArray -> v.map { rawValue(it, where) }.toSet()
                    else -> setOf(rawValue(v, where))
                },
            condition = (o["when"] as? JsonObject)?.let { condition(it, "$where.when") },
            expr = o.str("expr")?.let { formula(it, "$where.expr") },
            whenExpr = (o["when"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { formula(it, "$where.when") },
            bits = bits.orEmpty(),
            values =
                (o["values"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to ((v as? JsonPrimitive)?.contentOrNull ?: "") } }
                    ?.toMap()
                    .orEmpty(),
            ascii = (o["ascii"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun formula(
        text: String,
        where: String,
    ): CanExpr {
        val e =
            try {
                CanExpr.parse(text)
            } catch (x: CanVehicleException) {
                throw CanVehicleException("$where: ${x.message}")
            }
        CanExpr.roleNames(text).forEach { role(it, where) }
        return e
    }

    private fun role(
        id: String,
        where: String,
    ): String = id.takeIf { CanRoles.isValid(it) } ?: throw CanVehicleException("$where: unknown role \"$id\" (see CanRoles, or x.<name> for a new one)")

    /** A raw value: a number or a hex string ("0xFF"). */
    private fun rawValue(
        e: JsonElement,
        where: String,
    ): Long {
        val p = e as? JsonPrimitive ?: throw CanVehicleException("$where: bad invalid value $e")
        return (if (p.isString) p.content.removePrefix("0x").removePrefix("0X").toLongOrNull(16) else p.content.toLongOrNull())
            ?: throw CanVehicleException("$where: bad invalid value $e")
    }

    private fun signalJson(s: CanSignal) =
        buildJsonObject {
            put("start_byte", s.startByte)
            if (s.ascii) {
                put("ascii", true)
            } else {
                put("size", s.size)
                if (!s.bigEndian) put("endian", "little")
                if (s.factor != 1.0) put("factor", s.factor)
                if (s.offset != 0.0) put("offset", s.offset)
                s.mask?.let { put("mask", "0x%X".format(it)) }
                if (s.shift != 0) put("shift", s.shift)
                if (s.signed) put("signed", true)
                if (s.signMagnitude) put("sign_magnitude", true)
                if (s.bitLength != s.size * 8) put("bit_length", s.bitLength)
                s.min?.let { put("min_value", it) }
                s.max?.let { put("max_value", it) }
            }
            if (s.unit.isNotEmpty()) put("unit", s.unit)
            if (s.labels.isNotEmpty()) {
                put("label", buildJsonObject { if (s.label.isNotEmpty() && "en" !in s.labels) put("en", s.label); s.labels.forEach { (k, v) -> put(k, v) } })
            } else if (s.label.isNotEmpty()) {
                put("label", s.label)
            }
            s.role?.let { put("role", it) }
            if (s.invalid.isNotEmpty()) put("invalid", kotlinx.serialization.json.JsonArray(s.invalid.sorted().map { JsonPrimitive(it) }))
            if (s.bits.isNotEmpty()) {
                put(
                    "bits",
                    buildJsonObject {
                        s.bits.forEach { (k, v) ->
                            val r = s.bitRoles[k]
                            if (r == null) put(k, v) else put(k, buildJsonObject { put("bit", v); put("role", r) })
                        }
                    },
                )
            }
            if (s.values.isNotEmpty()) put("values", buildJsonObject { s.values.forEach { (k, v) -> put(k.toString(), v) } })
            s.expr?.let { put("expr", it.source) }
            s.whenExpr?.let { put("when", it.source) }
            s.condition?.let { c ->
                put(
                    "when",
                    buildJsonObject {
                        c.byte?.let { put("byte", it) }
                        if (c.values.isNotEmpty()) put("is", kotlinx.serialization.json.JsonArray(c.values.sorted().map { JsonPrimitive("0x%02X".format(it)) }))
                        if (c.mask != 0xFF) put("mask", "0x%02X".format(c.mask))
                        c.len?.let { put("len", it) }
                        c.minLen?.let { put("min_len", it) }
                        c.maxLen?.let { put("max_len", it) }
                    },
                )
            }
        }

    private fun hexKey(key: String): Int {
        val v = key.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        if (v == null || v !in 0..0xFF) throw CanVehicleException("Bad message key \"$key\" (0x00..0xFF)")
        return v
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

}
