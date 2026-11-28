package org.librehu.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Ak7604Test {
    /** Fake chip: control registers, downloads (CRC over each block), coefficient writes while running. */
    private class FakeChip(
        var id: Int = Ak7604.CHIP_ID,
    ) : Ak7604.Bus {
        val regs = HashMap<Int, Int>()
        val packets = mutableListOf<ByteArray>()
        private val block = java.io.ByteArrayOutputStream()
        private var lastCommand = -1

        override fun write(data: ByteArray): Boolean {
            packets += data
            val cmd = data[0].toInt() and 0xFF
            when (cmd) {
                0xC0 -> regs[data[2].toInt() and 0xFF] = data[3].toInt() and 0xFF
                Ak7604.CMD_PRAM, Ak7604.CMD_CRAM -> {
                    if (cmd != lastCommand || (data[1].toInt() == 0 && data[2].toInt() == 0)) block.reset()
                    lastCommand = cmd
                    block.write(data, 3, data.size - 3)
                }
            }
            return true
        }

        override fun read(
            command: ByteArray,
            length: Int,
        ): ByteArray? =
            when (command[0].toInt() and 0xFF) {
                0x40 -> {
                    val reg = command[2].toInt() and 0xFF
                    byteArrayOf((if (reg == Ak7604.REG_ID) id else regs[reg] ?: 0).toByte())
                }
                0x72 -> Ak7604.crc16(block.toByteArray()).let { byteArrayOf((it shr 8).toByte(), it.toByte()) }
                else -> null
            }
    }

    private val program =
        Ak7604Program(
            registers = byteArrayOf(0x00, 0x0F, 0x02, 0x10, 0xA3.toByte(), 0x00),
            pram = ByteArray(5 * 120) { (it * 7).toByte() },
            cram = ByteArray(3 * 70) { (it * 3).toByte() },
        )

    private val chip = FakeChip()
    private val pins = mutableListOf<Boolean>()
    private val dsp = Ak7604(chip, program, { pins += it }, sleep = {})

    @Test
    fun initFollowsJancar() {
        assertTrue(dsp.init())
        assertEquals(listOf(true, false, true), pins)
        // Program in 250-byte packets (50 words each), coefficients in 150-byte packets, word addresses 0, 50, 100.
        val pram = chip.packets.filter { (it[0].toInt() and 0xFF) == Ak7604.CMD_PRAM }
        assertEquals(listOf(253, 253, 103), pram.map { it.size })
        assertEquals(listOf(0, 50, 100), pram.map { ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) })
        assertEquals(listOf(153, 63), chip.packets.filter { (it[0].toInt() and 0xFF) == Ak7604.CMD_CRAM }.map { it.size })
        // DSP released (A3h bits 2..0 set), muted, Android on the I2S input.
        assertEquals(0x07, (chip.regs[0xA3] ?: 0) and 0x07)
        assertEquals(Ak7604.MUTE_ON, chip.regs[Ak7604.REG_MUTE])
        assertEquals(Ak7604.gain24(-6.0), dsp.cram[Ak7604.MIX_I2S])
        assertEquals(0, dsp.cram[Ak7604.MIX_ADC])
    }

    @Test
    fun wrongIdStopsInit() {
        chip.id = 0x05
        assertFalse(dsp.init())
        assertTrue(chip.packets.none { (it[0].toInt() and 0xFF) == Ak7604.CMD_PRAM })
    }

    @Test
    fun gainAndCoefficientFormats() {
        assertEquals(0x200000, Ak7604.gain24(0.0))
        assertEquals(0, Ak7604.gain24(-145.0))
        assertEquals(0x400000, Ak7604.coef24(1.0))
        assertEquals(0xC00000, Ak7604.coef24(-1.0))
        // Through filter: b0 = 1, the rest 0, in the order b2, b1, -a2, -a1, b0.
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 1.0), Ak7604.biquad(Ak7604.THROUGH, 100, 0.78, 0.0).toList())
        assertEquals(0, Ak7604.crc16(byteArrayOf()))
        assertEquals(0x31C3, Ak7604.crc16("123456789".toByteArray()))
    }

    /** Gain at DC (z = 1) and Nyquist (z = -1) of a biquad in the program's order. */
    private fun gainAt(
        c: DoubleArray,
        z: Double,
    ): Double = (c[4] + c[1] * z + c[0] * z * z) / (1 - c[3] * z - c[2] * z * z)

    @Test
    fun filtersHaveTheRightShape() {
        val lpf = Ak7604.biquad(Ak7604.LPF, 80, 1.0, 0.0)
        assertTrue(abs(gainAt(lpf, 1.0) - 1.0) < 1e-9)
        assertTrue(abs(gainAt(lpf, -1.0)) < 1e-9)
        val hpf = Ak7604.biquad(Ak7604.HPF, 80, 0.78, 0.0)
        assertTrue(abs(gainAt(hpf, 1.0)) < 1e-9)
        assertTrue(abs(gainAt(hpf, -1.0) - 1.0) < 1e-9)
        // Low shelf +6 dB: +6 dB at DC, flat at Nyquist; high shelf the other way round.
        val low = Ak7604.biquad(Ak7604.LOW_SHELF, 80, 0.78, 6.0)
        assertTrue(abs(gainAt(low, 1.0) - 10.0.let { Math.pow(it, 6.0 / 20) }) < 1e-6)
        assertTrue(abs(gainAt(low, -1.0) - 1.0) < 1e-6)
        val high = Ak7604.biquad(Ak7604.HIGH_SHELF, 8000, 0.78, 6.0)
        assertTrue(abs(gainAt(high, 1.0) - 1.0) < 1e-6)
    }

    @Test
    fun volumeSpeakersAndSub() {
        dsp.init()
        dsp.setVolume(40, 0)
        assertEquals(0x200000, dsp.cram[Ak7604.MAIN_VOLUME])
        dsp.setVolume(0, 0)
        assertEquals(0, dsp.cram[Ak7604.MAIN_VOLUME])
        dsp.setBalanceFade(30, 30, intArrayOf(0, -6, 0, 0))
        assertEquals(0x200000, dsp.cram[Ak7604.SPEAKERS[0]])
        assertEquals(Ak7604.gain24(-6.0), dsp.cram[Ak7604.SPEAKERS[1]])
        dsp.setSubwoofer(true, 5, SubConfig(phase180 = true))
        assertEquals(0x200000, dsp.cram[Ak7604.SUB_LEVEL])
        // Phase 180°: numerator negated (b0 negative).
        assertTrue((dsp.cram[Ak7604.SUB_LPF + 4] ?: 0) and 0x800000 != 0)
        dsp.setSubwoofer(false, 5, SubConfig())
        assertEquals(0, dsp.cram[Ak7604.SUB_LEVEL])
        // Coefficient writes while running: 80h + count - 1, then A4h.
        val last = chip.packets.takeLast(2)
        assertEquals(0x80, last[0][0].toInt() and 0xFF)
        assertEquals(listOf(0xA4, 0, 0), last[1].map { it.toInt() and 0xFF })
    }

    @Test
    fun settingsRoundTrip() {
        val s = Ak7604Settings(hpfFront = 80, p2BassFrontHz = 60, p2BassFrontDb = 6, subLpf = 100, subHpf = 25)
        assertEquals(s, Ak7604Settings.decode(s.encode()))
        assertEquals(Ak7604Settings(), Ak7604Settings.decode("1,2"))
    }
}
