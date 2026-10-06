package org.librehu.core.unit

import org.librehu.core.audio.Bd37534
import org.librehu.core.mcu.BoardSpec
import org.librehu.core.mcu.JacProtocol
import org.librehu.core.mcu.McuEvent
import org.librehu.core.mcu.McuFrame
import org.librehu.core.mcu.McuProfiles
import org.librehu.core.mcu.McuProtocol
import org.librehu.core.mcu.McuTransport
import java.time.LocalDateTime
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** SoC GPIOs through `/dev/gpios_ioctl`. */
interface Gpio {
    fun set(
        gpio: Int,
        high: Boolean,
    ): Boolean

    /** Level 0/1, or -1 on error. Reading also switches the pin to input (driver behaviour): inputs only. */
    fun read(gpio: Int): Int
}

/** GPIO numbers of board A0_AN (from ivi-services `Platform_AutoChips_8257_Base`). */
object BoardGpio {
    const val REVERSE = 2 // input, active low
    const val BACKLIGHT = 5 // output, 1 = on
    const val TURN_RIGHT = 6 // input, active low
    const val TURN_LEFT = 7 // input, active low
    const val AMP_MUTE = 166 // output, 1 = muted
    const val RADIO_ANTENNA = 110 // output, 1 = antenna powered (with MCU 0x43)
}

/** Vehicle inputs whose polarity can be inverted (wiring or car active the other way). */
enum class VehicleInput { REVERSE, HANDBRAKE, HEADLIGHT, TURN_LEFT, TURN_RIGHT }

data class VehicleState(
    val mcuOnline: Boolean = false,
    val acc: Boolean = false,
    val handbrake: Boolean = false,
    val headlight: Boolean = false,
    val reverse: Boolean = false,
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val mcuVersion: String = "",
)

data class HeadUnitSettings(
    val volume: Int = 12,
    val muted: Boolean = false,
    val bass: Int = Bd37534.TONE_FLAT,
    val middle: Int = Bd37534.TONE_FLAT,
    val treble: Int = Bd37534.TONE_FLAT,
    val balance: Int = Bd37534.FADER_CENTER,
    val fade: Int = Bd37534.FADER_CENTER,
    val loudness: Int = 0,
    val subwoofer: Boolean = false,
    val subLevel: Int = Bd37534.SUB_DEFAULT_LEVEL,
    val externalAmp: Boolean = false,
)

interface SettingsStore {
    fun load(): HeadUnitSettings

    fun save(settings: HeadUnitSettings)
}

/**
 * Core behaviour of the head unit, replacing the parts of ivi-services' `IVICore` and platform layer that matter on
 * the UJC201: MCU handshake, ACC handling (backlight, mute, external amplifier), SoC GPIO inputs, clock sync and the
 * BD37534 settings. Everything runs on [executor] (single thread), so state needs no locking.
 */
class HeadUnit(
    private val gpio: Gpio,
    private val dsp: Bd37534?,
    private val store: SettingsStore,
    private val executor: ScheduledExecutorService,
    private val listener: Listener,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val protocol: McuProtocol = JacProtocol,
    private val board: BoardSpec = McuProfiles.JANCAR_JAC_V1.board,
    /** Set Android's clock from the MCU's RTC (once per start, see [Listener.onMcuClock]). */
    private val clockFromMcu: () -> Boolean = { true },
    /** Send Android's clock to the MCU every minute. */
    private val clockToMcu: () -> Boolean = { true },
    /**
     * Frame disarming the MCU's watchdog, sent after each PC_READY; null = do not disarm. Default of the protocol:
     * [McuProtocol.watchdogOff].
     */
    private val watchdogFrame: () -> McuFrame? = { null },
    /** Read the turn signal inputs (GPIO 7 / 6). Off: never reported (inputs not wired). */
    private val turnSignals: () -> Boolean = { true },
    /** Inputs reported the other way round (MCU frames and SoC GPIO alike). */
    private val inverted: () -> Set<VehicleInput> = { emptySet() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : McuTransport.Listener {
    interface Listener {
        fun onStateChanged(state: VehicleState) {}

        fun onSettingsChanged(settings: HeadUnitSettings) {}

        fun onMcuFrame(
            frame: McuFrame,
            fromMcu: Boolean,
        ) {}

        fun onKey(key: McuEvent.Key) {}

        fun onCanData(bytes: ByteArray) {}

        /**
         * The MCU answered PC_READY (version received). [watchdogDisarmed]: the watchdog frame was sent. Called after
         * each handshake (start, wake up).
         */
        fun onHandshake(watchdogDisarmed: Boolean) {}

        /** First date/time sent by the MCU since start (its RTC keeps time while the SoC is off). */
        fun onMcuClock(time: LocalDateTime) {}

        fun onLog(message: String) {}
    }

    @Volatile
    var state = VehicleState()
        private set

    @Volatile
    var settings = store.load()
        private set

    private var sender: (McuFrame) -> Unit = {}
    private var backlightTask: ScheduledFuture<*>? = null
    private var mcuDate: McuEvent.Date? = null

    /** Input of the sound processor: the Android audio or the AUX jack (not saved: Android again at each start). */
    @Volatile
    var source = SOURCE_ANDROID
        private set

    /** Antenna requested by the radio app; powered only while ACC is on. */
    @Volatile
    var radioAntennaRequested = false
        private set
    private var clockReceived = false

    /** Starts with [send] as the way to the MCU (usually [McuTransport.send]). */
    fun start(send: (McuFrame) -> Unit) {
        sender = send
        executor.execute {
            // PC_READY first: after a SoC reset the MCU waits 10 s at most for it. The audio stays muted meanwhile.
            setGpio(board.ampMuteGpio, true)
            handshake()
            val ok = dsp?.init()
            log("MCU protocol: ${protocol.name}; BD37534 init: ${ok ?: "absent"}")
            applyAudio()
        }
        executor.scheduleWithFixedDelay(::pollGpio, 500, GPIO_POLL_MS, TimeUnit.MILLISECONDS)
        executor.scheduleWithFixedDelay(::syncClockToMcu, 60, 60, TimeUnit.SECONDS)
    }

    /**
     * The SoC comes back from standby: the MCU waits for PC_READY again (10 s at most on firmware 2024.08.09, then it
     * cuts the SoC). Call it on screen on / resume.
     */
    fun wake() = executor.execute { handshake() }

    // PC_READY until the MCU answers with its version (ivi-services: again after 4 s without version).
    private var handshakeDone = false
    private var handshakeTries = 0
    private var handshakeTask: ScheduledFuture<*>? = null
    private var watchdogSent = false

    private fun handshake() {
        handshakeDone = false
        handshakeTries = 0
        sendPcReady()
    }

    private fun sendPcReady() {
        handshakeTask?.cancel(false)
        handshakeTries++
        sendIf(protocol.pcReady())
        val wd = watchdogFrame()
        watchdogSent = wd != null
        if (wd != null) sendToMcu(wd)
        if (handshakeTries < HANDSHAKE_TRIES) {
            handshakeTask =
                executor.schedule({ if (!handshakeDone) sendPcReady() }, HANDSHAKE_RETRY_MS, TimeUnit.MILLISECONDS)
        } else {
            log("MCU: no answer to PC_READY after $handshakeTries tries")
        }
    }

    /** Power cycle of the SoC by the MCU (mutes first, like ivi-services' PowerUtil.reboot()). */
    fun resetSoc() =
        executor.execute {
            applyMute(true)
            sendIf(protocol.mute(true))
            sendIf(protocol.resetSoc())
        }

    /** Before an Android reboot: audio muted (chip, amplifier GPIO and MCU), like ivi-services' PowerUtil.reboot(). */
    fun prepareReboot() =
        executor.execute {
            applyMute(true)
            sendIf(protocol.mute(true))
            setGpio(board.backlightGpio, false)
        }

    fun sendToMcu(frame: McuFrame) {
        sender(frame)
        listener.onMcuFrame(frame, false)
    }

    /** Frames the protocol does not support are null: nothing to send. */
    private fun sendIf(frame: McuFrame?) {
        if (frame != null) sendToMcu(frame)
    }

    private fun setGpio(
        n: Int?,
        high: Boolean,
    ) {
        if (n != null) gpio.set(n, high)
    }

    // --- MCU ---------------------------------------------------------------------------------------------------

    override fun onFrame(frame: McuFrame) {
        listener.onMcuFrame(frame, true)
        executor.execute { handle(protocol.decode(frame)) }
    }

    /** Debug: handles [frame] as if the MCU had sent it. Nothing is written to the port. */
    fun simulate(frame: McuFrame) = executor.execute { handle(protocol.decode(frame)) }

    override fun onAckTimeout(frame: McuFrame) = log("MCU: no ACK for $frame")

    override fun onError(e: Exception) = log("MCU link error: ${e.message}")

    private fun handle(event: McuEvent) {
        if (!state.mcuOnline) update { it.copy(mcuOnline = true) }
        when (event) {
            is McuEvent.Acc -> {
                setAcc(event.on)
            }

            is McuEvent.Handbrake -> {
                update { it.copy(handbrake = event.on xor (VehicleInput.HANDBRAKE in inverted())) }
            }

            is McuEvent.Headlight -> {
                update { it.copy(headlight = event.on xor (VehicleInput.HEADLIGHT in inverted())) }
            }

            is McuEvent.Reverse -> {
                if (board.reverseGpio == null) update { it.copy(reverse = event.on xor (VehicleInput.REVERSE in inverted())) }
            }

            is McuEvent.Version -> {
                update { it.copy(mcuVersion = event.text) }
                if (!handshakeDone) {
                    handshakeDone = true
                    handshakeTask?.cancel(false)
                    listener.onHandshake(watchdogSent)
                }
            }

            is McuEvent.Date -> {
                mcuDate = event
            }

            is McuEvent.Time -> {
                onMcuTime(event)
            }

            is McuEvent.Key -> {
                listener.onKey(event)
            }

            is McuEvent.Can -> {
                listener.onCanData(event.bytes)
            }

            else -> {}
        }
    }

    private fun onMcuTime(t: McuEvent.Time) {
        val d = mcuDate ?: return
        if (clockReceived) return
        clockReceived = true
        val forced = forcePull
        forcePull = false
        if (!clockFromMcu() && !forced) return
        try {
            listener.onMcuClock(LocalDateTime.of(d.year, d.month, d.day, t.hour, t.minute, t.second))
        } catch (e: java.time.DateTimeException) {
            log("MCU clock invalid: $d $t")
        }
    }

    /** Android time to the MCU RTC every minute, once the MCU clock has been read (as ivi-services does). */
    private fun syncClockToMcu() {
        // Wait for the MCU clock first (it may be the better one at boot), unless it is not used.
        if (!clockToMcu() || (!clockReceived && clockFromMcu())) return
        pushClockToMcu()
    }

    /** Android's clock to the MCU now (after a GPS time fix, or from the settings). */
    fun pushClockToMcu() {
        val now = clock()
        sendIf(protocol.date(now.year, now.monthValue, now.dayOfMonth))
        sendIf(protocol.time(now.hour, now.minute, now.second))
    }

    /** Reads the MCU clock again and hands it to [Listener.onMcuClock] (even when [clockFromMcu] is off). */
    fun pullClockFromMcu() {
        executor.execute {
            clockReceived = false
            forcePull = true
            sendIf(protocol.queryClock())
        }
    }

    private var forcePull = false

    // --- Radio antenna -----------------------------------------------------------------------------------------

    /**
     * Selects the input of the sound processor, as ivi-services' `platformSwitchAudioTo`: short anti-pop mute, input
     * (AUX = 0 at +5 dB, Android = 11 at 0 dB), then the previous mute state. While on AUX, Android's audio is not heard.
     */
    fun setSource(value: Int) =
        executor.execute {
            val aux = value == SOURCE_AUX
            source = if (aux) SOURCE_AUX else SOURCE_ANDROID
            val dsp = dsp ?: return@execute
            val wasMuted = dsp.isMuted
            if (!wasMuted) dsp.setMute(true)
            if (aux) dsp.setInput(Bd37534.INPUT_AUX, Bd37534.AUX_GAIN_DB) else dsp.setInput(Bd37534.INPUT_ANDROID, 0)
            if (!wasMuted) dsp.setMute(false)
            log("Audio source: ${if (aux) "AUX" else "Android"}")
        }

    fun setRadioAntenna(on: Boolean) {
        executor.execute {
            radioAntennaRequested = on
            applyAntenna()
        }
    }

    private fun applyAntenna() {
        val on = radioAntennaRequested && state.acc
        setGpio(board.antennaGpio, on)
        sendIf(protocol.antenna(on))
    }

    // --- ACC / power ---------------------------------------------------------------------------------------------

    private fun setAcc(on: Boolean) {
        val changed = on != state.acc
        update { it.copy(acc = on) }
        if (!changed && on) return
        log("ACC ${if (on) "on" else "off"}")
        backlightTask?.cancel(false)
        if (on) {
            sendIf(protocol.mute(false))
            applyAudio()
            sendIf(protocol.externalAmp(settings.externalAmp))
            if (radioAntennaRequested) applyAntenna()
            backlightTask = executor.schedule({ setGpio(board.backlightGpio, true) }, BACKLIGHT_DELAY_MS, TimeUnit.MILLISECONDS)
        } else {
            applyMute(true)
            sendIf(protocol.mute(true))
            sendIf(protocol.externalAmp(false))
            if (radioAntennaRequested) applyAntenna()
            setGpio(board.backlightGpio, false)
        }
    }

    // --- GPIO inputs ---------------------------------------------------------------------------------------------

    private val leftFilter = TurnSignalFilter()
    private val rightFilter = TurnSignalFilter()

    /** A turn input stays low without blinking (not wired), for the diagnostics. */
    val turnInputStuck: Boolean get() = leftFilter.stuck || rightFilter.stuck

    private var wasStuck = false

    private fun pollGpio() {
        val inv = inverted()
        val reverse = board.reverseGpio?.let { activeLow(it)?.xor(VehicleInput.REVERSE in inv) } ?: state.reverse
        val t = now()
        val read = turnSignals()
        val left =
            if (read) {
                board.turnLeftGpio?.let {
                    leftFilter.update(
                        activeLow(it)?.xor(VehicleInput.TURN_LEFT in inv),
                        t,
                    )
                } ?: false
            } else {
                false
            }
        val right =
            if (read) {
                board.turnRightGpio?.let {
                    rightFilter.update(
                        activeLow(it)?.xor(VehicleInput.TURN_RIGHT in inv),
                        t,
                    )
                } ?: false
            } else {
                false
            }
        if (turnInputStuck != wasStuck) {
            wasStuck = turnInputStuck
            log(if (wasStuck) "Turn signal input low without blinking (not wired?): ignored" else "Turn signal inputs blinking again")
        }
        if (reverse != state.reverse) log("Reverse ${if (reverse) "on" else "off"}")
        update { it.copy(reverse = reverse, turnLeft = left, turnRight = right) }
    }

    private fun activeLow(n: Int): Boolean? =
        when (gpio.read(n)) {
            0 -> true
            1 -> false
            else -> null
        }

    // --- Audio ---------------------------------------------------------------------------------------------------

    fun changeSettings(transform: (HeadUnitSettings) -> HeadUnitSettings) {
        executor.execute {
            val old = settings
            val new = transform(old).normalized()
            if (new == old) return@execute
            settings = new
            store.save(new)
            applyAudio(old)
            if (new.externalAmp != old.externalAmp && state.acc) sendIf(protocol.externalAmp(new.externalAmp))
            listener.onSettingsChanged(new)
        }
    }

    /** Writes the settings that differ from [old] (all of them when null) to the chip. */
    private fun applyAudio(old: HeadUnitSettings? = null) {
        val s = settings
        val dsp = dsp
        if (dsp != null) {
            if (old == null || old.volume != s.volume) dsp.setVolume(s.volume)
            if (old == null || old.bass != s.bass || old.middle != s.middle || old.treble != s.treble) {
                dsp.setTone(s.bass, s.middle, s.treble)
            }
            if (old == null || old.balance != s.balance || old.fade != s.fade) dsp.setBalanceFade(s.balance, s.fade)
            if (old == null || old.loudness != s.loudness) dsp.setLoudness(s.loudness)
            if (old == null || old.subwoofer != s.subwoofer || old.subLevel != s.subLevel) {
                dsp.setSubwoofer(s.subwoofer, s.subLevel)
            }
        }
        applyMute(s.muted || !state.acc)
    }

    private fun applyMute(mute: Boolean) {
        if (dsp != null && dsp.isMuted != mute) dsp.setMute(mute)
        setGpio(board.ampMuteGpio, mute)
    }

    private fun HeadUnitSettings.normalized() =
        copy(
            volume = volume.coerceIn(0, Bd37534.MAX_VOLUME),
            bass = bass.coerceIn(0, Bd37534.TONE_MAX),
            middle = middle.coerceIn(0, Bd37534.TONE_MAX),
            treble = treble.coerceIn(0, Bd37534.TONE_MAX),
            balance = balance.coerceIn(0, Bd37534.FADER_MAX),
            fade = fade.coerceIn(0, Bd37534.FADER_MAX),
            loudness = loudness.coerceIn(0, 15),
            subLevel = subLevel.coerceIn(0, Bd37534.SUB_MAX_LEVEL),
        )

    private inline fun update(transform: (VehicleState) -> VehicleState) {
        val new = transform(state)
        if (new != state) {
            state = new
            listener.onStateChanged(new)
        }
    }

    private fun log(message: String) = listener.onLog(message)

    companion object {
        const val GPIO_POLL_MS = 100L
        const val SOURCE_ANDROID = 0
        const val SOURCE_AUX = 1
        const val BACKLIGHT_DELAY_MS = 800L
        const val HANDSHAKE_RETRY_MS = 4000L
        const val HANDSHAKE_TRIES = 5
    }
}
