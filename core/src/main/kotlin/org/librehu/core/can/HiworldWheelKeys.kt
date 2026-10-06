package org.librehu.core.can

/**
 * Steering wheel keys in the Hiworld `0x11` message (protocol LNP002): D1 bit 3 = key event, D2 & 0x1F = key code,
 * D3 = 1 on the frame of the press. A Clio 3 press, as captured: `08 kk 01`, `08 kk 00`, `08 00 00`, `00 00 00`
 * (D1 D2 D3, ~100 ms apart); holding the key repeats the whole sequence (~every 380 ms), so each repeat is a press.
 */
class HiworldWheelKeys(
    private val onPress: (Int) -> Unit,
) {
    private var pressed = 0

    fun onFrame(f: CanboxFrame) {
        if (f.cmd != CMD || f.data.size < 4) return
        val event = f.u(1) and 0x08 != 0
        val key = f.u(2) and 0x1F
        val down = event && key != 0 && f.u(3) == 1
        if (down && pressed != key) onPress(key)
        pressed = if (down) key else 0
    }

    companion object {
        const val CMD = 0x11

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
