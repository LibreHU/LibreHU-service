package org.librehu.service.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.LibreHuService
import org.librehu.service.backup.ConfigBackup
import org.librehu.service.root.RootShell
import java.util.concurrent.Executors

/**
 * Ahead-of-time compilation of the LibreHU apps (and forks). On Android 9 an app installed outside an app store, or
 * put in /system, gets `speed-profile`, which without a profile is `verify` (no native code), and the background
 * compilation only runs when the device is idle and charging (source.android.com, "Package Manager configuration"):
 * on a head unit that is almost never, so the Jetpack Compose apps stay interpreted / JIT and lag (opening, scrolling).
 * Here each one is compiled with `cmd package compile -m speed -f` (root), once per installed version: at start (a
 * minute after, low priority) and when one is installed or updated.
 */
class AppCompiler private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "app-compiler").apply { priority = Thread.MIN_PRIORITY } }

    private val _auto = MutableStateFlow(prefs.getBoolean(PREF_AUTO, true))
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    private val _status = MutableStateFlow("")

    /** Package being compiled, `done:<n>` after a round, or `root` without root. */
    val status: StateFlow<String> = _status.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    fun setAuto(on: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO, on).apply()
        _auto.value = on
        if (on) start()
    }

    fun start() {
        if (!_auto.value) return
        if (receiver == null) {
            val r =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        val pkg = intent.data?.schemeSpecificPart ?: return
                        if (_auto.value && isLibreHu(pkg)) {
                            worker.execute {
                                // Let the install finish its own dexopt first.
                                Thread.sleep(20_000)
                                compile(listOf(pkg), force = false)
                            }
                        }
                    }
                }
            val f =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f)
            receiver = r
        }
        worker.execute {
            Thread.sleep(60_000)
            compile(packages(), force = false)
        }
    }

    /** Compiles every LibreHU app now (settings button), even those already done. */
    fun compileAll() = worker.execute { compile(packages(), force = true) }

    private fun isLibreHu(pkg: String) = pkg.startsWith("org.librehu.") || pkg in ConfigBackup.OTHERS

    private fun packages(): List<String> = ConfigBackup.apps(app).map { it.pkg }

    private fun version(pkg: String): String? =
        runCatching {
            val i = app.packageManager.getPackageInfo(pkg, 0)
            "${i.longVersionCode}:${i.lastUpdateTime}"
        }.getOrNull()

    private fun compile(
        pkgs: List<String>,
        force: Boolean,
    ) {
        if (!RootShell.isAvailable()) {
            _status.value = "root"
            return
        }
        var done = 0
        for (pkg in pkgs) {
            if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) continue
            val v = version(pkg) ?: continue
            if (!force && prefs.getString(PREF_DONE + pkg, null) == v) continue
            _status.value = pkg
            val out = RootShell.run("cmd package compile -m speed -f $pkg", timeoutS = 600)
            if (out != null && out.contains("Success")) {
                prefs.edit().putString(PREF_DONE + pkg, v).apply()
                done++
                Log.i(TAG, "compiled $pkg ($v)")
            } else {
                Log.w(TAG, "compile $pkg: ${out?.trim()}")
            }
        }
        _status.value = "done:$done"
    }

    companion object {
        private const val TAG = "LibreHU-Compile"
        private const val PREF_AUTO = "aot_auto"
        private const val PREF_DONE = "aot_done_"

        @Volatile
        private var instance: AppCompiler? = null

        fun get(context: Context): AppCompiler = instance ?: synchronized(this) { instance ?: AppCompiler(context).also { instance = it } }
    }
}
