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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.ui.ThemeFollower
import kotlin.math.roundToInt

enum class VolumeStyle { VERTICAL, HORIZONTAL }

enum class VolumeEdge { RIGHT, LEFT, TOP, BOTTOM }

data class VolumeOverlaySettings(
    val enabled: Boolean = true,
    val style: VolumeStyle = VolumeStyle.VERTICAL,
    val edge: VolumeEdge = VolumeEdge.RIGHT,
    /** 0 small, 1 normal, 2 large. */
    val size: Int = 1,
    val timeoutMs: Long = 2_500,
    val showNumber: Boolean = true,
    /** Drag on the bar sets the volume, a touch on the speaker toggles mute. */
    val touch: Boolean = true,
) {
    companion object {
        val TIMEOUTS = listOf(1_500L, 2_500L, 4_000L, 6_000L)
    }
}

/**
 * Volume panel of the audio chip, Android style, over every app (TYPE_APPLICATION_OVERLAY: the "display over other
 * apps" permission). Replaces the volume bar ivi-services showed. Colours of LibreHU Launcher (light / dark, accent).
 */
class VolumeOverlay private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("volume_overlay", Context.MODE_PRIVATE)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<VolumeOverlaySettings> = _settings.asStateFlow()

    /** Volume step / mute from the panel (wired to the service). */
    @Volatile
    var onSetVolume: ((Int) -> Unit)? = null

    @Volatile
    var onToggleMute: (() -> Unit)? = null

    private var view: VolumeView? = null
    private var dark = true
    private var accent = 0
    private val theme =
        ThemeFollower(app) { d, a ->
            main.post {
                dark = d
                accent = a
                view?.setColors(d, a)
            }
        }
    private var themeStarted = false
    private val hide = Runnable { remove() }

    fun canShow() = Settings.canDrawOverlays(app)

    fun update(transform: (VolumeOverlaySettings) -> VolumeOverlaySettings) {
        val s = transform(_settings.value)
        prefs
            .edit()
            .putBoolean("enabled", s.enabled)
            .putString("style", s.style.name)
            .putString("edge", s.edge.name)
            .putInt("size", s.size)
            .putLong("timeout", s.timeoutMs)
            .putBoolean("number", s.showNumber)
            .putBoolean("touch", s.touch)
            .apply()
        _settings.value = s
        main.post { remove() }
    }

    /** Shows (or refreshes) the panel; any thread. */
    fun show(
        volume: Int,
        max: Int,
        muted: Boolean,
        force: Boolean = false,
    ) {
        main.post {
            val s = _settings.value
            if ((!s.enabled && !force) || !canShow()) return@post
            if (!themeStarted) {
                themeStarted = true
                theme.start()
            }
            val v = view ?: attach(s) ?: return@post
            v.setLevel(volume, max, muted)
            main.removeCallbacks(hide)
            main.postDelayed(hide, s.timeoutMs)
        }
    }

    private fun attach(s: VolumeOverlaySettings): VolumeView? {
        val dm = app.resources.displayMetrics
        val unit = dm.density * (0.8f + 0.2f * s.size)
        val vertical = s.style == VolumeStyle.VERTICAL
        val long = (if (vertical) 260 else 420) * unit
        val thick = 64 * unit
        val v = VolumeView(app, vertical, unit, s.showNumber)
        v.setColors(dark, accent)
        if (s.touch) {
            v.onLevel = { level ->
                onSetVolume?.invoke(level)
                main.removeCallbacks(hide)
                main.postDelayed(hide, s.timeoutMs)
            }
            v.onMute = { onToggleMute?.invoke() }
        }
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
                    val margin = (24 * dm.density).roundToInt()
                    gravity =
                        when (s.edge) {
                            VolumeEdge.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                            VolumeEdge.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                            VolumeEdge.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            VolumeEdge.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        }
                    x = if (s.edge == VolumeEdge.LEFT || s.edge == VolumeEdge.RIGHT) margin else 0
                    y = if (s.edge == VolumeEdge.TOP || s.edge == VolumeEdge.BOTTOM) margin else 0
                    windowAnimations = android.R.style.Animation_Toast
                }
        return try {
            wm.addView(v, lp)
            view = v
            v
        } catch (e: Exception) {
            android.util.Log.w("LibreHU", "Volume overlay: ${e.message}")
            null
        }
    }

    private fun remove() {
        main.removeCallbacks(hide)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun load(): VolumeOverlaySettings {
        val d = VolumeOverlaySettings()
        return VolumeOverlaySettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            style = runCatching { VolumeStyle.valueOf(prefs.getString("style", null)!!) }.getOrDefault(d.style),
            edge = runCatching { VolumeEdge.valueOf(prefs.getString("edge", null)!!) }.getOrDefault(d.edge),
            size = prefs.getInt("size", d.size),
            timeoutMs = prefs.getLong("timeout", d.timeoutMs),
            showNumber = prefs.getBoolean("number", d.showNumber),
            touch = prefs.getBoolean("touch", d.touch),
        )
    }

    companion object {
        @Volatile
        private var instance: VolumeOverlay? = null

        fun get(context: Context): VolumeOverlay =
            instance ?: synchronized(this) { instance ?: VolumeOverlay(context).also { instance = it } }
    }
}

/** Rounded panel: speaker icon, track filled to the level in the accent colour, number. */
@SuppressLint("ViewConstructor")
private class VolumeView(
    context: Context,
    private val vertical: Boolean,
    private val unit: Float,
    private val showNumber: Boolean,
) : View(context) {
    var onLevel: ((Int) -> Unit)? = null
    var onMute: (() -> Unit)? = null
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
    ) {
        val accent =
            if (accentArgb != 0) {
                accentArgb
            } else if (dark) {
                0xFF8AB4F8.toInt()
            } else {
                0xFF1A73E8.toInt()
            }
        bg.color = if (dark) 0xF2202124.toInt() else 0xF2FFFFFF.toInt()
        track.color = if (dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
        fill.color = accent
        icon.color = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        text.color = icon.color
        bg.setShadowLayer(8 * unit, 0f, 2 * unit, Color.argb(90, 0, 0, 0))
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
        val tail = if (showNumber) 40 * unit else 16 * unit
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
            drawSpeaker(c, cx, h - 30 * unit)
            if (showNumber) drawNumber(c, cx, 26 * unit)
        } else {
            val cy = h / 2
            rect.set(a, cy - thick / 2, b, cy + thick / 2)
            c.drawRoundRect(rect, thick, thick, track)
            rect.set(a, cy - thick / 2, a + (b - a) * frac, cy + thick / 2)
            if (frac > 0) c.drawRoundRect(rect, thick, thick, fill)
            drawSpeaker(c, 30 * unit, cy)
            if (showNumber) drawNumber(c, w - 22 * unit, cy + 6 * unit)
        }
    }

    private fun drawNumber(
        c: Canvas,
        x: Float,
        y: Float,
    ) {
        text.textSize = 15 * unit
        c.drawText(if (muted) "–" else level.toString(), x, y, text)
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
        val onIcon = if (vertical) pos > length - 56 * unit else pos < 56 * unit
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (onIcon && e.actionMasked == MotionEvent.ACTION_DOWN) return true
                if (!onIcon) {
                    val (a, b) = trackRange()
                    val f = ((pos - a) / (b - a)).coerceIn(0f, 1f)
                    val v = ((if (vertical) 1 - f else f) * max).roundToInt()
                    if (v != level || muted) onLevel?.invoke(v)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (onIcon) onMute?.invoke()
            }
        }
        return true
    }
}
