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
 */
object CanBoxTalk {
    private const val TAG = "LibreHU-CAN"
    private const val TICK_MS = 250L

    /** What the link did, for the CAN tab. */
    data class Status(
        val running: Boolean = false,
        val acks: Long = 0,
        /** Profile messages sent: name → bytes and time (ms since the epoch). */
        val sent: Map<String, Pair<String, Long>> = emptyMap(),
        /** Commands the box acknowledged. */
        val acked: Set<Int> = emptySet(),
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile
    private var sender: ((ByteArray) -> Unit)? = null

    /** MCU configuration frames (`0F 00 <speed>`). */
    @Volatile
    private var mcu: ((org.librehu.core.mcu.McuFrame) -> Unit)? = null

    @Volatile
    private var link: CanBoxLink? = null

    @Volatile
    private var linkFor: CanVehicle? = null

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

    /** The MCU link is up (Jancar MCU): [send] writes the bytes to the box, [mcuFrame] sends an MCU frame (cmd, data). */
    @Synchronized
    fun start(
        mcuFrame: (org.librehu.core.mcu.McuFrame) -> Unit,
        send: (ByteArray) -> Unit,
    ) {
        sender = send
        mcu = mcuFrame
        rebuild()
        if (timer == null) {
            timer =
                Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "can-box").apply { isDaemon = true } }.also {
                    it.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
                }
        }
    }

    @Synchronized
    fun stop() {
        sender = null
        baudAgain = null
        link = null
        linkFor = null
        timer?.shutdownNow()
        timer = null
        _status.value = Status()
    }

    /** Every message of the box (MCU thread). */
    fun onFrame(f: CanboxFrame) {
        if (CanMonitor.vehicle !== linkFor) rebuild()
        link?.onFrame(f, SystemClock.elapsedRealtime())
    }

    private fun tick() {
        try {
            if (CanMonitor.vehicle !== linkFor) rebuild()
            val now = SystemClock.elapsedRealtime()
            baudAgain?.let { (at, i) ->
                if (now >= at) {
                    baudAgain = null
                    sendBaud(i, "again")
                }
            }
            link?.tick(now)
        } catch (e: Exception) {
            Log.w(TAG, "box link: ${e.message}")
        }
    }

    /** A new link for the profile selected now (or none: off, MCU not held). */
    @Synchronized
    private fun rebuild() {
        val v = CanMonitor.vehicle
        linkFor = v
        val out = sender
        if (out == null || !_enabled.value) {
            link = null
            _status.value = Status()
            return
        }
        val l = v.link
        Log.i(
            TAG,
            "box link (${v.family.name}, ${v.id}): acknowledgements ${if (l.ack) "on" else "off"}" +
                l.send.joinToString("") { ", ${it.name} 0x%02X every ${it.everyMs} ms".format(it.cmd) },
        )
        // The box's speed, as its app sets it at each start (CanBusService.sendBaudRateData): MCU 0F 00 <index>.
        v.baud?.let { b ->
            val i = org.librehu.core.mcu.JacDebug.CAN_BAUDS
                .indexOf(b)
            if (i >= 0) {
                sendBaud(i, "$b bit/s")
                baudAgain = SystemClock.elapsedRealtime() + BAUD_AGAIN_MS to i
            }
        }
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
            ).also { it.start(SystemClock.elapsedRealtime()) }
    }

    /** MCU `0F 00 <index>`: speed of the box's serial link. */
    private fun sendBaud(
        i: Int,
        why: String,
    ) {
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
        if (what == "ack") {
            _status.update { it.copy(acks = it.acks + 1) }
            return
        }
        if (what !in _status.value.sent) Log.i(TAG, "→ box: $what $hex") else Log.d(TAG, "→ box: $what $hex")
        _status.update { it.copy(sent = it.sent + (what to (hex to System.currentTimeMillis()))) }
    }

    private const val KEY_ENABLED = "talk"
    private const val BAUD_AGAIN_MS = 5_000L
}
