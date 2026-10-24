package org.librehu.service.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Parcel
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextClock
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlin.math.ceil
import kotlin.math.max
import org.librehu.service.R

/**
 * A widget's views, its texts in the segment display font when it is chosen in LibreHU Launcher ([segment]). Android 9
 * widgets cannot use an app's font (TextView does not load fonts in the host's restricted context): each text is drawn
 * into a picture shown by the ImageView `<id>_seg` laid out like the TextView (added next to every text of the widget
 * layouts by tools/segment_widgets.py), the TextView hidden. Text, colour, size, visibility, background and touch given
 * to a TextView are recorded here and drawn when the views are sent; without [segment], plain RemoteViews.
 */
class SegmentViews(
    private val context: Context,
    private val layout: Int,
    private val segment: Boolean,
) : RemoteViews(context.packageName, layout) {
    private class Text {
        var text: CharSequence? = null
        var color: Int? = null
        var sizePx: Float? = null
        var visibility: Int? = null
        var chronometerBase: Long? = null
    }

    private val texts = HashMap<Int, Text>()
    private val pairs: Map<Int, Int> = if (segment) pairs(context, layout) else emptyMap()
    private var drawn = false

    private fun text(id: Int): Text? = if (id in pairs) texts.getOrPut(id, ::Text) else null

    override fun setTextViewText(
        viewId: Int,
        text: CharSequence?,
    ) {
        super.setTextViewText(viewId, text)
        text(viewId)?.text = text ?: ""
    }

    override fun setTextColor(
        viewId: Int,
        color: Int,
    ) {
        super.setTextColor(viewId, color)
        text(viewId)?.color = color
    }

    override fun setTextViewTextSize(
        viewId: Int,
        units: Int,
        size: Float,
    ) {
        super.setTextViewTextSize(viewId, units, size)
        text(viewId)?.sizePx = TypedValue.applyDimension(units, size, context.resources.displayMetrics)
    }

    override fun setViewVisibility(
        viewId: Int,
        visibility: Int,
    ) {
        val t = text(viewId)
        if (t != null) t.visibility = visibility else super.setViewVisibility(viewId, visibility)
    }

    override fun setChronometer(
        viewId: Int,
        base: Long,
        format: String?,
        started: Boolean,
    ) {
        super.setChronometer(viewId, base, format, started)
        text(viewId)?.chronometerBase = base
    }

    override fun setOnClickPendingIntent(
        viewId: Int,
        pendingIntent: android.app.PendingIntent?,
    ) {
        super.setOnClickPendingIntent(viewId, pendingIntent)
        pairs[viewId]?.let { super.setOnClickPendingIntent(it, pendingIntent) }
    }

    override fun setInt(
        viewId: Int,
        methodName: String?,
        value: Int,
    ) {
        super.setInt(viewId, methodName, value)
        if (methodName?.startsWith("setBackground") == true) pairs[viewId]?.let { super.setInt(it, methodName, value) }
    }

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        draw()
        super.writeToParcel(dest, flags)
    }

    /** The texts as pictures, once (the views can be sent several times). */
    private fun draw() {
        if (drawn || pairs.isEmpty()) return
        drawn = true
        val face = font(context)
        if (face == null) {
            // No font: the texts as they are.
            for ((id, t) in texts) t.visibility?.let { super.setViewVisibility(id, it) }
            return
        }
        val looks = looks(context, layout)
        for ((id, image) in pairs) {
            val look = looks[id] ?: continue
            val t = texts[id]
            val visibility = t?.visibility ?: look.visibility
            super.setViewVisibility(id, View.GONE)
            super.setViewVisibility(image, visibility)
            if (visibility == View.GONE) continue
            var shown: CharSequence = t?.text ?: look.text
            var color = t?.color ?: look.color
            when {
                t?.chronometerBase != null -> shown = elapsed(t.chronometerBase!!)
                look.clockFormat != null -> shown = DateFormat.format(look.clockFormat, System.currentTimeMillis())
                shown.isEmpty() && look.hint.isNotEmpty() -> {
                    shown = look.hint
                    color = look.hintColor
                }
            }
            super.setImageViewBitmap(image, picture(shown.toString(), color, t?.sizePx ?: look.sizePx, face, look.gravity))
            super.setContentDescription(image, shown)
        }
    }

    /** What a TextView of the layout shows by itself (inflated here once per layout). */
    private class Look(
        val text: CharSequence,
        val hint: CharSequence,
        val color: Int,
        val hintColor: Int,
        val sizePx: Float,
        val gravity: Int,
        val visibility: Int,
        val clockFormat: CharSequence?,
    )

    companion object {
        private val pairsCache = HashMap<Int, Map<Int, Int>>()
        private val looksCache = HashMap<Int, Map<Int, Look>>()
        private var typeface: Typeface? = null

        private fun font(context: Context): Typeface? =
            typeface ?: runCatching { ResourcesCompat.getFont(context, R.font.librehu_seg14) }.getOrNull()?.also { typeface = it }

        private fun inflate(
            context: Context,
            layout: Int,
        ): View = LayoutInflater.from(context).inflate(layout, FrameLayout(context), false)

        private fun textViews(
            v: View,
            out: MutableList<TextView>,
        ) {
            if (v is TextView && v.id != View.NO_ID) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) textViews(v.getChildAt(i), out)
        }

        /** TextView id → its picture's ImageView id (`<name>_seg`). */
        @Synchronized
        private fun pairs(
            context: Context,
            layout: Int,
        ): Map<Int, Int> =
            pairsCache.getOrPut(layout) {
                val list = mutableListOf<TextView>()
                runCatching { textViews(inflate(context, layout), list) }
                val res = context.resources
                list
                    .mapNotNull { tv ->
                        val name = runCatching { res.getResourceEntryName(tv.id) }.getOrNull() ?: return@mapNotNull null
                        @Suppress("DiscouragedApi")
                        val image = res.getIdentifier(name + "_seg", "id", context.packageName)
                        if (image != 0) tv.id to image else null
                    }.toMap()
            }

        @Synchronized
        private fun looks(
            context: Context,
            layout: Int,
        ): Map<Int, Look> =
            looksCache.getOrPut(layout) {
                val list = mutableListOf<TextView>()
                runCatching { textViews(inflate(context, layout), list) }
                list.associate { tv ->
                    val clock =
                        (tv as? TextClock)?.let {
                            if (DateFormat.is24HourFormat(context)) it.format24Hour ?: "H:mm" else it.format12Hour ?: "h:mm a"
                        }
                    tv.id to
                        Look(
                            tv.text ?: "",
                            tv.hint ?: "",
                            tv.currentTextColor,
                            tv.currentHintTextColor,
                            tv.textSize,
                            tv.gravity,
                            tv.visibility,
                            clock,
                        )
                }
            }

        /** Time since [base] (elapsedRealtime) as hours and minutes: redrawn with the widget, not every second. */
        private fun elapsed(base: Long): String {
            val m = ((SystemClock.elapsedRealtime() - base) / 60_000).coerceAtLeast(0)
            return "%d:%02d".format(m / 60, m % 60)
        }

        private fun picture(
            text: String,
            color: Int,
            sizePx: Float,
            face: Typeface,
            gravity: Int,
        ): Bitmap {
            val p =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = face
                    textSize = sizePx
                    this.color = color
                }
            val lines = text.split('\n')
            val fm = p.fontMetrics
            val lineHeight = fm.descent - fm.ascent
            val widths = lines.map { p.measureText(it) }
            val w = max(1, ceil(widths.max()).toInt())
            val h = max(1, ceil(lineHeight * lines.size).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val horizontal = Gravity.getAbsoluteGravity(gravity, View.LAYOUT_DIRECTION_LTR) and Gravity.HORIZONTAL_GRAVITY_MASK
            lines.forEachIndexed { i, line ->
                val x =
                    when (horizontal) {
                        Gravity.CENTER_HORIZONTAL -> (w - widths[i]) / 2
                        Gravity.RIGHT -> w - widths[i]
                        else -> 0f
                    }
                c.drawText(line, x, -fm.ascent + i * lineHeight, p)
            }
            return bmp
        }
    }
}
