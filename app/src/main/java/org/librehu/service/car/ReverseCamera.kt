package org.librehu.service.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell

/** What happens when the reverse gear is released. */
enum class ReverseExit {
    /** The camera app is closed (root `am force-stop`): the app used before comes back. */
    CLOSE,

    /** Home screen. */
    HOME,

    /** The camera app stays open. */
    NOTHING,
}

data class ReverseCameraSettings(
    val enabled: Boolean = false,
    /** LibreHU's own view ([ReverseView]) or another app. */
    val mode: ReverseMode = ReverseMode.BUILT_IN,
    /** Package of the camera app; empty = the first known rear view app found. */
    val packageName: String = "",
    val delayMs: Int = 0,
    val exit: ReverseExit = ReverseExit.CLOSE,
)

/**
 * Shows the rear view when the reverse gear is engaged (MCU / reverse GPIO, after the "invert" setting) and leaves it
 * when the gear is released: LibreHU's own view ([ReverseView]) or a camera app.
 */
class ReverseCamera private constructor(
    private val context: Context,
) {
    private val prefs = LibreHuService.prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ReverseCameraSettings> = _settings

    private var reverse = false
    private var opened: String? = null

    init {
        // Baony AVM 360 starts slowly (native init): its process is brought up now, so that reverse shows at once.
        if (_settings.value.enabled) warmUp(_settings.value)
    }

    private val view by lazy { ReverseView.get(context) }
    private val hideView = Runnable { if (!reverse) view.hide() }

    fun update(transform: (ReverseCameraSettings) -> ReverseCameraSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        if (s.enabled) warmUp(s)
        prefs
            .edit()
            .putString(
                PREF,
                JSONObject()
                    .put("enabled", s.enabled)
                    .put("mode", s.mode.name)
                    .put("pkg", s.packageName)
                    .put("delay", s.delayMs)
                    .put("exit", s.exit.name)
                    .toString(),
            ).apply()
    }

    /** Reverse gear state from the head unit (every vehicle state change). */
    fun onReverse(on: Boolean) {
        if (on == reverse) return
        reverse = on
        // Whether Android Auto was on, to bring it back after the reverse camera app.
        if (on) {
            org.librehu.service.projection.AndroidAuto
                .onReverse()
        }
        val s = _settings.value
        if (!s.enabled) return
        main.removeCallbacksAndMessages(null)
        // What ivi-services broadcast to the other apps (projection, DVR…).
        context.sendBroadcast(Intent(if (on) ACTION_BACKCAR_STARTED else ACTION_BACKCAR_FINISH))
        if (on) {
            main.postDelayed({ if (reverse) open(s) }, s.delayMs.toLong())
        } else {
            leave(s)
        }
    }

    /** Shows the rear view now (test button); the built-in one as a preview with a close button. */
    fun test() {
        val s = _settings.value
        if (s.mode == ReverseMode.BUILT_IN) view.show(preview = true) else open(s)
    }

    private fun open(s: ReverseCameraSettings) {
        if (s.mode == ReverseMode.BUILT_IN) {
            main.removeCallbacks(hideView)
            view.show()
            return
        }
        val pkg = s.packageName.ifEmpty { detect() } ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        if (pkg == AVM) warmUp(s)
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            opened = pkg
        } catch (e: Exception) {
            Log.w(TAG, "open $pkg: ${e.message}")
        }
        if (pkg == AVM) {
            // Rear view with guide lines: the reverse signal the app gets from the CAN box. Its receiver is registered
            // by its Application, so it is sent again once a cold start is surely over.
            avmReverse(1)
            main.postDelayed({ if (reverse) avmReverse(1) }, AVM_RESEND_MS)
        }
    }

    /** Starts the AVM 360 app's process (its CAN bus service), where its reverse receiver lives. */
    private fun warmUp(s: ReverseCameraSettings) {
        if (s.mode != ReverseMode.APP) return
        if ((s.packageName.ifEmpty { detect() }) != AVM) return
        try {
            context.startService(Intent(AVM_SERVICE).setComponent(ComponentName(AVM, AVM_SERVICE)))
        } catch (e: Exception) {
            Log.w(TAG, "AVM service: ${e.message}")
        }
    }

    private fun avmReverse(state: Int) {
        context.sendBroadcast(Intent(AVM_REVERSE).setPackage(AVM).putExtra(AVM_STATE, state))
    }

    private fun leave(s: ReverseCameraSettings) {
        if (s.mode == ReverseMode.BUILT_IN || view.showing.value) {
            main.postDelayed(
                hideView,
                view.settings.value.closeDelayMs
                    .toLong(),
            )
            if (s.mode == ReverseMode.BUILT_IN) return
        }
        val pkg = opened ?: return
        opened = null
        if (pkg == AVM) avmReverse(0)
        org.librehu.service.projection.AndroidAuto
            .afterReverseApp(context)
        when (s.exit) {
            ReverseExit.CLOSE -> {
                Thread({
                    if (!RootShell.isAvailable() || RootShell.run("am force-stop $pkg") == null) main.post { home() }
                }, "reverse-exit").start()
            }

            ReverseExit.HOME -> {
                home()
            }

            ReverseExit.NOTHING -> {}
        }
    }

    private fun home() {
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "home: ${e.message}")
        }
    }

    /**
     * First installed rear view app among the known ones, in the order of the ROM's camera type
     * (`Settings.Global avm_hd2cam`, set by Jancar's settings app: 1 = AVM 360, else the hd2cam DVR).
     */
    fun detect(): String? =
        (if (avmSelected()) listOf(AVM, HD2CAM) else listOf(HD2CAM, AVM)).firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun avmSelected(): Boolean =
        runCatching {
            android.provider.Settings.Global
                .getInt(context.contentResolver, KEY_CAMERA_TYPE, 0) == 1
        }.getOrDefault(false)

    private fun load(): ReverseCameraSettings =
        prefs.getString(PREF, null)?.let {
            runCatching {
                val o = JSONObject(it)
                ReverseCameraSettings(
                    enabled = o.optBoolean("enabled", false),
                    // Settings saved before the built-in view opened an app.
                    mode = ReverseMode.entries.firstOrNull { m -> m.name == o.optString("mode") } ?: ReverseMode.APP,
                    packageName = o.optString("pkg", ""),
                    delayMs = o.optInt("delay", 0),
                    exit = ReverseExit.entries.firstOrNull { e -> e.name == o.optString("exit") } ?: ReverseExit.CLOSE,
                )
            }.getOrNull()
        } ?: ReverseCameraSettings()

    companion object {
        private const val TAG = "LibreHU-Reverse"
        private const val PREF = "reverse_camera"

        /**
         * Baony AVM 360 (vendor/app/ivi-avm360) of the stock UJC201 ROM, used when the camera type is AVM: it shows
         * its rear view on its "CAN" reverse broadcast. ivi-services itself starts no camera app.
         */
        const val AVM = "com.baony.avm360"
        private const val AVM_SERVICE = "com.baony.ui.service.AVMCanBusService"
        private const val AVM_REVERSE = "Voice_Reverse_State"
        private const val AVM_STATE = "Signal_State"
        private const val AVM_RESEND_MS = 1500L

        private const val ACTION_BACKCAR_STARTED = "android.backcar.action.STARTED"
        private const val ACTION_BACKCAR_FINISH = "android.backcar.action.FINISH"

        /** Jancar's DVR (dual recording); with ivi-services it shows the rear camera itself, in an overlay window. */
        const val HD2CAM = "com.jancar.hd2cam"

        /** `JACSettings.Global.KEY_AVM_HD2CAM` of the ROM: camera type, 1 = AVM 360. */
        private const val KEY_CAMERA_TYPE = "avm_hd2cam"

        @Volatile
        private var instance: ReverseCamera? = null

        fun get(context: Context): ReverseCamera =
            instance ?: synchronized(this) {
                instance ?: ReverseCamera(context.applicationContext).also { instance = it }
            }
    }
}
