package org.librehu.service.can

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.can.CanValue
import org.librehu.core.can.CanVehicle
import org.librehu.core.can.CanVehicles
import org.librehu.core.can.CanboxFrame
import org.librehu.core.can.CanBoxParser
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
 *
 * Locks: [lock] guards the parser, the history and the decoded values, and nothing is called out while it is held;
 * [frameListener] runs after it ([feed]), and what was sent to the box has its own ([recordSent]). Until 10/11/2026 the
 * listener ran under the monitor's lock, the box link ([CanBoxTalk]) noted its messages under it from its own thread,
 * and both threads could wait for each other for good: the service froze while driving (volume, keys, headlights).
 */
object CanMonitor {
    private const val TAG = "LibreHU-CAN"
    private const val MAX_LINES = 400

    /** An export waits this long at most for [lock]. */
    private const val LOCK_WAIT_MS = 3_000L

    /** Parser, [history], decoded values; held for no call out of this object. */
    private val lock = Any()

    /** Hands the messages to [frameListener] one at a time and in order; taken by [feed] only, with no other lock held. */
    private val dispatch = Any()

    /** Messages of the [feed] under way, for [frameListener] once [lock] is released. */
    private val parsed = ArrayList<CanboxFrame>()

    private val _byCmd = MutableStateFlow<Map<Int, CanEntry>>(emptyMap())
    val byCmd: StateFlow<Map<Int, CanEntry>> = _byCmd.asStateFlow()

    private val _lines = MutableStateFlow<List<CanLine>>(emptyList())
    val lines: StateFlow<List<CanLine>> = _lines.asStateFlow()

    private val _rawBytes = MutableStateFlow(0L)

    /** Bytes received from the box (chunks of frames 10). */
    val rawBytes: StateFlow<Long> = _rawBytes.asStateFlow()

    @Volatile
    var paused = false

    @Volatile
    private var current: CanVehicle = CanVehicles.GENERIC_HIWORLD

    /** Car profile decoding the messages (set by [CanVehicleStore]); a change clears the decoded values. */
    var vehicle: CanVehicle
        get() = current
        set(value) {
            synchronized(lock) {
                // Another box family frames its messages its own way.
                if (value.family != current.family) parser = value.family.parser(::onFrame)
                current = value
                _values.value = emptyMap()
                _roles.value = emptyMap()
            }
        }

    private val _values = MutableStateFlow<Map<String, CanValue>>(emptyMap())

    /** Last value of each decoded signal, keyed by message and signal. */
    val values: StateFlow<Map<String, CanValue>> = _values.asStateFlow()

    private val _roles = MutableStateFlow<Map<String, CanValue>>(emptyMap())

    /**
     * Last value of each role (org.librehu.core.can.CanRoles: `steering.angle`, `radar.rear.left`, `door.front_left`…),
     * whatever the car profile: what LibreHU's features read.
     */
    val roles: StateFlow<Map<String, CanValue>> = _roles.asStateFlow()

    private var parser: CanBoxParser = current.family.parser(::onFrame)

    /**
     * Every message once decoded (the box link, then the steering wheel keys, see CanWheelKeys), one at a time and in
     * order, on the thread that feeds them (the head unit's for the MCU, the port's otherwise), with no lock of this
     * object held.
     */
    @Volatile
    var frameListener: ((CanboxFrame) -> Unit)? = null

    /** All messages since the start, for the export (bounded). */
    private val history = ArrayDeque<CanLine>()

    /** What the service sent to the box (acknowledgements, profile messages, box speed), for the export. */
    private class SentLine(
        val at: Long,
        val hex: String,
        val what: String,
    )

    private val sent = ArrayDeque<SentLine>()

    /** Lock of [sent] alone: nothing else is done while it is held. */
    private val sentLock = Any()

    /** A message sent to the box ([CanBoxTalk], any thread): in the export, between the box's messages, marked →. */
    fun recordSent(
        hex: String,
        what: String,
    ) {
        val line = SentLine(System.currentTimeMillis(), hex, what)
        synchronized(sentLock) {
            sent.addLast(line)
            while (sent.size > 20_000) sent.removeFirst()
        }
    }

    /** Listener errors logged (the first ones only: one per message otherwise). */
    private var listenerErrors = 0

    /**
     * Bytes from the box: parsed and decoded under [lock], then each message handed to [frameListener] after it. An
     * error of the listener does not stop the other messages; the first one is thrown at the end (a port that fails
     * to answer the box is opened again by its loop, see CanBoxPorts).
     */
    fun feed(bytes: ByteArray) {
        synchronized(dispatch) {
            val frames =
                synchronized(lock) {
                    _rawBytes.value += bytes.size
                    parser.feed(bytes)
                    if (parsed.isEmpty()) return
                    parsed.toList().also { parsed.clear() }
                }
            val l = frameListener ?: return
            var error: Exception? = null
            for (f in frames) {
                try {
                    l(f)
                } catch (e: Exception) {
                    if (listenerErrors++ < 20) Log.w(TAG, "message 0x%02X: %s".format(f.cmd, e))
                    if (error == null) error = e
                }
            }
            error?.let { throw it }
        }
    }

    val skipped: Long get() = parser.skipped

    /** A message rebuilt by the parser, under [lock]. */
    private fun onFrame(f: CanboxFrame) {
        val now = System.currentTimeMillis()
        val line = CanLine(now, f)
        history.addLast(line)
        while (history.size > 20_000) history.removeFirst()
        parsed += f
        val car = current
        val decoded = car.decode(f) { r -> _roles.value[r]?.number ?: _roles.value[r]?.flag?.let { if (it) 1.0 else 0.0 } }
        // Roles first: a feature collecting [values] reads [roles] of the same message.
        val byRole = decoded.filter { it.role != null }
        if (byRole.isNotEmpty()) _roles.value = _roles.value + byRole.associateBy { it.role!! }
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
            (f.cmd to CanEntry(f.cmd, f.data, f.checksumOk, (old?.count ?: 0) + 1, now, changed, car.describe(f)))
        _lines.value = (listOf(line) + _lines.value).take(MAX_LINES)
    }

    fun clear() {
        synchronized(lock) {
            _byCmd.value = emptyMap()
            _lines.value = emptyList()
            _values.value = emptyMap()
            _roles.value = emptyMap()
            history.clear()
        }
        synchronized(sentLock) { sent.clear() }
    }

    /** Writes every message received since the start to a text file; returns it. */
    fun export(context: Context): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "can-$stamp.txt")
        file.bufferedWriter().use { writeExport(it, stamp) }
        return file
    }

    /** The messages, the bytes received and the profile, copied under [lock]: [LOCK_WAIT_MS] at most, else null. */
    private fun snapshot(): Triple<List<CanLine>, Long, CanVehicle>? {
        val copy =
            java.util.concurrent.FutureTask<Triple<List<CanLine>, Long, CanVehicle>> {
                synchronized(lock) { Triple(history.toList(), _rawBytes.value, current) }
            }
        Thread(copy, "can-export").apply {
            isDaemon = true
            start()
        }
        return try {
            copy.get(LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The export's text: a header (profile, dialogue with the box), then every message since the start with, between
     * them, what the head unit sent (→). Copied under the locks, written without them: the box's messages go on
     * meanwhile. A thread holding the monitor's lock for more than [LOCK_WAIT_MS] is written instead of waited for
     * (LibreHU Helper's report, diag/DiagnosticsProvider).
     */
    fun writeExport(
        w: java.io.Writer,
        stamp: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()),
    ) {
        val snap = snapshot()
        if (snap == null) {
            w.write("# LibreHU CAN box log, $stamp: the monitor's lock held by another thread for more than ${LOCK_WAIT_MS / 1000} s (stuck: see the threads)\n")
            return
        }
        val (messages, rawBytes, car) = snap
        val out = synchronized(sentLock) { sent.toList() }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        w.write("# LibreHU CAN box log (Hiworld framing), $stamp, $rawBytes bytes, $skipped skipped\n")
        w.write("# Car profile: ${car.name} (${car.id})\n")
        val talk = CanBoxTalk.status.value
        w.write(
            "# Dialogue with the box: " +
                (if (!talk.running) "not running" else "${talk.acks} acknowledgements sent") +
                talk.sent.entries.joinToString("") { (k, v) -> ", $k ${v.first} at ${time.format(Date(v.second))}" } +
                ", acknowledged by the box: " +
                (if (talk.acked.isEmpty()) "nothing" else talk.acked.joinToString(" ") { "0x%02X".format(it) }) +
                (if (talk.requests > 0) ", requests answered by the box: ${talk.answers} of ${talk.requests}" else "") + "\n",
        )
        w.write("# Lines with → : sent by the head unit to the box\n")
        var i = 0
        for (l in messages) {
            while (i < out.size && out[i].at <= l.at) writeSent(w, time, out[i++])
            w.write(time.format(Date(l.at)))
            w.write("  ")
            w.write(l.frame.toString())
            car.describe(l.frame)?.let { w.write("  # $it") }
            w.write("\n")
        }
        while (i < out.size) writeSent(w, time, out[i++])
    }

    private fun writeSent(
        w: java.io.Writer,
        time: SimpleDateFormat,
        s: SentLine,
    ) {
        w.write("${time.format(Date(s.at))}  → ${s.hex}  # ${s.what}\n")
    }
}
