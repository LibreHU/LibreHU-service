package org.librehu.service.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.librehu.core.unit.VehicleState
import org.librehu.service.LibreHuService
import org.librehu.service.R
import kotlin.math.roundToInt

data class StatusOsdSettings(
    /** Off by default. */
    val enabled: Boolean = false,
    val headlights: Boolean = true,
    val reverse: Boolean = true,
    val handbrake: Boolean = true,
    val turnSignals: Boolean = true,
    /** At the top of the screen, else at the bottom. */
    val top: Boolean = true,
    val durationMs: Int = 2_000,
)

/**
 * Small indicator over every app when a vehicle input changes: headlights on / off, reverse gear, parking brake, and
 * the turn signals (shown as long as they blink). Display over other apps; nothing is touchable.
 */
class StatusOsd private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<StatusOsdSettings> = _settings.asStateFlow()

    private var last: VehicleState? = null
    private var view: OsdView? = null
    private var message: Item? = null
    private var turn: Item? = null
    private val hideMessage =
        Runnable {
            message = null
            refresh()
        }

    fun update(transform: (StatusOsdSettings) -> StatusOsdSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs
            .edit()
            .putString(
                PREF,
                JSONObject()
                    .put("enabled", s.enabled)
                    .put("headlights", s.headlights)
                    .put("reverse", s.reverse)
                    .put("handbrake", s.handbrake)
                    .put("turn", s.turnSignals)
                    .put("top", s.top)
                    .put("duration", s.durationMs)
                    .toString(),
            ).apply()
        main.post { remove() }
    }

    /** Every vehicle state (any thread). The first one only sets the reference. */
    fun onState(state: VehicleState) =
        main.post {
            val old = last
            last = state
            val s = _settings.value
            if (old == null || !s.enabled || !state.powered || !Settings.canDrawOverlays(app)) {
                if (!s.enabled) {
                    message = null
                    turn = null
                    remove()
                }
                return@post
            }
            if (s.headlights && old.headlight != state.headlight) {
                show(
                    Item(
                        Icon.HEADLIGHT,
                        app.getString(if (state.headlight) R.string.osd_lights_on else R.string.osd_lights_off),
                        state.headlight,
                    ),
                )
            }
            if (s.reverse && old.reverse != state.reverse) {
                show(
                    Item(
                        Icon.REVERSE,
                        app.getString(if (state.reverse) R.string.osd_reverse_on else R.string.osd_reverse_off),
                        state.reverse,
                    ),
                )
            }
            if (s.handbrake && old.handbrake != state.handbrake) {
                show(
                    Item(
                        Icon.HANDBRAKE,
                        app.getString(if (state.handbrake) R.string.osd_brake_on else R.string.osd_brake_off),
                        state.handbrake,
                    ),
                )
            }
            if (s.turnSignals) {
                val t =
                    when {
                        state.turnLeft && state.turnRight -> Item(Icon.HAZARD, app.getString(R.string.osd_hazard), true)
                        state.turnLeft -> Item(Icon.LEFT, app.getString(R.string.osd_left), true)
                        state.turnRight -> Item(Icon.RIGHT, app.getString(R.string.osd_right), true)
                        else -> null
                    }
                if (t?.icon != turn?.icon) {
                    turn = t
                    refresh()
                }
            }
        }

    /** Shows a sample (settings). */
    fun test() =
        main.post {
            show(Item(Icon.HEADLIGHT, app.getString(R.string.osd_lights_on), true))
        }

    private fun show(item: Item) {
        message = item
        main.removeCallbacks(hideMessage)
        main.postDelayed(hideMessage, _settings.value.durationMs.toLong())
        refresh()
    }

    private fun refresh() {
        val items = listOfNotNull(turn, message)
        if (items.isEmpty()) {
            remove()
            return
        }
        val v = view ?: attach() ?: return
        v.items = items
        v.requestLayout()
        v.invalidate()
    }

    private fun attach(): OsdView? {
        if (!Settings.canDrawOverlays(app)) return null
        val s = _settings.value
        val v = OsdView(app)
        val lp =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = (if (s.top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
                    y = (24 * app.resources.displayMetrics.density).roundToInt()
                    windowAnimations = android.R.style.Animation_Toast
                }
        return try {
            wm.addView(v, lp)
            view = v
            v
        } catch (e: Exception) {
            android.util.Log.w("LibreHU", "status OSD: ${e.message}")
            null
        }
    }

    private fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun load(): StatusOsdSettings {
        val d = StatusOsdSettings()
        val o = prefs.getString(PREF, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return d
        return StatusOsdSettings(
            enabled = o.optBoolean("enabled", d.enabled),
            headlights = o.optBoolean("headlights", d.headlights),
            reverse = o.optBoolean("reverse", d.reverse),
            handbrake = o.optBoolean("handbrake", d.handbrake),
            turnSignals = o.optBoolean("turn", d.turnSignals),
            top = o.optBoolean("top", d.top),
            durationMs = o.optInt("duration", d.durationMs),
        )
    }

    enum class Icon { HEADLIGHT, REVERSE, HANDBRAKE, LEFT, RIGHT, HAZARD }

    data class Item(
        val icon: Icon,
        val text: String,
        val on: Boolean,
    )

    companion object {
        private const val PREF = "status_osd"

        @Volatile
        private var instance: StatusOsd? = null

        fun get(context: Context): StatusOsd =
            instance ?: synchronized(this) {
                instance ?: StatusOsd(context.applicationContext).also { instance = it }
            }
    }
}

/** Rounded pills side by side: a coloured badge with the symbol, then the text. */
@SuppressLint("ViewConstructor")
private class OsdView(
    context: Context,
) : View(context) {
    var items: List<StatusOsd.Item> = emptyList()
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 32, 33, 36) }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3 * d
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20 * d
            typeface = Typeface.DEFAULT_BOLD
        }
    private val rect = RectF()
    private val path = Path()
    private val pillH = 52 * d
    private val gap = 12 * d

    private fun pillWidth(i: StatusOsd.Item) = pillH + text.measureText(i.text) + 22 * d

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val w = items.sumOf { pillWidth(it).toDouble() } + gap * (items.size - 1).coerceAtLeast(0)
        setMeasuredDimension(w.roundToInt().coerceAtLeast(1), pillH.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        var x = 0f
        for (i in items) {
            val w = pillWidth(i)
            rect.set(x, 0f, x + w, pillH)
            canvas.drawRoundRect(rect, pillH / 2, pillH / 2, bg)
            val cx = x + pillH / 2
            val cy = pillH / 2
            val r = pillH / 2 - 6 * d
            badge.color =
                when (i.icon) {
                    StatusOsd.Icon.HEADLIGHT -> if (i.on) Color.rgb(52, 199, 89) else Color.rgb(110, 110, 115)
                    StatusOsd.Icon.REVERSE -> if (i.on) Color.rgb(255, 255, 255) else Color.rgb(110, 110, 115)
                    StatusOsd.Icon.HANDBRAKE -> if (i.on) Color.rgb(255, 59, 48) else Color.rgb(110, 110, 115)
                    StatusOsd.Icon.LEFT, StatusOsd.Icon.RIGHT -> Color.rgb(52, 199, 89)
                    StatusOsd.Icon.HAZARD -> Color.rgb(255, 59, 48)
                }
            canvas.drawCircle(cx, cy, r, badge)
            drawGlyph(canvas, i, cx, cy, r * 0.55f)
            canvas.drawText(i.text, x + pillH + 4 * d, cy - (text.ascent() + text.descent()) / 2, text)
            x += w + gap
        }
    }

    private fun drawGlyph(
        canvas: Canvas,
        i: StatusOsd.Item,
        cx: Float,
        cy: Float,
        s: Float,
    ) {
        glyph.color = if (i.icon == StatusOsd.Icon.REVERSE && i.on) Color.BLACK else Color.WHITE
        when (i.icon) {
            StatusOsd.Icon.HEADLIGHT -> {
                // Lamp (half disc) with its beams.
                glyph.style = Paint.Style.FILL
                rect.set(cx - s * 0.2f, cy - s * 0.7f, cx + s * 1.0f, cy + s * 0.7f)
                canvas.drawArc(rect, 90f, 180f, true, glyph)
                glyph.style = Paint.Style.STROKE
                for (k in -1..1) canvas.drawLine(cx - s * 0.4f, cy + k * s * 0.5f, cx - s * 1.1f, cy + k * s * 0.5f, glyph)
            }

            StatusOsd.Icon.REVERSE, StatusOsd.Icon.HANDBRAKE -> {
                glyph.style = Paint.Style.FILL
                val t =
                    Paint(text).apply {
                        color = glyph.color
                        textSize = s * 2f
                        textAlign = Paint.Align.CENTER
                    }
                canvas.drawText(if (i.icon == StatusOsd.Icon.REVERSE) "R" else "P", cx, cy - (t.ascent() + t.descent()) / 2, t)
            }

            StatusOsd.Icon.LEFT, StatusOsd.Icon.RIGHT -> arrow(canvas, cx, cy, s, i.icon == StatusOsd.Icon.LEFT)

            StatusOsd.Icon.HAZARD -> {
                glyph.style = Paint.Style.STROKE
                path.reset()
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s * 0.95f, cy + s * 0.7f)
                path.lineTo(cx - s * 0.95f, cy + s * 0.7f)
                path.close()
                canvas.drawPath(path, glyph)
            }
        }
    }

    private fun arrow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
        left: Boolean,
    ) {
        val k = if (left) -1 else 1
        glyph.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx + k * s, cy)
        path.lineTo(cx, cy - s * 0.8f)
        path.lineTo(cx, cy - s * 0.3f)
        path.lineTo(cx - k * s * 0.9f, cy - s * 0.3f)
        path.lineTo(cx - k * s * 0.9f, cy + s * 0.3f)
        path.lineTo(cx, cy + s * 0.3f)
        path.lineTo(cx, cy + s * 0.8f)
        path.close()
        canvas.drawPath(path, glyph)
    }
}
