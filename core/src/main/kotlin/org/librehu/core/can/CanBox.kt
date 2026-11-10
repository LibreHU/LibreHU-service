package org.librehu.core.can

/** Rebuilds the messages of a CAN box from the bytes relayed by the MCU (frames `10`, cut anywhere). */
interface CanBoxParser {
    fun feed(bytes: ByteArray)

    /** Bytes that did not belong to a message (other framing, noise). */
    val skipped: Long
}

/**
 * A family of CAN boxes (Hiworld, Raise…): how its messages are framed, how the head unit writes one, and how it
 * acknowledges the box's. The car's decoding is in the vehicle profile ([CanVehicle]), which names its family.
 */
interface CanBoxFamily {
    /** Name in the profiles (`"box"`). */
    val id: String
    val name: String

    /** Speed of the box's serial link (MCU `0F 00`), when the profile gives none. */
    val defaultBaud: Int

    fun parser(onFrame: (CanboxFrame) -> Unit): CanBoxParser

    fun encode(
        cmd: Int,
        data: ByteArray,
    ): ByteArray

    /** The head unit's answer to [f] (good checksum only), or null: the box's own acknowledgements, no answer in this family. */
    fun ack(f: CanboxFrame): ByteArray?
}

/**
 * The CAN box types LibreHU knows, described as data, not in code: each one is the generic profile of its type (a
 * profile with `"box_type"` and its `"framing"`, engine 2.6), so a new box needs a profile, not a new service. The
 * service has one built in per type (Hiworld 5A A5 and AA 55, Raise 2E, Raise FD with an 8-bit or a 16-bit sum,
 * [CanVehicles.BUILT_IN]: also the fallback profile of each type); the box types of the `boxes` pack of
 * LibreHU-car-profiles ([register]) add new ones and replace the built-in ones of the same id. A car profile names its
 * type (`"box": "hiworld"`, or one of its makers: `"simple"`, `"bagoo"`…) or describes its own framing.
 */
object CanBoxFamilies {
    /** A box type: its family (how it frames and acknowledges), its framing, its generic profile. */
    private class Entry(
        val family: CanBoxFamily,
        val framing: CanFraming,
        val profile: CanVehicle,
    )

    /** The built-in box types (the service's generic profiles), by id and by maker. */
    private val builtIn: Map<String, Entry> by lazy { entries(CanVehicles.BUILT_IN) }

    /** The box types in use: the built-in ones, then those of the installed `boxes` pack. */
    @Volatile
    private var types: Map<String, Entry>? = null

    private fun types(): Map<String, Entry> = types ?: synchronized(this) { types ?: builtIn.also { types = it } }

    private fun entries(profiles: List<CanVehicle>): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (p in profiles) {
            val t = p.boxType ?: continue
            val f = p.framing ?: continue
            val e = Entry(f.family(p.box, t.name), f, p)
            out[p.box] = e
            for (a in t.aliases) out.putIfAbsent(a, e)
        }
        return out
    }

    /** The box types of the installed `boxes` pack ([CanVehicle.boxType] profiles), over the built-in ones; empty: built-in only. */
    fun register(profiles: List<CanVehicle>) {
        synchronized(this) { types = builtIn + entries(profiles) }
    }

    /** One family per box type, built-in ones first. */
    val ALL: List<CanBoxFamily> get() = types().values.map { it.family }.distinct()

    /** Box makers of Hiworld's app ("Car Info" `assets/canbus.db`, table `canbox`) and the type each one uses. */
    val MAKERS: Map<String, CanBoxFamily> get() = types().filter { (k, e) -> k != e.family.id }.mapValues { it.value.family }

    fun byId(id: String): CanBoxFamily? = types()[id.trim().lowercase()]?.family

    /** The framing of the box type [id] (TWRP's file, [TwrpCanConf]). */
    fun framing(id: String): CanFraming? = types()[id.trim().lowercase()]?.framing

    /** The generic profile of the box type [id]: raw messages, the fallback when no car profile is installed. */
    fun generic(id: String): CanVehicle? = types()[id.trim().lowercase()]?.profile

    /** Hiworld, `5A A5 LEN CMD D0..Dn-1 CS`, CS = ((LEN + CMD + ΣD) & 0xFF) - 1, each message acknowledged with `FF <cmd>`. */
    val HIWORLD: CanBoxFamily get() = byId(CanVehicle.BOX_HIWORLD)!!

    /** Hiworld's other framing, `AA 55 LEN CMD D0..Dn-1 CS`, no acknowledgement. */
    val HIWORLD_AA55: CanBoxFamily get() = byId("hiworld-aa55")!!

    /** Raise (RZC) and most other makers: `2E CMD LEN D0..Dn-1 CS`, CS = (CMD + LEN + ΣD) XOR 0xFF, acknowledged with `FF`. */
    val RAISE: CanBoxFamily get() = byId("raise")!!

    /** Raise's `FD LEN CMD D0..Dn-1 CS` (LEN = n + 3, 8-bit sum), no acknowledgement. */
    val RAISE_FD: CanBoxFamily get() = byId("raise-fd")!!

    /** Raise's `FD LEN CMD D0..Dn-1 CSH CSL` (LEN = n + 4, 16-bit sum), no acknowledgement. */
    val RAISE_FD2: CanBoxFamily get() = byId("raise-fd2")!!

    /** Speeds of the box's serial link the Jancar MCU knows (`0F 00 <index>`, MCU-tools-app docs/mcu_firmware.md). */
    val BAUDS: List<Int> get() = org.librehu.core.mcu.JacDebug.CAN_BAUDS
}

/** Raise framing, `2E CMD LEN D0..Dn-1 CS`; the box's single-byte answers (FF, F0, F3, FC) are not messages. */
class RaiseParser(
    private val onFrame: (CanboxFrame) -> Unit,
) : CanBoxParser {
    private var state = 0
    private var cmd = 0
    private var len = 0
    private var buf = ByteArray(0)
    private var pos = 0

    override var skipped = 0L
        private set

    /** The box's single-byte answers (acknowledgements). */
    var answers = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun feed(v: Int) {
        when (state) {
            0 -> {
                when (v) {
                    HEAD -> state = 1
                    0xFF, 0xF0, 0xF3, 0xFC -> answers++
                    else -> skipped++
                }
            }
            1 -> {
                cmd = v
                state = 2
            }
            2 -> {
                len = v
                buf = ByteArray(len)
                pos = 0
                state = if (len == 0) 4 else 3
            }
            3 -> {
                buf[pos++] = v.toByte()
                if (pos == len) state = 4
            }
            4 -> {
                onFrame(CanboxFrame(cmd, buf, v == sum(cmd, buf)))
                state = 0
            }
        }
    }

    companion object {
        const val HEAD = 0x2E

        fun sum(
            cmd: Int,
            data: ByteArray,
        ): Int = ((cmd + data.size + data.sumOf { it.toInt() and 0xFF }) xor 0xFF) and 0xFF

        fun encode(
            cmd: Int,
            data: ByteArray,
        ): ByteArray = byteArrayOf(HEAD.toByte(), cmd.toByte(), data.size.toByte()) + data + sum(cmd, data).toByte()
    }
}

/** Raise's FD framing: `FD LEN CMD D0..Dn-1` then a sum of LEN, CMD and D on [sumBytes] bytes (big-endian). */
class FdParser(
    private val onFrame: (CanboxFrame) -> Unit,
    private val sumBytes: Int,
) : CanBoxParser {
    private var state = 0
    private var len = 0
    private var cmd = 0
    private var buf = ByteArray(0)
    private var pos = 0
    private var got = 0
    private var have = 0

    override var skipped = 0L
        private set

    override fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun feed(v: Int) {
        when (state) {
            0 -> if (v == HEAD) state = 1 else skipped++
            1 -> {
                len = v
                // LEN counts itself, CMD, the data and the sum.
                val n = len - 2 - sumBytes
                if (n < 0) {
                    skipped += 2
                    state = 0
                } else {
                    buf = ByteArray(n)
                    pos = 0
                    state = 2
                }
            }
            2 -> {
                cmd = v
                got = 0
                have = 0
                state = if (buf.isEmpty()) 4 else 3
            }
            3 -> {
                buf[pos++] = v.toByte()
                if (pos == buf.size) state = 4
            }
            4 -> {
                have = (have shl 8) or v
                if (++got == sumBytes) {
                    onFrame(CanboxFrame(cmd, buf, have == sum(len, cmd, buf, sumBytes)))
                    state = 0
                }
            }
        }
    }

    companion object {
        const val HEAD = 0xFD

        fun sum(
            len: Int,
            cmd: Int,
            data: ByteArray,
            sumBytes: Int,
        ): Int {
            val s = len + cmd + data.sumOf { it.toInt() and 0xFF }
            return if (sumBytes == 1) s and 0xFF else s and 0xFFFF
        }

        fun encode(
            cmd: Int,
            data: ByteArray,
            sumBytes: Int,
        ): ByteArray {
            val len = data.size + 2 + sumBytes
            val s = sum(len, cmd, data, sumBytes)
            val tail = if (sumBytes == 1) byteArrayOf(s.toByte()) else byteArrayOf((s shr 8).toByte(), s.toByte())
            return byteArrayOf(HEAD.toByte(), len.toByte(), cmd.toByte()) + data + tail
        }
    }
}

/**
 * The head unit's side of the link with the CAN box, as the box's own app does it: each message with a good checksum
 * gets the family's acknowledgement ([CanLink.ack]), and the profile's messages ([CanLink.send], the car type of a
 * Hiworld box) go [CanSend.firstMs] after the start, then every [CanSend.everyMs]. The box coming back after a
 * silence (switched off, reset) gets them again from the start. Without them a Hiworld box waits: it sends its
 * messages one a second in a loop and never a steering wheel key (CAN logs of 02/11 and 04/11/2026).
 * Pure logic: [send] gets the bytes and what they are, [tick] is called regularly with the time (ms, monotonic).
 *
 * Thread-safe, and [send], [onAcked] and [state] are never called with the link's lock held (open calls): whatever
 * locks the caller holds, they cannot meet this one in the other order. Until 10/11/2026 [send] ran under it, and the
 * service froze for good while driving: the head unit's thread, holding the CAN monitor's lock, waited in [onFrame]
 * for the link, while the box's thread, holding the link in [tick], waited for the monitor to note what it sent.
 * A message decided in [tick] is due again only at its next time, even when [send] throws (a port that fails is
 * opened again, which starts the link again).
 */
class CanBoxLink(
    private val family: CanBoxFamily,
    private val link: CanLink,
    private val send: (ByteArray, String) -> Unit,
    /** A command the box acknowledged (the car type…). */
    private val onAcked: (Int) -> Unit = {},
    /** What the head unit knows (clock…), for the messages built from it ([CanSend.template]). */
    private val state: CanState = CanState.NONE,
) {
    /** Guards the schedule below; held for no call out of this class. */
    private val lock = Any()
    private val due = LongArray(link.send.size) { -1 }

    /** Data last sent by each message built from the state (`on: change`). */
    private val lastBuilt = arrayOfNulls<String>(link.send.size)
    private val lastAt = LongArray(link.send.size)
    private var lastFrameAt = Long.MIN_VALUE

    /** Starts the schedule of the profile's messages. */
    fun start(now: Long) = synchronized(lock) { schedule(now) }

    private fun schedule(now: Long) {
        for (i in due.indices) due[i] = now + link.send[i].firstMs
        lastBuilt.fill(null)
    }

    fun onFrame(
        f: CanboxFrame,
        now: Long,
    ) {
        synchronized(lock) {
            if (lastFrameAt != Long.MIN_VALUE && now - lastFrameAt > SILENCE_MS) schedule(now)
            lastFrameAt = now
        }
        if (!f.checksumOk) return
        if ((f.cmd == 0xFF || f.cmd == 0xFE) && f.data.isNotEmpty()) onAcked(f.data[0].toInt() and 0xFF)
        if (link.ack) family.ack(f)?.let { send(it, "ack") }
    }

    fun tick(now: Long) {
        // The state read before the lock, the messages sent after it, in the profile's order.
        val built = Array(link.send.size) { link.send[it].template?.build(state) }
        val out = ArrayList<Int>(0)
        synchronized(lock) {
            for ((i, s) in link.send.withIndex()) {
                if (s.template != null) {
                    if (dueBuilt(i, s, built[i], now)) out += i
                    continue
                }
                // No data: set by the car to choose (the box gets no car type until then).
                if (s.data.isEmpty() || due[i] < 0 || now < due[i]) continue
                out += i
                due[i] = if (s.everyMs > 0) now + s.everyMs else -1
            }
        }
        for (i in out) {
            val s = link.send[i]
            send(family.encode(s.cmd, built[i] ?: ByteArray(s.data.size) { s.data[it].toByte() }), s.name)
        }
    }

    /**
     * A message built from the state: at its time, and for `on: change` as soon as its data changes (not faster than
     * [MIN_CHANGE_MS]). True when it goes now (noted as sent). Under the lock.
     */
    private fun dueBuilt(
        i: Int,
        s: CanSend,
        data: ByteArray?,
        now: Long,
    ): Boolean {
        val started = due[i] >= 0 || lastBuilt[i] != null
        if (!started || (lastBuilt[i] == null && now < due[i])) return false
        if (data == null) return false
        val hex = data.joinToString("") { "%02X".format(it) }
        val timeDue = due[i] in 0..now
        val changed = s.on == "change" && lastBuilt[i] != null && hex != lastBuilt[i] && now - lastAt[i] >= MIN_CHANGE_MS
        if (!timeDue && !changed) return false
        lastBuilt[i] = hex
        lastAt[i] = now
        due[i] = if (s.everyMs > 0) now + s.everyMs else -1
        return true
    }

    /** Sends the command [name] of the profile with [value]; false when unknown or its data cannot be built. */
    fun command(
        name: String,
        value: Double? = null,
    ): Boolean {
        val c = link.commands[name] ?: return false
        val data = c.template.build(state, value) ?: return false
        send(family.encode(c.cmd, data), "command $name")
        return true
    }

    companion object {
        /** A box silent this long, then talking again, has restarted. */
        const val SILENCE_MS = 5_000L

        /** A message sent on change is not sent again sooner (a clock's seconds…). */
        const val MIN_CHANGE_MS = 500L
    }
}
