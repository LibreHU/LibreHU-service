package org.librehu.service.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
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
import org.librehu.service.R
import org.librehu.service.ui.ThemeFollower
import kotlin.math.roundToInt

/** A volume the sound menu can change: the audio chip's (all the sound of the head unit) or one of Android's streams. */
enum class SoundChannel(
    val label: Int,
    /** AudioManager stream, -1 for the audio chip. */
    val stream: Int,
) {
    UNIT(R.string.sound_unit, -1),
    MEDIA(R.string.sound_media, AudioManager.STREAM_MUSIC),
    CALL(R.string.sound_call, AudioManager.STREAM_VOICE_CALL),
    RING(R.string.sound_ring, AudioManager.STREAM_RING),
    NOTIFICATION(R.string.sound_notification, AudioManager.STREAM_NOTIFICATION),
    ALARM(R.string.sound_alarm, AudioManager.STREAM_ALARM),
    SYSTEM(R.string.sound_system, AudioManager.STREAM_SYSTEM),
}

data class SoundOsdSettings(
    /** Off by default: the volume keys change the audio chip's volume, as before. */
    val enabled: Boolean = false,
    val channels: Set<SoundChannel> = SoundChannel.entries.toSet(),
    /** Each time the menu opens it starts on the head unit's volume (else on the last one chosen). */
    val backToUnit: Boolean = true,
    val timeoutMs: Long = 4_000,
) {
    companion object {
        val TIMEOUTS = listOf(2_500L, 4_000L, 6_000L, 10_000L)
    }
}

/**
 * Advanced sound menu (option of LibreHU-service, off by default): the volume keys open a panel listing the volumes
 * (head unit, Android media, calls, ring, notifications, alarm, system); while it shows, ▶▶ / ◀◀ (the wheel, the knob)
 * choose the volume and the volume keys change it; ▶‖ or a few seconds without a key close it. Touch: a row chooses,
 * a drag on its bar sets the level. Over every app (TYPE_APPLICATION_OVERLAY), colours of LibreHU Launcher.
 */
class SoundOsd private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("sound_osd", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<SoundOsdSettings> = _settings.asStateFlow()

    /** Audio chip: one step (+1 / -1, 0 = mute) and the current level / maximum / muted; set by the service. */
    @Volatile
    var unitStep: ((Int) -> Boolean)? = null

    @Volatile
    var unitLevel: (() -> Triple<Int, Int, Boolean>)? = null

    /** Audio chip: a level set by a drag on the panel. */
    @Volatile
    var unitSet: ((Int) -> Unit)? = null

    private var selected = SoundChannel.UNIT
    private var view: OsdView? = null
    private var dark = true
    private var accent = 0
    private var themeStarted = false
    private val theme =
        ThemeFollower(app) { d, a ->
            main.post {
                dark = d
                accent = a
                view?.setColors(d, a)
            }
        }
    private val hide = Runnable { remove() }

    val visible: Boolean get() = view != null

    fun update(transform: (SoundOsdSettings) -> SoundOsdSettings) {
        val s = transform(_settings.value)
        prefs
            .edit()
            .putBoolean("enabled", s.enabled)
            .putStringSet("channels", s.channels.map { it.name }.toSet())
            .putBoolean("back_to_unit", s.backToUnit)
            .putLong("timeout", s.timeoutMs)
            .apply()
        _settings.value = s
        main.post { remove() }
    }

    private fun channels() = SoundChannel.entries.filter { it in _settings.value.channels || it == SoundChannel.UNIT }

    /**
     * A volume key ([step] +1 / -1, 0 = mute): with the menu on, it changes the volume chosen (and shows the menu).
     * False when the menu is off (the key does its usual action).
     */
    fun volumeKey(step: Int): Boolean {
        val s = _settings.value
        if (!s.enabled || !Settings.canDrawOverlays(app)) return false
        main.post {
            if (view == null && s.backToUnit) selected = SoundChannel.UNIT
            if (selected !in channels()) selected = SoundChannel.UNIT
            change(selected, step)
            show()
        }
        return true
    }

    /** Opens the menu without changing anything (settings screen). */
    fun open() {
        if (!Settings.canDrawOverlays(app)) return
        main.post {
            if (view == null && _settings.value.backToUnit) selected = SoundChannel.UNIT
            show()
        }
    }

    /** ▶▶ / ◀◀ while the menu shows: the next / previous volume. False when it does not show. */
    fun navKey(delta: Int): Boolean {
        if (view == null) return false
        main.post {
            val list = channels()
            val i = list.indexOf(selected).coerceAtLeast(0)
            selected = list[Math.floorMod(i + delta, list.size)]
            show()
        }
        return true
    }

    /** ▶‖ while the menu shows: closes it. False when it does not show. */
    fun closeKey(): Boolean {
        if (view == null) return false
        main.post { remove() }
        return true
    }

    private fun change(
        c: SoundChannel,
        step: Int,
    ) {
        if (c == SoundChannel.UNIT) {
            unitStep?.invoke(step)
            return
        }
        val dir =
            when {
                step > 0 -> AudioManager.ADJUST_RAISE
                step < 0 -> AudioManager.ADJUST_LOWER
                else -> AudioManager.ADJUST_TOGGLE_MUTE
            }
        runCatching { audio.adjustStreamVolume(c.stream, dir, 0) }
    }

    private fun level(c: SoundChannel): Triple<Int, Int, Boolean> =
        if (c == SoundChannel.UNIT) {
            unitLevel?.invoke() ?: Triple(0, 1, false)
        } else {
            Triple(audio.getStreamVolume(c.stream), audio.getStreamMaxVolume(c.stream).coerceAtLeast(1), audio.isStreamMute(c.stream))
        }

    private fun set(
        c: SoundChannel,
        value: Int,
    ) {
        if (c == SoundChannel.UNIT) unitSet?.invoke(value) else runCatching { audio.setStreamVolume(c.stream, value, 0) }
    }

    private fun show() {
        if (!themeStarted) {
            themeStarted = true
            theme.start()
        }
        val list = channels()
        val v = view ?: attach(list.size) ?: return
        v.rows = list.map { c -> Row(app.getString(c.label), level(c), c == selected) }
        v.invalidate()
        main.removeCallbacks(hide)
        main.postDelayed(hide, _settings.value.timeoutMs)
        // The plain volume panel would show over it
        LevelOverlay.volume(app).quiet(_settings.value.timeoutMs)
    }

    private fun attach(rows: Int): OsdView? {
        val dm = app.resources.displayMetrics
        val d = dm.density
        val v = OsdView(app, d)
        v.setColors(dark, accent)
        v.onRow = { i ->
            channels().getOrNull(i)?.let { selected = it }
            show()
        }
        v.onLevel = { i, f ->
            channels().getOrNull(i)?.let { c ->
                selected = c
                set(c, (f * level(c).second).roundToInt())
            }
            show()
        }
        val lp =
            WindowManager
                .LayoutParams(
                    (380 * d).roundToInt(),
                    ((24 + rows * 52) * d).roundToInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    x = (24 * d).roundToInt()
                    windowAnimations = android.R.style.Animation_Toast
                }
        return try {
            wm.addView(v, lp)
            view = v
            v
        } catch (e: Exception) {
            android.util.Log.w("LibreHU", "sound menu: ${e.message}")
            null
        }
    }

    private fun remove() {
        main.removeCallbacks(hide)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun load(): SoundOsdSettings {
        val d = SoundOsdSettings()
        return SoundOsdSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            channels =
                prefs
                    .getStringSet("channels", null)
                    ?.mapNotNull { n -> SoundChannel.entries.firstOrNull { it.name == n } }
                    ?.toSet() ?: d.channels,
            backToUnit = prefs.getBoolean("back_to_unit", d.backToUnit),
            timeoutMs = prefs.getLong("timeout", d.timeoutMs),
        )
    }

    private class Row(
        val label: String,
        val level: Triple<Int, Int, Boolean>,
        val selected: Boolean,
    )

    /** Rows of the menu: name, bar and value, the chosen one in the accent colour. */
    @SuppressLint("ViewConstructor")
    private class OsdView(
        context: Context,
        private val d: Float,
    ) : View(context) {
        var rows: List<Row> = emptyList()
        var onRow: ((Int) -> Unit)? = null
        var onLevel: ((Int, Float) -> Unit)? = null
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sel = Paint(Paint.ANTI_ALIAS_FLAG)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17 * d }
        private val value =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 15 * d
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.DEFAULT_BOLD
            }
        private var accentColor = 0

        fun setColors(
            dark: Boolean,
            accentArgb: Int,
        ) {
            accentColor = if (accentArgb != 0) accentArgb else if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
            bg.color = if (dark) 0xF2202124.toInt() else 0xF2FFFFFF.toInt()
            sel.color = (accentColor and 0x00FFFFFF) or 0x33000000
            track.color = if (dark) 0xFF3C4043.toInt() else 0xFFDADCE0.toInt()
            fill.color = accentColor
            text.color = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
            value.color = text.color
            bg.setShadowLayer(8 * d, 0f, 2 * d, Color.argb(90, 0, 0, 0))
            setLayerType(LAYER_TYPE_SOFTWARE, bg)
            invalidate()
        }

        private fun rowTop(i: Int) = 12 * d + i * 52 * d

        private fun barLeft() = width * 0.42f

        private fun barRight() = width - 64 * d

        override fun onDraw(canvas: Canvas) {
            val r = 20 * d
            canvas.drawRoundRect(RectF(4 * d, 4 * d, width - 4 * d, height - 4 * d), r, r, bg)
            for ((i, row) in rows.withIndex()) {
                val top = rowTop(i)
                val mid = top + 26 * d
                if (row.selected) canvas.drawRoundRect(RectF(10 * d, top + 2 * d, width - 10 * d, top + 50 * d), 14 * d, 14 * d, sel)
                text.color = if (row.selected) accentColor else value.color
                text.typeface = if (row.selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                canvas.drawText(row.label, 22 * d, mid + 6 * d, text)
                val (lv, max, muted) = row.level
                val l = barLeft()
                val rr = barRight()
                val h = 6 * d
                canvas.drawRoundRect(RectF(l, mid - h / 2, rr, mid + h / 2), h, h, track)
                val f = if (muted) 0f else (lv.toFloat() / max).coerceIn(0f, 1f)
                if (f > 0f) canvas.drawRoundRect(RectF(l, mid - h / 2, l + (rr - l) * f, mid + h / 2), h, h, fill)
                canvas.drawText(if (muted) "—" else lv.toString(), width - 22 * d, mid + 5 * d, value)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val i = ((e.y - 12 * d) / (52 * d)).toInt()
            if (i !in rows.indices) return true
            val onBar = e.x >= barLeft() - 12 * d
            when (e.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    if (onBar) onLevel?.invoke(i, ((e.x - barLeft()) / (barRight() - barLeft())).coerceIn(0f, 1f))
                MotionEvent.ACTION_UP -> if (!onBar) onRow?.invoke(i)
            }
            return true
        }
    }

    companion object {
        @Volatile
        private var instance: SoundOsd? = null

        fun get(context: Context): SoundOsd = instance ?: synchronized(this) { instance ?: SoundOsd(context).also { instance = it } }
    }
}
