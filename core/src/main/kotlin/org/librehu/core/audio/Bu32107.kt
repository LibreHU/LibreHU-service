package org.librehu.core.audio

import kotlin.math.abs
import kotlin.math.roundToInt

/** Writes bytes to consecutive 16-bit "Select Addresses" of an I2C chip (the chip increments the address itself). */
fun interface BlockWriter {
    fun write(
        address: Int,
        data: ByteArray,
    ): Boolean
}

/**
 * Driver for the ROHM BU32107EFV-M sound processor (boards D0 and others with a 4th board-id character other than A),
 * written from the ROHM datasheet (BU32107EFV-M Rev.001, 07.Apr.2017, "Select Address & Data" and "Command
 * Specification"). The board wiring (which pins carry Android's audio, the subwoofer outputs, the formats of the
 * digital ports) comes from Jancar's `AudioBU32107` / `BU32107_Init` in `libJanCarIVI.so`, read statically; every value
 * Jancar writes was checked against the datasheet (docs/audio.md, "BU32107").
 *
 * I2C: `/dev/i2c-6`, slave 80h (7-bit 0x40), 16-bit select address then data, address auto-increment. Android's audio
 * reaches the chip digitally (I2S 16 bits on the D-1 pins, Digital Input1), the radio and the AUX jack through the ADC
 * (Single4 / Single5). The subwoofer leaves on the SL and SR faders (fader input selector SL/SL).
 *
 * Everything runs in the chip's "Table" mode (fixed filter choices computed by the chip), unlike Jancar which sends its
 * own biquad coefficients for the EQ and the front high-pass: nothing here can write a broken coefficient.
 *
 * Volume: digital attenuator of the six outputs (0.5 dB steps), the analog faders only for balance, fader, speaker
 * levels, subwoofer level and mute, as Jancar does. Not verified on hardware: no tester has a BU32107 board yet.
 */
class Bu32107(
    private val io: BlockWriter,
    /** Analog input of the AUX jack (0103h selector: 0 = Single1 .. 4 = Single5) and its gain (0..36 dB). */
    private val auxInput: Int = 4,
    private val auxGainDb: Int = 5,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : SoundProcessor {
    override val chip = "BU32107"

    /** Last value written to each select address. */
    val shadow = HashMap<Int, Int>()

    private var muted = true
    private var android = true
    private var androidGainDb = 0
    private var balanceLevels = IntArray(4)
    private var trims = IntArray(4)
    private var loudness = 0
    private var subOn = false
    private var subDb = 0
    private var subInvert = false
    private var tone = intArrayOf(0, 0, 0)
    private var extras = Bu32107Settings()
    private var noise = 0

    override val isMuted: Boolean get() = muted

    /**
     * Datasheet "Initialization": System Reset, Initial Setup with the RAM clear bits set, RAM clear released, every
     * other address, the faders last (left at -inf: muted). Board values as in Jancar's `BU32107_Init`.
     */
    override fun init(): Boolean {
        shadow.clear()
        var ok = w(SYSTEM_RESET, 0x81)
        sleep(100)
        // Initial Setup1: Data and Coef RAM clear, MCK 256 fs, fs 48 kHz; Setup2: every block used (all flat for now).
        ok = w(0x0001, 0xCC, 0x00, 0x00, 0x00, 0x00, 0x33, 0xFF, 0xF3) && ok
        ok = w(0x0001, 0x0C) && ok
        // Digital formats: BCK/LRCK inputs, Input1 / Input3 / Output3 / ExtIO I2S 16 bits, Output1 S/PDIF 24 bits.
        ok = w(0x0010, 0x0F, 0x02, 0x02, 0x0C, 0x00, 0x00, 0x02, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00) && ok
        // Test A..F: the values the datasheet requires.
        ok = w(0x001D, 0x00, 0xC0, 0x00, 0xC0, 0x34, 0x05) && ok
        // Input gain 0, fader inputs: rear RL/RR, sub SL/SL (subwoofer on the SL and SR faders).
        ok = w(INPUT_GAIN, 0x00) && ok
        ok = w(0x0102, 0x20) && ok
        // Analog mixing: sources stereo mix, mixing off, AVol(AMix) -inf; digital mixing FL/FR with DAC mixing on, its
        // level AVol(DMix) -inf until Android is mixed over AUX.
        ok = w(0x0104, 0xAA, 0x0A, 0x80, 0x80, 0x80, 0xF0, 0x80) && ok
        // Digital IO: Output1 on C-1, Input1 on D-1 (Android).
        ok = w(0x0200, 0x00, 0x03, IO_ANDROID_TO_INPUT1) && ok
        // DSP selectors: DSP input = Input1 (Android), P2Bass and S X'over after the time alignment, SR = digital mix.
        ok = w(DSP_SELECTOR1, DSP_INPUT1, 0x00, 0x05, 0x32, 0x00, 0x20) && ok
        ok = writeDelays() && ok
        // Spectrum analyser as Jancar leaves it (not read by LibreHU).
        ok = w(0x0500, 0x04, 0x09) && ok
        ok = writeEq() && ok
        ok = writeTone() && ok
        // DC cut / de-emphasis off; loudness, high-pass filters, P2Bass, subwoofer filters; IIR through.
        ok = w(0x0700, 0x00) && ok
        ok = writeFilters() && ok
        ok = w(0x0709, 0x00) && ok
        // BEEP off, Test1, DVol(Output2) 0 dB.
        ok = w(0x0800, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40) && ok
        // Digital attenuators -inf until the volume is set, boosts 0 dB, faders -inf.
        ok = w(DVOL_ATT, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) && ok
        ok = w(DVOL_BOOST, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80) && ok
        ok = w(FADER, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80) && ok
        muted = true
        android = true
        return ok
    }

    /** Fader volume -inf on every output (the chip ramps it, "Advanced Switch"), or the levels back. */
    override fun setMute(on: Boolean): Boolean {
        muted = on
        val ok = writeFaders()
        if (on) sleep(50)
        return ok
    }

    override fun selectAndroid(gainDb: Int): Boolean {
        android = true
        androidGainDb = gainDb.coerceIn(0, ANDROID_GAIN_MAX)
        var ok = w(0x0202, IO_ANDROID_TO_INPUT1)
        ok = w(DSP_SELECTOR1, dspSelector1()) && ok
        return writeBoosts() && ok
    }

    override fun selectAux(): Boolean {
        android = false
        var ok = w(ANALOG_INPUT, auxInput and 0x07)
        ok = w(INPUT_GAIN, auxGainDb.coerceIn(0, INPUT_GAIN_MAX)) && ok
        ok = w(DSP_SELECTOR1, dspSelector1()) && ok
        return writeBoosts() && ok
    }

    /** Android's gain: digital (DVol boost), Android reaching this chip in I2S. */
    override fun setInputGain(gainDb: Int): Boolean {
        androidGainDb = gainDb.coerceIn(0, ANDROID_GAIN_MAX)
        return writeBoosts()
    }

    override fun setVolume(
        step: Int,
        offsetDb: Int,
    ): Boolean {
        val s = step.coerceIn(0, Bd37534.MAX_VOLUME)
        val db = if (s == 0) Double.NEGATIVE_INFINITY else (Bd37534.VOLUME_CURVE[s] - VOLUME_TOP_DB + offsetDb).toDouble()
        val v = dvolAtt(db)
        return w(DVOL_ATT, v, v, v, v, v, v)
    }

    /**
     * Android over AUX: the D-1 pins move to Digital Input3, mixed into FL / FR by the DAC digital mixing, at
     * AVol(DMix) = volume + offset (0..-69 dB), as Jancar's `setMixer` / `BU32107_SetDigitalMixVol`.
     */
    override fun setMixing(
        step: Int?,
        offsetDb: Int,
    ): Boolean {
        if (step == null || android) {
            return w(0x010A, 0x80) && w(0x0202, IO_ANDROID_TO_INPUT1)
        }
        val s = step.coerceIn(0, Bd37534.MAX_VOLUME)
        val db = Bd37534.VOLUME_CURVE[s] - VOLUME_TOP_DB + offsetDb
        val ok = w(0x0202, IO_ANDROID_TO_INPUT3)
        return w(0x010A, avol(if (s == 0) -100 else db)) && ok
    }

    override fun setToneDb(
        bassDb: Int,
        middleDb: Int,
        trebleDb: Int,
    ): Boolean {
        tone = intArrayOf(bassDb, middleDb, trebleDb)
        return writeTone()
    }

    /** The BD37534 tone frequencies do not exist here: [Bu32107Settings.bassFc]... are used instead. */
    override fun setToneSetup(setup: ToneSetup): Boolean = true

    override fun setBalanceFade(
        balance: Int,
        fade: Int,
        trimsDb: IntArray,
    ): Boolean {
        // Jancar's table gives +1 dB at the centre (a BD37534 habit): 0 dB at most here, boosts only from the levels.
        balanceLevels = Bd37534.speakerLevels(balance, fade).map { minOf(0, it) }.toIntArray()
        trims = IntArray(4) { trimsDb.getOrElse(it) { 0 }.coerceIn(Bd37534.TRIM_MIN_DB, Bd37534.TRIM_MAX_DB) }
        return writeFaders() && writeBoosts()
    }

    /**
     * Loudness of [gainDb] (0..15 dB): the chip's loudness filter cuts 1 kHz by that much (bass under
     * [Bu32107Settings.loudLpf] and treble over [Bu32107Settings.loudHpf] unchanged), the digital boost gives it back,
     * so bass and treble rise by [gainDb] as on the BD37534. The BD37534 frequency and high cut are not used.
     */
    override fun setLoudness(
        gainDb: Int,
        freqHz: Int,
        hicut: Int,
    ): Boolean {
        loudness = gainDb.coerceIn(0, LOUDNESS_MAX_DB)
        return writeLoudness() && writeBoosts()
    }

    /**
     * Subwoofer on the SL / SR faders, level 0..12 = -5..+7 dB (fader up to 0 dB, digital boost above), phase of its
     * low-pass; the low-pass frequency and order are [Bu32107Settings.subLpf] / [Bu32107Settings.subLpfSteep].
     */
    override fun setSubwoofer(
        on: Boolean,
        level: Int,
        config: SubConfig,
    ): Boolean {
        subOn = on
        subDb = level.coerceIn(0, Bd37534.SUB_MAX_LEVEL) + Bd37534.SUB_BASE_DB
        subInvert = config.phase180
        return w(0x0707, subLpfByte()) && writeFaders() && writeBoosts()
    }

    /** Every setting of [Bu32107Settings] (EQ, tone frequencies, loudness filter, high-pass, P2Bass, delays). */
    fun applySettings(settings: Bu32107Settings): Boolean {
        extras = settings.normalized()
        return writeEq() && writeTone() && writeFilters() && writeDelays()
    }

    /**
     * Pink (1) or white (2) noise of the chip's generator in place of the music, 0 = off: for measurements (REW, RTA).
     * The volume applies: start low.
     */
    fun setTestNoise(mode: Int): Boolean {
        noise = mode.coerceIn(0, 2)
        val ok = w(0x0204, (if (noise > 0) 0x01 else 0) or (if (noise == 2) 0x04 else 0))
        return w(DSP_SELECTOR1, dspSelector1()) && ok
    }

    val testNoise: Int get() = noise

    // --- register values ---

    private fun dspSelector1(): Int = (if (android) DSP_INPUT1 else 0) or (if (noise > 0) 0x40 else 0)

    private fun writeEq(): Boolean {
        val e = extras
        val front = IntArray(EQ_BANDS) { eqByte(e.eqFront[it], e.eqNarrow, common = e.eqRearSame, rear = false) }
        var ok = w(EQ_MODE, if (e.eqHeadroom) 0x11 else 0x00)
        if (e.eqRearSame) return w(EQ_FRONT, *front) && ok
        // Datasheet: before the common setting is cancelled, the rear must hold the front's values (pop noise).
        ok = w(EQ_REAR, *IntArray(EQ_BANDS) { eqByte(e.eqFront[it], e.eqNarrow, common = false, rear = true) }) && ok
        ok = w(EQ_FRONT, *front) && ok
        return w(EQ_REAR, *IntArray(EQ_BANDS) { eqByte(e.eqRear[it], e.eqNarrow, common = false, rear = true) }) && ok
    }

    private fun writeTone(): Boolean {
        val e = extras
        val fcs = intArrayOf(BASS_FC.indexOf(e.bassFc), MIDDLE_F0.indexOf(e.middleF0), TREBLE_FC.indexOf(e.trebleFc))
        val front = IntArray(3) { toneByte(tone[it], fcs[it].coerceAtLeast(0), common = true) }
        return w(TONE_FRONT, *front)
    }

    private fun writeLoudness(): Boolean {
        val e = extras
        val filter = (LOUD_HPF.indexOf(e.loudHpf).coerceAtLeast(0) shl 4) or LOUD_LPF.indexOf(e.loudLpf).coerceAtLeast(0)
        return w(0x0701, filter, 0x80 or (LOUD_HIBOOST.indexOf(e.loudHiBoost).coerceAtLeast(0) shl 4) or loudness)
    }

    private fun writeFilters(): Boolean {
        val e = extras
        var ok = writeLoudness()
        ok = w(0x0703, hpfByte(e.hpfFront, e.hpfFrontSteep, e.hpfFrontInvert), hpfByte(e.hpfRear, e.hpfRearSteep, e.hpfRearInvert)) && ok
        ok = w(0x0705, p2Bass(e.p2BassFrontDb, e.p2BassFrontHz), p2Bass(e.p2BassRearDb, e.p2BassRearHz)) && ok
        return w(0x0707, subLpfByte(), SUB_HPF_FC.indexOf(e.subHpf).coerceAtLeast(0)) && ok
    }

    private fun subLpfByte(): Int =
        (if (subInvert) 0x40 else 0) or (if (extras.subLpfSteep) 0x10 else 0) or SUB_LPF_FC.indexOf(extras.subLpf).coerceAtLeast(0)

    /** Time alignment FL, FR, RL, RR, S: high 2 bits then low 8 bits each (applied on the low byte). */
    private fun writeDelays(): Boolean {
        val bytes = IntArray(14)
        for (i in 0 until 5) {
            val v = delayData(extras.delays.getOrElse(i) { 0 })
            bytes[2 * i] = (v shr 8) and 0x03
            bytes[2 * i + 1] = v and 0xFF
        }
        return w(0x0400, *bytes)
    }

    private fun writeFaders(): Boolean {
        val f =
            IntArray(6) { ch ->
                when {
                    muted -> 0x80
                    ch < 4 -> fader(if (balanceLevels[ch] <= -79) -100 else minOf(0, balanceLevels[ch] + trims[ch]))
                    subOn -> fader(minOf(0, subDb))
                    else -> 0x80
                }
            }
        return w(FADER, *f)
    }

    /** Digital boost per output: Android gain (Android source), loudness make-up, speaker level and sub above 0 dB. */
    private fun writeBoosts(): Boolean {
        val base = (if (android) androidGainDb else 0) + loudness
        val b =
            IntArray(6) { ch ->
                val extra = if (ch < 4) maxOf(0, balanceLevels[ch] + trims[ch]) else maxOf(0, subDb)
                dvolBoost(base + extra)
            }
        return w(DVOL_BOOST, *b)
    }

    private fun w(
        address: Int,
        vararg values: Int,
    ): Boolean {
        val data = ByteArray(values.size) { values[it].toByte() }
        var ok = false
        for (attempt in 0 until WRITE_TRIES) {
            ok = io.write(address, data)
            if (ok) break
            sleep(20)
        }
        if (ok) values.forEachIndexed { i, v -> shadow[address + i] = v and 0xFF }
        return ok
    }

    companion object {
        const val I2C_ADDRESS = 0x40
        const val SYSTEM_RESET = 0xFEFE
        const val INPUT_GAIN = 0x0101
        const val ANALOG_INPUT = 0x0103
        const val DSP_SELECTOR1 = 0x0203
        const val EQ_MODE = 0x0600
        const val EQ_FRONT = 0x0610
        const val TONE_FRONT = 0x061D
        const val EQ_REAR = 0x0620
        const val DVOL_ATT = 0x0900
        const val DVOL_BOOST = 0x0906
        const val FADER = 0x0A00

        /** 0202h: D-1 pins to Digital Input1 (Android is the source) or to Input3 (Android mixed over AUX). */
        const val IO_ANDROID_TO_INPUT1 = 0x08
        const val IO_ANDROID_TO_INPUT3 = 0x80

        /** 0203h bit 7: DSP input = DC cut HPF (Input1) instead of the ADC. */
        const val DSP_INPUT1 = 0x80

        const val INPUT_GAIN_MAX = 36
        const val ANDROID_GAIN_MAX = 20
        const val LOUDNESS_MAX_DB = 15
        const val EQ_BANDS = 13
        const val EQ_MAX_DB = 24
        const val TONE_MAX_DB = 12
        const val P2BASS_MAX_DB = 12

        /** Top of the volume curve: the BD37534 curve ends at +5 dB, the digital attenuator at 0 dB. */
        const val VOLUME_TOP_DB = 5

        /** 21.3 ms: largest time alignment in 2ch-input mode (3FFh at 48 kHz). */
        const val DELAY_MAX_TENTHS = 213
        private const val WRITE_TRIES = 3

        val EQ_BANDS_HZ = intArrayOf(50, 80, 125, 200, 315, 500, 800, 1250, 2000, 3150, 5000, 8000, 12500)
        val BASS_FC = intArrayOf(40, 63, 100, 160)
        val MIDDLE_F0 = intArrayOf(400, 630, 1000, 1600)
        val TREBLE_FC = intArrayOf(2500, 4000, 6300, 10000)
        val LOUD_LPF = intArrayOf(30, 40, 50, 63, 80, 100, 125)
        val LOUD_HPF = intArrayOf(3000, 4000, 5000, 6300, 8000, 10000, 12500)
        val LOUD_HIBOOST = intArrayOf(0, 20, 55)

        /** Front / rear high-pass and subwoofer low-pass corners, index = register value (0 = Through). */
        val HPF_FC = intArrayOf(0, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250)
        val SUB_LPF_FC = HPF_FC
        val SUB_HPF_FC = intArrayOf(0, 20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200)
        val P2BASS_FC = intArrayOf(0, 54, 68, 86, 108, 134, 172, 214)

        /** 13-band EQ byte: gain in 2 dB steps (bit 4 = cut), bit 5 Q 2.2 (0 = 4.7), bit 6 front and rear together. */
        fun eqByte(
            db: Int,
            narrow: Boolean,
            common: Boolean,
            rear: Boolean,
        ): Int {
            val g = db.coerceIn(-EQ_MAX_DB, EQ_MAX_DB) / 2
            var b = abs(g) and 0x0F
            if (g < 0) b = b or 0x10
            if (!narrow) b = b or 0x20
            if (common && !rear) b = b or 0x40
            return b
        }

        /** 3-band tone byte: gain in 2 dB steps 0..12 dB (bit 3 = cut), frequency bits 5..4, bit 6 front and rear. */
        fun toneByte(
            db: Int,
            fcIndex: Int,
            common: Boolean,
        ): Int {
            val g = db.coerceIn(-TONE_MAX_DB, TONE_MAX_DB) / 2
            var b = abs(g) and 0x07
            if (g < 0) b = b or 0x08
            b = b or ((fcIndex and 0x03) shl 4)
            if (common) b = b or 0x40
            return b
        }

        /** Front (0703h) / rear (0704h) high-pass: corner bits 3..0, bit 5 4th order, bit 6 180°. */
        fun hpfByte(
            hz: Int,
            steep: Boolean,
            invert: Boolean,
        ): Int = HPF_FC.indexOf(hz).coerceAtLeast(0) or (if (steep) 0x20 else 0) or (if (invert) 0x40 else 0)

        /** P2Bass byte: bit 7 set, frequency bits 6..4 (0 = Through), gain 0..12 dB bits 3..0. */
        fun p2Bass(
            db: Int,
            hz: Int,
        ): Int = 0x80 or (P2BASS_FC.indexOf(hz).coerceAtLeast(0) shl 4) or db.coerceIn(0, P2BASS_MAX_DB)

        /** DVol(Att) 0900h..0905h: 64 - 2·dB, 0.5 dB steps, 0 dB..-95.5 dB, 00h = -inf. */
        fun dvolAtt(db: Double): Int =
            when {
                db.isInfinite() || db < -95.5 -> 0x00
                db >= 0.0 -> 0x40
                else -> (64 - (db * 2).roundToInt()).coerceIn(0x40, 0xFF)
            }

        /** DVol(Boost) 0906h..090Bh: 128 - 2·dB, 0..+36 dB. */
        fun dvolBoost(db: Int): Int = 128 - 2 * db.coerceIn(0, 36)

        /** Fader volume 0A00h..0A05h: bit 7 set, 32 - dB for 0..-79 dB, 80h = -inf. */
        fun fader(db: Int): Int = if (db <= -80) 0x80 else 0x80 or (32 - db.coerceIn(-79, 0))

        /** AVol(DMix) 010Ah: bit 7 set, 32 - dB for 0..-69 dB, 80h = -inf. */
        fun avol(db: Int): Int = if (db < -69) 0x80 else 0x80 or (32 - db.coerceIn(-69, 0))

        /** Time alignment data: time (tenths of ms) × 4.8 (48 samples per ms), at most 3FFh. */
        fun delayData(tenths: Int): Int = (tenths.coerceIn(0, DELAY_MAX_TENTHS) * 4.8).roundToInt().coerceAtMost(0x3FF)
    }
}
