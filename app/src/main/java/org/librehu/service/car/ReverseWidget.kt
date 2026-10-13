package org.librehu.service.car

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.librehu.service.R
import org.librehu.service.ui.ThemeFollower

/** Home screen button: opens LibreHU's rear view full screen (without the reverse gear), or closes it. */
class ReverseWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        manager.updateAppWidget(appWidgetIds, views(context))
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == ACTION_TOGGLE) {
            ReverseView.get(context).toggle()
            return
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
            val (dark, accent) = ThemeFollower.current(context)
            val showing = ReverseView.get(context).showing.value
            val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
            return RemoteViews(context.packageName, R.layout.widget_reverse).apply {
                setInt(
                    R.id.reverse_root,
                    "setBackgroundResource",
                    if (dark) R.drawable.widget_background else R.drawable.widget_background_light,
                )
                setInt(R.id.reverse_icon, "setColorFilter", if (showing && accent != 0) accent else text)
                setTextViewText(
                    R.id.reverse_label,
                    context.getString(if (showing) R.string.reverse_widget_close else R.string.reverse_widget_title),
                )
                setTextColor(R.id.reverse_label, text)
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
