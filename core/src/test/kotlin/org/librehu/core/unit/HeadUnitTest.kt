package org.librehu.core.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.librehu.core.audio.Bd37534
import org.librehu.core.mcu.Mcu
import org.librehu.core.mcu.McuFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeadUnitTest {
    private val outputs = ConcurrentHashMap<Int, Boolean>()
    private val inputs = ConcurrentHashMap<Int, Int>()
    private val gpio =
        object : Gpio {
            override fun set(
                gpio: Int,
                high: Boolean,
            ): Boolean {
                outputs[gpio] = high
                return true
            }

            override fun read(gpio: Int): Int = inputs[gpio] ?: 1
        }
    private val regs = ConcurrentHashMap<Int, Int>()
    private val chip =
        Bd37534({ r, v ->
            regs[r] = v
            true
        }, sleep = {})
    private var saved = HeadUnitSettings(externalAmp = true)
    private val store =
        object : SettingsStore {
            override fun load() = saved

            override fun save(settings: HeadUnitSettings) {
                saved = settings
            }
        }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val sent = CopyOnWriteArrayList<McuFrame>()
    private val unit = HeadUnit(gpio, chip, store, executor, object : HeadUnit.Listener {})

    private fun settle(ms: Long = 50) {
        Thread.sleep(ms)
        executor.submit {}.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun startupHandshakeAndAcc() {
        unit.start { sent += it }
        settle()
        assertEquals(Mcu.pcReady(), sent.first())
        assertEquals(true, outputs[BoardGpio.AMP_MUTE])
        assertEquals(0x80, regs[Bd37534.INPUT_GAIN]!! and 0x80) // muted until ACC

        unit.onFrame(McuFrame.of(Mcu.CMD_ACC, 1))
        settle(HeadUnit.BACKLIGHT_DELAY_MS + 200)
        assertTrue(unit.state.acc && unit.state.mcuOnline)
        assertEquals(false, outputs[BoardGpio.AMP_MUTE])
        assertEquals(true, outputs[BoardGpio.BACKLIGHT])
        assertEquals(0, regs[Bd37534.INPUT_GAIN]!! and 0x80)
        assertTrue(Mcu.externalAmp(true) in sent && Mcu.mute(false) in sent)

        unit.onFrame(McuFrame.of(Mcu.CMD_ACC, 0))
        settle()
        assertEquals(false, outputs[BoardGpio.BACKLIGHT])
        assertEquals(true, outputs[BoardGpio.AMP_MUTE])
        assertEquals(Mcu.externalAmp(false), sent.last { it.cmd == Mcu.CMD_EXT_AMP })
        executor.shutdownNow()
    }

    @Test
    fun reverseFromGpioAndSettingsPersisted() {
        unit.start { sent += it }
        inputs[BoardGpio.REVERSE] = 0
        settle(700)
        assertTrue(unit.state.reverse)

        unit.changeSettings { it.copy(volume = 99, bass = 14) }
        settle()
        assertEquals(Bd37534.MAX_VOLUME, saved.volume)
        assertEquals(Bd37534.tone(14), regs[Bd37534.BASS_GAIN])
        executor.shutdownNow()
    }
}
