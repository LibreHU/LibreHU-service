package org.librehu.service.root

import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Root commands (`su`, Magisk) for what Android does not give apps: raw touch events (`/dev/input`, group input),
 * the touch driver's calibration node and Jancar's config partition. Every call first tries without root.
 */
object RootShell {
    private const val TAG = "LibreHU-Root"

    @Volatile
    private var available: Boolean? = null

    /** True when `su` grants root (asked once; Magisk shows its prompt the first time). */
    fun isAvailable(): Boolean {
        available?.let { return it }
        val ok = run("id").let { it != null && it.contains("uid=0") }
        available = ok
        return ok
    }

    /** Runs [command] as root; returns its output, or null on failure / timeout. */
    fun run(
        command: String,
        stdin: ByteArray? = null,
        timeoutS: Long = 10,
    ): String? =
        try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            if (stdin != null) p.outputStream.use { it.write(stdin) } else p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) {
                p.destroy()
                null
            } else if (p.exitValue() == 0) {
                out
            } else {
                Log.w(TAG, "$command: exit ${p.exitValue()} $out")
                null
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
