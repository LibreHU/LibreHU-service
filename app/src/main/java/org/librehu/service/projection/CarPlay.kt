package org.librehu.service.projection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
    /**
     * CarPlay opened when an iPhone is plugged in (USB, Apple vendor id): the app only connects while its screen is
     * open, and its own USB filter only lists its CH341 MFi adapter.
     */
    val openOnIphone: Boolean = true,
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
    private const val PREF_OPEN_ON_IPHONE = "cp_open_on_iphone"
    private const val APPLE_VENDOR_ID = 0x05ac

    /** The iPhone re-enumerates during the CarPlay handshake: one opening per plug. */
    private const val OPEN_DEBOUNCE_MS = 20_000L
    private var lastAutoOpen = 0L
    private var usbReceiver: BroadcastReceiver? = null

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
        val p = LibreHuService.prefs(app)
        _settings.value = CarPlaySettings(p.getBoolean(PREF_NIGHT, true), p.getBoolean(PREF_OPEN_ON_IPHONE, true))
        if (receiver != null) return
        usbReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    if (device.vendorId != APPLE_VENDOR_ID) return
                    if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                        org.librehu.service.phone.PhoneInfo
                            .onIphoneUnplugged()
                        return
                    }
                    org.librehu.service.phone.PhoneInfo
                        .onIphonePlugged(app)
                    onIphone(app)
                }
            }
        val usbFilter =
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(usbReceiver, usbFilter)
        }
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    val state = intent.getStringExtra("state").orEmpty()
                    _session.value = CpSession(state, intent.getStringExtra("detail").orEmpty(), SystemClock.elapsedRealtime())
                    ProjectionWidgets.refresh(app)
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
        usbReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        usbReceiver = null
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
            .putBoolean(PREF_OPEN_ON_IPHONE, s.openOnIphone)
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

    /** An iPhone was plugged in: CarPlay opened, unless a session is already starting or running. */
    private fun onIphone(context: Context) {
        if (!_settings.value.openOnIphone) return
        val state = _session.value.state
        if (state == "connecting" || state == "active") return
        val now = SystemClock.elapsedRealtime()
        if (lastAutoOpen != 0L && now - lastAutoOpen < OPEN_DEBOUNCE_MS) return
        lastAutoOpen = now
        Log.i(TAG, "iPhone plugged in: opening CarPlay")
        runCatching { open(context) }.onFailure { Log.w(TAG, "open: ${it.message}") }
    }

    /** Opens CarPlay (its screen; the projection when an iPhone is connected). */
    fun open(context: Context): Boolean {
        val pkg = installed(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
