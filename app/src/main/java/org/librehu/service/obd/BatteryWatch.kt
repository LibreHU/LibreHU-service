package org.librehu.service.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.obd.BatteryAlarm
import org.librehu.core.obd.BatteryAlarms
import org.librehu.core.obd.BatteryLimits
import org.librehu.core.obd.BatteryReading
import org.librehu.core.obd.CarBattery
import org.librehu.service.R
import java.util.Locale

/** Battery settings: alarms and their thresholds, sound, and a correction of the adapter's voltage. */
data class BatterySettings(
    val limits: BatteryLimits = BatteryLimits(),
    val sound: Boolean = true,
    /** Added to the adapter's reading (volts, ±0.5): an ELM327 can read 0.1–0.3 V off. */
    val offset: Double = 0.0,
)

/**
 * The car battery from the ELM327's voltage ([CarBattery]): charge engine off, charging or not engine running, and
 * the alarms ([BatteryAlarms]) as a notification (with its sound when wanted). Read by the launcher (lock screen,
 * standby clock, OBD screen) through the AIDL `getObdBattery` and the `org.librehu.action.OBD` broadcast.
 */
class BatteryWatch private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val alarms = BatteryAlarms()

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<BatterySettings> = _settings.asStateFlow()

    private val _reading = MutableStateFlow<BatteryReading?>(null)

    /** Last reading, null while the adapter is not connected. */
    val reading: StateFlow<BatteryReading?> = _reading.asStateFlow()

    fun update(transform: (BatterySettings) -> BatterySettings) {
        val s = transform(_settings.value)
        val l = s.limits
        prefs
            .edit()
            .putFloat("bat_low", l.low.toFloat())
            .putFloat("bat_charge", l.chargeMin.toFloat())
            .putFloat("bat_over", l.overMax.toFloat())
            .putBoolean("bat_alarm_low", l.alarmLow)
            .putBoolean("bat_alarm_charge", l.alarmCharge)
            .putBoolean("bat_alarm_over", l.alarmOver)
            .putBoolean("bat_sound", s.sound)
            .putFloat("bat_offset", s.offset.toFloat())
            .apply()
        _settings.value = s
    }

    /** A new voltage from the adapter, and the engine speed when it is read. */
    fun onVoltage(
        volts: Double,
        rpm: Double?,
    ) {
        val s = _settings.value
        val r = CarBattery.read(volts + s.offset, rpm, s.limits)
        _reading.value = r
        for (a in alarms.update(r, s.limits, System.currentTimeMillis())) notify(a, r, s.sound)
    }

    fun onDisconnected() {
        _reading.value = null
        alarms.reset()
    }

    /** voltage (double), percent (int, −1 = unknown: engine running), status ([org.librehu.core.obd.BatteryStatus] name). */
    fun bundle(): Bundle =
        Bundle().apply {
            val r = _reading.value ?: return@apply
            putDouble("voltage", r.voltage)
            putInt("percent", r.percent ?: -1)
            putString("status", r.status.name)
        }

    private fun notify(
        alarm: BatteryAlarm,
        r: BatteryReading,
        sound: Boolean,
    ) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val channel = if (sound) CHANNEL_SOUND else CHANNEL_SILENT
        nm.createNotificationChannel(
            NotificationChannel(
                channel,
                app.getString(if (sound) R.string.bat_channel else R.string.bat_channel_silent),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                if (sound) {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
                    )
                } else {
                    setSound(null, null)
                }
            },
        )
        val volts = String.format(Locale.ROOT, "%.1f V", r.voltage)
        val (title, text) =
            when (alarm) {
                BatteryAlarm.LOW -> R.string.bat_alarm_low_title to app.getString(R.string.bat_alarm_low_text, volts, r.percent ?: 0)
                BatteryAlarm.NOT_CHARGING -> R.string.bat_alarm_charge_title to app.getString(R.string.bat_alarm_charge_text, volts)
                BatteryAlarm.OVERVOLTAGE -> R.string.bat_alarm_over_title to app.getString(R.string.bat_alarm_over_text, volts)
            }
        Log.i(TAG, "battery alarm $alarm at $volts")
        nm.notify(
            NOTIFICATION_BASE + alarm.ordinal,
            Notification
                .Builder(app, channel)
                .setSmallIcon(R.drawable.ic_stat_headunit)
                .setContentTitle(app.getString(title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun load(): BatterySettings {
        val d = BatteryLimits()
        return BatterySettings(
            limits =
                BatteryLimits(
                    low = prefs.getFloat("bat_low", d.low.toFloat()).toDouble(),
                    chargeMin = prefs.getFloat("bat_charge", d.chargeMin.toFloat()).toDouble(),
                    overMax = prefs.getFloat("bat_over", d.overMax.toFloat()).toDouble(),
                    alarmLow = prefs.getBoolean("bat_alarm_low", d.alarmLow),
                    alarmCharge = prefs.getBoolean("bat_alarm_charge", d.alarmCharge),
                    alarmOver = prefs.getBoolean("bat_alarm_over", d.alarmOver),
                ),
            sound = prefs.getBoolean("bat_sound", true),
            offset = prefs.getFloat("bat_offset", 0f).toDouble(),
        )
    }

    companion object {
        private const val TAG = "LibreHU-OBD"
        private const val CHANNEL_SOUND = "obd_battery"
        private const val CHANNEL_SILENT = "obd_battery_silent"
        private const val NOTIFICATION_BASE = 7300

        @Volatile
        private var instance: BatteryWatch? = null

        fun get(context: Context): BatteryWatch = instance ?: synchronized(this) { instance ?: BatteryWatch(context).also { instance = it } }
    }
}
