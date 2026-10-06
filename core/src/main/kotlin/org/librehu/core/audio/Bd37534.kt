package org.librehu.core.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Writes one register of an I2C chip. Returns false on bus error. */
fun interface RegisterWriter {
    fun write(
        register: Int,
        value: Int,
    ): Boolean
}

/**
 * Driver for the ROHM BD37534 sound processor of board A0_AN (`/dev/i2c-6`, address 0x40), rebuilt from the
 * register writes of `AudioBD37534` in Jancar's `libJanCarIVI.so` (see MCU-tools-app docs/ivi_audio.md §7) and the
 * register map of the public BD37534FV Arduino library.
 *
 * Attenuator encoding (volume, faders): `0x80` = 0 dB, `0x80 - dB` otherwise (+15..-79 dB), as in libJanCarIVI.
 *
 * Not verified on hardware yet: which of fader 1/2 is left or right ([FADER_FRONT_1] is assumed to be left).
 */
class Bd37534(
    private val io: RegisterWriter,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Last value written per register (for diagnostics and the UI). */
    val shadow = IntArray(256) { -1 }

    private var inputGainDb = 0
    private var muted = true
    private var subOn = false
    private var subLevel = SUB_DEFAULT_LEVEL

    /** Power-on sequence of libJanCarIVI `AudioBD37534::init()`, then Android input selected and muted. */
    fun init(): Boolean {
        var ok = true
        ok = w(SETUP_1, 0xB7) && ok
        ok = w(SETUP_2, 0x00) && ok
        ok = w(SETUP_3, 0x11) && ok
        ok = w(BASS_SETUP, 0x11) && ok
        ok = w(MIDDLE_SETUP, 0x21) && ok
        ok = w(TREBLE_SETUP, 0x20) && ok
        ok = w(MIXING, 0xFF) && ok
        ok = w(FADER_SUB, 0x00) && ok
        sleep(300)
        ok = w(SETUP_2, setup2(subFc = 0)) && ok
        ok = setLoudness(0) && ok
        ok = setBalanceFade(FADER_CENTER, FADER_CENTER) && ok
        ok = setInput(INPUT_ANDROID, 0) && ok
        ok = setMute(true) && ok
        return ok
    }

    /** Input selector (0x05, bit 7 set as Jancar does) and its gain (0x06, 0..+16 dB). Android/PC = input 11. */
    fun setInput(
        input: Int,
        gainDb: Int,
    ): Boolean {
        inputGainDb = gainDb.coerceIn(0, 16)
        return w(INPUT_SELECT, 0x80 or (input and 0x7F)) && writeInputGain()
    }

    /** Soft mute through bit 7 of the input gain register, followed by 50 ms for the ramp when muting. */
    fun setMute(on: Boolean): Boolean {
        muted = on
        val ok = writeInputGain()
        if (on) sleep(50)
        return ok
    }

    val isMuted: Boolean get() = muted

    private fun writeInputGain() = w(INPUT_GAIN, (if (muted) 0x80 else 0) or inputGainDb)

    /** Master volume as a step of [VOLUME_CURVE] (0..[MAX_VOLUME]), plus an optional offset in dB. */
    fun setVolume(
        step: Int,
        offsetDb: Int = 0,
    ): Boolean = w(VOLUME, attenuator(VOLUME_CURVE[step.coerceIn(0, MAX_VOLUME)] + offsetDb))

    /** Bass / middle / treble, 0..20 each (10 = flat, 2 dB per step). */
    fun setTone(
        bass: Int,
        middle: Int,
        treble: Int,
    ): Boolean = w(BASS_GAIN, tone(bass)) and w(MIDDLE_GAIN, tone(middle)) and w(TREBLE_GAIN, tone(treble))

    /** Loudness gain, 0..15 dB (register 0x75). */
    fun setLoudness(level: Int): Boolean = w(LOUDNESS, level.coerceIn(0, 15))

    /**
     * Balance and fader, 0..60 each (30 = centre). Balance 0 = left, fader 0 = front (ivi-services convention).
     * Every speaker loses the distance of the chosen point to it, mapped to dB with Jancar's table.
     */
    fun setBalanceFade(
        balance: Int,
        fade: Int,
    ): Boolean {
        val levels = speakerLevels(balance, fade)
        var ok = true
        for (i in levels.indices) ok = w(SPEAKER_REGISTERS[i], attenuator(levels[i])) && ok
        return ok
    }

    /**
     * Subwoofer output with its level 0..12 (= -5..+7 dB on this board). On: low-pass at 120 Hz, then level.
     * Off: fader cut, anti-pop pause, filter off — the sequence of libJanCarIVI `setSubWooferOnOff`.
     */
    fun setSubwoofer(
        on: Boolean,
        level: Int,
    ): Boolean {
        subLevel = level.coerceIn(0, SUB_MAX_LEVEL)
        val wasOn = subOn
        subOn = on
        return if (on) {
            val ok = w(SETUP_2, setup2(subFc = SUB_FC_120HZ))
            if (!wasOn) sleep(150)
            w(FADER_SUB, attenuator(SUB_BASE_DB + subLevel)) && ok
        } else {
            val ok = w(FADER_SUB, 0x00)
            if (wasOn) sleep(300)
            w(SETUP_2, setup2(subFc = 0)) && ok
        }
    }

    private fun w(
        register: Int,
        value: Int,
    ): Boolean {
        val v = value and 0xFF
        val ok = io.write(register, v)
        if (ok) shadow[register] = v
        return ok
    }

    companion object {
        const val I2C_BUS = 6
        const val I2C_ADDRESS = 0x40

        const val SETUP_1 = 0x01
        const val SETUP_2 = 0x02
        const val SETUP_3 = 0x03
        const val INPUT_SELECT = 0x05
        const val INPUT_GAIN = 0x06
        const val VOLUME = 0x20
        const val FADER_FRONT_1 = 0x28
        const val FADER_FRONT_2 = 0x29
        const val FADER_REAR_1 = 0x2A
        const val FADER_REAR_2 = 0x2B
        const val FADER_SUB = 0x2C
        const val MIXING = 0x30
        const val BASS_SETUP = 0x41
        const val MIDDLE_SETUP = 0x44
        const val TREBLE_SETUP = 0x47
        const val BASS_GAIN = 0x51
        const val MIDDLE_GAIN = 0x54
        const val TREBLE_GAIN = 0x57
        const val LOUDNESS = 0x75

        /** Physical input of the Android/PC audio on board A0_AN (`mPCPhyAudioChannel`). */
        const val INPUT_ANDROID = 11

        /** AUX / AV input and its gain (`Platform_AutoChips_8257_37534`: input 0, +5 dB). */
        const val INPUT_AUX = 0
        const val AUX_GAIN_DB = 5

        /** Speakers in the order FL, FR, RL, RR. */
        val SPEAKER_REGISTERS = intArrayOf(FADER_FRONT_1, FADER_FRONT_2, FADER_REAR_1, FADER_REAR_2)

        const val FADER_CENTER = 30
        const val FADER_MAX = 60
        const val TONE_FLAT = 10
        const val TONE_MAX = 20
        const val SUB_BASE_DB = -5
        const val SUB_MAX_LEVEL = 12
        const val SUB_DEFAULT_LEVEL = 6
        const val SUB_FC_120HZ = 3

        /** Default volume curve of `Platform_AutoChips_8257_37534` (41 steps, dB). */
        @Suppress("ktlint:standard:argument-list-wrapping")
        val VOLUME_CURVE =
            intArrayOf(
                -79, -60, -55, -50, -45, -40, -35, -30, -25, -20, -14, -13, -12, -12, -11, -10, -10, -9, -9, -8, -7,
                -7, -6, -6, -5, -4, -4, -3, -3, -2, -1, -1, 0, 0, 1, 2, 2, 3, 3, 4, 5,
            )
        val MAX_VOLUME = VOLUME_CURVE.size - 1

        /** Speaker index (0..30, 30 = full level) to dB, from libJanCarIVI `setBalanceFade`. */
        val FADER_TABLE =
            IntArray(31) { i ->
                when (i) {
                    0 -> -79
                    1 -> -60
                    2 -> -50
                    3 -> -40
                    4 -> -37
                    5 -> -34
                    6 -> -29
                    7 -> -26
                    8 -> -23
                    in 9..28 -> i - 29
                    29 -> 0
                    else -> 1
                }
            }

        /** `0x80` = 0 dB, `0x80 - dB` otherwise, clamped to +15..-79 dB. */
        fun attenuator(db: Int): Int = (0x80 - db.coerceIn(-79, 15)) and 0xFF

        /** Tone step 0..20 to register value: gain 2·v − 20 dB, negative gains as `0x80 | -gain`. */
        fun tone(step: Int): Int {
            val g = 2 * step.coerceIn(0, TONE_MAX) - 20
            return if (g < 0) 0x80 or -g else g
        }

        /** Setup 2 register: subwoofer phase (bit 7), output select (6..5), level meter (4..3), low-pass fc (2..0). */
        fun setup2(
            subFc: Int,
            phase: Boolean = false,
        ): Int = (if (phase) 0x80 else 0) or (subFc and 0x07)

        /** Level in dB of FL, FR, RL, RR for a balance / fader position (0..60 each). */
        fun speakerLevels(
            balance: Int,
            fade: Int,
        ): IntArray {
            val b = balance.coerceIn(0, FADER_MAX) - FADER_CENTER // < 0: towards left
            val f = fade.coerceIn(0, FADER_MAX) - FADER_CENTER // < 0: towards front
            return IntArray(4) { speaker ->
                val right = speaker % 2 == 1
                val rear = speaker >= 2
                val dx = if (right) maxOf(0, -b) else maxOf(0, b)
                val dy = if (rear) maxOf(0, -f) else maxOf(0, f)
                val index = (FADER_CENTER - sqrt((dx * dx + dy * dy).toDouble()).roundToInt()).coerceIn(0, FADER_CENTER)
                FADER_TABLE[index]
            }
        }
    }
}
