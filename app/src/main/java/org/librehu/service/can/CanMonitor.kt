package org.librehu.service.can

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.can.CanValue
import org.librehu.core.can.CanVehicle
import org.librehu.core.can.CanVehicles
import org.librehu.core.can.CanboxFrame
import org.librehu.core.can.HiworldParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Last message of one command, with what changed since the previous one. */
data class CanEntry(
    val cmd: Int,
    val data: ByteArray,
    val checksumOk: Boolean,
    val count: Int,
    val lastAt: Long,
    /** Indexes of the data bytes that differ from the previous message of the same command. */
    val changed: Set<Int>,
    val text: String?,
)

data class CanLine(
    val at: Long,
    val frame: CanboxFrame,
)

/**
 * CAN box traffic relayed by the MCU (frames `10`): raw chunks rebuilt into Hiworld messages ([HiworldParser]),
 * per command (last value, count, changed bytes) and in order (last [MAX_LINES]). Fed by the service.
 */
object CanMonitor {
    private const val MAX_LINES = 400

    private val _byCmd = MutableStateFlow<Map<Int, CanEntry>>(emptyMap())
    val byCmd: StateFlow<Map<Int, CanEntry>> = _byCmd.asStateFlow()

    private val _lines = MutableStateFlow<List<CanLine>>(emptyList())
    val lines: StateFlow<List<CanLine>> = _lines.asStateFlow()

    private val _rawBytes = MutableStateFlow(0L)

    /** Bytes received from the box (chunks of frames 10). */
    val rawBytes: StateFlow<Long> = _rawBytes.asStateFlow()

    @Volatile
    var paused = false

    /** Car profile decoding the messages (set by [CanVehicleStore]); a change clears the decoded values. */
    @Volatile
    var vehicle: CanVehicle = CanVehicles.RENAULT_CLIO3_HIWORLD
        set(value) {
            field = value
            _values.value = emptyMap()
        }

    private val _values = MutableStateFlow<Map<String, CanValue>>(emptyMap())

    /** Last value of each decoded signal, keyed by message and signal. */
    val values: StateFlow<Map<String, CanValue>> = _values.asStateFlow()

    private val parser = HiworldParser(::onFrame)

    /** All messages since the start, for the export (bounded). */
    private val history = ArrayDeque<CanLine>()

    @Synchronized
    fun feed(bytes: ByteArray) {
        _rawBytes.value += bytes.size
        parser.feed(bytes)
    }

    val skipped: Long get() = parser.skipped

    private fun onFrame(f: CanboxFrame) {
        val now = System.currentTimeMillis()
        val line = CanLine(now, f)
        history.addLast(line)
        while (history.size > 20_000) history.removeFirst()
        val decoded = vehicle.decode(f)
        if (decoded.isNotEmpty()) _values.value = _values.value + decoded.associateBy { "%02X.%s".format(f.cmd, it.key) }
        if (paused) return
        val old = _byCmd.value[f.cmd]
        val changed =
            if (old == null) {
                emptySet()
            } else {
                (0 until maxOf(old.data.size, f.data.size)).filter { i -> old.data.getOrNull(i) != f.data.getOrNull(i) }.toSet()
            }
        _byCmd.value = _byCmd.value +
            (f.cmd to CanEntry(f.cmd, f.data, f.checksumOk, (old?.count ?: 0) + 1, now, changed, vehicle.describe(f)))
        _lines.value = (listOf(line) + _lines.value).take(MAX_LINES)
    }

    @Synchronized
    fun clear() {
        _byCmd.value = emptyMap()
        _lines.value = emptyList()
        _values.value = emptyMap()
        history.clear()
    }

    /** Writes every message received since the start to a text file; returns it. */
    @Synchronized
    fun export(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val file = File(dir, "can-$stamp.txt")
        file.bufferedWriter().use { w ->
            w.write("# LibreHU CAN box log (Hiworld framing), $stamp, ${_rawBytes.value} bytes, $skipped skipped\n")
            w.write("# Car profile: ${vehicle.name} (${vehicle.id})\n")
            for (l in history) {
                w.write(time.format(Date(l.at)))
                w.write("  ")
                w.write(l.frame.toString())
                vehicle.describe(l.frame)?.let { w.write("  # $it") }
                w.write("\n")
            }
        }
        return file
    }
}
