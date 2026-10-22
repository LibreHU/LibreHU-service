package org.librehu.service.perf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.service.LibreHuService
import org.librehu.service.backup.ConfigBackup
import org.librehu.service.root.RootShell
import java.util.concurrent.Executors

/** One app of the list: LibreHU (or a fork) or added by the user, its compiler filter now, and whether it is on. */
data class CompiledApp(
    val pkg: String,
    val label: String,
    val librehu: Boolean,
    val enabled: Boolean,
    /** ART's compiler filter now (`speed`, `verify`, `speed-profile`…), null while unknown (no root, not read yet). */
    val filter: String?,
    val compiling: Boolean = false,
    /** Last compilation failed (its message). */
    val error: String? = null,
)

/**
 * Ahead-of-time compilation of a list of apps: the LibreHU apps and forks (each can be switched off) and any other
 * app the user adds. On Android 9 an app installed outside an app store, or put in /system, gets `speed-profile`,
 * which without a profile is `verify` (no native code), and the background compilation only runs when the device is
 * idle and charging (source.android.com, "Package Manager configuration"): on a head unit that is almost never, so
 * the apps stay interpreted / JIT and lag (opening, scrolling). Here each one is compiled with
 * `cmd package compile -m speed -f` (root), once per installed version: at start (a minute after, low priority) and
 * when one is installed or updated. The filter of each app is read from `dumpsys package dexopt`.
 */
class AppCompiler private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "app-compiler").apply { priority = Thread.MIN_PRIORITY } }

    private val _auto = MutableStateFlow(prefs.getBoolean(PREF_AUTO, true))
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    private val _apps = MutableStateFlow<List<CompiledApp>>(emptyList())

    /** The list, LibreHU apps first. */
    val apps: StateFlow<List<CompiledApp>> = _apps.asStateFlow()

    private val _rootMissing = MutableStateFlow(false)
    val rootMissing: StateFlow<Boolean> = _rootMissing.asStateFlow()

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
                        if (!_auto.value || pkg !in wanted()) return
                        worker.execute {
                            // Let the install finish its own dexopt first.
                            Thread.sleep(20_000)
                            compile(listOf(pkg), force = false)
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
            compile(wanted(), force = false)
        }
    }

    /** Reads the list and every filter again (screen opened). */
    fun refresh() = worker.execute { refreshNow() }

    /** Compiles every app of the list that is on, even those already done. */
    fun compileAll() = worker.execute { compile(wanted(), force = true) }

    fun compileOne(pkg: String) = worker.execute { compile(listOf(pkg), force = true) }

    /** A LibreHU app in or out of the automatic compilation. */
    fun setEnabled(
        pkg: String,
        on: Boolean,
    ) {
        val off = prefs.getStringSet(PREF_EXCLUDED, emptySet()).orEmpty().toMutableSet()
        if (on) off -= pkg else off += pkg
        prefs.edit().putStringSet(PREF_EXCLUDED, off).apply()
        update(pkg) { it.copy(enabled = on) }
    }

    /** Adds another installed app to the list (compiled at once). */
    fun addExtra(pkg: String) {
        if (!SAFE.matches(pkg)) return
        prefs.edit().putStringSet(PREF_EXTRA, extras() + pkg).apply()
        worker.execute {
            refreshNow()
            compile(listOf(pkg), force = true)
        }
    }

    fun removeExtra(pkg: String) {
        prefs.edit().putStringSet(PREF_EXTRA, extras() - pkg).apply()
        _apps.value = _apps.value.filterNot { it.pkg == pkg && !it.librehu }
    }

    /** Installed apps that can be added: launchable, not already in the list, sorted by name. */
    fun candidates(): List<Pair<String, String>> {
        val pm = app.packageManager
        val inList = _apps.value.map { it.pkg }.toSet()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm
            .queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it !in inList && it != app.packageName }
            .map { it to label(it) }
            .sortedBy { it.second.lowercase() }
    }

    private fun extras(): Set<String> = prefs.getStringSet(PREF_EXTRA, emptySet()).orEmpty()

    private fun excluded(): Set<String> = prefs.getStringSet(PREF_EXCLUDED, emptySet()).orEmpty()

    /** Packages compiled automatically: LibreHU apps that are on, and the added apps that are installed. */
    private fun wanted(): List<String> {
        val off = excluded()
        val ours = ConfigBackup.apps(app).map { it.pkg }.filter { it !in off }
        return ours + extras().filter { installed(it) && it !in ours }
    }

    private fun installed(pkg: String) = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun label(pkg: String): String =
        runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

    private fun isSystem(pkg: String) =
        runCatching { app.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)

    private fun refreshNow() {
        val off = excluded()
        val ours = ConfigBackup.apps(app)
        val filters = readFilters()
        val old = _apps.value.associateBy { it.pkg }
        val list =
            ours.map {
                CompiledApp(it.pkg, it.label, true, it.pkg !in off, filters[it.pkg], old[it.pkg]?.compiling == true, old[it.pkg]?.error)
            } +
                extras()
                    .filter { installed(it) && ours.none { o -> o.pkg == it } }
                    .map { CompiledApp(it, label(it), false, true, filters[it], old[it]?.compiling == true, old[it]?.error) }
                    .sortedBy { it.label.lowercase() }
        _apps.value = list
    }

    /**
     * Compiler filter of every package, from `dumpsys package dexopt`:
     * `  [org.librehu.launcher]` then `      arm64: [status=speed] [reason=cmdline]`.
     */
    private fun readFilters(): Map<String, String> {
        val out = RootShell.run("dumpsys package dexopt", timeoutS = 30)
        _rootMissing.value = out == null && !RootShell.isAvailable()
        if (out == null) return emptyMap()
        val map = HashMap<String, String>()
        var pkg: String? = null
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]") && !t.contains('=')) {
                pkg = t.removePrefix("[").removeSuffix("]")
                continue
            }
            val p = pkg ?: continue
            if (p in map) continue
            Regex("""\[status=([a-z-]+)]""").find(t)?.let { map[p] = it.groupValues[1] }
        }
        return map
    }

    private fun update(
        pkg: String,
        change: (CompiledApp) -> CompiledApp,
    ) {
        _apps.value = _apps.value.map { if (it.pkg == pkg) change(it) else it }
    }

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
            _rootMissing.value = true
            return
        }
        if (_apps.value.isEmpty()) refreshNow()
        for (pkg in pkgs) {
            if (!SAFE.matches(pkg)) continue
            val v = version(pkg) ?: continue
            if (!force && prefs.getString(PREF_DONE + pkg, null) == v) continue
            update(pkg) { it.copy(compiling = true, error = null) }
            // A system app keeps its /system oat otherwise: -f compiles it again into /data like any other
            val out = RootShell.run("cmd package compile -m speed -f $pkg", timeoutS = 600)
            val ok = out != null && out.contains("Success")
            if (ok) {
                prefs.edit().putString(PREF_DONE + pkg, v).apply()
                Log.i(TAG, "compiled $pkg ($v${if (isSystem(pkg)) ", system" else ""})")
            } else {
                Log.w(TAG, "compile $pkg: ${out?.trim()}")
            }
            update(pkg) { it.copy(compiling = false, error = if (ok) null else out?.trim()?.lines()?.lastOrNull() ?: "root") }
        }
        refreshNow()
    }

    companion object {
        private const val TAG = "LibreHU-Compile"
        private const val PREF_AUTO = "aot_auto"
        private const val PREF_DONE = "aot_done_"
        private const val PREF_EXCLUDED = "aot_excluded"
        private const val PREF_EXTRA = "aot_extra"
        private val SAFE = Regex("[A-Za-z0-9_.]+")

        @Volatile
        private var instance: AppCompiler? = null

        fun get(context: Context): AppCompiler = instance ?: synchronized(this) { instance ?: AppCompiler(context).also { instance = it } }
    }
}
