package org.librehu.service.bridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.service.root.RootShell
import org.librehu.service.time.TimeController
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the phone app does on the head unit beyond the settings pages: its apps (listed with their icons, started),
 * reboots (Android, recovery, fastboot), the system (temperatures of the SoC and of the sensors, CPU, memory,
 * storage), GPS (fix and satellites, never the position), files (internal storage and USB keys / SD cards; the whole
 * file system as root only when allowed on the head unit), logs, and Android's settings (every key, as root).
 */
object BridgeTools {
    /** Largest piece of a file read or written in one request (the channel's frames go up to 4 MB, base64 included). */
    const val CHUNK = 512 * 1024

    // --- Apps ----------------------------------------------------------------------------------------------------

    /** Apps with an icon in the launcher: package, name, system or not, icon (PNG, base64). */
    fun apps(context: Context): JSONArray {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        pm
            .queryIntentActivities(intent, 0)
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo.applicationInfo
                Triple(ri, ri.loadLabel(pm).toString(), ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.sortedBy { it.second.lowercase() }
            .forEach { (ri, label, system) ->
                out.put(
                    JSONObject()
                        .put("pkg", ri.activityInfo.packageName)
                        .put("label", label)
                        .put("system", system)
                        .put("icon", runCatching { icon(ri.loadIcon(pm)) }.getOrDefault("")),
                )
            }
        return out
    }

    private fun icon(d: android.graphics.drawable.Drawable): String {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun launch(
        context: Context,
        pkg: String,
    ): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return true
    }

    // --- Reboot --------------------------------------------------------------------------------------------------

    /** Reboots in [mode] (`normal`, `recovery`, `bootloader` = fastboot) half a second after the answer. Root. */
    fun reboot(mode: String): Boolean {
        val cmd =
            when (mode) {
                "normal" -> "svc power reboot || reboot"
                "recovery" -> "svc power reboot recovery || reboot recovery"
                "bootloader" -> "reboot bootloader"
                else -> return false
            }
        if (!RootShell.isAvailable()) return false
        Thread({
            Thread.sleep(500)
            RootShell.run(cmd, timeoutS = 20)
        }, "bridge-reboot").start()
        return true
    }

    // --- System --------------------------------------------------------------------------------------------------

    /** Temperatures (thermal zones: SoC, PMIC, battery…, °C), CPU frequencies and load, memory, storage, uptime. */
    fun system(context: Context): JSONObject {
        val temps = JSONArray()
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }?.forEach { z ->
            val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: return@forEach
            val raw = runCatching { File(z, "temp").readText().trim().toDouble() }.getOrNull() ?: return@forEach
            val c = if (raw > 1000 || raw < -1000) raw / 1000.0 else raw
            if (c > -40 && c < 200) temps.put(JSONObject().put("name", type).put("c", Math.round(c * 10) / 10.0))
        }
        val freqs = JSONArray()
        var cpu = 0
        while (true) {
            val f = File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_cur_freq")
            if (!File("/sys/devices/system/cpu/cpu$cpu").exists()) break
            freqs.put(runCatching { f.readText().trim().toLong() / 1000 }.getOrDefault(0L))
            cpu++
        }
        val load = runCatching { File("/proc/loadavg").readText().trim().split(' ').take(3).map { it.toDouble() } }.getOrDefault(emptyList())
        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        fun storage(path: String) =
            runCatching {
                val s = StatFs(path)
                JSONObject().put("total", s.totalBytes).put("free", s.availableBytes)
            }.getOrNull()
        return JSONObject()
            .put("temps", temps)
            .put("cpuMhz", freqs)
            .put("load", JSONArray(load))
            .put("memTotal", mem.totalMem)
            .put("memFree", mem.availMem)
            .put("uptimeS", SystemClock.elapsedRealtime() / 1000)
            .apply {
                storage("/data")?.let { put("data", it) }
                storage("/sdcard")?.let { put("sdcard", it) }
            }
    }

    // --- GPS -----------------------------------------------------------------------------------------------------

    private val CONSTELLATIONS = mapOf(1 to "GPS", 2 to "SBAS", 3 to "GLONASS", 4 to "QZSS", 5 to "BeiDou", 6 to "Galileo", 7 to "IRNSS")

    /** Fix and satellites, as the launcher shows them; the position itself is never sent. */
    fun gps(context: Context): JSONObject {
        val g = TimeController.get(context).gps.value
        val by = JSONArray()
        g.satellites.groupBy { it.constellation }.toSortedMap().forEach { (c, list) ->
            by.put(JSONObject().put("name", CONSTELLATIONS[c] ?: "?").put("used", list.count { it.usedInFix }).put("visible", list.size))
        }
        val used = g.satellites.filter { it.usedInFix }
        val fix = g.fix
        return JSONObject()
            .put("enabled", g.enabled)
            .put("listening", g.listening)
            .put("visible", g.satellites.size)
            .put("used", used.size)
            .put("cn0", if (used.isEmpty()) 0 else Math.round(used.map { it.cn0 }.average()))
            .put("constellations", by)
            .apply {
                if (fix != null) {
                    val age = (System.currentTimeMillis() - fix.time) / 1000
                    put("fix", age < 10)
                    put("ageS", age)
                    if (fix.hasAccuracy()) put("accuracyM", Math.round(fix.accuracy))
                    if (fix.hasSpeed()) put("speedKmh", Math.round(fix.speed * 3.6))
                    if (fix.hasAltitude()) put("altitudeM", Math.round(fix.altitude))
                } else {
                    put("fix", false)
                }
                if (g.firstFixMs >= 0) put("firstFixMs", g.firstFixMs)
            }
    }

    // --- Files ---------------------------------------------------------------------------------------------------

    /** Folders the phone may open: internal storage and removable volumes; `/` too when root files are allowed. */
    fun roots(root: Boolean): JSONArray {
        val out = JSONArray().put(JSONObject().put("path", "/sdcard").put("kind", "internal"))
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.forEach {
            out.put(JSONObject().put("path", it.path).put("kind", "removable"))
        }
        if (root) out.put(JSONObject().put("path", "/").put("kind", "system"))
        return out
    }

    private val ALLOWED = listOf("/sdcard", "/storage/emulated/0", "/storage", "/mnt/media_rw", "/mnt/runtime")

    /** [path] checked: absolute, no `..`; inside the storage unless [root]. */
    fun check(
        path: String,
        root: Boolean,
    ): String {
        if (!path.startsWith("/") || path.split('/').any { it == ".." } || path.contains('\u0000')) throw IllegalArgumentException("bad path")
        val p = path.trimEnd('/').ifEmpty { "/" }
        if (root) return p
        val canonical = runCatching { File(p).canonicalPath }.getOrDefault(p)
        val ok = ALLOWED.any { a -> canonical == a || canonical.startsWith("$a/") } && canonical != "/storage" && canonical != "/storage/emulated"
        if (!ok) throw SecurityException("outside the storage (root files are off on the head unit)")
        return p
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Entries of a folder: name, folder or not, size, modification time (s). */
    fun list(
        path: String,
        root: Boolean,
    ): JSONArray {
        val p = check(path, root)
        val out = JSONArray()
        val files = File(p).listFiles()
        if (files != null) {
            files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach {
                out.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", it.length()).put("mtime", it.lastModified() / 1000))
            }
            return out
        }
        if (!RootShell.isAvailable()) throw IllegalStateException("cannot read $p")
        val text =
            RootShell.run(
                "cd ${q(p)} && for f in * .*; do [ \"\$f\" = . ] || [ \"\$f\" = .. ] || { [ -e \"\$f\" ] || [ -L \"\$f\" ]; } && stat -c '%F|%s|%Y|%n' -- \"\$f\"; done",
                timeoutS = 30,
            ) ?: throw IllegalStateException("cannot read $p")
        text
            .lines()
            .mapNotNull { l ->
                val parts = l.split('|', limit = 4)
                if (parts.size < 4) null else parts
            }.sortedWith(compareBy({ !it[0].contains("directory") }, { it[3].lowercase() }))
            .forEach { (type, size, mtime, name) ->
                out.put(JSONObject().put("name", name).put("dir", type.contains("directory")).put("size", size.toLongOrNull() ?: 0).put("mtime", mtime.toLongOrNull() ?: 0))
            }
        return out
    }

    /** [length] bytes (at most [CHUNK]) from [offset]: `data` (base64), `size` of the file, `eof`. */
    fun read(
        path: String,
        offset: Long,
        length: Int,
        root: Boolean,
    ): JSONObject {
        val p = check(path, root)
        val n = length.coerceIn(1, CHUNK)
        val f = File(p)
        if (f.canRead()) {
            RandomAccessFile(f, "r").use { r ->
                val size = r.length()
                r.seek(offset)
                val buf = ByteArray(minOf(n.toLong(), (size - offset).coerceAtLeast(0)).toInt())
                r.readFully(buf)
                return JSONObject().put("data", Base64.encodeToString(buf, Base64.NO_WRAP)).put("size", size).put("eof", offset + buf.size >= size)
            }
        }
        if (!root) throw SecurityException("cannot read $p")
        val size = RootShell.run("stat -c %s ${q(p)}", timeoutS = 10)?.trim()?.toLongOrNull() ?: throw IllegalStateException("cannot read $p")
        if (offset % 4096 != 0L || n % 4096 != 0) throw IllegalArgumentException("offset and length by 4096 as root")
        val b64 =
            RootShell.run("dd if=${q(p)} bs=4096 skip=${offset / 4096} count=${n / 4096} 2>/dev/null | base64", timeoutS = 60)
                ?: throw IllegalStateException("cannot read $p")
        val data = Base64.decode(b64.replace("\n", ""), Base64.DEFAULT)
        return JSONObject().put("data", Base64.encodeToString(data, Base64.NO_WRAP)).put("size", size).put("eof", offset + data.size >= size)
    }

    /** Writes [data] (base64) at [offset]: 0 starts the file again; the next pieces follow. */
    fun write(
        path: String,
        offset: Long,
        data: String,
        root: Boolean,
    ): JSONObject {
        val p = check(path, root)
        val bytes = Base64.decode(data, Base64.DEFAULT)
        if (bytes.size > CHUNK) throw IllegalArgumentException("piece too large")
        val f = File(p)
        val direct =
            runCatching {
                RandomAccessFile(f, "rw").use { r ->
                    if (offset == 0L) r.setLength(0)
                    r.seek(offset)
                    r.write(bytes)
                }
            }.isSuccess
        if (!direct) {
            if (!root) throw SecurityException("cannot write $p")
            val redirect = if (offset == 0L) ">" else ">>"
            RootShell.run("base64 -d $redirect ${q(p)}", stdin = Base64.encode(bytes, Base64.DEFAULT), timeoutS = 60)
                ?: throw IllegalStateException("cannot write $p")
        }
        return JSONObject().put("written", bytes.size)
    }

    fun delete(
        path: String,
        root: Boolean,
    ): Boolean {
        val p = check(path, root)
        if (p == "/" || p == "/sdcard") throw IllegalArgumentException("refused")
        val f = File(p)
        if (f.deleteRecursively() && !f.exists()) return true
        if (!root) throw SecurityException("cannot delete $p")
        return RootShell.run("rm -rf ${q(p)}", timeoutS = 60) != null
    }

    fun mkdir(
        path: String,
        root: Boolean,
    ): Boolean {
        val p = check(path, root)
        if (File(p).mkdirs() || File(p).isDirectory) return true
        if (!root) throw SecurityException("cannot create $p")
        return RootShell.run("mkdir -p ${q(p)}") != null
    }

    fun rename(
        from: String,
        to: String,
        root: Boolean,
    ): Boolean {
        val a = check(from, root)
        val b = check(to, root)
        if (File(a).renameTo(File(b))) return true
        if (!root) throw SecurityException("cannot rename $a")
        return RootShell.run("mv ${q(a)} ${q(b)}") != null
    }

    // --- Logs ----------------------------------------------------------------------------------------------------

    /**
     * Writes the [kind] log (`logcat` every buffer, `kernel`, `librehu` = the LibreHU lines of logcat) to
     * /sdcard/LibreHU/logs and gives its path, to fetch with the files requests.
     */
    fun logs(kind: String): JSONObject {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val path = "/sdcard/LibreHU/logs/$kind-$stamp.txt"
        val cmd =
            when (kind) {
                "logcat" -> "logcat -b all -d -v threadtime"
                "kernel" -> "dmesg"
                "librehu" -> "logcat -d -v threadtime | grep -i librehu"
                else -> throw IllegalArgumentException("unknown log $kind")
            }
        RootShell.run("mkdir -p /sdcard/LibreHU/logs && ($cmd) > ${q(path)} 2>&1", timeoutS = 120) ?: throw IllegalStateException("root needed")
        return JSONObject().put("path", path).put("size", File(path).length())
    }

    // --- Android settings ----------------------------------------------------------------------------------------

    private val NAMESPACES = setOf("system", "secure", "global")
    private val KEY = Regex("[A-Za-z0-9_.:-]{1,128}")

    /** Every key of [ns] (`system`, `secure`, `global`) with its value. Root. */
    fun settingsList(ns: String): JSONArray {
        if (ns !in NAMESPACES) throw IllegalArgumentException("namespace")
        val text = RootShell.run("settings list $ns", timeoutS = 20) ?: throw IllegalStateException("root needed")
        val out = JSONArray()
        text.lines().filter { it.contains('=') }.sortedBy { it.lowercase() }.forEach { l ->
            out.put(JSONObject().put("key", l.substringBefore('=')).put("value", l.substringAfter('=')))
        }
        return out
    }

    fun settingsPut(
        context: Context,
        ns: String,
        key: String,
        value: String,
    ): Boolean {
        if (ns !in NAMESPACES || !KEY.matches(key) || value.length > 4096 || value.contains('\n')) throw IllegalArgumentException("bad setting")
        val direct =
            runCatching {
                when (ns) {
                    "system" -> Settings.System.putString(context.contentResolver, key, value)
                    "secure" -> Settings.Secure.putString(context.contentResolver, key, value)
                    else -> Settings.Global.putString(context.contentResolver, key, value)
                }
            }.getOrDefault(false)
        return direct || RootShell.run("settings put $ns $key ${q(value)}") != null
    }

    fun settingsDelete(
        ns: String,
        key: String,
    ): Boolean {
        if (ns !in NAMESPACES || !KEY.matches(key)) throw IllegalArgumentException("bad setting")
        return RootShell.run("settings delete $ns $key") != null
    }
}
