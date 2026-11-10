package org.librehu.service.diag

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The service's log in a file, so that it is still there once logcat's buffer has gone round (a busy app fills it in a
 * few minutes), after a restart or a cold boot: every line of the service's own log (link, MCU, CAN box, standby,
 * watchdog, errors of its threads). Device protected storage, two files of [MAX_BYTES] at most (`service.log`, then
 * `service.1.log`). One thread of its own writes it: no caller ever waits for the storage, and lines added before
 * [init] are kept for it. Read by LibreHU Helper through [DiagnosticsProvider].
 */
object ServiceLog {
    private const val MAX_BYTES = 512 * 1024L
    private const val QUEUE = 4_000

    /** Written to the storage (fsync) at most this often: the page cache survives the process, not a power cut. */
    private const val SYNC_MS = 2_000L

    private class Line(
        val at: Long,
        val up: Long,
        val text: String,
    )

    private val queue = LinkedBlockingQueue<Line>(QUEUE)
    private val added = AtomicLong()
    private val written = AtomicLong()
    private val dropped = AtomicInteger()

    @Volatile
    private var dir: File? = null

    /** Starts the writer (the process's first service start). */
    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        dir = context.applicationContext.createDeviceProtectedStorageContext().filesDir
        Thread(::loop, "service-log").apply {
            isDaemon = true
            start()
        }
    }

    /** A line of the service's log (any thread, never waits). */
    fun add(text: String) {
        if (queue.offer(Line(System.currentTimeMillis(), SystemClock.uptimeMillis(), text))) {
            added.incrementAndGet()
        } else {
            dropped.incrementAndGet()
        }
    }

    /** Waits, [timeoutMs] at most, until the lines added so far are in the file: before the process ends. */
    fun flush(timeoutMs: Long) {
        if (dir == null) return
        val target = added.get()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (written.get() < target && SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
    }

    /** Older file first. */
    fun files(): List<File> {
        val d = dir ?: return emptyList()
        return listOf(File(d, "service.1.log"), File(d, "service.log")).filter { it.exists() }
    }

    private fun loop() {
        val d = dir ?: return
        val file = File(d, "service.log")
        val old = File(d, "service.1.log")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
        var syncedAt = 0L
        val batch = ArrayList<Line>()
        while (true) {
            try {
                val first = queue.poll(SYNC_MS, TimeUnit.MILLISECONDS) ?: continue
                batch.clear()
                batch += first
                queue.drainTo(batch)
                if (file.length() > MAX_BYTES) {
                    old.delete()
                    file.renameTo(old)
                }
                FileOutputStream(file, true).use { out ->
                    val n = dropped.getAndSet(0)
                    if (n > 0) out.write("${time.format(Date())}  ($n lines dropped: too many at once)\n".toByteArray())
                    for (l in batch) out.write("${time.format(Date(l.at))}  up=${l.up / 1000}s  ${l.text}\n".toByteArray())
                    val now = SystemClock.uptimeMillis()
                    if (now - syncedAt >= SYNC_MS) {
                        out.fd.sync()
                        syncedAt = now
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // Storage full or gone: the lines of this batch are lost, the next ones are tried again.
            } finally {
                written.addAndGet(batch.size.toLong())
                batch.clear()
            }
        }
    }
}
