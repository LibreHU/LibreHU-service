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
)

/** A message of the CAN box: Hiworld command [cmd] and its signals. */
data class CanMessage(
    val cmd: Int,
    val name: String,
    val signals: List<CanSignal>,
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
) {
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
 * How to read the messages of one car's CAN box. The box (Hiworld here) is set for a car model and translates its
 * CAN bus into its own messages, so the decoding depends on the car: one profile per car, selectable and importable.
 */
data class CanVehicle(
    val id: String,
    val name: String,
    val description: String = "",
    val box: String = BOX_HIWORLD,
    val messages: Map<Int, CanMessage> = emptyMap(),
) {
    /** Every value of [f] this profile knows; empty when the command is unknown. */
    fun decode(f: CanboxFrame): List<CanValue> {
        val m = messages[f.cmd] ?: return emptyList()
        return m.signals.flatMap { decodeSignal(m, it, f.data) }
    }

    /** One line for the CAN viewer, or null when the command is unknown. */
    fun describe(f: CanboxFrame): String? {
        val m = messages[f.cmd] ?: return null
        val values = decode(f)
        val flags = values.filter { it.flag == true }.joinToString(", ") { it.label }
        val others = values.filter { it.flag == null }.joinToString(" · ") { "${it.label} ${it.display()}" }
        return listOf(m.name, flags, others).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    companion object {
        const val BOX_HIWORLD = "hiworld"
    }
}

private fun decodeSignal(
    m: CanMessage,
    s: CanSignal,
    data: ByteArray,
): List<CanValue> {
    val label = s.label.ifEmpty { s.name }
    if (s.ascii) {
        if (s.startByte >= data.size) return emptyList()
        val text = String(data, s.startByte, data.size - s.startByte, Charsets.US_ASCII).trim { it <= ' ' }
        return listOf(CanValue(s.name, label, m.name, text = text))
    }
    if (s.startByte < 0 || s.startByte + s.size > data.size) return emptyList()
    var raw = 0L
    for (i in 0 until s.size) {
        val b = data[s.startByte + if (s.bigEndian) i else s.size - 1 - i].toLong() and 0xFF
        raw = (raw shl 8) or b
    }
    if (s.bits.isNotEmpty()) {
        return s.bits.map { (bit, pos) -> CanValue(bit, bit, m.name, flag = (raw shr pos) and 1L == 1L) }
    }
    if (s.mask != null) raw = raw and s.mask
    if (s.shift > 0) raw = raw shr s.shift
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
            CanValue(s.name, label, m.name, number = value, text = named)
        } else {
            CanValue(s.name, label, m.name, value, unit = s.unit)
        },
    )
}

class CanVehicleException(
    message: String,
) : Exception(message)

/** Built-in car profiles and the JSON format of the importable ones (docs/can-vehicles.md). */
object CanVehicles {
    const val FORMAT = "librehu-can-vehicle"
    const val VERSION = 1

    private val json = Json { prettyPrint = true }

    /** Renault Clio 3 2005-2014, Hiworld box with protocol LNP002 (docs/ivi-services/12-canbus.md §12.4). */
    val RENAULT_CLIO3_HIWORLD: CanVehicle by lazy { fromJson(RENAULT_CLIO3_JSON) }

    /** Any car: no decoding, the CAN viewer shows the raw messages. */
    val GENERIC_HIWORLD =
        CanVehicle(
            id = "hiworld-generic",
            name = "Hiworld (raw messages only)",
            description = "No decoding: raw messages, to find the fields of a new car.",
        )

    val BUILT_IN: List<CanVehicle> by lazy { listOf(RENAULT_CLIO3_HIWORLD, GENERIC_HIWORLD) }

    val DEFAULT_ID = "renault-clio3-hiworld"

    fun fromJson(text: String): CanVehicle {
        val root =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw CanVehicleException("Not JSON: ${e.message}")
            }
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
        if (box != CanVehicle.BOX_HIWORLD) throw CanVehicleException("Unknown box \"$box\" (supported: hiworld)")
        val messages =
            (root["messages"] as? JsonObject).orEmpty().map { (key, e) ->
                val cmd = hexKey(key)
                val o = e as? JsonObject ?: throw CanVehicleException("Message $key is not an object")
                val signals =
                    (o["signals"] as? JsonObject).orEmpty().map { (name, se) ->
                        signal(name, se as? JsonObject ?: throw CanVehicleException("Signal $key.$name is not an object"), key)
                    }
                cmd to CanMessage(cmd, o.str("name") ?: "%02X".format(cmd), signals)
            }
        return CanVehicle(
            id = id,
            name = root.str("name")?.ifBlank { null } ?: id,
            description = root.str("description").orEmpty(),
            box = box,
            messages = messages.toMap(),
        )
    }

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
                put(
                    "messages",
                    buildJsonObject {
                        for (m in v.messages.values.sortedBy { it.cmd }) {
                            put(
                                "0x%02X".format(m.cmd),
                                buildJsonObject {
                                    put("name", m.name)
                                    put("signals", buildJsonObject { m.signals.forEach { put(it.name, signalJson(it)) } })
                                },
                            )
                        }
                    },
                )
            },
        )

    private fun signal(
        name: String,
        o: JsonObject,
        key: String,
    ): CanSignal {
        val size = o.int("size") ?: 1
        val start = o.int("start_byte") ?: 0
        if (size !in 1..8 || start < 0) throw CanVehicleException("Signal $key.$name: bad start_byte / size")
        val endian = o.str("endian") ?: "big"
        if (endian != "big" && endian != "little") throw CanVehicleException("Signal $key.$name: endian must be big or little")
        val bits =
            (o["bits"] as? JsonObject)?.mapValues { (bit, v) ->
                val pos = (v as? JsonPrimitive)?.intOrNull
                if (pos == null || pos !in 0 until size * 8) throw CanVehicleException("Signal $key.$name: bad bit $bit")
                pos
            }
        val mask =
            o.str("mask")?.let {
                it.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                    ?: throw CanVehicleException("Signal $key.$name: bad mask $it")
            }
        return CanSignal(
            name = name,
            startByte = start,
            size = size,
            bigEndian = endian == "big",
            factor = o.dbl("factor") ?: 1.0,
            offset = o.dbl("offset") ?: 0.0,
            mask = mask,
            shift = o.int("shift") ?: 0,
            signed = (o["signed"] as? JsonPrimitive)?.booleanOrNull ?: false,
            signMagnitude = (o["sign_magnitude"] as? JsonPrimitive)?.booleanOrNull ?: false,
            bitLength = o.int("bit_length") ?: size * 8,
            min = o.dbl("min_value"),
            max = o.dbl("max_value"),
            unit = o.str("unit").orEmpty(),
            label = o.str("label").orEmpty(),
            bits = bits.orEmpty(),
            values =
                (o["values"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to ((v as? JsonPrimitive)?.contentOrNull ?: "") } }
                    ?.toMap()
                    .orEmpty(),
            ascii = (o["ascii"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
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
            if (s.label.isNotEmpty()) put("label", s.label)
            if (s.bits.isNotEmpty()) put("bits", buildJsonObject { s.bits.forEach { (k, v) -> put(k, v) } })
            if (s.values.isNotEmpty()) put("values", buildJsonObject { s.values.forEach { (k, v) -> put(k.toString(), v) } })
        }

    private fun hexKey(key: String): Int {
        val v = key.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
        if (v == null || v !in 0..0xFF) throw CanVehicleException("Bad message key \"$key\" (0x00..0xFF)")
        return v
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.dbl(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private val RENAULT_CLIO3_JSON =
        """
        {
          "format": "librehu-can-vehicle", "version": 1,
          "id": "renault-clio3-hiworld",
          "name": "Renault Clio 3 (Hiworld LNP002)",
          "description": "Clio 3 2005-2014, Hiworld box, protocol LNP002, from Hiworld's own app (HdRenaultProtocolLNP002).",
          "box": "hiworld",
          "messages": {
            "0x11": { "name": "State", "signals": {
              "status": { "start_byte": 0, "bits": { "ACC": 0, "lights": 1, "reverse": 2, "handbrake": 3, "SOS mute": 7 } },
              "wheel_key": { "start_byte": 2, "mask": "0x1F", "label": "wheel key", "values": {
                "0": "-", "1": "volume +", "2": "volume -", "3": "mute / answer / hang up", "4": "navigation",
                "5": "previous / answer", "6": "next / hang up", "8": "right", "9": "left", "10": "mode", "13": "previous",
                "14": "next", "15": "OK", "16": "phone", "18": "mute", "19": "answer", "20": "hang up", "21": "scan / answer",
                "24": "voice" } },
              "wheel_key_state": { "start_byte": 3, "label": "key state" },
              "steering_angle": { "start_byte": 6, "size": 2, "sign_magnitude": true, "factor": 0.1, "min_value": -540,
                "max_value": 540, "unit": "°", "label": "steering" }
            } },
            "0x12": { "name": "Doors", "signals": {
              "doors": { "start_byte": 2, "bits": { "front left": 7, "front right": 6, "rear left": 5, "rear right": 4, "trunk": 3 } }
            } },
            "0x14": { "name": "Trip computer", "signals": {
              "avg_consumption": { "start_byte": 0, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "L/100 km", "label": "average consumption" },
              "avg_speed": { "start_byte": 2, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "km/h", "label": "average speed" },
              "distance": { "start_byte": 4, "size": 3, "factor": 0.1, "max_value": 1677721.4, "unit": "km", "label": "distance" },
              "time_minutes": { "start_byte": 7, "max_value": 254, "unit": "min", "label": "driving time (min)" },
              "time_hours": { "start_byte": 8, "size": 2, "max_value": 65534, "unit": "h", "label": "driving time (h)" },
              "fuel_used": { "start_byte": 10, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "L", "label": "fuel used" },
              "green_distance": { "start_byte": 12, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "km", "label": "distance (green)" }
            } },
            "0x21": { "name": "Front panel key", "signals": {
              "key": { "start_byte": 0, "label": "key" }, "state": { "start_byte": 1, "label": "state" } } },
            "0x22": { "name": "Knob", "signals": {
              "knob": { "start_byte": 0, "label": "knob", "values": { "1": "volume", "2": "tune" } },
              "steps": { "start_byte": 1, "label": "counter" } } },
            "0x31": { "name": "Climate", "signals": {
              "climate": { "start_byte": 0, "bits": { "AC": 0, "sync": 2, "auto": 3, "rear": 4, "climate on": 6 } },
              "air": { "start_byte": 1, "bits": { "auto recirculation": 3, "recirculation": 4 } },
              "defrost": { "start_byte": 2, "bits": { "front defrost": 4, "rear defrost": 5 } },
              "seat_heat_left": { "start_byte": 2, "mask": "0x03", "label": "seat heating L" },
              "seat_heat_right": { "start_byte": 2, "mask": "0x0C", "shift": 2, "label": "seat heating R" },
              "air_mode": { "start_byte": 4, "label": "air distribution" },
              "fan": { "start_byte": 5, "label": "fan" },
              "temp_left": { "start_byte": 6, "factor": 0.5, "unit": "°C", "label": "temp L", "values": { "254": "LO", "255": "HI" } },
              "temp_right": { "start_byte": 7, "factor": 0.5, "unit": "°C", "label": "temp R", "values": { "254": "LO", "255": "HI" } },
              "outside_temp": { "start_byte": 11, "factor": 0.5, "offset": -40, "unit": "°C", "label": "outside" }
            } },
            "0x41": { "name": "Parking radar", "signals": {
              "rear_left": { "start_byte": 0, "factor": -1, "offset": 4, "min_value": 0, "label": "rear L" },
              "rear_center_left": { "start_byte": 1, "factor": -1, "offset": 4, "min_value": 0, "label": "rear CL" },
              "rear_center_right": { "start_byte": 2, "factor": -1, "offset": 4, "min_value": 0, "label": "rear CR" },
              "rear_right": { "start_byte": 3, "factor": -1, "offset": 4, "min_value": 0, "label": "rear R" },
              "front_left": { "start_byte": 4, "factor": -1, "offset": 4, "min_value": 0, "label": "front L" },
              "front_center_left": { "start_byte": 5, "factor": -1, "offset": 4, "min_value": 0, "label": "front CL" },
              "front_center_right": { "start_byte": 6, "factor": -1, "offset": 4, "min_value": 0, "label": "front CR" },
              "front_right": { "start_byte": 7, "factor": -1, "offset": 4, "min_value": 0, "label": "front R" },
              "radar_shown": { "start_byte": 8, "label": "radar shown" }
            } },
            "0x42": { "name": "Side radar", "signals": {
              "right_front": { "start_byte": 0, "factor": -1, "offset": 4, "min_value": 0, "label": "right F" },
              "right_front_mid": { "start_byte": 1, "factor": -1, "offset": 4, "min_value": 0, "label": "right FM" },
              "right_rear_mid": { "start_byte": 2, "factor": -1, "offset": 4, "min_value": 0, "label": "right RM" },
              "right_rear": { "start_byte": 3, "factor": -1, "offset": 4, "min_value": 0, "label": "right R" },
              "left_front": { "start_byte": 4, "factor": -1, "offset": 4, "min_value": 0, "label": "left F" },
              "left_front_mid": { "start_byte": 5, "factor": -1, "offset": 4, "min_value": 0, "label": "left FM" },
              "left_rear_mid": { "start_byte": 6, "factor": -1, "offset": 4, "min_value": 0, "label": "left RM" },
              "left_rear": { "start_byte": 7, "factor": -1, "offset": 4, "min_value": 0, "label": "left R" }
            } },
            "0x48": { "name": "Tyre pressure", "signals": {
              "front_left": { "start_byte": 2, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "kPa", "label": "front L" },
              "front_right": { "start_byte": 4, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "kPa", "label": "front R" },
              "rear_left": { "start_byte": 6, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "kPa", "label": "rear L" },
              "rear_right": { "start_byte": 8, "size": 2, "factor": 0.1, "max_value": 6553.4, "unit": "kPa", "label": "rear R" }
            } },
            "0x60": { "name": "Park assist", "signals": {
              "mode": { "start_byte": 0, "mask": "0x0F", "label": "park assist mode" } } },
            "0x61": { "name": "Vehicle settings", "signals": {} },
            "0x62": { "name": "Vehicle settings / service", "signals": {
              "service": { "start_byte": 2, "size": 2, "max_value": 65534, "unit": "km", "label": "service in" } } },
            "0xF0": { "name": "Box version", "signals": { "version": { "start_byte": 0, "ascii": true, "label": "version" } } }
          }
        }
        """.trimIndent()
}
