package org.librehu.service.projection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.librehu.service.LibreHuService
import org.librehu.service.display.DisplayController

/** CarPlay session reported by the LibreHU fork of xcertplay (`org.librehu.carplay.SESSION_STATE`). */
data class CpSession(
    /** connecting, active, ended, failed; empty before the first report. */
    val state: String = "",
    val detail: String = "",
    val at: Long = 0,
)

data class CarPlaySettings(
    /** CarPlay's day / night from the headlights (else the launcher's dark mode, else Android's). */
    val nightFromHeadlights: Boolean = true,
)

/**
 * CarPlay through the LibreHU fork of xcertplay (`org.librehu.carplay`): its session state, and day / night from the
 * headlights (`org.librehu.carplay.action.SET_NIGHT_MODE`, `state` = night / day / auto, accepted only from apps
 * holding its signature permission `org.librehu.carplay.permission.CONFIGURE`). Started by [LibreHuService].
 */
object CarPlay {
    private const val TAG = "LibreHU-CarPlay"
    const val PACKAGE = "org.librehu.carplay"

    /** xcertplay itself (no LibreHU integration: open only). */
    const val UPSTREAM = "com.shilapi.xcertplay"
    private const val SESSION_STATE = "org.librehu.carplay.SESSION_STATE"
    private const val SET_NIGHT_MODE = "org.librehu.carplay.action.SET_NIGHT_MODE"
    private const val PREF_NIGHT = "cp_night_headlights"

    private val _session = MutableStateFlow(CpSession())
    val session: StateFlow<CpSession> = _session.asStateFlow()

    private val _settings = MutableStateFlow(CarPlaySettings())
    val settings: StateFlow<CarPlaySettings> = _settings.asStateFlow()

    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null

    /** The fork, else xcertplay, else null. */
    fun installed(context: Context): String? =
        listOf(PACKAGE, UPSTREAM).firstOrNull {
            try {
                context.packageManager.getPackageInfo(it, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    fun start(context: Context) {
        val app = context.applicationContext
        _settings.value = CarPlaySettings(LibreHuService.prefs(app).getBoolean(PREF_NIGHT, true))
        if (receiver != null) return
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    val state = intent.getStringExtra("state").orEmpty()
                    _session.value = CpSession(state, intent.getStringExtra("detail").orEmpty(), SystemClock.elapsedRealtime())
                    // A new session takes the current day / night.
                    if (state == "active") applyNight(app)
                }
            }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, IntentFilter(SESSION_STATE), Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, IntentFilter(SESSION_STATE))
        }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        DisplayController
            .get(app)
            .headlights
            .filterNotNull()
            .onEach { applyNight(app) }
            .launchIn(sc)
    }

    fun stop(context: Context) {
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
        scope?.cancel()
        scope = null
    }

    fun update(
        context: Context,
        transform: (CarPlaySettings) -> CarPlaySettings,
    ) {
        val s = transform(_settings.value)
        _settings.value = s
        LibreHuService
            .prefs(context)
            .edit()
            .putBoolean(PREF_NIGHT, s.nightFromHeadlights)
            .apply()
        applyNight(context)
    }

    /** The headlights to CarPlay's day / night, or `auto` (the app's own choice) when switched off. */
    private fun applyNight(context: Context) {
        if (installed(context) != PACKAGE) return
        val lights = DisplayController.get(context).headlights.value
        val state =
            when {
                !_settings.value.nightFromHeadlights || lights == null -> "auto"
                lights -> "night"
                else -> "day"
            }
        try {
            context.sendBroadcast(Intent(SET_NIGHT_MODE).setPackage(PACKAGE).putExtra("state", state))
        } catch (e: Exception) {
            Log.w(TAG, "night: ${e.message}")
        }
    }

    /** Opens CarPlay (its screen; the projection when an iPhone is connected). */
    fun open(context: Context): Boolean {
        val pkg = installed(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
