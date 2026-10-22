package org.librehu.core.can

/**
 * Steering wheel keys in the Hiworld `0x11` message (protocol LNP002): D1 bit 3 = key event, D2 = key code, D3 = 1 on
 * the frame of the press. A Clio 3 press, as captured: `08 kk 01`, `08 kk 00`, `08 00 00`, `00 00 00` (D1 D2 D3,
 * ~100 ms apart); holding the key repeats the whole sequence (~every 380 ms), so each repeat is a press (the caller
 * tells a hold from them, see [REPEAT_MS]).
 *
 * The whole D2 byte is the code: Hiworld's decoder keeps only D2 & 0x1F, so two buttons whose codes differ in the
 * upper bits (as SRC+ / SRC- of a Clio 3 remote may) came out as the same key. Codes under 32 are unchanged.
 * [onRaw] gets D1 D2 D3 of every key frame (key test, finding what a button sends). [onRelease] gets the code when its
 * press frame is followed by a frame without it (D3 back to 0, another key, or no key).
 */
class HiworldWheelKeys(
    private val onRaw: ((Int, Int, Int) -> Unit)? = null,
    private val onRelease: (Int) -> Unit = {},
    private val onPress: (Int) -> Unit,
) {
    private var pressed = 0
    private var lastRaw = -1

    fun onFrame(f: CanboxFrame) {
        if (f.cmd != CMD || f.data.size < 4) return
        val d1 = f.u(1)
        val key = f.u(2)
        val d3 = f.u(3)
        val raw = (d1 shl 16) or (key shl 8) or d3
        if (raw != lastRaw) onRaw?.invoke(d1, key, d3)
        lastRaw = raw
        val event = d1 and 0x08 != 0
        val down = event && key != 0 && d3 == 1
        if (pressed != 0 && (!down || pressed != key)) onRelease(pressed)
        if (down && pressed != key) onPress(key)
        pressed = if (down) key else 0
    }

    companion object {
        const val CMD = 0x11

        /** A press of the same key within this time of the previous one is the key still held (repeat ~380 ms). */
        const val REPEAT_MS = 650L

        /** Name of a code: Hiworld's for its low 5 bits when the whole byte has none. */
        fun name(code: Int): String? = NAMES[code] ?: NAMES[code and 0x1F]?.takeIf { code > 0x1F }?.let { "$it (0x%02X)".format(code) }

        /** Names of the codes, from Hiworld's app (HdRenaultProtocolLNP002.TranslateKey) and a Clio 3 capture. */
        val NAMES =
            mapOf(
                1 to "volume +",
                2 to "volume -",
                3 to "mute",
                4 to "navigation",
                5 to "previous / answer",
                6 to "next / hang up",
                8 to "right",
                9 to "left",
                10 to "mode",
                13 to "previous",
                14 to "next",
                15 to "OK",
                16 to "phone",
                18 to "mute",
                19 to "answer",
                20 to "hang up",
                21 to "scan",
                24 to "voice",
            )
    }
}
