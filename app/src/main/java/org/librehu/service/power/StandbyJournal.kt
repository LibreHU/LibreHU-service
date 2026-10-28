package org.librehu.service.power

import android.content.Context
import android.os.SystemClock
import org.librehu.service.ServiceState
import org.librehu.service.root.RootShell
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standby journal: every step of a standby and of the wake up, written to a file and forced to the storage at each
 * line (`fsync`), so that what happened before the MCU cut the SoC is still there at the next start. Each line has the
 * wall clock, the uptime (stops while the SoC is suspended) and the time since boot (does not).
 *
 * Root snapshots add what the kernel and Android say: wakelocks that keep the SoC awake, suspend / resume lines of
 * `dmesg`, and after a cold boot the end of the previous kernel log (pstore), the boot reason.
 *
 * In the device protected storage (readable before the first unlock), two files of [MAX_BYTES] at most.
 */
class StandbyJournal private constructor(
    context: Context,
) {
    private val dir = context.applicationContext.createDeviceProtectedStorageContext().filesDir
    private val file = File(dir, "standby.log")
    private val old = File(dir, "standby.1.log")
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        ServiceState.addLog(line)
        val up = SystemClock.uptimeMillis() / 1000
        val el = SystemClock.elapsedRealtime() / 1000
        val text = "${time.format(Date())}  up=${up}s boot=${el}s  $line\n"
        try {
            if (file.length() > MAX_BYTES) {
                old.delete()
                file.renameTo(old)
            }
            FileOutputStream(file, true).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
        } catch (_: Exception) {
        }
    }

    /** Several lines under a title (a snapshot), each one indented. */
    fun block(
        title: String,
        text: String?,
    ) {
        val body =
            text
                ?.lines()
                ?.map { it.trimEnd() }
                ?.filter { it.isNotEmpty() }
                ?.takeLast(MAX_BLOCK_LINES)
                .orEmpty()
        add(if (body.isEmpty()) "$title: (nothing)" else "$title:\n" + body.joinToString("\n") { "    $it" })
    }

    /** The whole journal, oldest first. */
    @Synchronized
    fun read(): String = (if (old.exists()) old.readText() else "") + (if (file.exists()) file.readText() else "")

    /** The last [n] lines, newest last (for the screen). */
    fun tail(n: Int): List<String> = read().lines().filter { it.isNotBlank() && !it.startsWith("    ") }.takeLast(n)

    @Synchronized
    fun clear() {
        old.delete()
        file.delete()
    }

    /**
     * What may keep the SoC awake: Android's wakelocks and the kernel's active wakeup sources (root). Off the main
     * thread.
     */
    fun wakelocks(tag: String) {
        block(
            "$tag: Android wakelocks",
            RootShell.run("dumpsys power | grep -iE 'mWakefulness=|PARTIAL_WAKE_LOCK|FULL_WAKE_LOCK|SCREEN_(BRIGHT|DIM)_WAKE_LOCK'"),
        )
        block("$tag: kernel wake_lock", RootShell.run("cat /sys/power/wake_lock"))
        // Sources held right now (active_since > 0, 6th column on 4.9), with their total time.
        val active =
            RootShell
                .run("cat /sys/kernel/debug/wakeup_sources")
                ?.lines()
                ?.drop(1)
                ?.map { it.trim().split(Regex("\\s+")) }
                ?.filter { it.size >= 7 && (it[5].toLongOrNull() ?: 0L) > 0L }
                ?.joinToString("\n") { "${it[0]} active_since=${it[5]}ms total=${it[6]}ms" }
        block("$tag: active wakeup sources", active)
    }

    /** Suspend and resume lines of the kernel log (root). */
    fun kernelPm(tag: String) {
        block(
            "$tag: dmesg (suspend / resume)",
            RootShell.run(
                "dmesg | grep -iE 'PM: |suspend|resume|Freezing|Restarting tasks|wakeup|Wakeup|wake up|mtk_pm|spm' | tail -n $MAX_BLOCK_LINES",
            ),
        )
        block("$tag: wakeup_count / suspend_stats", RootShell.run("cat /sys/power/wakeup_count; cat /sys/kernel/debug/suspend_stats 2>/dev/null | head -n 12"))
    }

    /**
     * Service process start: how long after the boot (a late start without a boot in between is the process restarted
     * by Android: killed or crashed), the boot reason, what Android filed since shortly before this boot (crashes,
     * ANR, system restarts, kernel logs of the dropbox), MediaTek's exception reports, the pstore (root).
     */
    fun boot() {
        val boot = SystemClock.elapsedRealtime()
        add(
            "Service process start (pid ${android.os.Process.myPid()}), ${boot / 1000} s after the boot" +
                if (boot > LATE_START_MS) ": the process was restarted (killed or crashed) unless just installed / updated" else "",
        )
        block(
            "Boot reason",
            RootShell.run("getprop sys.boot.reason; getprop ro.boot.bootreason; getprop persist.sys.boot.reason"),
        )
        dropbox(System.currentTimeMillis() - boot - DROPBOX_BEFORE_BOOT_MS)
        block(
            "MediaTek exception reports (newest first)",
            RootShell.run("ls -lt /data/aee_exp /data/vendor/aee_exp /data/tombstones /data/anr 2>/dev/null | head -n 20"),
        )
        // console-ramoops of this kernel only holds the first 0.15 s (it stops at "bootconsole disabled"): the
        // panic records (dmesg-ramoops) are what matters.
        block(
            "pstore",
            RootShell.run(
                "ls -l /sys/fs/pstore; for f in /sys/fs/pstore/dmesg-ramoops* /sys/fs/pstore/pmsg-ramoops*; do [ -f \$f ] && { echo \"== \$f\"; tail -n 30 \$f; }; done",
            ),
        )
    }

    /**
     * Dropbox entries written since [sinceMillis] (`TAG@millis.txt[.gz]` in /data/system/dropbox): the list, then the
     * start of each crash, ANR, watchdog, restart or kernel log entry (copied out as root, gunzipped here).
     */
    private fun dropbox(sinceMillis: Long) {
        val box = "/data/system/dropbox"
        val names =
            RootShell
                .run("ls $box")
                ?.lines()
                ?.map { it.trim() }
                ?.filter { '@' in it }
                .orEmpty()
        val entries =
            names
                .mapNotNull { n ->
                    n.substringAfter('@').substringBefore('.').toLongOrNull()?.let { t -> Triple(n.substringBefore('@'), t, n) }
                }.filter { it.second >= sinceMillis }
                .sortedBy { it.second }
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        block("Android dropbox since shortly before this boot", entries.joinToString("\n") { "${stamp.format(Date(it.second))} ${it.first}" })
        val tmp = File(dir, "dropbox.tmp")
        for ((tag, t, name) in entries.filter { e -> DROPBOX_TAGS.any { e.first.startsWith(it) } }.takeLast(MAX_DROPBOX_ENTRIES)) {
            tmp.delete()
            RootShell.run("cp '$box/$name' '${tmp.absolutePath}' && chmod 644 '${tmp.absolutePath}'")
            val text =
                runCatching {
                    if (name.endsWith(".gz")) {
                        java.util.zip.GZIPInputStream(tmp.inputStream()).bufferedReader().use { it.readText() }
                    } else {
                        tmp.readText()
                    }
                }.getOrNull()
            if (tag == "SYSTEM_LAST_KMSG" && text != null) {
                lastKmsg(stamp.format(Date(t)), text)
            } else {
                block("dropbox $tag at ${stamp.format(Date(t))}", text?.lines()?.take(MAX_BLOCK_LINES)?.joinToString("\n"))
            }
        }
        tmp.delete()
    }

    /**
     * The kernel log of the boot before this one: why it ended (MediaTek's hang_detect, a panic, the watchdog, a plain
     * reboot), how many "wait VSYNC timeout" (the display pipeline with the screen off), and its last lines without them.
     */
    private fun lastKmsg(
        at: String,
        text: String,
    ) {
        val lines = text.lines()
        val vsync = lines.count { "wait VSYNC timeout" in it }
        val verdict =
            when {
                lines.any { "hang_detect" in it && ("exception reboot" in it || "counts down -1" in it) } ->
                    "ended by MediaTek's hang_detect (Android blocked: its watchdog stopped)"
                lines.any { "Kernel panic" in it } -> "ended by a kernel panic"
                lines.any { "exception reboot" in it } -> "ended by an exception reboot"
                lines.any { "reboot: Restarting system" in it || "Restarting system" in it } -> "ended by a normal reboot"
                lines.any { "reboot: Power down" in it || "Power down" in it } -> "ended by a power off"
                else -> "no end in the log (power cut, e.g. the MCU)"
            }
        add("Previous boot $verdict${if (vsync > 0) ", $vsync \"wait VSYNC timeout\" (display pipeline with the screen off)" else ""} (dropbox SYSTEM_LAST_KMSG at $at)")
        val kept =
            lines
                .filter { "wait VSYNC timeout" !in it && !it.contains(Regex("hang_detect1\\]\\[ *\\d+\\] ")) }
                .let { l -> l.indexOfFirst { "early pstore console" in it }.takeIf { it > 0 }?.let { l.take(it) } ?: l }
        block("Previous boot: last kernel lines", kept.takeLast(MAX_BLOCK_LINES).joinToString("\n"))
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        private const val MAX_BLOCK_LINES = 40
        private const val LATE_START_MS = 180_000L
        private const val DROPBOX_BEFORE_BOOT_MS = 15 * 60_000L
        private const val MAX_DROPBOX_ENTRIES = 6

        /** Dropbox tags worth reading after a restart: crashes, ANR, watchdog, restarts, kernel logs. */
        private val DROPBOX_TAGS =
            listOf(
                "data_app_crash",
                "data_app_native_crash",
                "data_app_anr",
                "system_app_crash",
                "system_app_native_crash",
                "system_app_anr",
                "system_server_crash",
                "system_server_native_crash",
                "system_server_watchdog",
                "system_server_anr",
                "SYSTEM_RESTART",
                "SYSTEM_TOMBSTONE",
                "SYSTEM_LAST_KMSG",
                "SYSTEM_FSCK",
            )

        @Volatile
        private var instance: StandbyJournal? = null

        fun get(context: Context): StandbyJournal = instance ?: synchronized(this) { instance ?: StandbyJournal(context).also { instance = it } }
    }
}
