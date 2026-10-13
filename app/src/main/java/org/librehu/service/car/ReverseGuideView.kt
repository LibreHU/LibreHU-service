package org.librehu.service.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * What is drawn over the rear camera image: guide lines (a rectangle on the ground behind the car seen in perspective,
 * set by its 4 corners on the screen), the trajectory following the steering wheel, the parking sensors, the moving
 * zones, a message (camera missing or busy) and the close button.
 */
@SuppressLint("ViewConstructor")
class ReverseGuideView(
    context: Context,
) : View(context) {
    var settings = ReverseViewSettings()
        set(value) {
            field = value
            invalidate()
        }

    /** Steering wheel angle (degrees, negative = left), null = not known. */
    var steering: Double? = null
        set(value) {
            field = value
            if (settings.lines.dynamic) invalidate()
        }

    /** Sensor levels (0 nothing .. 4 very close), rear left to rear right; null = no sensor data. */
    var radar: IntArray? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Moving cells of the image ([MOTION_W] × [MOTION_H], row by row), with the watched ones. */
    var motion: BooleanArray? = null
    var zone: BooleanArray? = null

    /** Image → view transform of the camera (to place the moving cells). */
    val imageMatrix = Matrix()
    var alert = false
    var message: String? = null
        set(value) {
            field = value
            invalidate()
        }
    var info: String = ""
    var preview = false
    var onClose: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val line =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val shadow = Paint(line)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4 * density, 0f, 0f, Color.BLACK)
        }
    private val ground = Matrix()
    private val pt = FloatArray(2)
    private val path = Path()
    private val rect = RectF()
    private val closeRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = settings
        drawMotion(canvas, w, h)
        if (s.lines.show) drawLines(canvas, w, h, s.lines)
        radar?.let { if (s.radar) drawRadar(canvas, w, it) }
        if (alert) {
            line.color = Color.argb(200, 255, 59, 48)
            line.strokeWidth = 8 * density
            canvas.drawRect(4 * density, 4 * density, w - 4 * density, h - 4 * density, line)
        }
        text.textSize = 15 * density
        text.textAlign = Paint.Align.LEFT
        if (s.showInfo && info.isNotEmpty()) canvas.drawText(info, 16 * density, h - 16 * density, text)
        message?.let {
            text.textSize = 22 * density
            text.textAlign = Paint.Align.CENTER
            var y = h / 2
            for (part in it.split('\n')) {
                canvas.drawText(part, w / 2, y, text)
                y += 30 * density
            }
        }
        drawClose(canvas, w)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return closeRect.contains(event.x, event.y)
        if (event.action == MotionEvent.ACTION_UP && closeRect.contains(event.x, event.y)) onClose?.invoke()
        return true
    }

    private fun drawClose(
        canvas: Canvas,
        w: Float,
    ) {
        val r = 26 * density
        val cx = w - r - 16 * density
        val cy = r + 16 * density
        closeRect.set(cx - r * 1.4f, cy - r * 1.4f, cx + r * 1.4f, cy + r * 1.4f)
        fill.color = Color.argb(140, 0, 0, 0)
        canvas.drawCircle(cx, cy, r, fill)
        line.color = Color.WHITE
        line.strokeWidth = 3 * density
        val c = r * 0.4f
        canvas.drawLine(cx - c, cy - c, cx + c, cy + c, line)
        canvas.drawLine(cx - c, cy + c, cx + c, cy - c, line)
        if (preview) {
            text.textSize = 16 * density
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(context.getString(org.librehu.service.R.string.rv_preview), cx - r - 12 * density, cy + 6 * density, text)
        }
    }

    private fun drawMotion(
        canvas: Canvas,
        w: Float,
        h: Float,
    ) {
        val m = motion ?: return
        val z = zone
        line.strokeWidth = 2 * density
        for (i in m.indices) {
            if (!m[i] || (z != null && !z[i])) continue
            val x = i % MOTION_W
            val y = i / MOTION_W
            rect.set(x.toFloat() / MOTION_W * w, y.toFloat() / MOTION_H * h, (x + 1f) / MOTION_W * w, (y + 1f) / MOTION_H * h)
            imageMatrix.mapRect(rect)
            fill.color = Color.argb(70, 255, 59, 48)
            canvas.drawRect(rect, fill)
        }
    }

    /** Ground (cm: x across, positive to the right of the picture; d behind the bumper) → view. */
    private fun setGround(
        w: Float,
        h: Float,
        g: GuideLines,
    ): Boolean {
        val half = g.carWidthCm / 2f
        val len = g.lengthCm.coerceAtLeast(50).toFloat()
        val cx = g.centerX / 100f * w
        val by = g.bottomY / 100f * h
        val ty = g.topY / 100f * h
        val bw = g.bottomWidth / 200f * w
        val tw = g.topWidth / 200f * w
        val src = floatArrayOf(-half, 0f, half, 0f, half, len, -half, len)
        val dst = floatArrayOf(cx - bw, by, cx + bw, by, cx + tw, ty, cx - tw, ty)
        return ground.setPolyToPoly(src, 0, dst, 0, 4)
    }

    private fun map(
        x: Float,
        d: Float,
    ): FloatArray {
        pt[0] = x
        pt[1] = d
        ground.mapPoints(pt)
        return pt
    }

    private fun colorAt(
        d: Float,
        g: GuideLines,
    ): Int =
        when {
            d < g.mark1Cm -> RED
            d < g.mark2Cm -> ORANGE
            d < g.mark3Cm -> YELLOW
            else -> GREEN
        }

    private fun drawLines(
        canvas: Canvas,
        w: Float,
        h: Float,
        g: GuideLines,
    ) {
        if (!setGround(w, h, g)) return
        val alpha = (g.opacity.coerceIn(10, 100) * 255 / 100)
        val thick = g.thickness.coerceIn(1, 20) * density
        line.strokeWidth = thick
        shadow.strokeWidth = thick + 3 * density
        shadow.color = Color.argb(alpha * 2 / 5, 0, 0, 0)
        val half = g.carWidthCm / 2f
        val len = g.lengthCm.coerceAtLeast(50).toFloat()
        val angle = steering
        val curved = g.dynamic && angle != null && abs(angle) >= 1.0
        if (curved) {
            // Reference: the straight lines, thin and pale, under the trajectory.
            line.strokeWidth = thick / 2
            line.color = Color.argb(alpha / 3, 255, 255, 255)
            segment(canvas, { t -> -half to t }, 0f, len)
            segment(canvas, { t -> half to t }, 0f, len)
            line.strokeWidth = thick
        }
        // Rear axle turning circle: radius = wheelbase / tan(wheel angle), the wheel angle being the steering wheel's
        // divided by the steering ratio. The bumper is taken on the axle (a few tens of cm off: fine for guides).
        val radius =
            if (curved) {
                val wheel = Math.toRadians(angle!! / g.steeringRatio.coerceAtLeast(5)) * (if (g.invertSteering) -1 else 1)
                g.wheelbaseCm / tan(wheel)
            } else {
                Double.POSITIVE_INFINITY
            }

        fun at(
            x0: Float,
            t: Float,
        ): Pair<Float, Float> {
            if (radius.isInfinite()) return x0 to t
            val r = radius.toFloat()
            val phi = t / r
            return (r - (r - x0) * cos(phi)) to ((r - x0) * sin(phi))
        }
        // How far along the path the lines go: [len] behind the bumper, or a quarter turn.
        val maxT = if (radius.isInfinite()) len else minOf(len * 1.6f, abs(radius.toFloat()) * 1.5f)
        val stops = floatArrayOf(0f, g.mark1Cm.toFloat(), g.mark2Cm.toFloat(), g.mark3Cm.toFloat(), Float.MAX_VALUE)
        for (side in floatArrayOf(-half, half)) {
            for (i in 0 until 4) {
                val from = stops[i]
                val to = minOf(stops[i + 1], maxT)
                if (from >= to) continue
                line.color = withAlpha(colorAt(from, g), alpha)
                segment(canvas, { t -> at(side, t) }, from, to, len)
            }
        }
        // Marks across, a quarter of the width from each line.
        for (mark in intArrayOf(g.mark1Cm, g.mark2Cm, g.mark3Cm)) {
            if (mark <= 0 || mark > maxT) continue
            val (lx, ld) = at(-half, mark.toFloat())
            val (rx, rd) = at(half, mark.toFloat())
            if (ld > len || rd > len) continue
            line.color = withAlpha(colorAt(mark - 1f, g), alpha)
            val f = 0.28f
            val ticks =
                arrayOf(
                    floatArrayOf(lx, ld, lx + (rx - lx) * f, ld + (rd - ld) * f),
                    floatArrayOf(rx, rd, rx - (rx - lx) * f, rd - (rd - ld) * f),
                )
            for (t in ticks) {
                val a = map(t[0], t[1]).copyOf()
                val b = map(t[2], t[3])
                canvas.drawLine(a[0], a[1], b[0], b[1], shadow)
                canvas.drawLine(a[0], a[1], b[0], b[1], line)
            }
        }
    }

    /** Polyline of [at] between two path lengths, cut at [maxD] behind the bumper. */
    private fun segment(
        canvas: Canvas,
        at: (Float) -> Pair<Float, Float>,
        from: Float,
        to: Float,
        maxD: Float = Float.MAX_VALUE,
    ) {
        path.reset()
        val steps = 24
        var started = false
        for (i in 0..steps) {
            val t = from + (to - from) * i / steps
            val (x, d) = at(t)
            if (d < 0 || d > maxD) break
            val p = map(x, d)
            if (!started) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
            started = true
        }
        if (!started) return
        canvas.drawPath(path, shadow)
        canvas.drawPath(path, line)
    }

    /** Car seen from above (bottom) with four rings of segments behind it, the lit one at each sensor's level. */
    private fun drawRadar(
        canvas: Canvas,
        w: Float,
        levels: IntArray,
    ) {
        val u = density
        val cx = 110 * u
        val carTop = 150 * u
        fill.color = Color.argb(150, 0, 0, 0)
        rect.set(cx - 100 * u, 16 * u, cx + 100 * u, carTop + 30 * u)
        canvas.drawRoundRect(rect, 18 * u, 18 * u, fill)
        fill.color = Color.argb(220, 230, 230, 230)
        rect.set(cx - 34 * u, carTop - 4 * u, cx + 34 * u, carTop + 22 * u)
        canvas.drawRoundRect(rect, 8 * u, 8 * u, fill)
        line.strokeCap = Paint.Cap.BUTT
        line.strokeWidth = 9 * u
        for (ring in 1..4) {
            // Ring 4 is the closest to the car.
            val r = (40 + (4 - ring) * 22) * u
            rect.set(cx - r, carTop - r, cx + r, carTop + r)
            for (i in 0 until 4) {
                val level = levels.getOrElse(i) { 0 }
                line.color = if (level == ring) withAlpha(RING_COLORS[ring], 255) else Color.argb(60, 255, 255, 255)
                canvas.drawArc(rect, 205f + i * 33f, 30f, false, line)
            }
        }
        line.strokeCap = Paint.Cap.ROUND
    }

    private fun withAlpha(
        c: Int,
        a: Int,
    ) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    /** Watched cells: those whose centre is on the screen below [ReverseViewSettings.motionTop]. */
    fun zoneMask(
        w: Float,
        h: Float,
        top: Int,
    ): BooleanArray {
        val mask = BooleanArray(MOTION_W * MOTION_H)
        val p = FloatArray(2)
        for (i in mask.indices) {
            p[0] = (i % MOTION_W + 0.5f) / MOTION_W * w
            p[1] = (i / MOTION_W + 0.5f) / MOTION_H * h
            imageMatrix.mapPoints(p)
            mask[i] = p[0] in 0f..w && p[1] >= top / 100f * h && p[1] <= h
        }
        return mask
    }

    /** Lowest point (0 top .. 1 bottom of the screen) of the moving watched cells, -1 if none. */
    fun lowest(
        m: BooleanArray,
        z: BooleanArray,
        w: Float,
        h: Float,
    ): Float {
        var best = -1f
        val p = FloatArray(2)
        for (i in m.indices) {
            if (!m[i] || !z[i]) continue
            p[0] = (i % MOTION_W + 0.5f) / MOTION_W * w
            p[1] = (i / MOTION_W + 1f) / MOTION_H * h
            imageMatrix.mapPoints(p)
            best = maxOf(best, p[1] / h)
        }
        return best
    }

    companion object {
        const val MOTION_W = 32
        const val MOTION_H = 18
        private val RED = Color.rgb(255, 59, 48)
        private val ORANGE = Color.rgb(255, 149, 0)
        private val YELLOW = Color.rgb(255, 214, 10)
        private val GREEN = Color.rgb(52, 199, 89)
        private val RING_COLORS = intArrayOf(0, GREEN, YELLOW, ORANGE, RED)
    }
}
