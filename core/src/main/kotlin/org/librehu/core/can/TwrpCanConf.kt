package org.librehu.core.can

/**
 * `ujc201_can.conf` of LibreHU's TWRP (device tree android_device_alps_ac8257_demo, `tools/touchfix`): the box
 * dialogue and the steering wheel keys of a car profile, so that TWRP answers the box like the service does
 * ([CanBoxLink]) and reads the keys of the car chosen in Android. Lines: `ack <0|1>`, `send <first ms> <every ms>
 * <bytes>` (already framed for the box, 32 bytes at most), `keys <cmd> <code byte> <state byte> <pressed> <event byte>
 * <mask>` (hex, FF = none). TWRP reads the Hiworld framing only: other families get no acknowledgement nor keys there.
 */
object TwrpCanConf {
    /** Longest message TWRP sends (its buffer). */
    const val MAX_FRAME = 32

    fun of(v: CanVehicle): String =
        buildString {
            val hiworld = v.family === CanBoxFamilies.HIWORLD
            append("# LibreHU service: CAN box dialogue for TWRP (touchfix), car profile ${v.id}\n")
            append("# ${v.name.replace('\n', ' ')}; box ${v.family.name}. Written again when the profile changes.\n")
            append("ack ${if (hiworld && v.link.ack) 1 else 0}\n")
            for (s in v.link.send) {
                if (s.data.isEmpty()) {
                    append("# ${s.name}: set by the car chosen in the CAN tab, none yet\n")
                    continue
                }
                val frame = v.family.encode(s.cmd, ByteArray(s.data.size) { s.data[it].toByte() })
                if (frame.size > MAX_FRAME) {
                    append("# ${s.name}: ${frame.size} bytes, more than TWRP sends\n")
                    continue
                }
                append("send ${s.firstMs} ${s.everyMs} ${frame.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}\n")
            }
            val k = v.keys
            if (hiworld && k != null) {
                append(
                    "keys %02X %02X %02X %02X %02X %02X\n".format(
                        k.cmd,
                        k.codeByte,
                        k.stateByte ?: 0xFF,
                        k.pressed.minOrNull() ?: 1,
                        k.eventByte ?: 0xFF,
                        k.eventMask,
                    ),
                )
            }
        }
}
