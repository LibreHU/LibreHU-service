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

    /** Service start: boot reason, uptime, and the end of the previous kernel log when there is one (root). */
    fun boot() {
        add(
            "Service start: uptime ${SystemClock.uptimeMillis() / 1000} s since boot ${SystemClock.elapsedRealtime() / 1000} s",
        )
        block(
            "Boot reason",
            RootShell.run("getprop sys.boot.reason; getprop ro.boot.bootreason; getprop persist.sys.boot.reason"),
        )
        block(
            "Previous kernel log (pstore, last lines)",
            RootShell.run(
                "for f in /sys/fs/pstore/console-ramoops* /proc/last_kmsg; do [ -f \$f ] && { echo \"== \$f\"; tail -n 25 \$f; }; done",
            ),
        )
    }

    companion object {
        private const val MAX_BYTES = 256 * 1024L
        private const val MAX_BLOCK_LINES = 40

        @Volatile
        private var instance: StandbyJournal? = null

        fun get(context: Context): StandbyJournal = instance ?: synchronized(this) { instance ?: StandbyJournal(context).also { instance = it } }
    }
}
