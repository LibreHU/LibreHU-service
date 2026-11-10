package org.librehu.service.can

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.librehu.core.can.CanBoxLink
import org.librehu.core.can.CanVehicle
import org.librehu.core.can.CanboxFrame
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The service talks back to the CAN box while it holds the MCU: the selected car profile's link ([CanBoxLink]:
 * acknowledgements of the box's messages, car type), sent in MCU frames `10`, which the MCU copies as they are to the
 * box's serial link. As the box's own app does; without it a Hiworld box waits and sends no steering wheel key.
 * Never while ivi-services runs (it talks to the box itself, and the service does not hold the MCU then).
 *
 * [lock] guards the start, the stop and the link's replacement, and nothing that leaves this object runs under it:
 * the box's speed and the link's messages go out after it (see [CanBoxLink], [CanMonitor]: until 10/11/2026 they went
 * out under it, and a thread holding the CAN monitor could wait for it for good).
 */
object CanBoxTalk {
    private const val TAG = "LibreHU-CAN"
    private const val TICK_MS = 250L

    /** Start, stop, link replacement; held for no call out of this object. */
    private val lock = Any()

    /** What the link did, for the CAN tab. */
    data class Status(
        val running: Boolean = false,
        val acks: Long = 0,
        /** Profile messages sent: name → bytes and time (ms since the epoch). */
        val sent: Map<String, Pair<String, Long>> = emptyMap(),
        /** Commands the box acknowledged. */
        val acked: Set<Int> = emptySet(),
        /** Requests sent (Hiworld 0x6A [5, 1, cmd]) and those the box answered at once: it hears the head unit. */
        val requests: Int = 0,
        val answers: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var sender: ((ByteArray) -> Unit)? = null

    /** MCU configuration frames (`0F 00 <speed>`). */
    @Volatile
    private var mcu: ((org.librehu.core.mcu.McuFrame) -> Unit)? = null

    /** Speed of a port other than the MCU (bit/s), see [CanBoxPorts]. */
    @Volatile
    private var portBaud: ((Int) -> Unit)? = null

    @Volatile
    private var link: CanBoxLink? = null

    @Volatile
    private var linkFor: CanVehicle? = null

    /** Message asked for by the last request, and when (elapsed ms). */
    @Volatile
    private var asked: Pair<Int, Long>? = null

    private var timer: ScheduledExecutorService? = null

    /** The box speed again, a few seconds after the start (the box's own app opens its port again, 5 to 10 s later). */
    @Volatile
    private var baudAgain: Pair<Long, Int>? = null
    private var prefs: android.content.SharedPreferences? = null

    private val _enabled = MutableStateFlow(true)

    /** Off: the service only listens to the box (as before 11/2026). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("can_box", Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        _enabled.value = on
        rebuild()
    }

    /**
     * The way to the box is up: [send] writes the bytes to the box; through the Jancar MCU, [mcuFrame] sends an MCU
     * frame (the box's speed is MCU `0F 00`); through another port ([CanBoxPorts]), [baud] sets its speed instead.
     */
    fun start(
        mcuFrame: (org.librehu.core.mcu.McuFrame) -> Unit,
        send: (ByteArray) -> Unit,
        baud: ((Int) -> Unit)? = null,
    ) {
        val speed =
            synchronized(lock) {
                sender = send
                mcu = mcuFrame
                portBaud = baud
                if (timer == null) {
                    timer =
                        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "can-box").apply { isDaemon = true } }.also {
                            it.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
                        }
                }
                rebuildLocked()
            }
        speed?.let(::sendSpeed)
    }

    fun stop() {
        synchronized(lock) {
            sender = null
            baudAgain = null
            link = null
            linkFor = null
            timer?.shutdownNow()
            timer = null
            _status.value = Status()
        }
    }

    /** Every message of the box (the head unit's thread for the MCU, the port's otherwise), no lock held. */
    fun onFrame(f: CanboxFrame) {
        rebuildIfChanged()
        val now = SystemClock.elapsedRealtime()
        asked?.let { (cmd, at) ->
            if (f.cmd == cmd && now - at <= ANSWER_MS) {
                asked = null
                if (_status.value.answers == 0) Log.i(TAG, "box answered the request for 0x%02X in %d ms: it hears the head unit".format(cmd, now - at))
                _status.update { it.copy(answers = it.answers + 1) }
            }
        }
        link?.onFrame(f, now)
    }

    private fun tick() {
        // Whatever goes wrong, the next tick comes: a periodic task that throws is never run again.
        try {
            rebuildIfChanged()
            val now = SystemClock.elapsedRealtime()
            baudAgain?.let { (at, i) ->
                if (now >= at) {
                    baudAgain = null
                    sendBaud(i, "again")
                }
            }
            link?.tick(now)
        } catch (e: Throwable) {
            Log.w(TAG, "box link: $e")
        }
    }

    /**
     * The profile changed what is said to the box: another profile, box, speed or messages (another car). The same
     * profile set again (the CAN screen, TWRP's file) keeps the link running: no box speed sent again, no restart.
     */
    private fun changed(v: CanVehicle): Boolean {
        val l = linkFor ?: return true
        if (v === l) return false
        if (v.id != l.id || v.family !== l.family || v.baud != l.baud || v.link != l.link) return true
        linkFor = v
        return false
    }

    /** A new link for the profile selected now (or none: off, MCU not held), the box's speed sent after the lock. */
    private fun rebuild() {
        synchronized(lock) { rebuildLocked() }?.let(::sendSpeed)
    }

    /** [rebuild] when the profile changed, once: the box's thread and the head unit's may see the change together. */
    private fun rebuildIfChanged() {
        if (!changed(CanMonitor.vehicle)) return
        synchronized(lock) { if (changed(CanMonitor.vehicle)) rebuildLocked() else null }?.let(::sendSpeed)
    }

    /** The box's speed of the profile ([rebuildLocked]), as its app sets it at each start: index in the MCU's list. */
    private fun sendSpeed(i: Int) = sendBaud(i, "${org.librehu.core.mcu.JacDebug.CAN_BAUDS[i]} bit/s")

    /** Under [lock]: the new link; returns the box's speed to send once the lock is released (index), or null. */
    private fun rebuildLocked(): Int? {
        val v = CanMonitor.vehicle
        linkFor = v
        val out = sender
        if (out == null || !_enabled.value) {
            link = null
            _status.value = Status()
            return null
        }
        val l = v.link
        Log.i(
            TAG,
            "box link (${v.family.name}, ${v.id}): acknowledgements ${if (l.ack) "on" else "off"}" +
                l.send.joinToString("") { ", ${it.name} 0x%02X every ${it.everyMs} ms".format(it.cmd) },
        )
        // The box's speed, as its app sets it at each start (CanBusService.sendBaudRateData): MCU 0F 00 <index>.
        val speed =
            v.baud
                ?.let { b ->
                    org.librehu.core.mcu.JacDebug.CAN_BAUDS
                        .indexOf(b)
                }?.takeIf { it >= 0 }
        baudAgain = speed?.let { SystemClock.elapsedRealtime() + BAUD_AGAIN_MS to it }
        _status.value = Status(running = true)
        link =
            CanBoxLink(
                v.family,
                l,
                send = { bytes, what -> send(out, bytes, what) },
                onAcked = { cmd ->
                    if (cmd !in _status.value.acked) Log.i(TAG, "box acknowledged 0x%02X".format(cmd))
                    _status.update { it.copy(acked = it.acked + cmd) }
                },
                state = HeadUnitState,
            ).also { it.start(SystemClock.elapsedRealtime()) }
        return speed
    }

    /** Sends the profile's command [name] (a car setting, the climate…) with [value]; false when not possible now. */
    fun command(
        name: String,
        value: Double? = null,
    ): Boolean = link?.command(name, value) ?: false

    /** What the head unit knows for the messages built from it (org.librehu.core.can.CanTemplate): the clock for now. */
    private object HeadUnitState : org.librehu.core.can.CanState {
        override fun number(name: String): Double? = org.librehu.core.can.CanState.clock(java.time.LocalDateTime.now())[name]

        override fun text(name: String): String? = null
    }

    /** MCU `0F 00 <index>`: speed of the box's serial link. */
    private fun sendBaud(
        i: Int,
        why: String,
    ) {
        portBaud?.let { set ->
            runCatching { set(org.librehu.core.mcu.JacDebug.CAN_BAUDS[i]) }
            Log.i(TAG, "box port speed ${org.librehu.core.mcu.JacDebug.CAN_BAUDS[i]} bit/s ($why)")
            return
        }
        mcu?.invoke(
            org.librehu.core.mcu.JacDebug
                .canBaud(i),
        )
        CanMonitor.recordSent("MCU 0F 00 %02X".format(i), "box serial speed ($why)")
        Log.i(TAG, "box serial link: MCU 0F 00 %02X ($why)".format(i))
    }

    private fun send(
        out: (ByteArray) -> Unit,
        bytes: ByteArray,
        what: String,
    ) {
        out(bytes)
        val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        CanMonitor.recordSent(hex, what)
        // Hiworld request 5A A5 03 6A 05 01 <cmd> CS: the box sends that message at once.
        if (bytes.size == 8 && (bytes[0].toInt() and 0xFF) == 0x5A && (bytes[1].toInt() and 0xFF) == 0xA5 && bytes[2].toInt() == 3 &&
            (bytes[3].toInt() and 0xFF) == 0x6A && bytes[4].toInt() == 5 && bytes[5].toInt() == 1
        ) {
            asked = (bytes[6].toInt() and 0xFF) to SystemClock.elapsedRealtime()
            _status.update { it.copy(requests = it.requests + 1) }
        }
        if (what == "ack") {
            _status.update { it.copy(acks = it.acks + 1) }
            return
        }
        if (what !in _status.value.sent) Log.i(TAG, "→ box: $what $hex") else Log.d(TAG, "→ box: $what $hex")
        _status.update { it.copy(sent = it.sent + (what to (hex to System.currentTimeMillis()))) }
    }

    private const val KEY_ENABLED = "talk"
    private const val BAUD_AGAIN_MS = 5_000L

    /** An answer comes within this after the request (~90 ms seen on a Clio 3 box). */
    private const val ANSWER_MS = 400L
}
