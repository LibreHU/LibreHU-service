package org.librehu.service.car

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
    /** Package of the camera app; empty = the first known rear view app found. */
    val packageName: String = "",
    val delayMs: Int = 0,
    val exit: ReverseExit = ReverseExit.CLOSE,
)

/**
 * Opens the rear camera app when the reverse gear is engaged (MCU / reverse GPIO, after the "invert" setting) and
 * leaves it when the gear is released.
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

    fun update(transform: (ReverseCameraSettings) -> ReverseCameraSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs
            .edit()
            .putString(
                PREF,
                JSONObject()
                    .put("enabled", s.enabled)
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
        val s = _settings.value
        if (!s.enabled) return
        main.removeCallbacksAndMessages(null)
        if (on) {
            main.postDelayed({ if (reverse) open(s) }, s.delayMs.toLong())
        } else {
            leave(s)
        }
    }

    /** Opens the camera app now (test button). */
    fun test() = open(_settings.value)

    private fun open(s: ReverseCameraSettings) {
        val pkg = s.packageName.ifEmpty { detect() } ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            opened = pkg
        } catch (e: Exception) {
            Log.w(TAG, "open $pkg: ${e.message}")
        }
    }

    private fun leave(s: ReverseCameraSettings) {
        val pkg = opened ?: return
        opened = null
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

    /** First installed rear view app among the known ones. */
    fun detect(): String? =
        KNOWN.firstOrNull { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun load(): ReverseCameraSettings =
        prefs.getString(PREF, null)?.let {
            runCatching {
                val o = JSONObject(it)
                ReverseCameraSettings(
                    enabled = o.optBoolean("enabled", false),
                    packageName = o.optString("pkg", ""),
                    delayMs = o.optInt("delay", 0),
                    exit = ReverseExit.entries.firstOrNull { e -> e.name == o.optString("exit") } ?: ReverseExit.CLOSE,
                )
            }.getOrNull()
        } ?: ReverseCameraSettings()

    companion object {
        private const val TAG = "LibreHU-Reverse"
        private const val PREF = "reverse_camera"

        /** Rear view apps tried when none is chosen: the stock UJC201 ones (vendor/app hd2cam, ivi-avm360). */
        private val KNOWN = listOf("com.jancar.hd2cam", "com.baony.avm360")

        @Volatile
        private var instance: ReverseCamera? = null

        fun get(context: Context): ReverseCamera =
            instance ?: synchronized(this) {
                instance ?: ReverseCamera(context.applicationContext).also { instance = it }
            }
    }
}
