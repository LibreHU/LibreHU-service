package org.librehu.core.can

/**
 * What the head unit knows, for the messages it builds for the box ([CanTemplate]): numbers (`hour`, `minute`…) and
 * texts (`title`…), by name; null when unknown (the message is then not sent).
 */
interface CanState {
    fun number(name: String): Double?

    fun text(name: String): String?

    companion object {
        val NONE =
            object : CanState {
                override fun number(name: String): Double? = null

                override fun text(name: String): String? = null
            }

        /** The clock: `year` (4 digits), `year2`, `month`, `day`, `hour`, `minute`, `second`, `weekday` (1 = Monday), `hour12`, `pm`. */
        fun clock(t: java.time.LocalDateTime): Map<String, Double> =
            mapOf(
                "year" to t.year.toDouble(),
                "year2" to (t.year % 100).toDouble(),
                "month" to t.monthValue.toDouble(),
                "day" to t.dayOfMonth.toDouble(),
                "hour" to t.hour.toDouble(),
                "minute" to t.minute.toDouble(),
                "second" to t.second.toDouble(),
                "weekday" to t.dayOfWeek.value.toDouble(),
                "hour12" to ((t.hour + 11) % 12 + 1).toDouble(),
                "pm" to if (t.hour >= 12) 1.0 else 0.0,
            )
    }
}

/**
 * The data of a message the head unit sends, written in the profile (`"data"`, format 2): hex bytes and fields in
 * braces. `{formula}` = one byte of a [CanExpr] over the head unit's state (`{hour}`, `{minute + 1}`, `{value * 2}` in a
 * command); `{formula:2be}` / `{formula:2le}` = on 2 (up to 4) bytes, big or little endian; `{text:title:12}` = the text
 * `title` in ASCII on 12 bytes, padded with spaces (`{text:title:12:00}`: padded with 0x00); `{utf16le:title:12}` /
 * `{utf16be:…}` = 12 characters in UTF-16 (24 bytes). Any unknown value: no message.
 */
class CanTemplate private constructor(
    val source: String,
    private val parts: List<Part>,
) {
    private sealed interface Part

    private class Fixed(
        val v: Int,
    ) : Part

    private class Formula(
        val e: CanExpr,
        val size: Int,
        val little: Boolean,
    ) : Part

    private class Text(
        val name: String,
        val size: Int,
        val pad: Int,
        val charset: java.nio.charset.Charset,
    ) : Part

    /** Bytes that do not depend on the state, or null. */
    val fixed: List<Int>? = if (parts.all { it is Fixed }) parts.map { (it as Fixed).v } else null

    /** The bytes for [state] ([value]: a command's value), or null when something is unknown. */
    fun build(
        state: CanState,
        value: Double? = null,
    ): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val vars: (String) -> Double? = { n -> if (n == "value") value else state.number(n) }
        for (p in parts) {
            when (p) {
                is Fixed -> out.write(p.v)
                is Formula -> {
                    val v = p.e.eval(CanExpr.Context(ByteArray(0), vars = vars)) ?: return null
                    val n = Math.round(v)
                    for (i in 0 until p.size) {
                        val shift = 8 * if (p.little) i else p.size - 1 - i
                        out.write(((n shr shift) and 0xFF).toInt())
                    }
                }
                is Text -> {
                    val t = state.text(p.name) ?: return null
                    val utf16 = p.charset != Charsets.US_ASCII
                    val chars = t.take(p.size).padEnd(p.size, if (utf16 || p.pad == 0x20) ' ' else '\u0000')
                    val bytes =
                        if (utf16) {
                            chars.toByteArray(p.charset)
                        } else {
                            ByteArray(p.size) { i ->
                                val c = chars[i]
                                when {
                                    c == '\u0000' -> p.pad.toByte()
                                    c.code in 0x20..0x7E -> c.code.toByte()
                                    else -> '?'.code.toByte()
                                }
                            }
                        }
                    out.write(bytes)
                }
            }
        }
        return out.toByteArray()
    }

    override fun equals(other: Any?) = other is CanTemplate && other.source == source

    override fun hashCode() = source.hashCode()

    override fun toString() = source

    companion object {
        private const val MAX_BYTES = 128

        fun parse(
            text: String,
            where: String,
        ): CanTemplate {
            val parts = ArrayList<Part>()
            var size = 0
            for (m in Regex("""\{[^}]*\}|[^\s,{}]+""").findAll(text)) {
                val t = m.value
                if (!t.startsWith("{")) {
                    val v = t.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFF } ?: throw CanVehicleException("$where: bad data byte \"$t\"")
                    parts += Fixed(v)
                    size += 1
                    continue
                }
                val inner = t.substring(1, t.length - 1).trim()
                val text16 = Regex("(text|utf16le|utf16be):([A-Za-z_][A-Za-z0-9_]*):([0-9]{1,3})(?::([0-9A-Fa-f]{2}))?").matchEntire(inner)
                if (text16 != null) {
                    val (kind, name, n, pad) = text16.destructured
                    val chars = n.toInt()
                    if (chars !in 1..64) throw CanVehicleException("$where: $kind length 1..64")
                    val cs =
                        when (kind) {
                            "utf16le" -> Charsets.UTF_16LE
                            "utf16be" -> Charsets.UTF_16BE
                            else -> Charsets.US_ASCII
                        }
                    parts += Text(name, chars, if (pad.isEmpty()) 0x20 else pad.toInt(16), cs)
                    size += if (cs == Charsets.US_ASCII) chars else 2 * chars
                    continue
                }
                val sized = Regex("(.*):([1-4])(be|le)").matchEntire(inner)
                val formula = sized?.groupValues?.get(1) ?: inner
                val e =
                    try {
                        CanExpr.parse(formula)
                    } catch (x: CanVehicleException) {
                        throw CanVehicleException("$where: ${x.message}")
                    }
                val n = sized?.groupValues?.get(2)?.toInt() ?: 1
                parts += Formula(e, n, sized?.groupValues?.get(3) == "le")
                size += n
            }
            if (size > MAX_BYTES) throw CanVehicleException("$where: more than $MAX_BYTES data bytes")
            return CanTemplate(text.trim(), parts)
        }
    }
}
