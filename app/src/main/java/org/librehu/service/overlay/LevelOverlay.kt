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
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.ui.ThemeFollower
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What a [LevelOverlay] shows: the volume of the audio chip, or the screen brightness. */
enum class OverlayKind(
    val prefsName: String,
) {
    VOLUME("volume_overlay"),
    BRIGHTNESS("brightness_overlay"),
}

enum class OverlayStyle { VERTICAL, HORIZONTAL }

enum class OverlayEdge { RIGHT, LEFT, TOP, BOTTOM }

data class LevelOverlaySettings(
    val enabled: Boolean = true,
    val style: OverlayStyle = OverlayStyle.VERTICAL,
    val edge: OverlayEdge = OverlayEdge.RIGHT,
    /** 0 extra small .. 4 extra large (2 = normal). */
    val size: Int = 2,
    /** Shift along the edge, in % of the screen (-40 = towards the top / left, 40 = towards the bottom / right). */
    val offset: Int = 0,
    /** Distance from the edge (dp). */
    val margin: Int = 24,
    /** Opacity of the panel background (%). */
    val opacity: Int = 95,
    val timeoutMs: Long = 2_500,
    val showNumber: Boolean = true,
    /** Drag on the bar sets the level (a touch on the speaker toggles mute). */
    val touch: Boolean = true,
) {
    companion object {
        val TIMEOUTS = listOf(1_500L, 2_500L, 4_000L, 6_000L)
        const val SIZES = 5
    }
}

/**
 * Level panel over every app (TYPE_APPLICATION_OVERLAY: the "display over other apps" permission), Android style, in
 * the colours of LibreHU Launcher (light / dark, accent): volume of the audio chip (replaces ivi-services' volume
 * bar) and screen brightness. Each kind has its own settings.
 */
class LevelOverlay private constructor(
    context: Context,
    val kind: OverlayKind,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences(kind.prefsName, Context.MODE_PRIVATE)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<LevelOverlaySettings> = _settings.asStateFlow()

    /** New level from a drag on the panel (wired to the service). */
    @Volatile
    var onSetLevel: ((Int) -> Unit)? = null

    /** Touch on the icon (volume: mute). */
    @Volatile
    var onIcon: (() -> Unit)? = null

    @Volatile
    private var quietUntil = 0L

    private var view: LevelView? = null
    private var dark = true
    private var accent = 0
    private val theme =
        ThemeFollower(app) { d, a ->
            main.post {
                dark = d
                accent = a
                view?.setColors(d, a, _settings.value.opacity)
            }
        }
    private var themeStarted = false
    private val hide = Runnable { remove() }

    fun canShow() = Settings.canDrawOverlays(app)

    fun update(transform: (LevelOverlaySettings) -> LevelOverlaySettings) {
        val s = transform(_settings.value)
        prefs
            .edit()
            .putBoolean("enabled", s.enabled)
            .putString("style", s.style.name)
            .putString("edge", s.edge.name)
            .putInt("size5", s.size)
            .putInt("offset", s.offset)
            .putInt("margin", s.margin)
            .putInt("opacity", s.opacity)
            .putLong("timeout", s.timeoutMs)
            .putBoolean("number", s.showNumber)
            .putBoolean("touch", s.touch)
            .apply()
        _settings.value = s
        main.post { remove() }
    }

    /** No panel for changes made in the next [ms] (automatic brightness of the headlights, for instance). */
    fun quiet(ms: Long) {
        quietUntil = SystemClock.uptimeMillis() + ms
    }

    /** Shows (or refreshes) the panel; any thread. */
    fun show(
        level: Int,
        max: Int,
        muted: Boolean = false,
        force: Boolean = false,
    ) {
        main.post {
            val s = _settings.value
            if (!force && (!s.enabled || SystemClock.uptimeMillis() < quietUntil)) return@post
            if (!canShow()) return@post
            if (!themeStarted) {
                themeStarted = true
                theme.start()
            }
            val v = view ?: attach(s) ?: return@post
            v.setLevel(level, max, muted)
            main.removeCallbacks(hide)
            main.postDelayed(hide, s.timeoutMs)
        }
    }

    private fun attach(s: LevelOverlaySettings): LevelView? {
        val dm = app.resources.displayMetrics
        val unit = dm.density * (0.7f + 0.15f * s.size.coerceIn(0, LevelOverlaySettings.SIZES - 1))
        val vertical = s.style == OverlayStyle.VERTICAL
        val long = (if (vertical) 260 else 420) * unit
        val thick = 64 * unit
        val v = LevelView(app, kind, vertical, unit, s.showNumber)
        v.setColors(dark, accent, s.opacity)
        if (s.touch) {
            v.onLevel = { level ->
                onSetLevel?.invoke(level)
                main.removeCallbacks(hide)
                main.postDelayed(hide, s.timeoutMs)
            }
            v.onIcon = { onIcon?.invoke() }
        }
        val alongEdge = s.offset.coerceIn(-45, 45) / 100f
        val margin = (s.margin.coerceIn(0, 200) * dm.density).roundToInt()
        val lp =
            WindowManager
                .LayoutParams(
                    (if (vertical) thick else long).roundToInt(),
                    (if (vertical) long else thick).roundToInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        (if (s.touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    val side = s.edge == OverlayEdge.LEFT || s.edge == OverlayEdge.RIGHT
                    gravity =
                        when (s.edge) {
                            OverlayEdge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                            OverlayEdge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                            OverlayEdge.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            OverlayEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        }
                    // x / y: distance from the edge, then the shift along it (from the centre).
                    x = if (side) margin else (alongEdge * dm.widthPixels).roundToInt()
                    y = if (side) (alongEdge * dm.heightPixels).roundToInt() else margin
                    windowAnimations = android.R.style.Animation_Toast
                }
        return try {
            wm.addView(v, lp)
            view = v
            v
        } catch (e: Exception) {
            android.util.Log.w("LibreHU", "${kind.name} overlay: ${e.message}")
            null
        }
    }

    private fun remove() {
        main.removeCallbacks(hide)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun load(): LevelOverlaySettings {
        val d = LevelOverlaySettings()
        // "size" (0..2) of the first volume panel → 0..4.
        val oldSize = if (prefs.contains("size")) prefs.getInt("size", 1) * 2 else d.size
        return LevelOverlaySettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            style = runCatching { OverlayStyle.valueOf(prefs.getString("style", null)!!) }.getOrDefault(d.style),
            edge = runCatching { OverlayEdge.valueOf(prefs.getString("edge", null)!!) }.getOrDefault(d.edge),
            size = prefs.getInt("size5", oldSize),
            offset = prefs.getInt("offset", d.offset),
            margin = prefs.getInt("margin", d.margin),
            opacity = prefs.getInt("opacity", d.opacity),
            timeoutMs = prefs.getLong("timeout", d.timeoutMs),
            showNumber = prefs.getBoolean("number", d.showNumber),
            touch = prefs.getBoolean("touch", d.touch),
        )
    }

    companion object {
        private val instances = HashMap<OverlayKind, LevelOverlay>()

        fun get(
            context: Context,
            kind: OverlayKind,
        ): LevelOverlay = synchronized(instances) { instances.getOrPut(kind) { LevelOverlay(context, kind) } }

        fun volume(context: Context) = get(context, OverlayKind.VOLUME)

        fun brightness(context: Context) = get(context, OverlayKind.BRIGHTNESS)
    }
}

/** Rounded panel: icon (speaker / sun), track filled to the level in the accent colour, number. */
@SuppressLint("ViewConstructor")
private class LevelView(
    context: Context,
    private val kind: OverlayKind,
    private val vertical: Boolean,
    private val unit: Float,
    private val showNumber: Boolean,
) : View(context) {
    var onLevel: ((Int) -> Unit)? = null
    var onIcon: (() -> Unit)? = null
    private var level = 0
    private var max = 40
    private var muted = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    private val rect = RectF()
    private val path = Path()

    fun setColors(
        dark: Boolean,
        accentArgb: Int,
        opacity: Int,
    ) {
        val accent =
            if (accentArgb != 0) {
                accentArgb
            } else if (dark) {
                0xFF8AB4F8.toInt()
            } else {
                0xFF1A73E8.toInt()
            }
        val o = opacity.coerceIn(20, 100)
        bg.color = (if (dark) 0x202124 else 0xFFFFFF) or ((o * 255 / 100) shl 24)
        track.color = if (dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
        fill.color = accent
        icon.color = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        text.color = icon.color
        bg.setShadowLayer(8 * unit, 0f, 2 * unit, Color.argb(90 * o / 100, 0, 0, 0))
        setLayerType(LAYER_TYPE_SOFTWARE, bg)
        invalidate()
    }

    fun setLevel(
        v: Int,
        m: Int,
        mute: Boolean,
    ) {
        level = v
        max = m.coerceAtLeast(1)
        muted = mute
        invalidate()
    }

    /** Zone of the track along the long axis (after the icon, before the number). */
    private fun trackRange(): Pair<Float, Float> {
        val head = 56 * unit
        val tail = if (showNumber) 44 * unit else 16 * unit
        val length = if (vertical) height.toFloat() else width.toFloat()
        // Vertical: icon at the bottom, number at the top; horizontal: icon left, number right.
        return if (vertical) tail to (length - head) else head to (length - tail)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2
        rect.set(4 * unit, 4 * unit, w - 4 * unit, h - 4 * unit)
        c.drawRoundRect(rect, r, r, bg)
        val (a, b) = trackRange()
        val frac = if (muted) 0f else level.toFloat() / max
        val thick = 10 * unit
        if (vertical) {
            val cx = w / 2
            rect.set(cx - thick / 2, a, cx + thick / 2, b)
            c.drawRoundRect(rect, thick, thick, track)
            rect.set(cx - thick / 2, b - (b - a) * frac, cx + thick / 2, b)
            if (frac > 0) c.drawRoundRect(rect, thick, thick, fill)
            drawIcon(c, cx, h - 30 * unit)
            if (showNumber) drawNumber(c, cx, 28 * unit)
        } else {
            val cy = h / 2
            rect.set(a, cy - thick / 2, b, cy + thick / 2)
            c.drawRoundRect(rect, thick, thick, track)
            rect.set(a, cy - thick / 2, a + (b - a) * frac, cy + thick / 2)
            if (frac > 0) c.drawRoundRect(rect, thick, thick, fill)
            drawIcon(c, 30 * unit, cy)
            if (showNumber) drawNumber(c, w - 24 * unit, cy + 6 * unit)
        }
    }

    private fun drawNumber(
        c: Canvas,
        x: Float,
        y: Float,
    ) {
        text.textSize = 15 * unit
        val shown =
            when {
                muted -> "–"
                kind == OverlayKind.BRIGHTNESS -> "${(level * 100f / max).roundToInt()}%"
                else -> level.toString()
            }
        c.drawText(shown, x, y, text)
    }

    private fun drawIcon(
        c: Canvas,
        cx: Float,
        cy: Float,
    ) {
        if (kind == OverlayKind.BRIGHTNESS) drawSun(c, cx, cy) else drawSpeaker(c, cx, cy)
    }

    /** Sun: disc, rays longer with the brightness. */
    private fun drawSun(
        c: Canvas,
        cx: Float,
        cy: Float,
    ) {
        val s = 9 * unit
        icon.style = Paint.Style.FILL
        c.drawCircle(cx, cy, s * 0.5f, icon)
        icon.style = Paint.Style.STROKE
        icon.strokeWidth = 2 * unit
        val ray = s * (0.25f + 0.45f * level / max)
        for (i in 0 until 8) {
            val ang = Math.PI * i / 4
            val x0 = cx + cos(ang).toFloat() * s * 0.8f
            val y0 = cy + sin(ang).toFloat() * s * 0.8f
            c.drawLine(x0, y0, x0 + cos(ang).toFloat() * ray, y0 + sin(ang).toFloat() * ray, icon)
        }
    }

    /** Speaker, with sound waves or a cross when muted. */
    private fun drawSpeaker(
        c: Canvas,
        cx: Float,
        cy: Float,
    ) {
        val s = 9 * unit
        icon.style = Paint.Style.FILL
        path.reset()
        path.moveTo(cx - s * 1.1f, cy - s * 0.45f)
        path.lineTo(cx - s * 0.5f, cy - s * 0.45f)
        path.lineTo(cx + s * 0.2f, cy - s)
        path.lineTo(cx + s * 0.2f, cy + s)
        path.lineTo(cx - s * 0.5f, cy + s * 0.45f)
        path.lineTo(cx - s * 1.1f, cy + s * 0.45f)
        path.close()
        c.drawPath(path, icon)
        icon.style = Paint.Style.STROKE
        icon.strokeWidth = 2 * unit
        if (muted) {
            c.drawLine(cx + s * 0.55f, cy - s * 0.45f, cx + s * 1.35f, cy + s * 0.45f, icon)
            c.drawLine(cx + s * 0.55f, cy + s * 0.45f, cx + s * 1.35f, cy - s * 0.45f, icon)
        } else {
            val waves =
                if (level == 0) {
                    0
                } else if (level < max / 2) {
                    1
                } else {
                    2
                }
            for (i in 1..waves) {
                val rr = s * (0.35f + 0.4f * i)
                rect.set(cx + s * 0.2f - rr, cy - rr, cx + s * 0.2f + rr, cy + rr)
                c.drawArc(rect, -45f, 90f, false, icon)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onLevel == null) return false
        val pos = if (vertical) e.y else e.x
        val length = if (vertical) height.toFloat() else width.toFloat()
        val onIconZone = if (vertical) pos > length - 56 * unit else pos < 56 * unit
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (onIconZone && e.actionMasked == MotionEvent.ACTION_DOWN) return true
                if (!onIconZone) {
                    val (a, b) = trackRange()
                    val f = ((pos - a) / (b - a)).coerceIn(0f, 1f)
                    val v = ((if (vertical) 1 - f else f) * max).roundToInt()
                    if (v != level || muted) {
                        level = v
                        invalidate()
                        onLevel?.invoke(v)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (onIconZone) onIcon?.invoke()
            }
        }
        return true
    }
}
