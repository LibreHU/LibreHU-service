package org.librehu.service.root

import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Root commands (`su` of Magisk, KernelSU, KernelSU Next, APatch, SuperSU) for what Android does not give apps: raw
 * touch events (`/dev/input`, group input), the touch driver's calibration node and Jancar's config partition. Every
 * call first tries without root.
 */
object RootShell {
    private const val TAG = "LibreHU-Root"

    /** A refusal is asked again after this delay: KernelSU grants apps from its manager, without a prompt. */
    private const val RETRY_MS = 30_000L

    @Volatile
    private var available: Boolean? = null

    @Volatile
    private var checkedAt = 0L

    /**
     * True when `su` grants root. Magisk / SuperSU show their prompt the first time; KernelSU and KernelSU Next only
     * give `su` to the apps allowed in their manager (Superuser tab), so a refusal is checked again later.
     */
    fun isAvailable(): Boolean {
        available?.let { if (it || System.currentTimeMillis() - checkedAt < RETRY_MS) return it }
        return recheck()
    }

    /** Asks `su` again now (after allowing the app in the root manager). */
    fun recheck(): Boolean {
        val ok = run("id").let { it != null && it.contains("uid=0") }
        available = ok
        checkedAt = System.currentTimeMillis()
        return ok
    }

    /** Root managers by package name. */
    private val MANAGERS =
        listOf(
            "com.rifsxd.ksunext" to "KernelSU Next",
            "me.weishu.kernelsu" to "KernelSU",
            "com.topjohnwu.magisk" to "Magisk",
            "io.github.vvb2060.magisk" to "Magisk Alpha",
            "io.github.huskydg.magisk" to "Kitsune Mask",
            "me.bmax.apatch" to "APatch",
            "eu.chainfire.supersu" to "SuperSU",
            "com.koushikdutta.superuser" to "Superuser",
        )

    /** Files left by root solutions whose manager may be hidden or renamed. */
    private val MARKERS =
        listOf(
            "/data/adb/ksud" to "KernelSU",
            "/data/adb/ksu" to "KernelSU",
            "/data/adb/magisk" to "Magisk",
            "/data/adb/ap" to "APatch",
            "/sbin/.magisk" to "Magisk",
            "/system/xbin/daemonsu" to "SuperSU",
        )

    /** Name of the installed root solution (manager app or marker files), or null when none is found. */
    fun manager(context: android.content.Context): String? {
        val pm = context.packageManager
        for ((pkg, name) in MANAGERS) {
            try {
                pm.getPackageInfo(pkg, 0)
                return name
            } catch (_: Exception) {
            }
        }
        for ((path, name) in MARKERS) if (File(path).exists()) return name
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su")
        return if (su.any { File(it).exists() }) "su" else null
    }

    /**
     * Runs [command] as root; returns its output, or null on failure / timeout. The output is read and [stdin] written
     * on their own threads: the timeout holds even when the command never ends nor closes its output.
     */
    fun run(
        command: String,
        stdin: ByteArray? = null,
        timeoutS: Long = 10,
    ): String? =
        try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader =
                Thread({
                    try {
                        val text = p.inputStream.bufferedReader().readText()
                        synchronized(out) { out.append(text) }
                    } catch (_: Exception) {
                    }
                }, "root-out").apply {
                    isDaemon = true
                    start()
                }
            Thread({
                try {
                    p.outputStream.use { if (stdin != null) it.write(stdin) }
                } catch (_: Exception) {
                }
            }, "root-in").apply {
                isDaemon = true
                start()
            }
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroy()
                reader.join(500)
                Log.w(TAG, "$command: timeout ${timeoutS}s")
                null
            } else {
                reader.join(2_000)
                val text = synchronized(out) { out.toString() }
                if (p.exitValue() == 0) {
                    text
                } else {
                    Log.w(TAG, "$command: exit ${p.exitValue()} $text")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$command: ${e.message}")
            null
        }

    /** File content, directly or as root. */
    fun read(path: String): String? =
        try {
            File(path).readText()
        } catch (_: Exception) {
            if (isAvailable()) run("cat '$path'") else null
        }

    /** Writes [text] to [path], directly or as root. */
    fun write(
        path: String,
        text: String,
    ): Boolean {
        try {
            File(path).writeText(text)
            return true
        } catch (_: Exception) {
        }
        if (!isAvailable()) return false
        return run("cat > '$path'", stdin = text.toByteArray()) != null
    }

    /** Long-running root reader of a binary file (`/dev/input/eventN`); null when root is refused. */
    fun stream(path: String): Pair<Process, InputStream>? =
        try {
            val direct = File(path)
            if (direct.canRead()) {
                null
            } else if (isAvailable()) {
                val p = ProcessBuilder("su", "-c", "cat '$path'").start()
                p.outputStream.close()
                p to p.inputStream
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream $path: ${e.message}")
            null
        }
}
