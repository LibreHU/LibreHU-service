package org.librehu.service.obd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.librehu.service.MainActivity
import org.librehu.service.R
import org.librehu.service.ui.ThemeFollower

/** Four OBD-II values chosen in the OBD tab, for LibreHU Launcher's widget slots or any home screen. */
class ObdWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        manager.updateAppWidget(appWidgetIds, views(context))
    }

    companion object {
        private val VALUES = intArrayOf(R.id.obd_value0, R.id.obd_value1, R.id.obd_value2, R.id.obd_value3)
        private val LABELS = intArrayOf(R.id.obd_label0, R.id.obd_label1, R.id.obd_label2, R.id.obd_label3)
        private val CELLS = intArrayOf(R.id.obd_cell0, R.id.obd_cell1, R.id.obd_cell2, R.id.obd_cell3)

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ObdWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val obd = ObdManager.get(context)
            val st = obd.state.value
            val s = obd.settings.value
            val dark = ThemeFollower.current(context).first
            val text = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
            val dim = if (dark) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
            val red = 0xFFF44336.toInt()
            return RemoteViews(context.packageName, R.layout.widget_obd).apply {
                setInt(
                    R.id.obd_root,
                    "setBackgroundResource",
                    if (dark) R.drawable.widget_background else R.drawable.widget_background_light,
                )
                setTextColor(R.id.obd_title, text)
                val status =
                    when (st.connection) {
                        ObdConnection.CONNECTED -> ""
                        ObdConnection.OFF -> context.getString(if (s.enabled) R.string.obd_off else R.string.obd_disabled)
                        ObdConnection.CONNECTING, ObdConnection.INITIALISING -> context.getString(R.string.obd_connecting)
                        ObdConnection.ERROR -> context.getString(R.string.obd_error)
                    }
                setTextViewText(R.id.obd_status, status)
                setTextColor(R.id.obd_status, if (st.connection == ObdConnection.ERROR) red else dim)
                for (i in 0 until ObdManager.WIDGET_SLOTS) {
                    val key = s.widget.getOrNull(i)
                    setInt(CELLS[i], "setBackgroundResource", if (dark) R.drawable.widget_cell else R.drawable.widget_cell_light)
                    setTextViewText(LABELS[i], key?.let { ObdLabels.label(context, it) } ?: "")
                    setTextViewText(VALUES[i], key?.let { k -> st.values[k]?.let { ObdManager.format(k, it) } } ?: "—")
                    setTextColor(VALUES[i], text)
                    setTextColor(LABELS[i], dim)
                }
                setOnClickPendingIntent(
                    R.id.obd_root,
                    PendingIntent.getActivity(
                        context,
                        2,
                        Intent(context, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_OBD)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
    }
}

/** Short names of the values, shared by the widget and the OBD tab. */
object ObdLabels {
    fun label(
        context: Context,
        key: String,
    ): String {
        val res =
            when (key) {
                ObdManager.BATTERY -> R.string.obd_v_battery
                "ENGINE_LOAD" -> R.string.obd_v_load
                "COOLANT_TEMP" -> R.string.obd_v_coolant
                "INTAKE_PRESSURE" -> R.string.obd_v_map
                "RPM" -> R.string.obd_v_rpm
                "SPEED" -> R.string.obd_v_speed
                "TIMING_ADVANCE" -> R.string.obd_v_timing
                "INTAKE_TEMP" -> R.string.obd_v_intake
                "MAF" -> R.string.obd_v_maf
                "THROTTLE" -> R.string.obd_v_throttle
                "RUN_TIME" -> R.string.obd_v_runtime
                "DISTANCE_MIL" -> R.string.obd_v_mil_distance
                "FUEL_LEVEL" -> R.string.obd_v_fuel
                "DISTANCE_SINCE_CLEAR" -> R.string.obd_v_clear_distance
                "BAROMETRIC" -> R.string.obd_v_baro
                "MODULE_VOLTAGE" -> R.string.obd_v_module_voltage
                "AMBIENT_TEMP" -> R.string.obd_v_ambient
                "OIL_TEMP" -> R.string.obd_v_oil
                "FUEL_RATE" -> R.string.obd_v_fuel_rate
                else -> 0
            }
        return if (res == 0) key else context.getString(res)
    }
}
