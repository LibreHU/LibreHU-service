package org.librehu.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Driver for the AKM AK7604 DSP of A1 boards whose 4th board-id character is neither A nor B (I2C bus 5, address
 * 0x18, reset GPIO 14), rebuilt from Jancar's `Ak7604_*` functions in `libJanCarIVI.so` (ivi-services 3.0.0, read
 * statically). The AK7604 is programmable: it runs Jancar's own DSP program ([Ak7604Program]); every coefficient
 * address below belongs to that program. AKM publishes only a product brief of this chip; the command set (C0h / 40h
 * registers, B8h program, B4h coefficients, 80h..8Fh + A4h coefficients while running, 72h CRC) is the one of AKM's
 * AK7755 datasheet, which Jancar's code follows.
 *
 * What Jancar's program offers and this driver drives: master volume, input mixer (analog / I2S), mute, four speaker
 * levels (balance, fader, speaker levels), subwoofer level with a low-pass and a subsonic high-pass, a high-pass for
 * the front and for the rear speakers, P2Bass (low shelf) front and rear. Not driven yet: Jancar's band EQ
 * (`Ak7604_SetEq`), loudness and time alignment, whose coefficient formats are not decoded. Not tested on a board.
 */
class Ak7604(
    private val bus: Bus,
    private val program: Ak7604Program,
    /** Reset pin of the chip (board profile: GPIO 14), true = high. */
    private val reset: (Boolean) -> Unit,
    /** Analog input of the AUX jack (register 8Dh = input × 2) and its level in the input mixer. */
    private val auxInput: Int = 1,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : SoundProcessor {
    /** Raw I2C transfers with the chip. */
    interface Bus {
        fun write(data: ByteArray): Boolean

        /** Writes [command], then reads [length] bytes (repeated start); null on error. */
        fun read(
            command: ByteArray,
            length: Int,
        ): ByteArray?
    }

    override val chip = "AK7604"

    /** Last 24-bit word written at each coefficient address while running. */
    val cram = HashMap<Int, Int>()
    val registers = HashMap<Int, Int>()

    private var muted = true
    private var android = true
    private var androidGainDb = 0
    private var mixOffsetDb: Int? = null
    private var balanceLevels = IntArray(4)
    private var trims = IntArray(4)
    private var subOn = false
    private var subDb = 0
    private var subInvert = false
    private var extras = Ak7604Settings()

    override val isMuted: Boolean get() = muted

    /** Jancar's `Ak7604_Init` and `init_setup_003`: reset, id check, registers, program, coefficients, run. */
    override fun init(): Boolean {
        reset(true)
        sleep(1)
        reset(false)
        sleep(10)
        reset(true)
        sleep(10)
        val id = readReg(REG_ID)
        if (id != CHIP_ID) return false
        var ok = updateReg(0x02) { it and 0x7F }
        ok = updateReg(0xA3) { it and 0xF8 } && ok
        sleep(10)
        val regs = program.registers
        for (i in 0 until regs.size / 2) ok = writeReg(regs[2 * i].toInt() and 0xFF, regs[2 * i + 1].toInt() and 0xFF) && ok
        sleep(10)
        ok = updateReg(0x02) { it or 0x80 } && ok
        ok = updateReg(0xA3) { it and 0xF8 } && ok
        sleep(10)
        ok = download(CMD_PRAM, program.pram, PRAM_CHUNK) && ok
        ok = download(CMD_CRAM, program.cram, CRAM_CHUNK) && ok
        sleep(10)
        ok = updateReg(0x02) { it or 0x80 } && ok
        ok = updateReg(0xA3) { it or 0x07 } && ok
        sleep(100)
        // init_setup_003: speaker high-pass and P2Bass flat, subwoofer pre-gain -10 dB.
        ok = writeFilters() && ok
        ok = runWrite(SUB_PRE_GAIN, gain24(-10.0)) && ok
        ok = writeReg(REG_MUTE, MUTE_ON) && ok
        muted = true
        android = true
        return ok && writeMixer()
    }

    override fun setMute(on: Boolean): Boolean {
        muted = on
        val ok = writeReg(REG_MUTE, if (on) MUTE_ON else 0x00)
        if (on) sleep(50)
        return ok
    }

    override fun selectAndroid(gainDb: Int): Boolean {
        android = true
        androidGainDb = gainDb.coerceIn(0, ANDROID_GAIN_MAX)
        return writeMixer()
    }

    override fun selectAux(): Boolean {
        android = false
        val ok = writeReg(REG_INPUT, (auxInput.coerceIn(0, 6) shl 1))
        return writeMixer() && ok
    }

    override fun setInputGain(gainDb: Int): Boolean {
        androidGainDb = gainDb.coerceIn(0, ANDROID_GAIN_MAX)
        return writeMixer()
    }

    override fun setVolume(
        step: Int,
        offsetDb: Int,
    ): Boolean {
        val s = step.coerceIn(0, Bd37534.MAX_VOLUME)
        val db = if (s == 0) OFF_DB else (Bd37534.VOLUME_CURVE[s] - VOLUME_TOP_DB + offsetDb).toDouble()
        return runWrite(MAIN_VOLUME, gain24(db))
    }

    /** Android over AUX: the I2S input of the mixer at [offsetDb] (Jancar's -6 dB plus the offset); null = off. */
    override fun setMixing(
        step: Int?,
        offsetDb: Int,
    ): Boolean {
        mixOffsetDb = if (step == null || step == 0) null else offsetDb
        return writeMixer()
    }

    /** Jancar's band EQ is not decoded: the chip's tone stays as its program sets it. */
    override fun setToneDb(
        bassDb: Int,
        middleDb: Int,
        trebleDb: Int,
    ): Boolean = true

    override fun setToneSetup(setup: ToneSetup): Boolean = true

    override fun setBalanceFade(
        balance: Int,
        fade: Int,
        trimsDb: IntArray,
    ): Boolean {
        balanceLevels = Bd37534.speakerLevels(balance, fade).map { minOf(0, it) }.toIntArray()
        trims = IntArray(4) { trimsDb.getOrElse(it) { 0 }.coerceIn(Bd37534.TRIM_MIN_DB, Bd37534.TRIM_MAX_DB) }
        var ok = true
        for (i in 0 until 4) {
            val db = if (balanceLevels[i] <= -79) OFF_DB else (balanceLevels[i] + trims[i]).toDouble()
            ok = runWrite(SPEAKERS[i], gain24(db)) && ok
        }
        return ok
    }

    /** The loudness of Jancar's program is not decoded. */
    override fun setLoudness(
        gainDb: Int,
        freqHz: Int,
        hicut: Int,
    ): Boolean = true

    /** Subwoofer level 0..12 = -5..+7 dB (11Ch) and its low-pass, whose phase 180° inverts the filter's numerator. */
    override fun setSubwoofer(
        on: Boolean,
        level: Int,
        config: SubConfig,
    ): Boolean {
        subOn = on
        subDb = level.coerceIn(0, Bd37534.SUB_MAX_LEVEL) + Bd37534.SUB_BASE_DB
        subInvert = config.phase180
        val ok = writeSubLowPass()
        return runWrite(SUB_LEVEL, if (on) gain24(subDb.toDouble()) else 0) && ok
    }

    fun applySettings(settings: Ak7604Settings): Boolean {
        extras = settings.normalized()
        return writeFilters()
    }

    // --- writes ---

    /** Input mixer of Jancar's program: ADC (18h / 1Ah) and I2S (19h / 1Bh), left and right. */
    private fun writeMixer(): Boolean {
        val adc = if (android) gain24(-144.0) else gain24(0.0)
        val i2sDb =
            when {
                android -> I2S_DB + androidGainDb
                mixOffsetDb != null -> I2S_DB + (mixOffsetDb ?: 0)
                else -> null
            }
        val i2s = if (i2sDb == null) 0 else gain24(i2sDb.toDouble())
        var ok = runWrite(MIX_ADC, adc)
        ok = runWrite(MIX_ADC + 2, adc) && ok
        ok = runWrite(MIX_I2S, i2s) && ok
        return runWrite(MIX_I2S + 2, i2s) && ok
    }

    private fun writeFilters(): Boolean {
        val e = extras
        var ok = runBiquad(HPF_FRONT, if (e.hpfFront <= 20) THROUGH else HPF, e.hpfFront, BASS_Q, 0.0)
        ok = runBiquad(HPF_FRONT + REAR, if (e.hpfRear <= 20) THROUGH else HPF, e.hpfRear, BASS_Q, 0.0) && ok
        ok = runBiquad(P2BASS_FRONT, if (e.p2BassFrontHz == 0) THROUGH else LOW_SHELF, e.p2BassFrontHz, BASS_Q, e.p2BassFrontDb.toDouble()) && ok
        ok = runBiquad(P2BASS_FRONT + REAR, if (e.p2BassRearHz == 0) THROUGH else LOW_SHELF, e.p2BassRearHz, BASS_Q, e.p2BassRearDb.toDouble()) && ok
        ok = runBiquad(SUB_HPF, if (e.subHpf == 0) THROUGH else HPF, e.subHpf, 1.0, 0.0) && ok
        return writeSubLowPass() && ok
    }

    private fun writeSubLowPass(): Boolean {
        val e = extras
        val c = biquad(if (e.subLpf == 0) THROUGH else LPF, e.subLpf, 1.0, 0.0)
        if (subInvert) for (i in intArrayOf(0, 1, 4)) c[i] = -c[i]
        return runWords(SUB_LPF, IntArray(5) { coef24(c[it]) })
    }

    private fun runBiquad(
        address: Int,
        type: Int,
        freqHz: Int,
        q: Double,
        gainDb: Double,
    ): Boolean {
        val c = biquad(type, freqHz, q, gainDb)
        return runWords(address, IntArray(5) { coef24(c[it]) })
    }

    private fun runWrite(
        address: Int,
        word: Int,
    ) = runWords(address, intArrayOf(word))

    /** Coefficients while the DSP runs: write preparation (80h + count - 1), then execution (A4h). */
    private fun runWords(
        address: Int,
        words: IntArray,
    ): Boolean {
        val data = ByteArray(3 + 3 * words.size)
        data[0] = (0x80 + words.size - 1).toByte()
        data[1] = (address shr 8).toByte()
        data[2] = address.toByte()
        words.forEachIndexed { i, w ->
            data[3 + 3 * i] = (w shr 16).toByte()
            data[4 + 3 * i] = (w shr 8).toByte()
            data[5 + 3 * i] = w.toByte()
        }
        val ok = bus.write(data) && bus.write(byteArrayOf(0xA4.toByte(), 0, 0))
        if (ok) words.forEachIndexed { i, w -> cram[address + i] = w }
        return ok
    }

    /** Program or coefficients while the DSP is held in reset, 50 words per transfer, then the chip's CRC checked. */
    private fun download(
        command: Int,
        data: ByteArray,
        chunk: Int,
    ): Boolean {
        val expected = crc16(data)
        for (attempt in 0 until DOWNLOAD_TRIES) {
            var ok = true
            var offset = 0
            var word = 0
            while (offset < data.size) {
                val n = minOf(chunk, data.size - offset)
                val packet = ByteArray(3 + n)
                packet[0] = command.toByte()
                packet[1] = (word shr 8).toByte()
                packet[2] = word.toByte()
                System.arraycopy(data, offset, packet, 3, n)
                ok = bus.write(packet) && ok
                offset += n
                word += WORDS_PER_CHUNK
            }
            val crc = bus.read(byteArrayOf(0x72, 0, 0), 2)
            if (ok && crc != null && ((crc[0].toInt() and 0xFF) shl 8 or (crc[1].toInt() and 0xFF)) == expected) return true
            sleep(10)
        }
        return false
    }

    private fun readReg(reg: Int): Int? = bus.read(byteArrayOf(0x40, 0, reg.toByte()), 1)?.let { it[0].toInt() and 0xFF }

    private fun writeReg(
        reg: Int,
        value: Int,
    ): Boolean {
        val ok = bus.write(byteArrayOf(0xC0.toByte(), 0, reg.toByte(), value.toByte()))
        if (ok) registers[reg] = value and 0xFF
        return ok
    }

    private fun updateReg(
        reg: Int,
        change: (Int) -> Int,
    ): Boolean {
        val v = readReg(reg) ?: return false
        return writeReg(reg, change(v) and 0xFF)
    }

    companion object {
        const val I2C_ADDRESS = 0x18
        const val CHIP_ID = 0x04
        const val REG_ID = 0xC0
        const val REG_MUTE = 0x89
        const val MUTE_ON = 0x70
        const val REG_INPUT = 0x8D
        const val CMD_PRAM = 0xB8
        const val CMD_CRAM = 0xB4
        private const val PRAM_CHUNK = 250
        private const val CRAM_CHUNK = 150
        private const val WORDS_PER_CHUNK = 50
        private const val DOWNLOAD_TRIES = 3

        // Coefficient addresses of Jancar's DSP program.
        const val MIX_ADC = 0x18
        const val MIX_I2S = 0x19
        const val MAIN_VOLUME = 0xC8
        val SPEAKERS = intArrayOf(0xDA, 0xDD, 0xEA, 0xED)
        const val P2BASS_FRONT = 0xDE
        const val HPF_FRONT = 0xE3
        const val REAR = 0x10
        const val SUB_PRE_GAIN = 0xFE
        const val SUB_HPF = 0x110
        const val SUB_LPF = 0x115
        const val SUB_LEVEL = 0x11C

        /** Jancar's I2S level in the mixer for Android (dB). */
        const val I2S_DB = -6
        const val ANDROID_GAIN_MAX = 12
        const val VOLUME_TOP_DB = 5
        const val OFF_DB = -150.0
        const val BASS_Q = 0.78

        // Filter types of Jancar's AK7604_GetIIRCoefData.
        const val THROUGH = 0
        const val HPF = 1
        const val LPF = 2
        const val PEAK = 3
        const val HIGH_SHELF = 4
        const val LOW_SHELF = 5

        val HPF_FC = intArrayOf(20, 40, 60, 80, 100, 120, 150, 200)
        val SUB_LPF_FC = intArrayOf(0, 50, 60, 70, 80, 90, 100, 120, 150)
        val SUB_HPF_FC = intArrayOf(0, 20, 25, 30, 35, 40)
        val P2BASS_FC = intArrayOf(0, 40, 60, 80, 100, 120)
        const val P2BASS_MAX_DB = 12

        /** Gain coefficient: 24 bits, 1.0 = 200000h (Q3.21), as `Ak7604_SetLevelMainVol`; -145 dB and below = 0. */
        fun gain24(db: Double): Int {
            if (db <= -145.0) return 0
            return fixed24(10.0.pow(minOf(db, 12.0) / 20.0) * (1 shl 21))
        }

        /** Filter coefficient: 24 bits, 1.0 = 400000h (Q2.22), as `AK7604_GetIIRCoefData` (single precision). */
        fun coef24(c: Double): Int = fixed24(c * (1 shl 22))

        private fun fixed24(v0: Double): Int {
            var v = v0.coerceIn(-8388608.0, 8388607.0)
            if (v < 0) v += 16777216.0
            v = minOf(v + 0.5, 16777215.0)
            return if (v < 0) 0 else v.toInt()
        }

        /**
         * Biquad of Jancar's `AK7604_GetIIRCoefData` at 48 kHz (RBJ cookbook forms), in the order the program reads
         * them: b2, b1, -a2, -a1, b0, all divided by a0.
         */
        fun biquad(
            type: Int,
            freqHz: Int,
            q: Double,
            gainDb: Double,
        ): DoubleArray {
            val w = 2 * PI * freqHz / 48000.0
            val cs = cos(w)
            val sn = sin(w)
            val a = 10.0.pow(gainDb / 40.0)
            val alpha = sn / (2 * q)
            return when (type) {
                HPF -> {
                    val a0 = 1 + alpha
                    doubleArrayOf((1 + cs) / 2 / a0, -(1 + cs) / a0, -(1 - alpha) / a0, 2 * cs / a0, (1 + cs) / 2 / a0)
                }
                LPF -> {
                    val a0 = 1 + alpha
                    doubleArrayOf((1 - cs) / 2 / a0, (1 - cs) / a0, -(1 - alpha) / a0, 2 * cs / a0, (1 - cs) / 2 / a0)
                }
                PEAK -> {
                    val a0 = 1 + alpha / a
                    doubleArrayOf((1 - alpha * a) / a0, -2 * cs / a0, -(1 - alpha / a) / a0, 2 * cs / a0, (1 + alpha * a) / a0)
                }
                HIGH_SHELF -> {
                    val beta = sn * sqrt(a / q)
                    val a0 = (a + 1) - (a - 1) * cs + beta
                    doubleArrayOf(
                        a * ((a + 1) + (a - 1) * cs - beta) / a0,
                        -2 * a * ((a - 1) + (a + 1) * cs) / a0,
                        -((a + 1) - (a - 1) * cs - beta) / a0,
                        -2 * ((a - 1) - (a + 1) * cs) / a0,
                        a * ((a + 1) + (a - 1) * cs + beta) / a0,
                    )
                }
                LOW_SHELF -> {
                    val beta = sn * sqrt(a / q)
                    val a0 = (a + 1) + (a - 1) * cs + beta
                    doubleArrayOf(
                        a * ((a + 1) - (a - 1) * cs - beta) / a0,
                        2 * a * ((a - 1) - (a + 1) * cs) / a0,
                        -((a + 1) + (a - 1) * cs - beta) / a0,
                        2 * ((a - 1) + (a + 1) * cs) / a0,
                        a * ((a + 1) - (a - 1) * cs + beta) / a0,
                    )
                }
                else -> doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0)
            }
        }

        /** CRC-16/XMODEM (polynomial 1021h, initial 0), what the chip computes over a download. */
        fun crc16(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xFFFF
            }
            return crc
        }
    }
}

/** Jancar's DSP program of the AK7604: control register table (address, value pairs), PRAM (40-bit words), CRAM. */
class Ak7604Program(
    val registers: ByteArray,
    val pram: ByteArray,
    val cram: ByteArray,
) {
    val valid: Boolean get() = registers.size % 2 == 0 && registers.isNotEmpty() && pram.size % 5 == 0 && pram.isNotEmpty() && cram.size % 3 == 0 && cram.isNotEmpty()
}

/** Settings of the AK7604 with Jancar's program (frequencies in Hz, 0 / 20 = filter off). */
data class Ak7604Settings(
    val hpfFront: Int = 20,
    val hpfRear: Int = 20,
    val p2BassFrontHz: Int = 0,
    val p2BassFrontDb: Int = 0,
    val p2BassRearHz: Int = 0,
    val p2BassRearDb: Int = 0,
    val subLpf: Int = 80,
    val subHpf: Int = 0,
) {
    fun normalized() =
        copy(
            hpfFront = Bu32107Settings.pick(Ak7604.HPF_FC, hpfFront),
            hpfRear = Bu32107Settings.pick(Ak7604.HPF_FC, hpfRear),
            p2BassFrontHz = Bu32107Settings.pick(Ak7604.P2BASS_FC, p2BassFrontHz),
            p2BassFrontDb = p2BassFrontDb.coerceIn(0, Ak7604.P2BASS_MAX_DB),
            p2BassRearHz = Bu32107Settings.pick(Ak7604.P2BASS_FC, p2BassRearHz),
            p2BassRearDb = p2BassRearDb.coerceIn(0, Ak7604.P2BASS_MAX_DB),
            subLpf = Bu32107Settings.pick(Ak7604.SUB_LPF_FC, subLpf),
            subHpf = Bu32107Settings.pick(Ak7604.SUB_HPF_FC, subHpf),
        )

    fun encode(): String = listOf(hpfFront, hpfRear, p2BassFrontHz, p2BassFrontDb, p2BassRearHz, p2BassRearDb, subLpf, subHpf).joinToString(",")

    companion object {
        fun decode(text: String?): Ak7604Settings {
            val v = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return Ak7604Settings()
            if (v.size != 8 || v.any { it == null }) return Ak7604Settings()
            return Ak7604Settings(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!, v[5]!!, v[6]!!, v[7]!!).normalized()
        }
    }
}
