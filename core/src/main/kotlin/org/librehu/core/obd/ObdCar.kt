package org.librehu.core.obd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigInteger
import java.util.Locale

/**
 * One value inside the answer of a manufacturer request, with the data syntax of ddt4all's ECU files
 * (github.com/cedricp/ddt4all, `src/ddt4all/core/ecu/ecu_data.py` and `data_item.py`): [firstByte] counts from 1 on
 * the whole answer (service byte included), [bitOffset] from the top bit of that byte (big endian), [bits] long.
 * `value = (raw × step + offset) / divideBy` when [scaled], else a name from [lists] or the raw hex; text when [ascii].
 * [signed]: two's complement on [bytes] × 8 bits (1 or 2 bytes, as ddt4all does).
 */
data class ObdCarItem(
    val name: String,
    val firstByte: Int,
    val bitOffset: Int = 0,
    val bits: Int = 8,
    val bytes: Int = 1,
    val littleEndian: Boolean = false,
    val signed: Boolean = false,
    val scaled: Boolean = false,
    val step: Double = 1.0,
    val offset: Double = 0.0,
    val divideBy: Double = 1.0,
    /** ddt4all display format: the digits after the dot give the decimals (`"0.0"` → 1). */
    val format: String = "",
    val unit: String = "",
    val ascii: Boolean = false,
    val lists: Map<Int, String> = emptyMap(),
    val comment: String = "",
)

/** A read request ([send]: service 21 or 22 and its identifier, in hex) and the values of its answer. */
data class ObdCarRequest(
    val name: String,
    val send: String,
    /** Expected start of the answer (`6101`…), empty = only the positive service byte is checked. */
    val reply: String = "",
    val minBytes: Int = 0,
    val items: List<ObdCarItem>,
) {
    val service: Int get() = send.substring(0, 2).toInt(16)
}

/** An ECU on the diagnostic CAN bus: requests go to [sendId], answers come from [recvId] (3 or 8 hex digits). */
data class ObdCarEcu(
    val name: String,
    val sendId: String,
    val recvId: String,
    val requests: List<ObdCarRequest>,
) {
    val extended: Boolean get() = sendId.length == 8
}

/**
 * A car for the ELM327 adapter: extra adapter commands ([elmInit], e.g. `ATSP6`), standard OBD values to read
 * ([pids], [ObdPid] names; empty = keep the user's choice) and manufacturer values read from its ECUs ([ecus]).
 */
data class ObdCar(
    val id: String,
    val name: String,
    val description: String = "",
    val elmInit: List<String> = emptyList(),
    val pids: List<String> = emptyList(),
    val ecus: List<ObdCarEcu> = emptyList(),
) {
    /** Every value: key ([ObdCars.key]) → ECU, request, item. */
    fun items(): List<Triple<ObdCarEcu, ObdCarRequest, ObdCarItem>> =
        ecus.flatMap { e -> e.requests.flatMap { r -> r.items.map { Triple(e, r, it) } } }
}

/** A value read from the car: [number] when scaled, [text] always (what is shown). */
data class ObdCarReading(
    val key: String,
    val ecu: String,
    val name: String,
    val text: String,
    val number: Double? = null,
    val unit: String = "",
    val error: Boolean = false,
)

class ObdCarException(
    message: String,
) : Exception(message)

/** Answer of the ECU to one request. */
sealed class EcuAnswer {
    /** Whole answer, service byte first (`61 01 …`). */
    class Positive(
        val bytes: IntArray,
    ) : EcuAnswer()

    /** `7F <service> <code>`: refused (ISO 14229 negative response code). */
    data class Negative(
        val code: Int,
    ) : EcuAnswer()

    /** No answer, or the adapter's message (`NO DATA`, `CAN ERROR`…). */
    data class None(
        val message: String,
    ) : EcuAnswer()
}

/**
 * Car files: LibreHU's own format ([FORMAT], shareable, several ECUs) and ddt4all's ECU files (JSON, one ECU each),
 * converted on import. Only read requests are kept (services 0x21 ReadDataByLocalIdentifier and 0x22
 * ReadDataByIdentifier): nothing that writes, resets, opens a session or drives an actuator is ever sent.
 */
object ObdCars {
    const val FORMAT = "librehu-obd-car"
    const val VERSION = 1

    /** Services allowed: read only. */
    val READ_SERVICES = setOf(0x21, 0x22)

    /** Adapter commands that write its EEPROM, refused in [ObdCar.elmInit]. */
    private val FORBIDDEN_AT = listOf("ATPP", "ATSD", "ATMA", "ATBD", "ATBI", "ATRD")

    fun key(
        ecu: ObdCarEcu,
        item: ObdCarItem,
    ) = "${ecu.name}/${item.name}"

    // --- Answers ---------------------------------------------------------------------------------------------------

    /**
     * ELM327 answer (CAN auto-formatting on, headers and spaces off) → the ECU answer to [service]. Multi-frame
     * answers come as a length line (`014`) then numbered lines (`0:6101…`, `1:…`), padded on the last one.
     */
    fun answer(
        raw: String,
        service: Int,
    ): EcuAnswer {
        val lines = Elm327.lines(raw)
        if (lines.isEmpty()) return EcuAnswer.None("NO DATA")
        val first = lines.first().replace(" ", "")
        val length = if (first.length == 3 && first.all { it.isHex() }) first.toInt(16) else null
        if (length != null) {
            val data = lines.drop(1).mapNotNull { Elm327.hexBytes(it) }.fold(IntArray(0)) { a, b -> a + b }
            if (data.size < length) return EcuAnswer.None("short answer")
            return classify(data.copyOf(length), service) ?: EcuAnswer.None("unexpected answer")
        }
        // Single frames: "response pending" (7F xx 78) may come before the answer.
        var negative: EcuAnswer.Negative? = null
        for (line in lines) {
            val b = Elm327.hexBytes(line) ?: continue
            when (val a = classify(b, service)) {
                is EcuAnswer.Positive -> return a
                is EcuAnswer.Negative -> if (a.code != 0x78) negative = a
                else -> {}
            }
        }
        return negative ?: EcuAnswer.None(lines.first())
    }

    private fun classify(
        b: IntArray,
        service: Int,
    ): EcuAnswer? =
        when {
            b.isNotEmpty() && b[0] == service + 0x40 -> EcuAnswer.Positive(b)
            b.size >= 3 && b[0] == 0x7F && b[1] == service -> EcuAnswer.Negative(b[2])
            else -> null
        }

    /** Raw bits of [item] in [answer] (whole answer, service byte first), or null when it is too short. */
    fun raw(
        item: ObdCarItem,
        answer: IntArray,
    ): BigInteger? {
        val start = item.firstByte - 1
        val span = (item.bits + item.bitOffset + 7) / 8
        if (start < 0 || item.bits <= 0 || start + span > answer.size) return null
        var v = BigInteger.ZERO
        for (i in 0 until span) {
            val b = answer[start + if (item.littleEndian) span - 1 - i else i]
            v = v.shiftLeft(8).or(BigInteger.valueOf(b.toLong()))
        }
        // Big endian: the bit offset counts from the top bit of the first byte; little endian (Intel): from bit 0.
        val shift = if (item.littleEndian) item.bitOffset else span * 8 - item.bitOffset - item.bits
        return v.shiftRight(shift).and(BigInteger.ONE.shiftLeft(item.bits).subtract(BigInteger.ONE))
    }

    /** The value shown for [item], as ddt4all computes it (`getDisplayValue`). */
    fun decode(
        key: String,
        ecu: String,
        item: ObdCarItem,
        answer: IntArray,
    ): ObdCarReading {
        val raw = raw(item, answer) ?: return ObdCarReading(key, ecu, item.name, "—", error = true)
        if (item.ascii) {
            val start = item.firstByte - 1
            val text =
                (start until start + item.bits / 8)
                    .map { answer[it] }
                    .filter { it in 0x20..0x7E }
                    .map { it.toChar() }
                    .joinToString("")
                    .trim()
            return ObdCarReading(key, ecu, item.name, text)
        }
        var value = raw.toLong()
        if (item.signed && item.bytes in 1..2) {
            val width = item.bytes * 8
            if (value and (1L shl (width - 1)) != 0L) value -= 1L shl width
        }
        if (!item.scaled) {
            item.lists[value.toInt()]?.let { return ObdCarReading(key, ecu, item.name, it, value.toDouble()) }
            val hex = raw.toString(16).uppercase(Locale.ROOT).padStart(((item.bits + 7) / 8) * 2, '0')
            return ObdCarReading(key, ecu, item.name, hex, value.toDouble())
        }
        if (item.divideBy == 0.0) return ObdCarReading(key, ecu, item.name, "—", error = true)
        val v = (value * item.step + item.offset) / item.divideBy
        val decimals = item.format.substringAfter('.', "").length.takeIf { item.format.contains('.') }
        val text =
            when {
                decimals != null -> String.format(Locale.ROOT, "%.${decimals}f", v)
                v == Math.floor(v) && kotlin.math.abs(v) < 1e15 -> v.toLong().toString()
                else -> String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
            }
        return ObdCarReading(key, ecu, item.name, text + if (item.unit.isNotEmpty()) " ${item.unit}" else "", v, item.unit)
    }

    // --- Files -----------------------------------------------------------------------------------------------------

    /** A LibreHU car file, or a ddt4all ECU file (then a car with that one ECU, named after it). */
    fun fromJson(
        text: String,
        fallbackName: String = "",
    ): ObdCar {
        val root =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw ObdCarException("Not JSON: ${e.message}")
            }
        if (root.str("format") == FORMAT) return librehu(root)
        if (root["requests"] is JsonArray && root["data"] is JsonObject) {
            val ecu = ddt4allEcu(root, fallbackName)
            val id = slug(ecu.name)
            return ObdCar(id, ecu.name, "ddt4all", ecus = listOf(ecu))
        }
        throw ObdCarException("Neither a LibreHU OBD car (format = $FORMAT) nor a ddt4all ECU file (requests, data)")
    }

    /** [ecu] added to [car], replacing the one with the same name. */
    fun withEcu(
        car: ObdCar,
        ecu: ObdCarEcu,
    ): ObdCar = car.copy(ecus = car.ecus.filter { it.name != ecu.name } + ecu)

    /** A ddt4all ECU file → its CAN address and read requests. */
    fun ddt4allEcu(
        root: JsonObject,
        fallbackName: String = "",
    ): ObdCarEcu {
        val obd = root["obd"] as? JsonObject ?: throw ObdCarException("No \"obd\" section (ECU address)")
        val protocol = obd.str("protocol").orEmpty()
        if (!protocol.equals("CAN", ignoreCase = true)) {
            throw ObdCarException("Protocol \"$protocol\": only CAN ECUs can be read through the ELM327 here")
        }
        val sendId = canId(obd.str("send_id"), "send_id")
        val recvId = canId(obd.str("recv_id"), "recv_id")
        val ecuLittle = root.str("endian").equals("Little", ignoreCase = true)
        val data = root["data"] as JsonObject
        val seen = HashSet<String>()
        val requests =
            (root["requests"] as JsonArray).mapNotNull { e ->
                val r = e as? JsonObject ?: return@mapNotNull null
                val send = r.str("sentbytes").orEmpty().replace(" ", "").uppercase(Locale.ROOT)
                if (!isReadRequest(send)) return@mapNotNull null
                // A request needing parameters (sendbyte_dataitems) cannot be polled as is.
                if ((r["sendbyte_dataitems"] as? JsonObject)?.isNotEmpty() == true) return@mapNotNull null
                val items =
                    (r["receivebyte_dataitems"] as? JsonObject).orEmpty().mapNotNull { (name, de) ->
                        val di = de as? JsonObject ?: return@mapNotNull null
                        val d = data[name] as? JsonObject ?: return@mapNotNull null
                        if (!seen.add(name)) return@mapNotNull null
                        ddt4allItem(name, di, d, ecuLittle)
                    }
                if (items.isEmpty()) return@mapNotNull null
                ObdCarRequest(
                    name = r.str("name").orEmpty().ifEmpty { send },
                    send = send,
                    reply = r.str("replybytes").orEmpty().replace(" ", "").uppercase(Locale.ROOT).take(send.length),
                    minBytes = r.int("minbytes") ?: 0,
                    items = items,
                )
            }
        if (requests.isEmpty()) throw ObdCarException("No read request (service 21 / 22) with values in this ECU file")
        val name = root.str("ecuname")?.trim().orEmpty().ifEmpty { fallbackName.ifEmpty { "ECU $sendId" } }
        return ObdCarEcu(name.take(64), sendId, recvId, requests)
    }

    private fun ddt4allItem(
        name: String,
        di: JsonObject,
        d: JsonObject,
        ecuLittle: Boolean,
    ): ObdCarItem? {
        val ascii = d.bool("bytesascii") ?: false
        val bytes = d.int("bytescount") ?: 1
        val bits = if (ascii) bytes * 8 else d.int("bitscount") ?: 8
        if (bits !in 1..(if (ascii) 512 else 64)) return null
        val endian = di.str("endian").orEmpty()
        return ObdCarItem(
            name = name.take(96),
            firstByte = di.int("firstbyte") ?: 0,
            bitOffset = di.int("bitoffset") ?: 0,
            bits = bits,
            bytes = bytes,
            littleEndian = if (endian.isEmpty()) ecuLittle else endian.equals("Little", ignoreCase = true),
            signed = d.bool("signed") ?: false,
            scaled = d.bool("scaled") ?: false,
            step = d.double("step") ?: 1.0,
            offset = d.double("offset") ?: 0.0,
            divideBy = d.double("divideby") ?: 1.0,
            format = d.str("format").orEmpty(),
            unit = d.str("unit").orEmpty(),
            ascii = ascii,
            lists = lists(d["lists"]),
            comment = d.str("comment").orEmpty().take(200),
        ).takeIf { it.firstByte >= 1 && it.bitOffset in 0..7 }
    }

    private fun librehu(root: JsonObject): ObdCar {
        val version = root.int("version") ?: 0
        if (version > VERSION) throw ObdCarException("Car file version $version is newer than this service")
        val id = root.str("id")?.trim().orEmpty()
        if (!Regex("[a-z0-9][a-z0-9._-]{1,63}").matches(id)) throw ObdCarException("Invalid id \"$id\" (a-z, 0-9, . _ -)")
        val elmInit = (root["elm_init"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty().replace(" ", "").uppercase(Locale.ROOT) }
        for (c in elmInit) {
            if (!c.startsWith("AT") || FORBIDDEN_AT.any { c.startsWith(it) } || c.length > 20) {
                throw ObdCarException("Adapter command \"$c\" not allowed (AT commands only, nothing written to the adapter)")
            }
        }
        val pids = (root["pids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { ObdPid.byName(it) != null }
        val ecus =
            (root["ecus"] as? JsonArray).orEmpty().map { e ->
                val o = e as? JsonObject ?: throw ObdCarException("ECU is not an object")
                val ecuName = o.str("name")?.trim().orEmpty().ifEmpty { throw ObdCarException("ECU without a name") }
                val requests =
                    (o["requests"] as? JsonArray).orEmpty().map { re ->
                        val r = re as? JsonObject ?: throw ObdCarException("$ecuName: request is not an object")
                        val send = r.str("send").orEmpty().replace(" ", "").uppercase(Locale.ROOT)
                        if (!isReadRequest(send)) throw ObdCarException("$ecuName: \"$send\" is not a read request (21 / 22)")
                        ObdCarRequest(
                            name = r.str("name").orEmpty().ifEmpty { send },
                            send = send,
                            reply = r.str("reply").orEmpty().replace(" ", "").uppercase(Locale.ROOT),
                            minBytes = r.int("min_bytes") ?: 0,
                            items = (r["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::item) },
                        )
                    }
                ObdCarEcu(ecuName, canId(o.str("send_id"), "send_id"), canId(o.str("recv_id"), "recv_id"), requests)
            }
        return ObdCar(
            id = id,
            name = root.str("name")?.ifBlank { null } ?: id,
            description = root.str("description").orEmpty(),
            elmInit = elmInit,
            pids = pids,
            ecus = ecus,
        )
    }

    private fun item(o: JsonObject): ObdCarItem? {
        val name = o.str("name")?.trim().orEmpty().ifEmpty { return null }
        val bits = o.int("bits") ?: 8
        val ascii = o.bool("ascii") ?: false
        if (bits !in 1..(if (ascii) 512 else 64)) throw ObdCarException("$name: bits = $bits")
        return ObdCarItem(
            name = name,
            firstByte = o.int("firstbyte") ?: throw ObdCarException("$name: no firstbyte"),
            bitOffset = o.int("bitoffset") ?: 0,
            bits = bits,
            bytes = o.int("bytes") ?: ((bits + 7) / 8),
            littleEndian = o.str("endian").equals("little", ignoreCase = true),
            signed = o.bool("signed") ?: false,
            scaled = o.bool("scaled") ?: false,
            step = o.double("step") ?: 1.0,
            offset = o.double("offset") ?: 0.0,
            divideBy = o.double("divideby") ?: 1.0,
            format = o.str("format").orEmpty(),
            unit = o.str("unit").orEmpty(),
            ascii = ascii,
            lists = lists(o["lists"]),
            comment = o.str("comment").orEmpty(),
        ).takeIf { it.firstByte >= 1 && it.bitOffset in 0..7 }
    }

    fun toJson(car: ObdCar): String {
        val o =
            buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("id", car.id)
                put("name", car.name)
                if (car.description.isNotEmpty()) put("description", car.description)
                if (car.elmInit.isNotEmpty()) put("elm_init", buildJsonArray { car.elmInit.forEach { add(JsonPrimitive(it)) } })
                if (car.pids.isNotEmpty()) put("pids", buildJsonArray { car.pids.forEach { add(JsonPrimitive(it)) } })
                put(
                    "ecus",
                    buildJsonArray {
                        for (e in car.ecus) {
                            add(
                                buildJsonObject {
                                    put("name", e.name)
                                    put("send_id", e.sendId)
                                    put("recv_id", e.recvId)
                                    put("requests", buildJsonArray { e.requests.forEach { add(requestJson(it)) } })
                                },
                            )
                        }
                    },
                )
            }
        return PRETTY.encodeToString(JsonObject.serializer(), o)
    }

    private fun requestJson(r: ObdCarRequest): JsonObject =
        buildJsonObject {
            put("name", r.name)
            put("send", r.send)
            if (r.reply.isNotEmpty()) put("reply", r.reply)
            if (r.minBytes > 0) put("min_bytes", r.minBytes)
            put(
                "items",
                buildJsonArray {
                    for (i in r.items) {
                        add(
                            buildJsonObject {
                                put("name", i.name)
                                put("firstbyte", i.firstByte)
                                if (i.bitOffset != 0) put("bitoffset", i.bitOffset)
                                put("bits", i.bits)
                                if (i.bytes != (i.bits + 7) / 8) put("bytes", i.bytes)
                                if (i.littleEndian) put("endian", "little")
                                if (i.signed) put("signed", true)
                                if (i.scaled) put("scaled", true)
                                if (i.step != 1.0) put("step", i.step)
                                if (i.offset != 0.0) put("offset", i.offset)
                                if (i.divideBy != 1.0) put("divideby", i.divideBy)
                                if (i.format.isNotEmpty()) put("format", i.format)
                                if (i.unit.isNotEmpty()) put("unit", i.unit)
                                if (i.ascii) put("ascii", true)
                                if (i.lists.isNotEmpty()) put("lists", buildJsonObject { i.lists.forEach { (k, v) -> put(k.toString(), v) } })
                                if (i.comment.isNotEmpty()) put("comment", i.comment)
                            },
                        )
                    }
                },
            )
        }

    /** `21xx` or `22xxxx`, at most 8 bytes, hex. */
    fun isReadRequest(send: String): Boolean =
        send.length in 4..16 && send.length % 2 == 0 && send.all { it.isHex() } && send.substring(0, 2).toInt(16) in READ_SERVICES

    fun slug(name: String): String =
        name
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(60)
            .let { if (it.length < 2) "car-$it" else it }

    private fun canId(
        v: String?,
        field: String,
    ): String {
        val s = v?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if ((s.length != 3 && s.length != 8) || !s.all { it.isHex() }) throw ObdCarException("$field \"$s\": 3 or 8 hex digits")
        return s
    }

    private fun lists(e: JsonElement?): Map<Int, String> =
        (e as? JsonObject).orEmpty().mapNotNull { (k, v) -> k.trim().toIntOrNull()?.let { it to (v as? JsonPrimitive)?.contentOrNull.orEmpty() } }.toMap()

    private fun Char.isHex() = isDigit() || uppercaseChar() in 'A'..'F'

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    private fun JsonObject.double(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } }

    private val PRETTY =
        Json {
            prettyPrint = true
        }
}
