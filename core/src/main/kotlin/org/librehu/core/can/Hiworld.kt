package org.librehu.core.can

/** One message of the CAN box (Hiworld framing), [data] = D0..Dn-1. */
class CanboxFrame(
    val cmd: Int,
    val data: ByteArray,
    val checksumOk: Boolean,
) {
    fun u(i: Int): Int = if (i in data.indices) data[i].toInt() and 0xFF else 0

    fun hex(): String = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    override fun toString() = "%02X: %s%s".format(cmd, hex(), if (checksumOk) "" else " (bad checksum)")
}

/**
 * Rebuilds the CAN box messages from the bytes relayed by the MCU (frames `10`, cut every ~128 bytes / 20 ms, so a
 * message can span two chunks). Hiworld framing (ivi-canbus, docs/ivi-services/12-canbus.md):
 * `5A A5 LEN CMD D0..Dn-1 CS`, LEN = n, CS = ((LEN + CMD + ΣD) & 0xFF) - 1.
 */
class HiworldParser(
    private val onFrame: (CanboxFrame) -> Unit,
) {
    private var state = 0
    private var len = 0
    private var cmd = 0
    private var buf = ByteArray(0)
    private var pos = 0

    /** Bytes that did not belong to a message (other framing, noise). */
    var skipped = 0L
        private set

    fun feed(bytes: ByteArray) {
        for (b in bytes) feed(b.toInt() and 0xFF)
    }

    private fun feed(v: Int) {
        when (state) {
            0 -> {
                if (v == 0x5A) state = 1 else skipped++
            }

            1 -> {
                state =
                    if (v == 0xA5) {
                        2
                    } else if (v == 0x5A) {
                        1
                    } else {
                        0.also { skipped += 2 }
                    }
            }

            2 -> {
                len = v
                state = 3
            }

            3 -> {
                cmd = v
                buf = ByteArray(len)
                pos = 0
                state = if (len == 0) 5 else 4
            }

            4 -> {
                buf[pos++] = v.toByte()
                if (pos == len) state = 5
            }

            5 -> {
                val sum = len + cmd + buf.sumOf { it.toInt() and 0xFF }
                val expected = ((sum and 0xFF) - 1) and 0xFF
                onFrame(CanboxFrame(cmd, buf, v == expected))
                state = 0
            }
        }
    }

    companion object {
        /** Builds a message (tests, sending to the box). */
        fun encode(
            cmd: Int,
            data: ByteArray,
        ): ByteArray {
            val sum = data.size + cmd + data.sumOf { it.toInt() and 0xFF }
            return byteArrayOf(0x5A, 0xA5.toByte(), data.size.toByte(), cmd.toByte()) + data + (((sum and 0xFF) - 1) and 0xFF).toByte()
        }
    }
}

/**
 * Readable text of the Renault messages of the Hiworld box (protocol LNP002, Clio 3 of this unit), from ivi-canbus'
 * `HdRenaultProtocolLNP002`. "Octet k" of the decoder = D(k-4). Null when the command is not known.
 */
object HiworldRenault {
    fun describe(f: CanboxFrame): String? =
        when (f.cmd) {
            0x11 -> {
                val flags = f.u(0)
                val names =
                    listOfNotNull(
                        "ACC".takeIf { flags and 1 != 0 },
                        "feux".takeIf { flags and 2 != 0 },
                        "marche AR".takeIf { flags and 4 != 0 },
                        "frein à main".takeIf { flags and 8 != 0 },
                    )
                val key = f.u(2) and 0x1F
                val angle = ((f.u(6) shl 8) or f.u(7)).toShort() / 10.0
                "état : ${names.ifEmpty { listOf("-") }.joinToString(", ")} · touche volant $key (${f.u(3)}) · volant %.1f°".format(angle)
            }

            0x12 -> {
                val d = f.u(2)
                val open =
                    listOfNotNull(
                        "AVG".takeIf { d and 0x80 != 0 },
                        "AVD".takeIf { d and 0x40 != 0 },
                        "ARG".takeIf { d and 0x20 != 0 },
                        "ARD".takeIf { d and 0x10 != 0 },
                        "coffre".takeIf { d and 0x08 != 0 },
                    )
                "portes ouvertes : ${open.ifEmpty { listOf("aucune") }.joinToString(", ")}"
            }

            0x31 -> {
                if (f.data.size > 11) "clim · température extérieure %.1f °C".format(f.u(11) * 0.5 - 40) else "clim"
            }

            0x41 -> {
                val v = (0 until 8).map { 4 - f.u(it).coerceAtMost(4) }
                "radar AR ${v.take(4).joinToString(" ")} · AV ${v.drop(4).joinToString(" ")} (0 = loin, 4 = proche)"
            }

            0x14 -> {
                "ordinateur de bord"
            }

            0x21 -> {
                "touche de façade ${f.u(0)}"
            }

            0x22 -> {
                "molette ${f.u(0)}"
            }

            0x48 -> {
                "pression des pneus"
            }

            0x61, 0x62 -> {
                "réglages véhicule"
            }

            0xF0 -> {
                "version : " + String(f.data, Charsets.US_ASCII).trim()
            }

            else -> {
                null
            }
        }
}
