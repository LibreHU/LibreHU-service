package org.librehu.service.power

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import org.librehu.service.root.RootShell

/**
 * Apps whose data directory belongs to another uid. After a ROM reflash (packages.xml rebuilt) or a reinstall the
 * package can get a new uid while `/data/user/0/<pkg>` keeps the old owner: installd refuses it ("Expected path … with
 * owner 10073:10073 but found 10074:10074") and the app crashes at each start (ViPER4Android at boot, so no effects).
 * [repair] gives the directories back to the package's uid (cache directories to its cache gid, as installd does),
 * restores their SELinux labels, and sends BOOT_COMPLETED again to the repaired apps that listen for it.
 */
object AppDataRepair {
    private const val TAG = "LibreHU-DataRepair"

    data class Issue(
        val pkg: String,
        val uid: Int,
        val found: Int,
        val dirs: List<String>,
    )

    /** Installed apps (uid 10000..19999, user 0) and their data directories. */
    private fun apps(context: Context): List<ApplicationInfo> =
        runCatching { context.packageManager.getInstalledApplications(0) }
            .getOrDefault(emptyList())
            .filter { it.uid in 10000..19999 }

    /** Root; null without root. */
    fun scan(context: Context): List<Issue>? {
        if (!RootShell.isAvailable()) return null
        val apps = apps(context)
        val script =
            buildString {
                append("c(){ [ -d \"\$3\" ] || return; o=\$(stat -c %u \"\$3\"); [ \"\$o\" = \"\$2\" ] || echo \"\$1 \$2 \$o \$3\"; }; ")
                for (a in apps) {
                    for (d in listOfNotNull(a.dataDir, a.deviceProtectedDataDir).distinct()) {
                        append("c ${a.packageName} ${a.uid} ${quote(d)}; ")
                    }
                }
                append("true")
            }
        // A few hundred lines: through stdin rather than one `su -c` argument.
        val out = RootShell.run("sh", stdin = script.toByteArray(), timeoutS = 60) ?: return null
        return out
            .lines()
            .mapNotNull { line ->
                val p = line.trim().split(" ", limit = 4)
                if (p.size < 4) return@mapNotNull null
                val uid = p[1].toIntOrNull() ?: return@mapNotNull null
                val found = p[2].toIntOrNull() ?: return@mapNotNull null
                Issue(p[0], uid, found, listOf(p[3]))
            }.groupBy { it.pkg }
            .map { (_, list) -> list.first().copy(dirs = list.flatMap { it.dirs }) }
    }

    /** Fixes [issues] (root) and starts the repaired apps' boot receivers again; the packages fixed. */
    fun repair(
        context: Context,
        issues: List<Issue>,
    ): List<String> {
        val fixed = mutableListOf<String>()
        for (i in issues) {
            // installd: cache and code_cache are owned by uid:cache_gid, cache_gid = 20000 + (appId - 10000).
            val cacheGid = 20000 + (i.uid % 100000 - 10000)
            val script =
                buildString {
                    append("am force-stop ${i.pkg}; ")
                    for (d in i.dirs) {
                        val q = quote(d)
                        append("chown -R ${i.uid}:${i.uid} $q && ")
                        append("for c in cache code_cache; do [ -d $q/\$c ] && chown -R ${i.uid}:$cacheGid $q/\$c; done; ")
                        append("restorecon -RF $q; ")
                    }
                    append("true")
                }
            if (RootShell.run(script, timeoutS = 60) != null) {
                fixed += i.pkg
                Log.i(TAG, "${i.pkg}: data owner ${i.found} -> ${i.uid}")
                restartBootReceivers(context, i.pkg)
            }
        }
        return fixed
    }

    /** At service start: scan and repair in the background (once per start). */
    fun runAtStart(context: Context) {
        val app = context.applicationContext
        Thread({
            val issues = scan(app) ?: return@Thread
            if (issues.isEmpty()) return@Thread
            Log.w(TAG, "data owned by another uid: ${issues.joinToString { "${it.pkg} (${it.found} != ${it.uid})" }}")
            repair(app, issues)
        }, "data-repair").start()
    }

    /** The app missed its BOOT_COMPLETED (it crashed): sent again to its own receivers, as root may. */
    private fun restartBootReceivers(
        context: Context,
        pkg: String,
    ) {
        @Suppress("DEPRECATION")
        val receivers =
            runCatching {
                context.packageManager.queryBroadcastReceivers(
                    Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(pkg),
                    PackageManager.GET_META_DATA,
                )
            }.getOrDefault(emptyList())
        for (r in receivers) {
            val c = ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToShortString()
            RootShell.run("am broadcast -a android.intent.action.BOOT_COMPLETED -n $c")
        }
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
