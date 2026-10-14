package org.librehu.service.phone

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
import java.util.Locale

/**
 * Phone widget: the connected iPhone (LibreHU CarPlay app: battery, charge, temperature, cycles) or else the
 * Bluetooth phone (hands-free indicators: battery, signal, operator); a touch opens the Phone page.
 */
class PhoneWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        PhoneInfo.start(context)
        manager.updateAppWidget(appWidgetIds, views(context))
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PhoneWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        fun views(context: Context): RemoteViews {
            val (dark, launcherAccent) = ThemeFollower.current(context)
            val accent =
                if (launcherAccent != 0) {
                    launcherAccent
                } else if (dark) {
                    0xFF8AB4F8.toInt()
                } else {
                    0xFF1A73E8.toInt()
                }
            val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
            val dim = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
            val iphone = PhoneInfo.iphone.value?.takeIf { it.connected }
            val bt = PhoneInfo.bluetooth.value
            val (title, status, active) =
                when {
                    iphone != null -> Triple(iphone.name ?: "iPhone", iphoneLine(context, iphone), true)
                    bt.deviceAddress.isNotEmpty() -> Triple(bt.deviceName.ifEmpty { bt.deviceAddress }, btLine(context, bt), true)
                    else -> Triple(context.getString(R.string.phone_widget_title), context.getString(R.string.phone_none), false)
                }
            return RemoteViews(context.packageName, R.layout.widget_projection).apply {
                setInt(
                    R.id.proj_root,
                    "setBackgroundResource",
                    if (dark) R.drawable.widget_background else R.drawable.widget_background_light,
                )
                setImageViewResource(R.id.proj_icon, R.drawable.ic_widget_phone)
                setInt(R.id.proj_icon, "setColorFilter", accent)
                setTextViewText(R.id.proj_title, title)
                setTextColor(R.id.proj_title, text)
                setTextViewText(R.id.proj_status, status)
                setTextColor(R.id.proj_status, if (active) accent else dim)
                setInt(R.id.proj_open, "setBackgroundResource", if (dark) R.drawable.widget_cell else R.drawable.widget_cell_light)
                setTextColor(R.id.proj_open, text)
                setTextViewText(R.id.proj_open, context.getString(R.string.phone_details))
                setViewVisibility(R.id.proj_disconnect, View.GONE)
                val open =
                    PendingIntent.getActivity(
                        context,
                        "phone-widget".hashCode(),
                        Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_TAB, "PHONE")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                setOnClickPendingIntent(R.id.proj_open, open)
                setOnClickPendingIntent(R.id.proj_root, open)
            }
        }

        private fun iphoneLine(
            context: Context,
            d: IphoneDetails,
        ): String =
            listOfNotNull(
                d.level?.let { "$it %" + if (d.charging == true) " ⚡" else "" },
                d.temperatureC?.let { String.format(Locale.ROOT, "%.1f °C", it) },
                d.cycles?.let { context.getString(R.string.phone_cycles_short, it) },
                d.healthPercent?.let { context.getString(R.string.phone_health_short, it) },
            ).joinToString(" · ").ifEmpty { context.getString(R.string.phone_iphone_connected) }

        private fun btLine(
            context: Context,
            s: org.librehu.service.bt.BtStatus,
        ): String =
            listOfNotNull(
                s.battery.takeIf { it >= 0 }?.let { context.getString(R.string.phone_battery_bt, it * 20) },
                s.signal.takeIf { it >= 0 }?.let { context.getString(R.string.phone_signal_short, it) },
                s.operator.takeIf { it.isNotEmpty() },
            ).joinToString(" · ").ifEmpty { context.getString(R.string.phone_bt_connected) }
    }
}
