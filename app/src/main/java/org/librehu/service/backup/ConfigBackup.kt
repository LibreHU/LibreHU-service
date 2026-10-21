package org.librehu.service.backup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.service.root.RootShell
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings of every LibreHU app in one file, for a migration (another head unit, a new ROM, a factory reset): the
 * service, the launcher, the other LibreHU apps, the LibreHU forks (ViPER4Android, Open Headunit, CarPlay) and their
 * upstream versions when installed. Root copies each app's `shared_prefs`, `files` and `databases` (credential and
 * device protected storage), its `Android/data/<package>/files` and ViPER's presets folder into a tar archive with a
 * manifest, in `Download/LibreHU`. Caches and the code are left out.
 *
 * Restore: each app chosen is stopped, its folders replaced, given back to its uid on this unit (it may differ from
 * the one of the archive) and relabelled for SELinux (`restorecon`). The service itself comes last, from a root shell
 * that outlives it: stopped, restored, started again.
 */
object ConfigBackup {
    private const val TAG = "LibreHU-Backup"
    const val FORMAT = "librehu-config-backup"
    private const val VERSION = 1
    private val DIRS = listOf("shared_prefs", "files", "databases")
    private const val STAGE = "/data/local/tmp/librehu-backup"
    private const val VIPER_PRESETS = "/sdcard/ViPER4Android"
    private const val VIPER = "com.llsl.viper4android"
    private const val SELF = "org.librehu.service"

    /** Apps of LibreHU and its forks (and their upstream versions), besides every org.librehu.* package. */
    val OTHERS =
        listOf(
            VIPER,
            "com.andrerinas.headunitrevived",
            "com.shilapi.xcertplay",
            "com.dokt.jacmcu",
        )

    data class App(
        val pkg: String,
        val label: String,
        val version: String,
        val installed: Boolean = true,
    )

    /** Installed LibreHU apps (and forks), the service first. */
    fun apps(context: Context): List<App> {
        val pm = context.packageManager
        return pm
            .getInstalledPackages(0)
            .filter { it.packageName.startsWith("org.librehu.") || it.packageName in OTHERS }
            .map { App(it.packageName, label(pm, it.packageName), it.versionName.orEmpty()) }
            .sortedWith(compareBy({ it.pkg != SELF }, { it.label.lowercase() }))
    }

    private fun label(
        pm: PackageManager,
        pkg: String,
    ) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun safe(p: String) = Regex("[A-Za-z0-9_.]+").matches(p)

    /** Writes the archive of [pkgs]; its path (under /sdcard), or an error. */
    fun export(
        context: Context,
        pkgs: List<String>,
    ): Result<String> {
        if (!RootShell.isAvailable()) return Result.failure(Exception("root"))
        val list = pkgs.filter(::safe)
        val all = apps(context).associateBy { it.pkg }
        val manifest =
            JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("date", System.currentTimeMillis())
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
                .put(
                    "apps",
                    JSONArray(
                        list.map { p ->
                            JSONObject().put("package", p).put("label", all[p]?.label ?: p).put("version", all[p]?.version.orEmpty())
                        },
                    ),
                )
        val name = "librehu-config-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".tar"
        val out = "/sdcard/Download/LibreHU/$name"
        val script =
            buildString {
                append("set -e\nrm -rf $STAGE && mkdir -p $STAGE/apps\n")
                for (p in list) {
                    val a = "$STAGE/apps/$p"
                    append("mkdir -p $a/data $a/de $a/ext\n")
                    for (d in DIRS) {
                        append("[ -d /data/data/$p/$d ] && cp -a /data/data/$p/$d $a/data/ || true\n")
                        append("[ -d /data/user_de/0/$p/$d ] && cp -a /data/user_de/0/$p/$d $a/de/ || true\n")
                    }
                    append("[ -d /sdcard/Android/data/$p/files ] && cp -a /sdcard/Android/data/$p/files $a/ext/ || true\n")
                    if (p == VIPER) append("[ -d $VIPER_PRESETS ] && mkdir -p $a/viper && cp -a $VIPER_PRESETS/. $a/viper/ || true\n")
                }
                append("cat > $STAGE/manifest.json\n")
                append("mkdir -p /sdcard/Download/LibreHU\n")
                append("cd $STAGE && tar -cf '$out' manifest.json apps\n")
                append("cd / && rm -rf $STAGE\n")
            }
        val r = RootShell.run(script, stdin = manifest.toString(1).toByteArray(), timeoutS = 300)
        return if (r != null) {
            Log.i(TAG, "exported ${list.size} apps to $out")
            Result.success(out)
        } else {
            Result.failure(Exception("tar"))
        }
    }

    /** Manifest of an archive copied to [file] (readable by root), or an error. */
    fun inspect(file: File): Result<JSONObject> {
        val text = RootShell.run("tar -xOf '${file.path}' manifest.json", timeoutS = 60) ?: return Result.failure(Exception("tar"))
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return Result.failure(Exception("manifest"))
        if (o.optString("format") != FORMAT) return Result.failure(Exception("format"))
        return Result.success(o)
    }

    /** Packages of an archive's manifest, marked installed here or not. */
    fun appsOf(
        context: Context,
        manifest: JSONObject,
    ): List<App> {
        val pm = context.packageManager
        val a = manifest.optJSONArray("apps") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val p = o.optString("package")
            val installed = runCatching { pm.getPackageInfo(p, 0) }.isSuccess
            App(p, o.optString("label", p), o.optString("version"), installed)
        }
    }

    /**
     * Restores [pkgs] from the archive [file]. The other apps are done now; the service, when chosen, by a detached
     * root shell after this process is stopped (it starts again by itself). Returns the apps restored now.
     */
    fun restore(
        context: Context,
        file: File,
        pkgs: List<String>,
    ): Result<List<String>> {
        if (!RootShell.isAvailable()) return Result.failure(Exception("root"))
        val pm = context.packageManager
        val list = pkgs.filter { safe(it) && runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
        val stage = "$STAGE-restore"
        val extract = "rm -rf $stage && mkdir -p $stage && cd $stage && tar -xf '${file.path}'\n"
        val others = list.filter { it != SELF }
        val script =
            buildString {
                append("set -e\n").append(extract)
                for (p in others) append(restoreApp(p, stage))
                if (SELF !in list) append("cd / && rm -rf $stage\n")
            }
        if (RootShell.run(script, timeoutS = 300) == null) return Result.failure(Exception("restore"))
        if (SELF in list) {
            // This process is stopped by the script: it runs on its own, under su (another uid, not killed with us)
            val self =
                "sleep 2\nam force-stop $SELF\n" + restoreApp(SELF, stage) +
                    "cd / && rm -rf $stage\nam start-foreground-service -n $SELF/.LibreHuService || am startservice -n $SELF/.LibreHuService\n"
            RootShell.run("cat > $STAGE-self.sh && chmod 700 $STAGE-self.sh && (nohup sh $STAGE-self.sh > /dev/null 2>&1 &)", stdin = self.toByteArray())
        }
        Log.i(TAG, "restored ${others.joinToString()}${if (SELF in list) " (service next)" else ""}")
        return Result.success(others)
    }

    /** Shell lines restoring one app from the extracted archive in [stage]. */
    private fun restoreApp(
        p: String,
        stage: String,
    ): String =
        buildString {
            val a = "$stage/apps/$p"
            append("if [ -d $a ]; then\n")
            append("  am force-stop $p || true\n")
            append("  uid=\$(stat -c %u /data/data/$p)\n")
            for ((src, dst) in listOf("data" to "/data/data/$p", "de" to "/data/user_de/0/$p")) {
                for (d in DIRS) {
                    append("  if [ -d $a/$src/$d ]; then rm -rf $dst/$d; cp -a $a/$src/$d $dst/; chown -R \$uid:\$uid $dst/$d; fi\n")
                }
                append("  [ -d $dst ] && restorecon -R $dst || true\n")
            }
            append("  if [ -d $a/ext/files ]; then mkdir -p /sdcard/Android/data/$p && rm -rf /sdcard/Android/data/$p/files && cp -a $a/ext/files /sdcard/Android/data/$p/; fi\n")
            if (p == VIPER) append("  if [ -d $a/viper ]; then mkdir -p $VIPER_PRESETS && cp -a $a/viper/. $VIPER_PRESETS/; fi\n")
            append("fi\n")
        }
}
