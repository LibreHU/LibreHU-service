package org.librehu.service.projection

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.display.DisplayController
import kotlin.coroutines.resume

/** Android Auto receiver installed: the LibreHU fork of Open Headunit, or Open Headunit itself. */
enum class HeadunitApp(
    val packageName: String,
    /** Receiver of the automation commands: the fork's one takes the configuration from the LibreHU apps. */
    val receiver: String,
) {
    LIBREHU("org.librehu.headunit", "com.andrerinas.openheadunit.automation.LibreHuAutomationReceiver"),
    UPSTREAM("com.andrerinas.headunitrevived", "com.andrerinas.openheadunit.automation.AutomationReceiver"),
}

/** Session reported by Open Headunit (SESSION_STATE). */
data class AaSession(
    /** connecting, connected, projecting, disconnected, failed; empty before the first report. */
    val state: String = "",
    /** usb, wifi, self, unknown. */
    val transport: String = "",
    val reason: String = "",
    /** When the report came (elapsedRealtime). */
    val at: Long = 0,
) {
    val active get() = state == "connecting" || state == "connected" || state == "projecting"
}

data class AndroidAutoSettings(
    /** Day / night of Android Auto from the headlights (else Open Headunit's own setting). */
    val nightFromHeadlights: Boolean = true,
)

/**
 * Android Auto through Open Headunit (or its LibreHU fork), driven by its automation interface (contract/README.md
 * of Open Headunit): commands to its AutomationReceiver (the fork's LibreHuAutomationReceiver, guarded by a
 * signature permission the LibreHU apps hold, also takes the configuration), session state from its
 * `com.andrerinas.headunitrevived.SESSION_STATE` broadcast. Started by [LibreHuService].
 */
object AndroidAuto {
    private const val TAG = "LibreHU-AA"
    private const val PREFIX = "com.andrerinas.openheadunit."
    const val ACTION_SESSION_STATE = "com.andrerinas.headunitrevived.SESSION_STATE"
    private const val PREF_NIGHT = "aa_night_headlights"

    const val CONNECT = PREFIX + "ACTION_CONNECT"
    const val DISCONNECT = PREFIX + "ACTION_DISCONNECT"
    const val CHECK_USB = PREFIX + "ACTION_CHECK_USB"
    const val RAISE = PREFIX + "ACTION_RAISE_PROJECTION"
    const val RESTART_AUDIO = PREFIX + "ACTION_RESTART_AUDIO"
    const val SET_NIGHT_MODE = PREFIX + "ACTION_SET_NIGHT_MODE"
    const val GET_SETTINGS = PREFIX + "ACTION_GET_SETTINGS"
    const val SET_SETTINGS = PREFIX + "ACTION_SET_SETTINGS"
    const val QUERY_STATE = PREFIX + "ACTION_QUERY_STATE"

    private val _session = MutableStateFlow(AaSession())
    val session: StateFlow<AaSession> = _session.asStateFlow()

    private val _settings = MutableStateFlow(AndroidAutoSettings())
    val settings: StateFlow<AndroidAutoSettings> = _settings.asStateFlow()

    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var lastNight: Boolean? = null

    fun installed(context: Context): HeadunitApp? =
        HeadunitApp.entries.firstOrNull {
            try {
                context.packageManager.getPackageInfo(it.packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    /** Session reports, and the headlights to Android Auto's day / night. */
    fun start(context: Context) {
        val app = context.applicationContext
        load(app)
        if (receiver != null) return
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    _session.value =
                        AaSession(
                            intent.getStringExtra("state").orEmpty(),
                            intent.getStringExtra("transport").orEmpty(),
                            intent.getStringExtra("reason").orEmpty(),
                            SystemClock.elapsedRealtime(),
                        )
                    // A new session takes the current day / night.
                    if (intent.getStringExtra("state") == "connected") {
                        lastNight = null
                        applyNight(app, DisplayController.get(app).headlights.value)
                    }
                }
            }
        // Sent by another app: exported.
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, IntentFilter(ACTION_SESSION_STATE), Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, IntentFilter(ACTION_SESSION_STATE))
        }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        DisplayController
            .get(app)
            .headlights
            .filterNotNull()
            .onEach { applyNight(app, it) }
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
        transform: (AndroidAutoSettings) -> AndroidAutoSettings,
    ) {
        load(context)
        val s = transform(_settings.value)
        _settings.value = s
        LibreHuService
            .prefs(context)
            .edit()
            .putBoolean(PREF_NIGHT, s.nightFromHeadlights)
            .apply()
        lastNight = null
        if (s.nightFromHeadlights) {
            applyNight(context, DisplayController.get(context).headlights.value)
        } else {
            send(context, SET_NIGHT_MODE, Bundle().apply { putString("state", "auto") })
        }
    }

    private fun applyNight(
        context: Context,
        headlights: Boolean?,
    ) {
        if (!_settings.value.nightFromHeadlights || headlights == null || headlights == lastNight) return
        lastNight = headlights
        send(context, SET_NIGHT_MODE, Bundle().apply { putString("state", if (headlights) "night" else "day") })
    }

    /** A command to the installed app; false when none is installed. */
    fun send(
        context: Context,
        action: String,
        extras: Bundle? = null,
    ): Boolean {
        val app = installed(context) ?: return false
        return try {
            context.sendBroadcast(intent(app, action, extras))
            true
        } catch (e: Exception) {
            Log.w(TAG, "$action: ${e.message}")
            false
        }
    }

    /** A command with its JSON reply (ordered broadcast), null when refused or not installed. */
    suspend fun ask(
        context: Context,
        action: String,
        extras: Bundle? = null,
    ): JSONObject? {
        val app = installed(context) ?: return null
        return suspendCancellableCoroutine { cont ->
            try {
                context.sendOrderedBroadcast(
                    intent(app, action, extras),
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            c: Context,
                            i: Intent,
                        ) {
                            val data = resultData
                            if (cont.isActive) cont.resume(data?.let { runCatching { JSONObject(it) }.getOrNull() })
                        }
                    },
                    Handler(Looper.getMainLooper()),
                    Activity.RESULT_CANCELED,
                    null,
                    null,
                )
            } catch (e: Exception) {
                Log.w(TAG, "$action: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** Open Headunit's settings (`settings` of its backup format), or the reason they cannot be read. */
    suspend fun readSettings(context: Context): Result<JSONObject> {
        val reply = ask(context, GET_SETTINGS) ?: return Result.failure(Exception("no reply"))
        if (!reply.optBoolean("ok")) return Result.failure(Exception(reply.optString("error").ifEmpty { "refused" }))
        val root = reply.optJSONObject("settings") ?: return Result.failure(Exception("settings"))
        return Result.success(root.optJSONObject("settings") ?: JSONObject())
    }

    /** Changes some of Open Headunit's settings; the reply says whether the projection has to restart. */
    suspend fun writeSettings(
        context: Context,
        values: JSONObject,
    ): Result<JSONObject> {
        val json =
            JSONObject()
                .put("format", "open-headunit-settings")
                .put("version", 1)
                .put("settings", values)
        val reply =
            ask(context, SET_SETTINGS, Bundle().apply { putString("json", json.toString()) })
                ?: return Result.failure(Exception("no reply"))
        return if (reply.optBoolean("ok")) {
            Result.success(reply)
        } else {
            Result.failure(Exception(reply.optString("error").ifEmpty { "refused" }))
        }
    }

    /** Opens Open Headunit (its own screen; the projection when a phone is connected). */
    fun open(context: Context): Boolean {
        val app = installed(context) ?: return false
        if (_session.value.active) return send(context, RAISE)
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun intent(
        app: HeadunitApp,
        action: String,
        extras: Bundle?,
    ) = Intent(action)
        .setComponent(ComponentName(app.packageName, app.receiver))
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        .apply { extras?.let { putExtras(it) } }

    @Synchronized
    private fun load(context: Context) {
        val p = LibreHuService.prefs(context)
        _settings.value = AndroidAutoSettings(p.getBoolean(PREF_NIGHT, true))
    }
}
