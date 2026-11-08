package org.librehu.core.can

/**
 * `ujc201_can.conf` of LibreHU's TWRP (device tree android_device_alps_ac8257_demo, `tools/touchfix`): the box, its
 * dialogue and the steering wheel keys of the car profile chosen in Android, so that TWRP reads the same box the same
 * way ([CanBoxLink]) without any setting of its own. Written again each time the profile changes. Lines:
 * - the framing, for every box family ([framing]): `frame <head bytes>`, `order lc|cl` (LEN then CMD, or CMD then
 *   LEN), `lenoff <n>` (LEN = data + n), `sum <sum|xor> <1|2 bytes> <add> <xor> <fields>` (fields: h head, l LEN,
 *   c CMD, d data), `ackcmd <cmd>` or `ackraw <bytes>` (the head unit's acknowledgement), `noack <cmds>`, `baud <bit/s>`;
 * - `ack <0|1>`, `send <first ms> <every ms> <bytes>` (already framed, 32 bytes at most);
 * - `keys <cmd> <code byte> <state byte> <pressed> <event byte> <mask> [<code mask>]` (hex, FF = none) and one
 *   `key <code> <action> <name>` per key of the profile with a TWRP action ([action]).
 * TWRP up to build 13 reads only the Hiworld framing, `ack`, `send` and `keys` (the other lines are ignored there).
 */
object TwrpCanConf {
    /** Longest message TWRP sends (its buffer). */
    const val MAX_FRAME = 32

    /** Hiworld `5A A5 LEN CMD D0..Dn-1 CS`, CS = (LEN + CMD + ΣD) - 1, acknowledged with `FF <cmd>` ([CanBoxFamilies.HIWORLD]). */
    val HIWORLD_FRAMING: CanFraming get() = CanBoxFamilies.framing(CanVehicle.BOX_HIWORLD)!!

    /** The framing of the profile's box as TWRP reads it: the profile's own, else its box type's ([CanBoxFamilies]). */
    fun framing(v: CanVehicle): CanFraming? = v.framing ?: framing(v.box)

    /** The framing of the box type [familyId] (built in or from the boxes pack, a maker's name works too); null when unknown. */
    fun framing(familyId: String): CanFraming? = CanBoxFamilies.framing(familyId)

    /**
     * TWRP's action for a key of the profile, from its name ("volume +", "previous / answer", "hang up"…): the first
     * function of a combined name; null when TWRP has nothing for it (voice, radio, settings…). TWRP has no
     * keyboard navigation: `enter` validates, `back` = previous page, `home` = main menu, up / down / left / right move.
     */
    fun action(name: String): String? =
        when (name.substringBefore(" / ").substringBefore(" (").trim().lowercase()) {
            "volume +", "vol +", "vol+" -> "volup"
            "volume -", "vol -", "vol-" -> "voldown"
            "mute", "ok", "answer", "play", "enter" -> "enter"
            "previous", "prev", "up" -> "up"
            "next", "down" -> "down"
            "left" -> "left"
            "right" -> "right"
            "mode", "hang up", "back" -> "back"
            "home", "navigation", "navi" -> "home"
            "power" -> "power"
            else -> null
        }

    private fun hex(b: Int) = "%02X".format(b and 0xFF)

    private fun hex(bytes: Collection<Int>) = bytes.joinToString(" ") { hex(it) }

    fun of(v: CanVehicle): String =
        buildString {
            val f = framing(v)
            append("# LibreHU service: CAN box dialogue for TWRP (touchfix), car profile ${v.id}\n")
            append("# ${v.name.replace('\n', ' ')}; box ${v.family.name}. Written again when the profile changes.\n")
            if (f != null) {
                append("frame ${hex(f.head)}\n")
                append("order ${if (f.lenFirst) "lc" else "cl"}\n")
                append("lenoff ${f.lenOffset}\n")
                val fields = listOf("head" to 'h', "len" to 'l', "cmd" to 'c', "data" to 'd').filter { it.first in f.sumOver }.map { it.second }
                append("sum ${if (f.sumAlgo == "xor") "xor" else "sum"} ${f.sumSize} ${f.sumAdd} ${hex(f.sumXor)} ${fields.joinToString("")}\n")
                when {
                    f.ackRaw != null -> append("ackraw ${hex(f.ackRaw)}\n")
                    f.ackCmd != null -> append("ackcmd ${hex(f.ackCmd)}\n")
                }
                append("noack ${hex(f.noAckFor.sorted())}".trimEnd() + "\n")
                append("baud ${v.baud ?: f.baud}\n")
            } else {
                append("# box ${v.family.id}: no framing TWRP can read\n")
            }
            val acked = f != null && (f.ackRaw != null || f.ackCmd != null)
            append("ack ${if (acked && v.link.ack) 1 else 0}\n")
            for (s in v.link.send) {
                if (s.template != null) {
                    append("# ${s.name}: built by the service from its state (${s.template.source}), not sent by TWRP\n")
                    continue
                }
                if (s.data.isEmpty()) {
                    append("# ${s.name}: set by the car chosen in the CAN tab, none yet\n")
                    continue
                }
                val frame = v.family.encode(s.cmd, ByteArray(s.data.size) { s.data[it].toByte() })
                if (frame.size > MAX_FRAME) {
                    append("# ${s.name}: ${frame.size} bytes, more than TWRP sends\n")
                    continue
                }
                append("send ${s.firstMs} ${s.everyMs} ${frame.joinToString(" ") { hex(it.toInt()) }}\n")
            }
            val k = v.keys
            if (f != null && k != null && k.codeBase == 0 && k.stepsByte == null) {
                val mask = if (k.codeMask != 0xFF) " ${hex(k.codeMask)}" else ""
                append(
                    "keys %s %s %s %s %s %s%s\n".format(
                        hex(k.cmd),
                        hex(k.codeByte),
                        hex(k.stateByte ?: 0xFF),
                        hex(k.pressed.minOrNull() ?: 1),
                        hex(k.eventByte ?: 0xFF),
                        hex(k.eventMask),
                        mask,
                    ),
                )
                for ((code, name) in k.names.toSortedMap()) {
                    val a = action(name) ?: continue
                    if (code !in 0..0xFF) continue
                    val label = name.substringBefore(" / ").substringBefore(" (").trim().replace(' ', '_').take(11)
                    append("key ${hex(code)} $a $label\n")
                }
            }
        }
}
