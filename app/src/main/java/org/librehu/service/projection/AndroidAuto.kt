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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.librehu.core.obd.ObdPid
import org.librehu.core.unit.VehicleState
import org.librehu.service.LibreHuService
import org.librehu.service.ServiceState
import org.librehu.service.display.DisplayController
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdManager
import org.librehu.service.obd.ObdState
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
    /** Parking brake, reverse, lights (MCU) and speed, engine speed, fuel, outside temperature (OBD) to the fork. */
    val carData: Boolean = true,
    /** Media, call and voice assistant keys to Android Auto while a session runs (the fork only). */
    val keys: Boolean = true,
    /** Projection brought back after the reverse camera app (the built-in rear view is an overlay, nothing to do). */
    val raiseAfterReverse: Boolean = true,
)

/** What the fork gets as Android Auto sensors (LibreHuCar on its side); null = unknown. */
data class AaCarData(
    val handbrake: Boolean? = null,
    val reverse: Boolean? = null,
    val headlight: Boolean? = null,
    val turn: String? = null,
    val speedKmh: Double? = null,
    val rpm: Double? = null,
    val fuelPct: Double? = null,
    val ambientC: Double? = null,
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
    private const val PREF_CAR = "aa_car_data"
    private const val PREF_KEYS = "aa_keys"
    private const val PREF_RAISE = "aa_raise_after_reverse"

    /** Broadcasts of the fork, guarded by its signature permission org.librehu.headunit.permission.CONFIGURE. */
    const val ACTION_CAR = "org.librehu.headunit.action.CAR"
    const val ACTION_KEY = "org.librehu.headunit.action.KEY"
    const val EXPORT_LOG = PREFIX + "ACTION_EXPORT_LOG"

    /** Keys Android Auto takes (media, call, voice assistant); others keep their usual action. */
    val AA_KEYS =
        setOf(
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,
            android.view.KeyEvent.KEYCODE_CALL,
            android.view.KeyEvent.KEYCODE_ENDCALL,
            android.view.KeyEvent.KEYCODE_VOICE_ASSIST,
            android.view.KeyEvent.KEYCODE_SEARCH,
        )

    const val CONNECT = PREFIX + "ACTION_CONNECT"
    const val DISCONNECT = PREFIX + "ACTION_DISCONNECT"
    const val CHECK_USB = PREFIX + "ACTION_CHECK_USB"
    const val RAISE = PREFIX + "ACTION_RAISE_PROJECTION"
    const val RESTART_AUDIO = PREFIX + "ACTION_RESTART_AUDIO"
    const val SET_NIGHT_MODE = PREFIX + "ACTION_SET_NIGHT_MODE"
    const val GET_SETTINGS = PREFIX + "ACTION_GET_SETTINGS"
    const val SET_SETTINGS = PREFIX + "ACTION_SET_SETTINGS"
    const val QUERY_STATE = PREFIX + "ACTION_QUERY_STATE"

    /** Result code of the fork's key receiver when it sent the key to the phone. */
    private const val KEY_TAKEN = 1
    private const val RAISE_DELAY_MS = 800L

    /** When the day / night and the car's state are sent again after a session starts. */
    private val SESSION_RESEND_MS = longArrayOf(0L, 1500L, 5000L)
    private val main = Handler(Looper.getMainLooper())

    private val _session = MutableStateFlow(AaSession())
    val session: StateFlow<AaSession> = _session.asStateFlow()

    private val _settings = MutableStateFlow(AndroidAutoSettings())
    val settings: StateFlow<AndroidAutoSettings> = _settings.asStateFlow()

    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var lastNight: Boolean? = null
    private var lastCar: AaCarData? = null

    /** The projection was on when reverse came: brought back after the reverse camera app. */
    private var projectingAtReverse = false

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
                    ProjectionWidgets.refresh(app)
                    // A new session takes the current day / night, and the car's state: at "connected", at
                    // "projecting", and again a little later (sent too early, the phone missed headlights already on
                    // and Android Auto stayed in day mode until they were switched off and on again).
                    val st = intent.getStringExtra("state")
                    if (st == "connected" || st == "projecting") {
                        for (delay in SESSION_RESEND_MS) {
                            main.postDelayed({
                                if (_session.value.active) {
                                    lastNight = null
                                    applyNight(app, DisplayController.get(app).headlights.value)
                                    lastCar?.let { sendCar(app, it) }
                                }
                            }, delay)
                        }
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
        combine(ServiceState.vehicle, ObdManager.get(app).state) { v, o -> carData(v, o) }
            .distinctUntilChanged()
            .onEach {
                lastCar = it
                sendCar(app, it)
            }.launchIn(sc)
    }

    private fun carData(
        v: VehicleState,
        o: ObdState,
    ): AaCarData {
        val mcu = v.mcuOnline
        val obd = if (o.connection == ObdConnection.CONNECTED) o.values else emptyMap()
        return AaCarData(
            handbrake = v.handbrake.takeIf { mcu },
            reverse = v.reverse.takeIf { mcu },
            headlight = v.headlight.takeIf { mcu },
            turn =
                if (!mcu) {
                    null
                } else {
                    when {
                        v.turnLeft && !v.turnRight -> "left"
                        v.turnRight && !v.turnLeft -> "right"
                        else -> "none"
                    }
                },
            speedKmh = obd[ObdPid.SPEED.name],
            rpm = obd[ObdPid.RPM.name],
            fuelPct = obd[ObdPid.FUEL_LEVEL.name],
            ambientC = obd[ObdPid.AMBIENT_TEMP.name],
        )
    }

    /** The car's state to the fork (it keeps the last values and sends them to the phone, sensor by sensor). */
    private fun sendCar(
        context: Context,
        d: AaCarData,
    ) {
        if (!_settings.value.carData || installed(context) != HeadunitApp.LIBREHU) return
        val i = Intent(ACTION_CAR).setPackage(HeadunitApp.LIBREHU.packageName)
        d.handbrake?.let { i.putExtra("handbrake", it) }
        d.reverse?.let { i.putExtra("reverse", it) }
        d.headlight?.let { i.putExtra("headlight", it) }
        d.turn?.let { i.putExtra("turn", it) }
        d.speedKmh?.let { i.putExtra("speed_kmh", it) }
        d.rpm?.let { i.putExtra("rpm", it) }
        d.fuelPct?.let { i.putExtra("fuel_pct", it) }
        d.ambientC?.let { i.putExtra("ambient_c", it) }
        if (i.extras?.isEmpty != false) return
        try {
            context.sendBroadcast(i)
        } catch (e: Exception) {
            Log.w(TAG, "car: ${e.message}")
        }
    }

    /**
     * [code] to Android Auto while a session runs (the fork answers whether it took it, its own switch included);
     * otherwise, or when it did not take it, [fallback] (the key's usual action).
     */
    fun key(
        context: Context,
        code: Int,
        fallback: () -> Unit,
    ) {
        val s = _session.value.state
        if (!_settings.value.keys || code !in AA_KEYS || (s != "connected" && s != "projecting") ||
            installed(context) != HeadunitApp.LIBREHU
        ) {
            fallback()
            return
        }
        try {
            context.sendOrderedBroadcast(
                Intent(ACTION_KEY).setPackage(HeadunitApp.LIBREHU.packageName).putExtra("keycode", code).putExtra("event", "click"),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        i: Intent,
                    ) {
                        if (resultCode != KEY_TAKEN) fallback()
                    }
                },
                Handler(Looper.getMainLooper()),
                0,
                null,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "key $code: ${e.message}")
            fallback()
        }
    }

    /** Reverse gear engaged: remembers whether the projection was on. */
    fun onReverse() {
        projectingAtReverse = _session.value.state == "projecting"
    }

    /** The reverse camera app was closed: the projection comes back if it was on when reverse came. */
    fun afterReverseApp(context: Context) {
        if (!projectingAtReverse || !_settings.value.raiseAfterReverse) return
        projectingAtReverse = false
        // After the camera app's exit (home or force-stop).
        Handler(Looper.getMainLooper()).postDelayed({ if (_session.value.active) send(context, RAISE) }, RAISE_DELAY_MS)
    }

    /** Asks Open Headunit for its log, copied to Download/LibreHU; returns the file name or null when refused. */
    suspend fun exportLog(context: Context): String? {
        val name = "android-auto-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".log"
        @Suppress("DEPRECATION")
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "LibreHU")
        val reply = ask(context, EXPORT_LOG, Bundle().apply { putString("path", java.io.File(dir, name).absolutePath) }) ?: return null
        return if (reply.optBoolean("ok")) "Download/LibreHU/$name" else null
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
            .putBoolean(PREF_CAR, s.carData)
            .putBoolean(PREF_KEYS, s.keys)
            .putBoolean(PREF_RAISE, s.raiseAfterReverse)
            .apply()
        lastCar?.let { sendCar(context, it) }
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
        val extras =
            if (installed(context) == HeadunitApp.LIBREHU) {
                // The fork's own "Car Headlight Signal" mode, fed with the headlights: Android Auto's night mode and the
                // app theme set on it follow them, and the user sees that mode in its settings.
                Bundle().apply {
                    putString("state", "car")
                    putString("headlight", if (headlights) "on" else "off")
                }
            } else {
                Bundle().apply { putString("state", if (headlights) "night" else "day") }
            }
        send(context, SET_NIGHT_MODE, extras)
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
        _settings.value =
            AndroidAutoSettings(
                nightFromHeadlights = p.getBoolean(PREF_NIGHT, true),
                carData = p.getBoolean(PREF_CAR, true),
                keys = p.getBoolean(PREF_KEYS, true),
                raiseAfterReverse = p.getBoolean(PREF_RAISE, true),
            )
    }
}
