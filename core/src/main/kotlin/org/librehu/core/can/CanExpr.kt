package org.librehu.core.can

/**
 * A small, safe formula of a car profile (`"expr"`, `"when"`): read, parsed once, evaluated on each message, never
 * code. C syntax: numbers (`12`, `0x1F`, `1.5`), `+ - * / %`, `& | ^ ~ << >>` (on integers), `== != < <= > >=`,
 * `&& || !`, `c ? a : b`, parentheses. Names: `D0`..`D63` (data bytes, unsigned; a byte past the end is 0), `len` (data
 * bytes), `raw` (the signal's own bytes as read), the value of a signal decoded before it in the same message (its
 * name), `true` / `false`. Functions: `min`, `max`, `abs`, `round`, `floor`, `ceil`, `clamp(x, lo, hi)`,
 * `u16be(i)` `u16le(i)` `s16be(i)` `s16le(i)` `u24be(i)` `u24le(i)` `u32be(i)` `u32le(i)` `s8(i)` (bytes from D`i`),
 * `bit(x, n)`, `bits(x, start, length)`, `signed(x, bits)`, `bcd(x)`, `role("…")` (last value of a role, from any
 * message; NaN when none), `has("…")` (1 when that role has a value). A failed evaluation (unknown name, division by
 * zero) gives no value.
 */
class CanExpr private constructor(
    val source: String,
    private val root: Node,
) {
    /** What a formula reads. */
    class Context(
        val data: ByteArray,
        val raw: Long = 0,
        val signals: Map<String, Double> = emptyMap(),
        val roles: (String) -> Double? = { null },
    )

    /** The value, or null when the formula cannot be computed for this message. */
    fun eval(c: Context): Double? = runCatching { root.eval(c) }.getOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** True when the formula is not 0 (a condition). */
    fun test(c: Context): Boolean = (eval(c) ?: 0.0) != 0.0

    override fun equals(other: Any?) = other is CanExpr && other.source == source

    override fun hashCode() = source.hashCode()

    override fun toString() = source

    private sealed interface Node {
        fun eval(c: Context): Double
    }

    private class Num(
        val v: Double,
    ) : Node {
        override fun eval(c: Context) = v
    }

    private class Str(
        val v: String,
    ) : Node {
        override fun eval(c: Context): Double = throw IllegalStateException("text \"$v\" is not a number")
    }

    private class Name(
        val n: String,
    ) : Node {
        private val byte = Regex("D([0-9]{1,2})").matchEntire(n)?.groupValues?.get(1)?.toInt()

        override fun eval(c: Context): Double =
            when {
                byte != null -> c.byteAt(byte).toDouble()
                n == "len" -> c.data.size.toDouble()
                n == "raw" -> c.raw.toDouble()
                n == "true" -> 1.0
                n == "false" -> 0.0
                else -> c.signals[n] ?: throw IllegalStateException("unknown name $n")
            }
    }

    private class Unary(
        val op: String,
        val a: Node,
    ) : Node {
        override fun eval(c: Context): Double {
            val x = a.eval(c)
            return when (op) {
                "-" -> -x
                "+" -> x
                "!" -> if (x == 0.0) 1.0 else 0.0
                else -> x.toLong().inv().toDouble()
            }
        }
    }

    private class Binary(
        val op: String,
        val a: Node,
        val b: Node,
    ) : Node {
        override fun eval(c: Context): Double {
            if (op == "&&") return if (a.eval(c) != 0.0 && b.eval(c) != 0.0) 1.0 else 0.0
            if (op == "||") return if (a.eval(c) != 0.0 || b.eval(c) != 0.0) 1.0 else 0.0
            val x = a.eval(c)
            val y = b.eval(c)
            fun bool(v: Boolean) = if (v) 1.0 else 0.0
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0.0) throw ArithmeticException("division by zero") else x / y
                "%" -> if (y == 0.0) throw ArithmeticException("division by zero") else x % y
                "&" -> (x.toLong() and y.toLong()).toDouble()
                "|" -> (x.toLong() or y.toLong()).toDouble()
                "^" -> (x.toLong() xor y.toLong()).toDouble()
                "<<" -> (x.toLong() shl y.toInt().coerceIn(0, 63)).toDouble()
                ">>" -> (x.toLong() shr y.toInt().coerceIn(0, 63)).toDouble()
                "==" -> bool(x == y)
                "!=" -> bool(x != y)
                "<" -> bool(x < y)
                "<=" -> bool(x <= y)
                ">" -> bool(x > y)
                else -> bool(x >= y)
            }
        }
    }

    private class Ternary(
        val c0: Node,
        val a: Node,
        val b: Node,
    ) : Node {
        override fun eval(c: Context) = if (c0.eval(c) != 0.0) a.eval(c) else b.eval(c)
    }

    private class Call(
        val f: String,
        val args: List<Node>,
    ) : Node {
        override fun eval(c: Context): Double {
            if (f == "role" || f == "has") {
                val id = (args.single() as? Str)?.v ?: throw IllegalStateException("$f(\"role\")")
                val v = c.roles(id)
                return if (f == "has") (if (v != null) 1.0 else 0.0) else v ?: Double.NaN
            }
            val a = args.map { it.eval(c) }

            fun i(k: Int) = a[k].toInt()

            fun le(
                start: Int,
                n: Int,
            ): Long = (0 until n).fold(0L) { acc, k -> acc or (c.byteAt(start + k).toLong() shl (8 * k)) }

            fun be(
                start: Int,
                n: Int,
            ): Long = (0 until n).fold(0L) { acc, k -> (acc shl 8) or c.byteAt(start + k).toLong() }
            return when (f) {
                "min" -> a.min()
                "max" -> a.max()
                "abs" -> kotlin.math.abs(a[0])
                "round" -> Math.round(a[0]).toDouble()
                "floor" -> kotlin.math.floor(a[0])
                "ceil" -> kotlin.math.ceil(a[0])
                "clamp" -> a[0].coerceIn(a[1], a[2])
                "u16be" -> be(i(0), 2).toDouble()
                "u16le" -> le(i(0), 2).toDouble()
                "s16be" -> be(i(0), 2).toShort().toDouble()
                "s16le" -> le(i(0), 2).toShort().toDouble()
                "u24be" -> be(i(0), 3).toDouble()
                "u24le" -> le(i(0), 3).toDouble()
                "u32be" -> be(i(0), 4).toDouble()
                "u32le" -> le(i(0), 4).toDouble()
                "s8" -> c.byteAt(i(0)).toByte().toDouble()
                "bit" -> ((a[0].toLong() shr i(1)) and 1L).toDouble()
                "bits" -> ((a[0].toLong() shr i(1)) and ((1L shl i(2).coerceIn(1, 62)) - 1)).toDouble()
                "signed" -> {
                    val n = i(1).coerceIn(1, 63)
                    val v = a[0].toLong() and ((1L shl n) - 1)
                    (if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v).toDouble()
                }
                "bcd" -> {
                    var v = a[0].toLong()
                    var out = 0L
                    var mul = 1L
                    while (v > 0) {
                        out += (v and 0xF) * mul
                        mul *= 10
                        v = v shr 4
                    }
                    out.toDouble()
                }
                else -> throw IllegalStateException("unknown function $f")
            }
        }
    }

    companion object {
        const val MAX_LENGTH = 600
        private const val MAX_DEPTH = 64

        private val FUNCTIONS =
            mapOf(
                "min" to -2, "max" to -2, "abs" to 1, "round" to 1, "floor" to 1, "ceil" to 1, "clamp" to 3,
                "u16be" to 1, "u16le" to 1, "s16be" to 1, "s16le" to 1, "u24be" to 1, "u24le" to 1, "u32be" to 1, "u32le" to 1,
                "s8" to 1, "bit" to 2, "bits" to 3, "signed" to 2, "bcd" to 1, "role" to 1, "has" to 1,
            )

        private fun Context.byteAt(i: Int): Int = if (i in data.indices) data[i].toInt() and 0xFF else 0

        /** Parses [text]; throws [CanVehicleException] with the place of the mistake. */
        fun parse(text: String): CanExpr {
            if (text.length > MAX_LENGTH) throw CanVehicleException("formula longer than $MAX_LENGTH characters")
            val p = Parser(text)
            val node = p.expression(0)
            p.end()
            return CanExpr(text, node)
        }

        /** Checks the role names a formula reads ([CanRoles.isValid]). */
        internal fun roleNames(text: String): List<String> = Regex("""(?:role|has)\(\s*"([^"]*)"\s*\)""").findAll(text).map { it.groupValues[1] }.toList()
    }

    private class Parser(
        val s: String,
    ) {
        var pos = 0

        fun fail(what: String): Nothing = throw CanVehicleException("formula \"$s\": $what at ${pos + 1}")

        fun skip() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun peek(op: String): Boolean {
            skip()
            return s.startsWith(op, pos)
        }

        fun take(op: String): Boolean {
            if (!peek(op)) return false
            // "<" must not eat "<<" or "<=", "&" not "&&"…
            val next = s.getOrNull(pos + op.length)
            if (op.length == 1 && next != null && (op + next) in TWO) return false
            pos += op.length
            return true
        }

        fun end() {
            skip()
            if (pos != s.length) fail("unexpected \"${s[pos]}\"")
        }

        // Precedence climbing, C order.
        fun expression(depth: Int): Node {
            if (depth > MAX_DEPTH) fail("too deeply nested")
            val c = binary(0, depth)
            if (take("?")) {
                val a = expression(depth + 1)
                if (!take(":")) fail("\":\" expected")
                val b = expression(depth + 1)
                return Ternary(c, a, b)
            }
            return c
        }

        fun binary(
            level: Int,
            depth: Int,
        ): Node {
            if (level == LEVELS.size) return unary(depth)
            var left = binary(level + 1, depth)
            while (true) {
                val op = LEVELS[level].firstOrNull { take(it) } ?: return left
                left = Binary(op, left, binary(level + 1, depth))
            }
        }

        fun unary(depth: Int): Node {
            if (depth > MAX_DEPTH) fail("too deeply nested")
            for (op in listOf("-", "+", "!", "~")) if (take(op)) return Unary(op, unary(depth + 1))
            return primary(depth)
        }

        fun primary(depth: Int): Node {
            skip()
            if (pos >= s.length) fail("value expected")
            val ch = s[pos]
            if (ch == '(') {
                pos++
                val e = expression(depth + 1)
                if (!take(")")) fail("\")\" expected")
                return e
            }
            if (ch == '"') {
                val close = s.indexOf('"', pos + 1)
                if (close < 0) fail("unclosed text")
                val v = s.substring(pos + 1, close)
                pos = close + 1
                return Str(v)
            }
            if (ch.isDigit() || ch == '.') {
                val m = Regex("0[xX][0-9a-fA-F]+|[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?").find(s, pos)?.takeIf { it.range.first == pos } ?: fail("bad number")
                pos = m.range.last + 1
                val t = m.value
                return Num(if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16).toDouble() else t.toDouble())
            }
            if (ch.isLetter() || ch == '_') {
                val m = Regex("[A-Za-z_][A-Za-z0-9_]*").find(s, pos)!!
                pos = m.range.last + 1
                val name = m.value
                if (take("(")) {
                    val arity = FUNCTIONS[name] ?: fail("unknown function $name")
                    val args = mutableListOf<Node>()
                    if (!take(")")) {
                        do args += expression(depth + 1) while (take(","))
                        if (!take(")")) fail("\")\" expected")
                    }
                    if (if (arity < 0) args.size < -arity else args.size != arity) fail("$name: wrong number of arguments")
                    return Call(name, args)
                }
                val byte = Regex("D([0-9]{1,2})").matchEntire(name)?.groupValues?.get(1)?.toInt()
                if (byte != null && byte > 63) fail("D$byte: bytes go up to D63")
                return Name(name)
            }
            fail("unexpected \"$ch\"")
        }

        companion object {
            val TWO = setOf("<<", ">>", "<=", ">=", "==", "!=", "&&", "||")
            val LEVELS =
                listOf(
                    listOf("||"),
                    listOf("&&"),
                    listOf("|"),
                    listOf("^"),
                    listOf("&"),
                    listOf("==", "!="),
                    listOf("<=", ">=", "<", ">"),
                    listOf("<<", ">>"),
                    listOf("+", "-"),
                    listOf("*", "/", "%"),
                )
        }
    }
}
