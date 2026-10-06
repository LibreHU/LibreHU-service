package org.librehu.service.obd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.librehu.core.obd.GaugeSpec
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class DashStyle { ANALOG, DIGITAL }

/** One value of the dashboard: [number] and [unit] already formatted, [value] null when unknown. */
data class DashItem(
    val key: String,
    val label: String,
    val value: Double?,
    val number: String,
    val unit: String,
)

data class DashData(
    val speed: DashItem,
    val rpm: DashItem,
    val others: List<DashItem>,
    /** Shown when not connected, null otherwise. */
    val status: String?,
)

/** Colours of LibreHU Launcher (car palette). */
class DashColors(
    dark: Boolean,
    accentArgb: Int,
) {
    val surface = if (dark) 0xFF1E1F22.toInt() else 0xFFFFFFFF.toInt()
    val raised = if (dark) 0xFF2B2D31.toInt() else 0xFFE8EAED.toInt()
    val track = if (dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
    val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    val dim = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    val accent =
        if (accentArgb != 0) {
            accentArgb
        } else if (dark) {
            0xFF8AB4F8.toInt()
        } else {
            0xFF1A73E8.toInt()
        }
    val red = if (dark) 0xFFF28B82.toInt() else 0xFFD93025.toInt()
}

/**
 * Car dashboard drawn for the OBD widgets (a widget can only show a picture of it): round dials with a value arc
 * and a pointer (analog), or big numbers, a segmented rev bar and tiles (digital), Android Auto style.
 */
object DashRenderer {
    /** Pixels of the bitmap at most: RemoteViews go through binder (1 MB). */
    private const val MAX_PIXELS = 200_000

    private const val START = 150f
    private const val SWEEP = 240f

    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun render(
        style: DashStyle,
        widthPx: Int,
        heightPx: Int,
        colors: DashColors,
        data: DashData,
    ): Bitmap {
        var w = widthPx.coerceAtLeast(200)
        var h = heightPx.coerceAtLeast(100)
        if (w * h > MAX_PIXELS) {
            val k = sqrt(MAX_PIXELS.toDouble() / (w * h))
            w = (w * k).toInt()
            h = (h * k).toInt()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colors.surface
        val radius = min(w, h) * 0.12f
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, p)
        when (style) {
            DashStyle.ANALOG -> analog(c, w.toFloat(), h.toFloat(), colors, data)
            DashStyle.DIGITAL -> digital(c, w.toFloat(), h.toFloat(), colors, data)
        }
        data.status?.let { status ->
            val t = text(colors.dim, h * 0.065f, medium)
            t.textAlign = Paint.Align.CENTER
            c.drawText(status, w / 2f, h * 0.1f, t)
        }
        return bmp
    }

    private fun text(
        color: Int,
        size: Float,
        face: Typeface,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = face
        textAlign = Paint.Align.CENTER
    }

    private fun angle(fraction: Double) = Math.toRadians(START + SWEEP * fraction)

    // --- Analog ------------------------------------------------------------------------------------------------

    private fun analog(
        c: Canvas,
        w: Float,
        h: Float,
        col: DashColors,
        d: DashData,
    ) {
        val side = w * 0.36f
        val r = min(side, h) * 0.44f
        dial(c, side / 2 + w * 0.01f, h * 0.54f, r, col, d.speed, full = true)
        dial(c, w - side / 2 - w * 0.01f, h * 0.54f, r, col, d.rpm, full = true)
        // Small dials stacked in the middle column.
        val others = d.others.take(3)
        if (others.isEmpty()) return
        val colW = w - 2 * side
        val cell = (h * 0.9f) / others.size
        val rs = min(colW * 0.42f, cell * 0.42f)
        others.forEachIndexed { i, item ->
            dial(c, w / 2, h * 0.08f + cell * (i + 0.5f), rs, col, item, full = false)
        }
    }

    /** Round dial: track, red zone, value arc, graduation (full), pointer, value in the middle. */
    private fun dial(
        c: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        col: DashColors,
        item: DashItem,
        full: Boolean,
    ) {
        val spec = GaugeSpec.of(item.key)
        val value = item.value
        val red = value != null && spec?.inRed(value) == true
        val arc =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * if (full) 0.08f else 0.13f
            }
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        arc.color = col.track
        c.drawArc(oval, START, SWEEP, false, arc)
        if (spec != null) {
            // Red zone: thin outer arc.
            val zone =
                Paint(arc).apply {
                    strokeWidth = r * 0.035f
                    strokeCap = Paint.Cap.BUTT
                    color = col.red
                }
            val outer = RectF(oval).apply { inset(-r * 0.09f, -r * 0.09f) }
            spec.redAbove?.let {
                c.drawArc(
                    outer,
                    START + SWEEP * spec.fraction(it).toFloat(),
                    SWEEP * (1 - spec.fraction(it).toFloat()),
                    false,
                    zone,
                )
            }
            spec.redBelow?.let { c.drawArc(outer, START, SWEEP * spec.fraction(it).toFloat(), false, zone) }
            if (value != null) {
                arc.color = if (red) col.red else col.accent
                val f = spec.fraction(value).toFloat()
                if (f > 0.002f) c.drawArc(oval, START, SWEEP * f, false, arc)
            }
            if (full) graduation(c, cx, cy, r, col, spec)
            if (value != null) pointer(c, cx, cy, r, if (red) col.red else col.text, spec.fraction(value), full)
        }
        // Value in the middle, label above, unit below.
        val big = text(if (red) col.red else col.text, r * if (full) 0.46f else 0.5f, light)
        c.drawText(item.number, cx, cy + big.textSize * 0.32f, big)
        val small = text(col.dim, r * if (full) 0.13f else 0.2f, medium)
        if (full) {
            c.drawText(item.label.uppercase(), cx, cy - r * 0.42f, small)
            c.drawText(item.unit, cx, cy + r * 0.42f, small)
        } else {
            c.drawText(item.label, cx, cy + r * 0.95f, small)
        }
    }

    private fun graduation(
        c: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        col: DashColors,
        spec: GaugeSpec,
    ) {
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        val label = text(col.dim, r * 0.11f, medium)
        val majors = spec.majors()
        majors.forEachIndexed { i, v ->
            val red = spec.inRed(v)
            tick.color = if (red) col.red else col.text
            tick.strokeWidth = r * 0.025f
            line(c, cx, cy, r * 0.8f, r * 0.9f, angle(spec.fraction(v)), tick)
            val a = angle(spec.fraction(v))
            c.drawText(
                spec.label(v),
                cx + (cos(a) * r * 0.66f).toFloat(),
                cy + (sin(a) * r * 0.66f).toFloat() + label.textSize * 0.35f,
                label.apply { color = if (red) col.red else col.dim },
            )
            if (i < majors.size - 1) {
                tick.strokeWidth = r * 0.012f
                tick.color = col.dim
                val step = (majors[i + 1] - v) / (spec.minors + 1)
                for (k in 1..spec.minors) line(c, cx, cy, r * 0.85f, r * 0.9f, angle(spec.fraction(v + step * k)), tick)
            }
        }
    }

    /** Pointer along the arc (keeps the middle free for the number), like recent digital clusters. */
    private fun pointer(
        c: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        color: Int,
        fraction: Double,
        full: Boolean,
    ) {
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeCap = Paint.Cap.ROUND
                strokeWidth = r * if (full) 0.04f else 0.07f
            }
        line(c, cx, cy, r * if (full) 0.55f else 0.7f, r * 1.04f, angle(fraction), p)
    }

    private fun line(
        c: Canvas,
        cx: Float,
        cy: Float,
        from: Float,
        to: Float,
        a: Double,
        p: Paint,
    ) {
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        c.drawLine(cx + ca * from, cy + sa * from, cx + ca * to, cy + sa * to, p)
    }

    // --- Digital -----------------------------------------------------------------------------------------------

    private fun digital(
        c: Canvas,
        w: Float,
        h: Float,
        col: DashColors,
        d: DashData,
    ) {
        val pad = h * 0.08f
        val left = w * 0.5f
        // Speed: huge number.
        val speedRed = d.speed.value?.let { GaugeSpec.of(d.speed.key)?.inRed(it) } == true
        val big = text(if (speedRed) col.red else col.text, h * 0.46f, light)
        big.textAlign = Paint.Align.LEFT
        c.drawText(d.speed.number, pad, h * 0.56f, big)
        val unit = text(col.dim, h * 0.08f, medium).apply { textAlign = Paint.Align.LEFT }
        c.drawText(d.speed.unit, pad + big.measureText(d.speed.number) + h * 0.03f, h * 0.56f, unit)
        // Revs: segmented bar, red zone at the end.
        val spec = GaugeSpec.of(d.rpm.key)
        val segments = 24
        val barW = left - pad * 1.5f
        val gap = barW * 0.012f
        val segW = (barW - gap * (segments - 1)) / segments
        val top = h * 0.68f
        val bh = h * 0.1f
        val lit = d.rpm.value?.let { v -> spec?.let { (it.fraction(v) * segments).toInt() } } ?: 0
        val seg = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until segments) {
            val mid = spec?.let { it.min + (it.max - it.min) * (i + 0.5) / segments }
            val redZone = mid != null && spec.inRed(mid)
            seg.color =
                when {
                    i < lit && redZone -> col.red
                    i < lit -> col.accent
                    redZone -> blend(col.red, col.surface, 0.7f)
                    else -> col.track
                }
            val x = pad + i * (segW + gap)
            // Taller towards the red zone.
            val grow = bh * (0.55f + 0.45f * i / (segments - 1))
            c.drawRoundRect(RectF(x, top + bh - grow, x + segW, top + bh), segW * 0.3f, segW * 0.3f, seg)
        }
        val rpmText = text(col.text, h * 0.085f, medium).apply { textAlign = Paint.Align.LEFT }
        c.drawText("${d.rpm.number} ${d.rpm.unit}", pad, top + bh + h * 0.12f, rpmText)
        // Other values: tiles.
        val others = d.others.take(4)
        if (others.isEmpty()) return
        val cols = if (others.size > 2) 2 else 1
        val rows = (others.size + cols - 1) / cols
        val gx = w - left - pad
        val tileW = (gx - pad * 0.5f * (cols - 1)) / cols
        val tileH = (h - pad * 2 - pad * 0.5f * (rows - 1)) / rows
        others.forEachIndexed { i, item ->
            val x = left + (i % cols) * (tileW + pad * 0.5f)
            val y = pad + (i / cols) * (tileH + pad * 0.5f)
            tile(c, RectF(x, y, x + tileW, y + tileH), col, item)
        }
    }

    private fun tile(
        c: Canvas,
        r: RectF,
        col: DashColors,
        item: DashItem,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.raised }
        val corner = min(r.width(), r.height()) * 0.18f
        c.drawRoundRect(r, corner, corner, p)
        val spec = GaugeSpec.of(item.key)
        val red = item.value != null && spec?.inRed(item.value) == true
        val inset = r.height() * 0.14f
        val label = text(col.dim, r.height() * 0.17f, medium).apply { textAlign = Paint.Align.LEFT }
        c.drawText(item.label, r.left + inset, r.top + inset + label.textSize * 0.8f, label)
        val value = text(if (red) col.red else col.text, r.height() * 0.34f, light).apply { textAlign = Paint.Align.LEFT }
        val baseline = r.top + r.height() * 0.68f
        c.drawText(item.number, r.left + inset, baseline, value)
        val unit = text(col.dim, r.height() * 0.15f, medium).apply { textAlign = Paint.Align.LEFT }
        c.drawText(item.unit, r.left + inset + value.measureText(item.number) + inset * 0.4f, baseline, unit)
        if (spec != null) {
            val barTop = r.bottom - inset * 0.9f
            val bar = RectF(r.left + inset, barTop, r.right - inset, barTop + r.height() * 0.06f)
            p.color = col.track
            c.drawRoundRect(bar, bar.height(), bar.height(), p)
            val v = item.value
            if (v != null) {
                p.color = if (red) col.red else col.accent
                bar.right = bar.left + bar.width() * spec.fraction(v).toFloat()
                c.drawRoundRect(bar, bar.height(), bar.height(), p)
            }
        }
    }

    private fun blend(
        a: Int,
        b: Int,
        t: Float,
    ): Int =
        Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )
}
