package org.librehu.service.car

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.librehu.service.R
import org.librehu.service.ui.RetroLook
import org.librehu.service.ui.SegmentViews
import org.librehu.service.ui.ThemeFollower
import org.librehu.service.ui.WidgetColors

/**
 * Home screen widget: live picture of the rear camera ([ReverseWidgetLive]) or a camera button; a touch opens
 * LibreHU's rear view full screen (without the reverse gear), or closes it.
 */
class ReverseWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        manager.updateAppWidget(appWidgetIds, views(context))
        ReverseWidgetLive.get(context).evaluate()
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        ReverseWidgetLive.get(context).evaluate()
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                ReverseView.get(context).toggle()
                return
            }

            // LibreHU Launcher's home screen, where the widget is, shown or left.
            ReverseWidgetLive.ACTION_HOME_VISIBLE -> {
                ReverseWidgetLive.get(context).setHomeVisible(intent.getBooleanExtra(ReverseWidgetLive.EXTRA_VISIBLE, false))
                return
            }
        }
        super.onReceive(context, intent)
    }

    companion object {
        private const val ACTION_TOGGLE = "org.librehu.service.REVERSE_VIEW_TOGGLE"

        /** Called when the view opens or closes. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ReverseWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val c = ThemeFollower.current(context).let { (dark, accent) -> WidgetColors(dark, accent) }
            val accent = c.launcherAccent
            val showing = ReverseView.get(context).showing.value
            val text = c.text
            val live = ReverseWidgetLive.get(context)
            val frame =
                live.lastFrame?.takeIf {
                    ReverseView
                        .get(context)
                        .settings.value.widgetLive != WidgetLive.OFF
                }
            live.onFullUpdate()
            return SegmentViews(context, R.layout.widget_reverse, RetroLook.segment).apply {
                c.background(this, R.id.reverse_root)
                setInt(R.id.reverse_icon, "setColorFilter", if (showing && accent != 0) accent else text)
                setTextViewText(
                    R.id.reverse_label,
                    context.getString(if (showing) R.string.reverse_widget_close else R.string.reverse_widget_title),
                )
                setTextColor(R.id.reverse_label, text)
                if (frame != null) {
                    setImageViewBitmap(R.id.reverse_live, frame)
                    live.liveLook(this)
                }
                setOnClickPendingIntent(
                    R.id.reverse_root,
                    PendingIntent.getBroadcast(
                        context,
                        5,
                        Intent(context, ReverseWidget::class.java).setAction(ACTION_TOGGLE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
    }
}
