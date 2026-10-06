package org.librehu.service.obd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import org.librehu.core.obd.ObdPid
import org.librehu.service.MainActivity
import org.librehu.service.R
import org.librehu.service.ui.ThemeFollower

/**
 * OBD dashboard widgets: speed and revs as a car cluster, plus the other values chosen for the widget in the OBD tab.
 * The picture is drawn at the size of each widget ([DashRenderer]) in the colours of LibreHU Launcher.
 */
abstract class ObdDashWidget(
    private val style: DashStyle,
) : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) update(context, manager, id, style)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = update(context, manager, appWidgetId, style)

    companion object {
        private val PROVIDERS =
            listOf(
                ObdDashAnalogWidget::class.java to DashStyle.ANALOG,
                ObdDashDigitalWidget::class.java to DashStyle.DIGITAL,
            )

        /** Redraws every dashboard widget (called with the 4-value widget on each OBD update). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for ((cls, style) in PROVIDERS) {
                for (id in manager.getAppWidgetIds(ComponentName(context, cls))) update(context, manager, id, style)
            }
        }

        private fun update(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            style: DashStyle,
        ) {
            val options = manager.getAppWidgetOptions(id)
            val density = context.resources.displayMetrics.density
            // Landscape head unit: max width, min height.
            val wDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: 360
            val hDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: 180
            val (dark, accent) = ThemeFollower.current(context)
            val bmp = DashRenderer.render(style, (wDp * density).toInt(), (hDp * density).toInt(), DashColors(dark, accent), data(context))
            val views =
                RemoteViews(context.packageName, R.layout.widget_obd_dash).apply {
                    setImageViewBitmap(R.id.dash_image, bmp)
                    setOnClickPendingIntent(
                        R.id.dash_image,
                        PendingIntent.getActivity(
                            context,
                            3,
                            Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_OBD)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            manager.updateAppWidget(id, views)
        }

        private fun data(context: Context): DashData {
            val obd = ObdManager.get(context)
            val st = obd.state.value
            val s = obd.settings.value
            val connected = st.connection == ObdConnection.CONNECTED

            fun item(key: String): DashItem {
                val v = if (connected) st.values[key] else null
                val text = v?.let { ObdManager.format(key, it) }
                val unit = text?.substringAfterLast(' ', "") ?: ObdPid.byName(key)?.unit ?: "V"
                return DashItem(key, ObdLabels.label(context, key), v, text?.substringBeforeLast(' ') ?: "—", unit)
            }
            val others =
                (s.widget + listOf(ObdPid.COOLANT_TEMP.name, ObdManager.BATTERY, ObdPid.FUEL_LEVEL.name, ObdPid.ENGINE_LOAD.name))
                    .filter { it != ObdPid.SPEED.name && it != ObdPid.RPM.name }
                    .distinct()
                    .take(4)
            val status =
                when (st.connection) {
                    ObdConnection.CONNECTED -> null
                    ObdConnection.OFF -> context.getString(if (s.enabled) R.string.obd_off else R.string.obd_disabled)
                    ObdConnection.CONNECTING, ObdConnection.INITIALISING -> context.getString(R.string.obd_connecting)
                    ObdConnection.ERROR -> context.getString(R.string.obd_error)
                }
            return DashData(item(ObdPid.SPEED.name), item(ObdPid.RPM.name), others.map(::item), status)
        }
    }
}

class ObdDashAnalogWidget : ObdDashWidget(DashStyle.ANALOG)

class ObdDashDigitalWidget : ObdDashWidget(DashStyle.DIGITAL)
