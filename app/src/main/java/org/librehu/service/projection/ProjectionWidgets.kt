package org.librehu.service.projection

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.librehu.service.MainActivity
import org.librehu.service.R
import org.librehu.service.ui.ThemeFollower

/** Android Auto widget: Open Headunit's session (from its SESSION_STATE reports), open / back to it, disconnect. */
class AndroidAutoWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        manager.updateAppWidget(appWidgetIds, ProjectionWidgets.androidAuto(context))
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ProjectionWidgets.ACTION_OPEN -> if (!AndroidAuto.open(context)) ProjectionWidgets.openPage(context, "ANDROID_AUTO")
            ProjectionWidgets.ACTION_DISCONNECT -> AndroidAuto.send(context, AndroidAuto.DISCONNECT)
            else -> super.onReceive(context, intent)
        }
    }
}

/** CarPlay widget: xcertplay LibreHU's session (its SESSION_STATE reports), open it. */
class CarPlayWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        manager.updateAppWidget(appWidgetIds, ProjectionWidgets.carPlay(context))
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ProjectionWidgets.ACTION_OPEN) {
            if (!CarPlay.open(context)) ProjectionWidgets.openPage(context, "CARPLAY")
        } else {
            super.onReceive(context, intent)
        }
    }
}

/**
 * The two projection widgets (LibreHU Launcher's widget slots or any home screen): icon in the launcher's accent, the
 * app's name, its session, Open (back to the projection during a session) and, for Android Auto, Disconnect. Updated
 * by [refresh] on every session report.
 */
object ProjectionWidgets {
    const val ACTION_OPEN = "org.librehu.service.widget.PROJECTION_OPEN"
    const val ACTION_DISCONNECT = "org.librehu.service.widget.PROJECTION_DISCONNECT"

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        manager.getAppWidgetIds(ComponentName(context, AndroidAutoWidget::class.java)).takeIf { it.isNotEmpty() }?.let {
            manager.updateAppWidget(it, androidAuto(context))
        }
        manager.getAppWidgetIds(ComponentName(context, CarPlayWidget::class.java)).takeIf { it.isNotEmpty() }?.let {
            manager.updateAppWidget(it, carPlay(context))
        }
    }

    fun androidAuto(context: Context): RemoteViews {
        val s = AndroidAuto.session.value
        val installed = AndroidAuto.installed(context) != null
        val status =
            when {
                !installed -> R.string.aa_app_none
                s.state == "connecting" -> R.string.aa_state_connecting
                s.state == "connected" -> R.string.aa_state_connected
                s.state == "projecting" -> R.string.aa_state_projecting
                s.state == "failed" -> R.string.aa_state_failed
                else -> R.string.proj_widget_waiting
            }
        return views(
            context,
            AndroidAutoWidget::class.java,
            R.drawable.ic_widget_android_auto,
            context.getString(R.string.android_auto_widget_title),
            context.getString(status),
            active = s.active,
            disconnect = s.active,
        )
    }

    fun carPlay(context: Context): RemoteViews {
        val s = CarPlay.session.value
        val installed = CarPlay.installed(context) != null
        val status =
            when {
                !installed -> context.getString(R.string.cp_app_none)
                s.state == "active" -> context.getString(R.string.cp_state_active)
                s.state == "connecting" -> context.getString(R.string.aa_state_connecting)
                s.state == "failed" -> context.getString(R.string.aa_state_failed) + if (s.detail.isNotEmpty()) " · ${s.detail}" else ""
                else -> context.getString(R.string.proj_widget_waiting)
            }
        return views(
            context,
            CarPlayWidget::class.java,
            R.drawable.ic_widget_carplay,
            context.getString(R.string.cp_title),
            status,
            active = s.state == "active" || s.state == "connecting",
            disconnect = false,
        )
    }

    private fun views(
        context: Context,
        provider: Class<*>,
        icon: Int,
        title: String,
        status: String,
        active: Boolean,
        disconnect: Boolean,
    ): RemoteViews {
        val (dark, launcherAccent) = ThemeFollower.current(context)
        val accent = if (launcherAccent != 0) launcherAccent else if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        val dim = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
        return RemoteViews(context.packageName, R.layout.widget_projection).apply {
            setInt(R.id.proj_root, "setBackgroundResource", if (dark) R.drawable.widget_background else R.drawable.widget_background_light)
            setImageViewResource(R.id.proj_icon, icon)
            setInt(R.id.proj_icon, "setColorFilter", accent)
            setTextViewText(R.id.proj_title, title)
            setTextColor(R.id.proj_title, text)
            setTextViewText(R.id.proj_status, status)
            setTextColor(R.id.proj_status, if (active) accent else dim)
            for (id in intArrayOf(R.id.proj_open, R.id.proj_disconnect)) {
                setInt(id, "setBackgroundResource", if (dark) R.drawable.widget_cell else R.drawable.widget_cell_light)
                setTextColor(id, text)
            }
            setTextViewText(R.id.proj_open, context.getString(if (active) R.string.proj_widget_show else R.string.aa_open))
            setOnClickPendingIntent(R.id.proj_open, broadcast(context, provider, ACTION_OPEN))
            setOnClickPendingIntent(R.id.proj_root, broadcast(context, provider, ACTION_OPEN))
            setViewVisibility(R.id.proj_disconnect, if (disconnect) View.VISIBLE else View.GONE)
            if (disconnect) {
                setTextViewText(R.id.proj_disconnect, context.getString(R.string.aa_disconnect))
                setOnClickPendingIntent(R.id.proj_disconnect, broadcast(context, provider, ACTION_DISCONNECT))
            }
        }
    }

    private fun broadcast(
        context: Context,
        provider: Class<*>,
        action: String,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (provider.name + action).hashCode(),
            Intent(context, provider).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The app is not installed: its page in LibreHU-service (how to get it). */
    fun openPage(
        context: Context,
        tab: String,
    ) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, tab)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
