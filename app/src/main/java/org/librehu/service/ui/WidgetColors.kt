package org.librehu.service.ui

import android.widget.RemoteViews
import org.librehu.service.R

/**
 * Colours of this app's widgets: the usual ones, or LibreHU Launcher's retro palette when its style is retro and the
 * widget has the same light / dark ([RetroLook], read by [ThemeFollower.current] just before). Retro backgrounds are
 * plain colours: Android 9 widgets cannot tint a drawable, the launcher's rounded slot clips them.
 */
class WidgetColors(
    val dark: Boolean,
    launcherAccent: Int,
) {
    private val retro = RetroLook.widget(dark)
    val text = retro?.text ?: if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
    val dim = retro?.textDim ?: if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    val accent = retro?.accent ?: launcherAccent.takeIf { it != 0 } ?: if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()

    /** The launcher's accent only (0 without it), retro included. */
    val launcherAccent = retro?.accent ?: launcherAccent

    fun background(
        views: RemoteViews,
        id: Int,
    ) = if (retro != null) {
        views.setInt(id, "setBackgroundColor", retro.surface)
    } else {
        views.setInt(id, "setBackgroundResource", if (dark) R.drawable.widget_background else R.drawable.widget_background_light)
    }

    fun cell(
        views: RemoteViews,
        id: Int,
    ) = if (retro != null) {
        views.setInt(id, "setBackgroundColor", retro.surfaceHigh)
    } else {
        views.setInt(id, "setBackgroundResource", if (dark) R.drawable.widget_cell else R.drawable.widget_cell_light)
    }

    /** Surface and raised colours of the retro palette for widgets drawn as pictures (null: the usual ones). */
    val retroSurfaces: Pair<Int, Int>? = retro?.let { it.surface to it.surfaceHigh }
}
