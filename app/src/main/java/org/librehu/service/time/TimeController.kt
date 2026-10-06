package org.librehu.service.time

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class TimeSettings(
    /** Android's clock from the MCU's RTC at each start. */
    val fromMcu: Boolean = true,
    /** Android's clock to the MCU every minute. */
    val toMcu: Boolean = true,
    /** Android's clock from GPS time, every [gpsIntervalMin] minutes (and at start). */
    val fromGps: Boolean = false,
    val gpsIntervalMin: Int = 60,
)

data class Satellite(
    val svid: Int,
    /** GnssStatus.CONSTELLATION_* (1 GPS, 3 GLONASS, 5 BeiDou, 6 Galileo…). */
    val constellation: Int,
    val cn0: Float,
    val usedInFix: Boolean,
)

data class GpsState(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val satellites: List<Satellite> = emptyList(),
    val fix: Location? = null,
    val firstFixMs: Int = -1,
    val lastSync: Long = 0,
    val lastSyncDeltaMs: Long = 0,
    val message: String = "",
)

/**
 * GPS test and clock sources. The MT6631 combo chip's GNSS is a normal Android location provider (`gnss_service`,
 * `ro.vendor.mtk_gps_support = 1`). GPS time is UTC and exact to well under a second; setting Android's clock needs
 * SET_TIME (privileged install). The MCU side goes through [mcu] while the service drives it.
 */
@SuppressLint("MissingPermission")
class TimeController private constructor(
    context: Context,
) {
    interface McuClock {
        fun pushToMcu()

        fun pullFromMcu()
    }

    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("time", Context.MODE_PRIVATE)
    private val lm = app.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<TimeSettings> = _settings.asStateFlow()

    private val _gps = MutableStateFlow(GpsState())
    val gps: StateFlow<GpsState> = _gps.asStateFlow()

    @Volatile
    var mcu: McuClock? = null

    /** Screens watching the GPS (test), plus the periodic sync while it waits for a fix. */
    private var watchers = 0
    private var syncPending = false
    private var started = false

    private val gnssCallback =
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val list =
                    (0 until status.satelliteCount).map {
                        Satellite(status.getSvid(it), status.getConstellationType(it), status.getCn0DbHz(it), status.usedInFix(it))
                    }
                _gps.value = _gps.value.copy(satellites = list.sortedByDescending { it.cn0 })
            }

            override fun onFirstFix(ttffMillis: Int) {
                _gps.value = _gps.value.copy(firstFixMs = ttffMillis)
            }
        }

    private val locationListener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                _gps.value = _gps.value.copy(fix = location)
                if (syncPending) syncFrom(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) = Unit

            override fun onProviderEnabled(provider: String) = refreshEnabled()

            override fun onProviderDisabled(provider: String) = refreshEnabled()
        }

    private val periodic =
        object : Runnable {
            override fun run() {
                if (_settings.value.fromGps) syncFromGps()
                main.postDelayed(this, _settings.value.gpsIntervalMin.coerceAtLeast(5) * 60_000L)
            }
        }

    private val giveUp = Runnable { if (syncPending) finishSync("No GPS fix") }

    fun start() {
        if (started) return
        started = true
        refreshEnabled()
        main.postDelayed(periodic, START_DELAY_MS)
    }

    fun stop() {
        started = false
        main.removeCallbacks(periodic)
        main.removeCallbacks(giveUp)
        syncPending = false
        watchers = 0
        updateListening()
    }

    fun update(transform: (TimeSettings) -> TimeSettings) {
        val s = transform(_settings.value)
        prefs
            .edit()
            .putBoolean("from_mcu", s.fromMcu)
            .putBoolean("to_mcu", s.toMcu)
            .putBoolean("from_gps", s.fromGps)
            .putInt("gps_interval", s.gpsIntervalMin)
            .apply()
        _settings.value = s
        main.removeCallbacks(periodic)
        if (started) main.postDelayed(periodic, if (s.fromGps) 1_000 else START_DELAY_MS)
    }

    /** GPS test screen visible. */
    fun watch(on: Boolean) =
        main.post {
            watchers = (watchers + if (on) 1 else -1).coerceAtLeast(0)
            updateListening()
        }

    /** Waits for a GPS fix (5 min at most), then sets Android's clock and the MCU's. */
    fun syncFromGps() =
        main.post {
            if (syncPending) return@post
            syncPending = true
            _gps.value = _gps.value.copy(message = "")
            updateListening()
            // A recent fix is enough.
            val recent = _gps.value.fix?.takeIf { System.currentTimeMillis() - it.time < 30_000 }
            if (recent != null) syncFrom(recent) else main.postDelayed(giveUp, SYNC_TIMEOUT_MS)
        }

    private fun syncFrom(location: Location) {
        // The fix time is the GPS UTC time of the measurement; correct by its age on the monotonic clock.
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        val gpsNow = location.time + ageMs
        val delta = gpsNow - System.currentTimeMillis()
        var message = ""
        if (abs(delta) > TOLERANCE_MS) {
            try {
                app.getSystemService(AlarmManager::class.java).setTime(gpsNow)
            } catch (e: SecurityException) {
                message = "SET_TIME refused"
                Log.w(TAG, "setTime: ${e.message}")
            }
        }
        if (message.isEmpty()) mcu?.pushToMcu()
        _gps.value = _gps.value.copy(lastSync = System.currentTimeMillis(), lastSyncDeltaMs = delta)
        finishSync(message)
    }

    /**
     * Cold start of the GNSS: aiding data deleted (ephemeris, almanac, position, time), then time and XTRA injected
     * again (`LocationManager.sendExtraCommand`, public API). [restartDaemon]: also restarts MediaTek's GNSS daemon
     * (`mnld`) as root, when the chip itself seems stuck. The next fix takes longer (no ephemeris).
     */
    fun resetGps(restartDaemon: Boolean = false) =
        Thread({
            val listening = _gps.value.listening
            main.post {
                if (listening) {
                    runCatching {
                        lm.unregisterGnssStatusCallback(gnssCallback)
                        lm.removeUpdates(locationListener)
                    }
                    _gps.value = _gps.value.copy(listening = false)
                }
            }
            val deleted = runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null) }.getOrDefault(false)
            var daemon: Boolean? = null
            if (restartDaemon) {
                daemon =
                    org.librehu.service.root.RootShell.run(
                        "if [ -n \"$(getprop init.svc.mnld)\" ]; then stop mnld; sleep 1; start mnld; else killall mnld; fi",
                        timeoutS = 15,
                    ) != null
            }
            Thread.sleep(GPS_RESET_SETTLE_MS)
            runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_time_injection", null) }
            runCatching { lm.sendExtraCommand(LocationManager.GPS_PROVIDER, "force_xtra_injection", null) }
            main.post {
                _gps.value =
                    _gps.value.copy(
                        satellites = emptyList(),
                        fix = null,
                        firstFixMs = -1,
                        message =
                            buildString {
                                append(if (deleted) "GPS reset (cold start)" else "GPS reset refused by the provider")
                                if (daemon !=
                                    null
                                ) {
                                    append(if (daemon) ", GNSS daemon restarted" else ", GNSS daemon restart failed (root?)")
                                }
                            },
                    )
                updateListening()
            }
        }, "gps-reset").start()

    private fun finishSync(message: String) {
        syncPending = false
        main.removeCallbacks(giveUp)
        _gps.value = _gps.value.copy(message = message)
        updateListening()
    }

    private fun updateListening() {
        val want = watchers > 0 || syncPending
        val now = _gps.value.listening
        if (want == now) return
        try {
            if (want) {
                lm.registerGnssStatusCallback(gnssCallback, main)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locationListener, Looper.getMainLooper())
            } else {
                lm.unregisterGnssStatusCallback(gnssCallback)
                lm.removeUpdates(locationListener)
            }
            _gps.value = _gps.value.copy(listening = want, satellites = if (want) _gps.value.satellites else emptyList())
        } catch (e: SecurityException) {
            _gps.value = _gps.value.copy(message = "Location permission missing")
            syncPending = false
        } catch (e: IllegalArgumentException) {
            _gps.value = _gps.value.copy(message = "No GPS provider")
            syncPending = false
        }
        refreshEnabled()
    }

    private fun refreshEnabled() {
        _gps.value = _gps.value.copy(enabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false))
    }

    private fun load(): TimeSettings {
        val d = TimeSettings()
        return TimeSettings(
            fromMcu = prefs.getBoolean("from_mcu", d.fromMcu),
            toMcu = prefs.getBoolean("to_mcu", d.toMcu),
            fromGps = prefs.getBoolean("from_gps", d.fromGps),
            gpsIntervalMin = prefs.getInt("gps_interval", d.gpsIntervalMin),
        )
    }

    companion object {
        private const val GPS_RESET_SETTLE_MS = 1500L
        private const val TAG = "LibreHU-Time"
        private const val TOLERANCE_MS = 2_000L
        private const val SYNC_TIMEOUT_MS = 5 * 60_000L
        private const val START_DELAY_MS = 30_000L
        val INTERVALS = listOf(15, 60, 240, 720)

        @Volatile
        private var instance: TimeController? = null

        fun get(context: Context): TimeController =
            instance ?: synchronized(this) { instance ?: TimeController(context).also { instance = it } }
    }
}
