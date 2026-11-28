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
 *
 * Everything the chip offers (ROHM BD37534FV datasheet, register map) is driven here, beyond what Jancar's software
 * used: subwoofer low-pass 55/85/120/160 Hz or off, its phase (0/180°) and output select (low-pass, front or rear
 * copy), each speaker's own level on top of balance / fader, centre frequency and Q of bass / middle / treble in
 * 1 dB steps, loudness centre frequency and high cut. The chip is write-only over I2C (the datasheet documents write
 * transfers only, slave address 80h with R/W = 0): [shadow] is the only record of its state.
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
    private var subConfig = SubConfig()

    /** Power-on sequence of libJanCarIVI `AudioBD37534::init()`, then Android input selected and muted. */
    fun init(): Boolean {
        var ok = true
        ok = w(SETUP_1, 0xB7) && ok
        ok = w(SETUP_2, 0x00) && ok
        ok = w(SETUP_3, 0x11) && ok
        ok = w(BASS_SETUP, 0x11) && ok
        ok = w(MIDDLE_SETUP, 0x21) && ok
        ok = w(TREBLE_SETUP, 0x20) && ok
        ok = w(MIXING, MIXING_OFF) && ok
        ok = w(FADER_SUB, 0x00) && ok
        sleep(300)
        ok = w(SETUP_2, setup2(subFc = 0)) && ok
        ok = setLoudness(0) && ok
        ok = setBalanceFade(FADER_CENTER, FADER_CENTER) && ok
        ok = setInput(INPUT_ANDROID, 0) && ok
        ok = setMute(true) && ok
        return ok
    }

    /** Input selector (0x05, bit 7 set as Jancar does) and its gain (0x06, 0..+20 dB). Android/PC = input 11. */
    fun setInput(
        input: Int,
        gainDb: Int,
    ): Boolean {
        inputGainDb = gainDb.coerceIn(0, INPUT_GAIN_MAX)
        return w(INPUT_SELECT, 0x80 or (input and 0x7F)) && writeInputGain()
    }

    /**
     * Gain of the selected input alone (0x06, 0..+16 dB here, no pop noise per the datasheet), mute bit kept. The
     * gain stage comes before the volume: it clips on loud content at any volume once the input level times the gain
     * goes past about 2.1 Vrms (datasheet: maximum input voltage 2.1 Vrms min, 2.3 typ, at THD+N 1 %).
     */
    fun setInputGain(gainDb: Int): Boolean {
        inputGainDb = gainDb.coerceIn(0, INPUT_GAIN_MAX)
        return writeInputGain()
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

    /**
     * Mixing input (0x30), a mono input added to the selected one: on this board, the SoC's (Android) audio, which
     * ivi-services mixes in while AUX, AV or an external radio is selected (`platformSwitchAudioTo` → `setMixer`).
     * Its level follows the master volume [step] plus [offsetDb], at most +7 dB, as libJanCarIVI's
     * `AudioBD37534::setMonoVolume`; null = off (0xFF).
     */
    fun setMixing(
        step: Int?,
        offsetDb: Int = 0,
    ): Boolean =
        w(
            MIXING,
            if (step == null) MIXING_OFF else attenuator(minOf(MIXING_MAX_DB, VOLUME_CURVE[step.coerceIn(0, MAX_VOLUME)] + offsetDb)),
        )

    /** Bass / middle / treble, 0..20 each (10 = flat, 2 dB per step). */
    fun setTone(
        bass: Int,
        middle: Int,
        treble: Int,
    ): Boolean = setToneDb(2 * bass - 20, 2 * middle - 20, 2 * treble - 20)

    /** Bass / middle / treble gains, -20..+20 dB each in 1 dB steps (registers 0x51, 0x54, 0x57). */
    fun setToneDb(
        bassDb: Int,
        middleDb: Int,
        trebleDb: Int,
    ): Boolean = w(BASS_GAIN, toneDb(bassDb)) and w(MIDDLE_GAIN, toneDb(middleDb)) and w(TREBLE_GAIN, toneDb(trebleDb))

    /**
     * Centre frequency and Q of the three bands (registers 0x41, 0x44, 0x47): bass 60/80/100/120 Hz, Q 0.5/1/1.5/2;
     * middle 500/1000/1500/2500 Hz, Q 0.75/1/1.25/1.5; treble 7500/10000/12500/15000 Hz, Q 0.75/1.25. Q given ×100.
     * Unknown values fall back to Jancar's: 80 Hz Q 1, 1500 Hz Q 1, 12500 Hz Q 0.75.
     */
    fun setToneSetup(setup: ToneSetup): Boolean =
        w(BASS_SETUP, bandSetup(BASS_FREQS, BASS_QS, setup.bassFreq, setup.bassQ, 1, 1)) and
            w(MIDDLE_SETUP, bandSetup(MIDDLE_FREQS, MIDDLE_QS, setup.middleFreq, setup.middleQ, 2, 1)) and
            w(TREBLE_SETUP, bandSetup(TREBLE_FREQS, TREBLE_QS, setup.trebleFreq, setup.trebleQ, 2, 0))

    /** Loudness gain, 0..20 dB (register 0x75, high cut 1 and the centre frequency left as they are). */
    fun setLoudness(level: Int): Boolean = w(LOUDNESS, loudness(level, loudnessHicut))

    private var loudnessHicut = 1

    /**
     * Loudness gain 0..20 dB, centre frequency 250/400/800 Hz (setup 3, 0x03) and high cut 1..4 (bits 6..5 of 0x75).
     * Unknown frequency: 800 Hz, Jancar's.
     */
    fun setLoudness(
        gainDb: Int,
        freqHz: Int,
        hicut: Int,
    ): Boolean {
        loudnessHicut = hicut.coerceIn(1, 4)
        val ok = w(SETUP_3, setup3(freqHz))
        return w(LOUDNESS, loudness(gainDb, loudnessHicut)) && ok
    }

    /**
     * Balance and fader, 0..60 each (30 = centre). Balance 0 = left, fader 0 = front (ivi-services convention).
     * Every speaker loses the distance of the chosen point to it, mapped to dB with Jancar's table.
     */
    fun setBalanceFade(
        balance: Int,
        fade: Int,
        trimsDb: IntArray = IntArray(4),
    ): Boolean {
        val levels = speakerLevels(balance, fade)
        var ok = true
        for (i in levels.indices) {
            val trim = trimsDb.getOrElse(i) { 0 }.coerceIn(TRIM_MIN_DB, TRIM_MAX_DB)
            // A speaker cut by balance / fader (-79 dB) stays cut whatever its trim.
            val db = if (levels[i] <= -79) -79 else levels[i] + trim
            ok = w(SPEAKER_REGISTERS[i], attenuator(db)) && ok
        }
        return ok
    }

    /**
     * Subwoofer output with its level 0..12 (= -5..+7 dB on this board). On: low-pass at 120 Hz, then level.
     * Off: fader cut, anti-pop pause, filter off — the sequence of libJanCarIVI `setSubWooferOnOff`.
     */
    fun setSubwoofer(
        on: Boolean,
        level: Int,
        config: SubConfig = subConfig,
    ): Boolean {
        subLevel = level.coerceIn(0, SUB_MAX_LEVEL)
        subConfig = config
        val wasOn = subOn
        subOn = on
        return if (on) {
            val ok = w(SETUP_2, setup2(subFcIndex(config.cutoffHz), config.phase180, config.output))
            if (!wasOn) sleep(150)
            w(FADER_SUB, attenuator(SUB_BASE_DB + subLevel)) && ok
        } else {
            val ok = w(FADER_SUB, 0x00)
            if (wasOn) sleep(300)
            w(SETUP_2, setup2(subFc = 0, phase = config.phase180)) && ok
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
        const val MIXING_OFF = 0xFF
        const val MIXING_MAX_DB = 7
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

        /**
         * Gain of the Android input chosen in LibreHU (0 by default: most headroom, see [setInputGain]); ivi-services
         * sets +4 dB on A0 boards and +5 dB on A1 boards ([org.librehu.core.unit.BoardProfile.androidGainDb]).
         */
        const val ANDROID_GAIN_MAX = INPUT_GAIN_MAX
        const val SUB_DEFAULT_LEVEL = 6
        const val SUB_FC_120HZ = 3

        /** Input gain range of the chip (0x06), dB. */
        const val INPUT_GAIN_MAX = 20
        const val TONE_DB_MAX = 20
        const val LOUDNESS_MAX = 20
        const val TRIM_MIN_DB = -20
        const val TRIM_MAX_DB = 6

        /** Subwoofer low-pass cut-offs (setup 2 bits 2..0 = 1..4), Hz; 0 = filter off. */
        val SUB_CUTOFFS = intArrayOf(55, 85, 120, 160)
        const val SUB_OUTPUT_LPF = 0
        const val SUB_OUTPUT_FRONT = 1
        const val SUB_OUTPUT_REAR = 2

        val BASS_FREQS = intArrayOf(60, 80, 100, 120)
        val BASS_QS = intArrayOf(50, 100, 150, 200)
        val MIDDLE_FREQS = intArrayOf(500, 1000, 1500, 2500)
        val MIDDLE_QS = intArrayOf(75, 100, 125, 150)
        val TREBLE_FREQS = intArrayOf(7500, 10000, 12500, 15000)
        val TREBLE_QS = intArrayOf(75, 125)
        val LOUDNESS_FREQS = intArrayOf(250, 400, 800)

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
        fun tone(step: Int): Int = toneDb(2 * step.coerceIn(0, TONE_MAX) - 20)

        /** Tone gain in dB (-20..+20) to register value: bit 7 = cut, bits 4..0 = |gain|. */
        fun toneDb(db: Int): Int {
            val g = db.coerceIn(-TONE_DB_MAX, TONE_DB_MAX)
            return if (g < 0) 0x80 or -g else g
        }

        /** Band setup register: fo index in bits 5..4, Q index in bits 1..0 (fallbacks: Jancar's indexes). */
        fun bandSetup(
            freqs: IntArray,
            qs: IntArray,
            freq: Int,
            q: Int,
            defaultFreq: Int,
            defaultQ: Int,
        ): Int {
            val f = freqs.indexOf(freq).takeIf { it >= 0 } ?: defaultFreq
            val qi = qs.indexOf(q).takeIf { it >= 0 } ?: defaultQ
            return (f shl 4) or qi
        }

        /** Setup 3 register: loudness fo index in bits 4..3, bit 0 set (datasheet); 800 Hz when unknown. */
        fun setup3(freqHz: Int): Int = ((LOUDNESS_FREQS.indexOf(freqHz).takeIf { it >= 0 } ?: 2) shl 3) or 0x01

        /** Loudness register: high cut 1..4 in bits 6..5, gain 0..20 dB in bits 4..0. */
        fun loudness(
            gainDb: Int,
            hicut: Int,
        ): Int = ((hicut.coerceIn(1, 4) - 1) shl 5) or gainDb.coerceIn(0, LOUDNESS_MAX)

        /** Setup 2 low-pass index (1..4) of a cut-off in Hz; 0 (off) for 0 or an unknown value. */
        fun subFcIndex(cutoffHz: Int): Int = SUB_CUTOFFS.indexOf(cutoffHz) + 1

        /**
         * Setup 2 register (datasheet): low-pass phase (bit 7), level meter reset (bit 6, left 0), subwoofer output
         * select (bits 5..4: 0 low-pass, 1 front, 2 rear), low-pass fc (bits 2..0: 0 off, 1..4 = 55/85/120/160 Hz).
         */
        fun setup2(
            subFc: Int,
            phase: Boolean = false,
            output: Int = SUB_OUTPUT_LPF,
        ): Int = (if (phase) 0x80 else 0) or ((output.coerceIn(0, 2)) shl 4) or (subFc and 0x07)

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

/** Subwoofer output of the BD37534: low-pass cut-off (Hz, 0 = off), phase, what the output carries. */
data class SubConfig(
    val cutoffHz: Int = 120,
    val phase180: Boolean = false,
    val output: Int = Bd37534.SUB_OUTPUT_LPF,
)

/** Centre frequencies (Hz) and Q (×100) of the BD37534's three bands; defaults = Jancar's setup. */
data class ToneSetup(
    val bassFreq: Int = 80,
    val bassQ: Int = 100,
    val middleFreq: Int = 1500,
    val middleQ: Int = 100,
    val trebleFreq: Int = 12500,
    val trebleQ: Int = 75,
)
