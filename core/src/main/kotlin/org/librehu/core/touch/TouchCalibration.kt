package org.librehu.core.touch

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/** 2D affine map: `x' = a·x + b·y + c`, `y' = d·x + e·y + f`. */
data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun map(
        x: Double,
        y: Double,
    ): Pair<Double, Double> = (a * x + b * y + c) to (d * x + e * y + f)

    fun inverse(): Affine {
        val det = a * e - b * d
        require(abs(det) > 1e-12) { "Affine map not invertible" }
        val ia = e / det
        val ib = -b / det
        val id = -d / det
        val ie = a / det
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f))
    }

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        /** Least-squares affine map sending [from] onto [to] (at least 3 points not on one line). */
        fun fit(
            from: List<Pair<Double, Double>>,
            to: List<Pair<Double, Double>>,
        ): Affine {
            require(from.size == to.size && from.size >= 3) { "At least 3 point pairs needed" }
            // Normal equations of [x y 1]·[p q r]ᵀ = target, solved for x' and y' separately.
            var sxx = 0.0
            var sxy = 0.0
            var sx = 0.0
            var syy = 0.0
            var sy = 0.0
            val n = from.size.toDouble()
            var sxu = 0.0
            var syu = 0.0
            var su = 0.0
            var sxv = 0.0
            var syv = 0.0
            var sv = 0.0
            for (i in from.indices) {
                val (x, y) = from[i]
                val (u, v) = to[i]
                sxx += x * x
                sxy += x * y
                sx += x
                syy += y * y
                sy += y
                sxu += x * u
                syu += y * u
                su += u
                sxv += x * v
                syv += y * v
                sv += v
            }
            val m = arrayOf(doubleArrayOf(sxx, sxy, sx), doubleArrayOf(sxy, syy, sy), doubleArrayOf(sx, sy, n))
            val (a, b, c) = solve3(m, doubleArrayOf(sxu, syu, su))
            val (d, e, f) = solve3(m, doubleArrayOf(sxv, syv, sv))
            return Affine(a, b, c, d, e, f)
        }

        private fun solve3(
            m: Array<DoubleArray>,
            r: DoubleArray,
        ): DoubleArray {
            fun det(q: Array<DoubleArray>) =
                q[0][0] * (q[1][1] * q[2][2] - q[1][2] * q[2][1]) -
                    q[0][1] * (q[1][0] * q[2][2] - q[1][2] * q[2][0]) +
                    q[0][2] * (q[1][0] * q[2][1] - q[1][1] * q[2][0])
            val d = det(m)
            require(abs(d) > 1e-9) { "Points are aligned: cannot calibrate" }
            return DoubleArray(3) { col ->
                val q = Array(3) { row -> DoubleArray(3) { k -> if (k == col) r[row] else m[row][k] } }
                det(q) / d
            }
        }
    }
}

/**
 * Calibration of the Goodix touch panel as the kernel driver applies it (`/sys/devices/platform/touch/gt9xx_props`,
 * text `A B C D E F Div`): `x' = (A·x + B·y + C) / Div`, `y' = (D·x + E·y + F) / Div`. Jancar's factory app writes
 * `1 0 0 0 1 0 1` (identity) before measuring, then the result with `Div = 65536` (ivi-settings `TouchCheckActivity`).
 */
data class Gt9xxMatrix(
    val a: Long,
    val b: Long,
    val c: Long,
    val d: Long,
    val e: Long,
    val f: Long,
    val div: Long,
) {
    fun toAffine(): Affine {
        val k = div.toDouble()
        return Affine(a / k, b / k, c / k, d / k, e / k, f / k)
    }

    /** Text written to the driver and to pointercal.xml. */
    override fun toString() = "$a $b $c $d $e $f $div"

    fun values(): List<Long> = listOf(a, b, c, d, e, f, div)

    companion object {
        const val DIV = 65536L
        val IDENTITY = Gt9xxMatrix(1, 0, 0, 0, 1, 0, 1)

        fun parse(text: String?): Gt9xxMatrix? {
            val v = text?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() } ?: return null
            if (v.size != 7 || v[6] == 0L) return null
            return Gt9xxMatrix(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        }

        fun of(t: Affine): Gt9xxMatrix =
            Gt9xxMatrix(
                (t.a * DIV).roundToLong(),
                (t.b * DIV).roundToLong(),
                (t.c * DIV).roundToLong(),
                (t.d * DIV).roundToLong(),
                (t.e * DIV).roundToLong(),
                (t.f * DIV).roundToLong(),
                DIV,
            )
    }
}

/**
 * One calibration touch: where the driver reported it ([kernelX], [kernelY], read from the input device), where
 * Android placed it on screen ([screenX], [screenY]), and where the target was ([targetX], [targetY]).
 */
data class CalibrationSample(
    val kernelX: Double,
    val kernelY: Double,
    val screenX: Double,
    val screenY: Double,
    val targetX: Double,
    val targetY: Double,
)

data class CalibrationResult(
    val matrix: Gt9xxMatrix,
    /** Largest distance, in screen pixels, between a target and where its touch will land. */
    val maxErrorPx: Double,
)

object TouchCalibration {
    /**
     * New driver matrix from the samples, whatever the panel orientation: Android's own mapping R (driver
     * coordinates → screen, rotation `persist.sf.hwrotation` included) is measured from the samples, raw panel
     * coordinates are recovered through the [current] matrix, and the new matrix sends them to `R⁻¹(target)`.
     */
    fun solve(
        current: Gt9xxMatrix,
        samples: List<CalibrationSample>,
    ): CalibrationResult {
        require(samples.size >= 3) { "At least 3 touches needed" }
        val kernel = samples.map { it.kernelX to it.kernelY }
        val r = Affine.fit(kernel, samples.map { it.screenX to it.screenY })
        val rInv = r.inverse()
        val oldInv = current.toAffine().inverse()
        val raw = kernel.map { (x, y) -> oldInv.map(x, y) }
        val wanted = samples.map { rInv.map(it.targetX, it.targetY) }
        val matrix = Gt9xxMatrix.of(Affine.fit(raw, wanted))
        val t = matrix.toAffine()
        val err =
            samples.indices.maxOf { i ->
                val (kx, ky) = t.map(raw[i].first, raw[i].second)
                val (sx, sy) = r.map(kx, ky)
                hypot(sx - samples[i].targetX, sy - samples[i].targetY)
            }
        return CalibrationResult(matrix, err)
    }

    /** Five targets as Jancar uses: four corners at [inset] of the size, and the centre. */
    fun targets(
        width: Int,
        height: Int,
        inset: Double = 0.1,
    ): List<Pair<Double, Double>> {
        val l = width * inset
        val t = height * inset
        val r = width * (1 - inset)
        val b = height * (1 - inset)
        return listOf(l to t, r to t, r to b, l to b, width / 2.0 to height / 2.0)
    }
}

/**
 * `/jancar/config/pointercal.xml`, read by ivi-services at boot (`TouchEventUtil.initTouchParameter`) to write the
 * driver matrix: one `<node touch="…">` per panel id (first 19 characters of `/proc/gt9xx_config`) with seven
 * `<item id="A|B|C|D|E|F|Div">` values.
 */
object Pointercal {
    private val IDS = listOf("A", "B", "C", "D", "E", "F", "Div")

    fun parse(xml: String): Map<String, Gt9xxMatrix> {
        val out = LinkedHashMap<String, Gt9xxMatrix>()
        val node = Regex("""<node\s+touch="([^"]*)"\s*>(.*?)</node>""", RegexOption.DOT_MATCHES_ALL)
        val item = Regex("""<item[^>]*>\s*(-?\d+)\s*</item>""")
        for (m in node.findAll(xml)) {
            val values = item.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            Gt9xxMatrix.parse(values.joinToString(" "))?.let { out[m.groupValues[1]] = it }
        }
        return out
    }

    fun write(nodes: Map<String, Gt9xxMatrix>): String =
        buildString {
            append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<config>\n")
            for ((id, m) in nodes) {
                append("<node touch=\"").append(id).append("\">\n")
                m.values().forEachIndexed { i, v ->
                    append("<item id=\"")
                        .append(IDS[i])
                        .append("\">")
                        .append(v)
                        .append("</item>\n")
                }
                append("</node>\n")
            }
            append("</config>\n")
        }

    fun upsert(
        xml: String?,
        id: String,
        m: Gt9xxMatrix,
    ): String = write((xml?.let(::parse) ?: emptyMap()) + (id to m))
}
