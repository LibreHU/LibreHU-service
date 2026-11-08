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
    /** Where the box puts the keys (the car profile's `wheel_keys`); Hiworld LNP002 by default. */
    private val spec: WheelKeySpec = WheelKeySpec.HIWORLD_LNP002,
    private val onPress: (Int) -> Unit,
) {
    private var pressed = 0
    private var lastRaw = -1

    fun onFrame(f: CanboxFrame) {
        if (spec.stepsByte != null) {
            knob(f)
            return
        }
        if (f.cmd != spec.cmd || f.data.size <= maxOf(spec.codeByte, spec.stateByte ?: 0, spec.eventByte ?: 0)) return
        val ev = spec.eventByte?.let { f.u(it) } ?: 0
        // Engine 2.8: another message under the same command, not a frame without a key (KSW: A1 19 car data, A1 17 keys).
        if (spec.eventByte != null && spec.eventValue != null && (ev and spec.eventMask) != spec.eventValue) return
        val key = f.u(spec.codeByte) and spec.codeMask
        val st = spec.stateByte?.let { f.u(it) } ?: 0
        val raw = (ev shl 16) or (key shl 8) or st
        if (raw != lastRaw) onRaw?.invoke(ev, key, st)
        lastRaw = raw
        val event = spec.eventByte == null || spec.eventValue != null || (ev and spec.eventMask) != 0
        val down = event && key != 0 && (spec.stateByte == null || st in (spec.pressedFor[key] ?: spec.pressed))
        if (pressed != 0 && (!down || pressed != key)) onRelease(spec.codeBase + pressed)
        if (down && pressed != key) onPress(spec.codeBase + key)
        pressed = if (down) key else 0
    }

    /** A knob: each step is a press and release of code base + 2 × knob (turned up) or + 1 (turned down). */
    private fun knob(f: CanboxFrame) {
        val sb = spec.stepsByte ?: return
        if (f.cmd != spec.cmd || f.data.size <= maxOf(spec.codeByte, sb)) return
        val k = f.u(spec.codeByte) and spec.codeMask
        val raw = f.u(sb)
        val steps = if (spec.stepsSigned) raw.toByte().toInt() else raw
        onRaw?.invoke(0, k, raw)
        if (steps == 0) return
        val code = spec.codeBase + 2 * k + if (steps < 0) 1 else 0
        repeat(kotlin.math.abs(steps).coerceAtMost(MAX_STEPS)) {
            onPress(code)
            onRelease(code)
        }
    }

    companion object {
        const val CMD = 0x11

        /** Steps of a knob taken from one message, at most (a wrong byte must not raise the volume 100 times). */
        const val MAX_STEPS = 10

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

/**
 * Where a box puts the steering wheel keys (car profile, `"wheel_keys"`): message [cmd], key code at [codeByte] (masked
 * by [codeMask]), pressed while the byte at [stateByte] is one of [pressed] (no [stateByte]: while the code is not 0),
 * and only when the byte at [eventByte] has a bit of [eventMask] (Hiworld LNP002: D1 bit 3). Bytes count from D0.
 * [names]: the buttons' names, for the CAN tab.
 *
 * Format 2: a profile may list more key sources (`"keys"`: media keys, front panel, knobs), each with its [codeBase]
 * (its codes are base + code, so they never clash with the steering wheel's). A knob ([stepsByte]: the steps turned in
 * one message, [stepsSigned]) gives code base + 2 × knob turned up, base + 2 × knob + 1 turned down.
 */
data class WheelKeySpec(
    val cmd: Int,
    val codeByte: Int,
    val codeMask: Int = 0xFF,
    val stateByte: Int? = null,
    val pressed: Set<Int> = setOf(1),
    val eventByte: Int? = null,
    val eventMask: Int = 0xFF,
    val names: Map<Int, String> = emptyMap(),
    val codeBase: Int = 0,
    val stepsByte: Int? = null,
    val stepsSigned: Boolean = true,
    /**
     * Engine 2.8: the key frames are those whose [eventByte] (masked by [eventMask]) is this value, instead of any
     * with a bit of [eventMask] (KSW: `A1 17 code state`, the other `A1` frames are car data).
     */
    val eventValue: Int? = null,
    /** Engine 2.8: codes pressed on other values of [stateByte] than [pressed] (KSW: 0 for volume, tracks, play). */
    val pressedFor: Map<Int, Set<Int>> = emptyMap(),
) {
    /** Uses what engine 2.8 brought: older services and TWRP's reader cannot read it. */
    val needs28: Boolean get() = eventValue != null || pressedFor.isNotEmpty()

    /** True when [code] is one of this source's. */
    fun owns(code: Int): Boolean = code - codeBase in 0 until if (stepsByte != null) 512 else 256

    /** Name of a code: the profile's, else its low 5 bits' (codes read before the whole byte was). */
    fun name(code: Int): String? {
        val c = code - codeBase
        if (stepsByte != null) return names[c / 2]?.let { it + if (c % 2 == 0) " +" else " −" }
        return names[c] ?: names[c and 0x1F]?.takeIf { c > 0x1F && codeBase == 0 }?.let { "$it (0x%02X)".format(c) }
    }

    companion object {
        /** Hiworld LNP002 (Renault…): `0x11`, D1 bit 3 = key event, D2 = code, D3 = 1 on the press. */
        val HIWORLD_LNP002 =
            WheelKeySpec(
                cmd = HiworldWheelKeys.CMD,
                codeByte = 2,
                stateByte = 3,
                pressed = setOf(1),
                eventByte = 1,
                eventMask = 0x08,
                names = HiworldWheelKeys.NAMES,
            )
    }
}
