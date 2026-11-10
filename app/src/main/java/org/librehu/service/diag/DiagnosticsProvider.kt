package org.librehu.service.diag

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.librehu.service.ServiceAccess
import org.librehu.service.ServiceState
import org.librehu.service.can.CanBoxPorts
import org.librehu.service.can.CanBoxTalk
import org.librehu.service.can.CanMonitor
import org.librehu.service.power.LinkWatchdog
import org.librehu.service.power.StandbyJournal
import java.io.File
import java.io.FileNotFoundException
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What LibreHU Helper reads of the service for its report, read only, with or without root (also
 * `adb shell content read --uri content://org.librehu.service.diagnostics/report`):
 * - [REPORT]: link, MCU, vehicle, watchdog, CAN box, the stack trace of every thread, the last lines of the service's
 *   log and the last [ServiceState.recentTraffic] MCU frames;
 * - [SERVICE_LOG]: the service's log files ([ServiceLog]);
 * - [STANDBY_JOURNAL]: the standby journal (power/StandbyJournal), with the watchdog's reports;
 * - [CAN]: the CAN export (every message of the box since the start, what the head unit sent between them).
 *
 * Answered on a binder thread, each item written into a pipe by a thread of its own, with bounded waits: a stuck head
 * unit thread or main thread does not stop it, which is when it is needed. Callers as for the API ([ServiceAccess]:
 * LibreHU apps, system, shell, apps the user allowed).
 */
class DiagnosticsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "text/plain"

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("LibreHU-service diagnostics: read only")
        val c = context ?: return null
        ServiceAccess.get(c).enforceCaller()
        // The process may have been started for this provider only (no service): the log file is still read.
        ServiceLog.init(c)
        val item = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
        val produce: (Writer) -> Unit =
            when (item) {
                REPORT -> { w -> report(c, w) }
                SERVICE_LOG -> { w ->
                    ServiceLog.flush(1_000)
                    files(ServiceLog.files(), w)
                }
                STANDBY_JOURNAL -> { w -> w.write(StandbyJournal.get(c).read()) }
                CAN -> { w -> CanMonitor.writeExport(w) }
                else -> throw FileNotFoundException("$uri: one of $REPORT, $SERVICE_LOG, $STANDBY_JOURNAL, $CAN")
            }
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).bufferedWriter().use { w -> produce(w) }
            } catch (e: Exception) {
                // The reader gone (timeout, cancelled report): nothing to do.
                Log.w(TAG, "$item: $e")
            }
        }, "diag-$item").apply {
            isDaemon = true
            start()
        }
        return read
    }

    private fun files(
        list: List<File>,
        w: Writer,
    ) {
        if (list.isEmpty()) w.write("# no service log yet\n")
        for (f in list) {
            w.write("# ${f.name}\n")
            f.bufferedReader().use { it.copyTo(w) }
        }
    }

    private fun report(
        c: Context,
        w: Writer,
    ) {
        val info = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        w.write("# LibreHU-service diagnostics, $now\n")
        w.write(
            "version ${info?.versionName} (${info?.longVersionCode}), pid ${Process.myPid()}, process up " +
                "${(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000} s, awake ${SystemClock.uptimeMillis() / 1000} s " +
                "of ${SystemClock.elapsedRealtime() / 1000} s since boot\n",
        )
        val link = ServiceState.link.value
        w.write("link: ${link.state} ${link.detail} (protocol ${link.protocol}${if (link.jancar) ", Jancar" else ""})\n")
        w.write("watchdog: ${LinkWatchdog.status(c)}\n")
        w.write("MCU: ${ServiceState.mcu.value}\n")
        w.write("vehicle: ${ServiceState.vehicle.value}\n")
        val car = CanMonitor.vehicle
        w.write(
            "CAN: way ${CanBoxPorts.settings.value.kind} ${CanBoxPorts.status.value}, profile ${car.id} (${car.family.name}), " +
                "${CanMonitor.rawBytes.value} bytes received, ${CanMonitor.skipped} skipped, dialogue ${if (CanBoxTalk.enabled.value) "on" else "off"}: " +
                "${CanBoxTalk.status.value}\n",
        )
        val threads = Thread.getAllStackTraces().entries.sortedBy { it.key.name }
        w.write("\n## Threads (${threads.size})\n")
        for ((t, st) in threads) {
            w.write("\"${t.name}\" ${t.state} id=${t.id} prio=${t.priority}${if (t.isDaemon) " daemon" else ""}\n")
            for (e in st.take(MAX_FRAMES)) w.write("    at $e\n")
            if (st.size > MAX_FRAMES) w.write("    … ${st.size - MAX_FRAMES} more\n")
        }
        w.write("\n## Service log (memory, oldest first)\n")
        for (l in ServiceState.log.value.asReversed()) w.write("$l\n")
        val traffic = ServiceState.recentTraffic()
        w.write("\n## MCU frames (the last ${traffic.size}, oldest first; > received, < sent)\n")
        for (l in traffic) w.write("$l\n")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read only")

    companion object {
        private const val TAG = "LibreHU-Diag"
        private const val MAX_FRAMES = 60
        const val AUTHORITY = "org.librehu.service.diagnostics"
        const val REPORT = "report"
        const val SERVICE_LOG = "service-log"
        const val STANDBY_JOURNAL = "standby-journal"
        const val CAN = "can"
    }
}
