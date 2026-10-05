package org.librehu.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import org.librehu.core.audio.Bd37534
import org.librehu.core.mcu.McuEvent
import org.librehu.core.mcu.McuFrame
import org.librehu.core.mcu.McuProfiles
import org.librehu.core.mcu.McuTransport
import org.librehu.core.unit.HeadUnit
import org.librehu.core.unit.HeadUnitSettings
import org.librehu.core.unit.VehicleState
import org.librehu.service.bt.BluetoothModule
import org.librehu.service.bt.ILibreHuBluetooth
import org.librehu.service.display.DisplayController
import org.librehu.service.hw.I2cDevice
import org.librehu.service.hw.SocGpio
import org.librehu.service.hw.TtySerialChannel
import org.librehu.service.mcu.ProfileStore
import org.librehu.service.obd.ObdManager
import org.librehu.service.time.TimeController
import org.librehu.service.touch.TouchKeys
import org.librehu.service.touch.TouchPanel
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

/**
 * LibreHU-service: owns the MCU link, the SoC GPIOs and the audio chip, and exposes them through [ILibreHuService].
 *
 * It refuses to touch the hardware while Jancar ivi-services is enabled: two programs on `/dev/ttyS1` would steal each
 * other's bytes. Disable it first (`adb shell pm disable-user --user 0 com.jancar.services`) or force the start from
 * the app (for tests).
 */
class LibreHuService : Service() {
    enum class Link { STOPPED, BLOCKED_BY_IVI, NO_MCU, RUNNING }

    private val callbacks = RemoteCallbackList<ILibreHuCallback>()
    private var executor: ScheduledExecutorService? = null
    private var transport: McuTransport? = null
    private var unit: HeadUnit? = null

    /** Bluetooth runs whatever the MCU link does: it only needs Android's Bluetooth stack. */
    private lateinit var bluetoothModule: BluetoothModule

    @Volatile
    private var link = Link.STOPPED

    @Volatile
    private var linkDetail = ""

    private var protocolName = ""

    /** Android dark mode and screen brightness following the headlights. */
    private lateinit var display: DisplayController

    /** ELM327 OBD-II adapter. */
    private lateinit var obd: ObdManager

    /** GPS test and clock sources (MCU, Android, GPS). */
    private lateinit var time: TimeController

    /** Front panel touch "buttons". */
    private lateinit var touchKeys: TouchKeys

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        bluetoothModule = BluetoothModule(this).also { it.start() }
        display = DisplayController.get(this).also { it.start() }
        obd = ObdManager.get(this).also { it.start() }
        time =
            TimeController.get(this).also {
                it.mcu =
                    object : TimeController.McuClock {
                        override fun pushToMcu() {
                            unit?.pushClockToMcu()
                        }

                        override fun pullFromMcu() {
                            unit?.pullClockFromMcu()
                        }
                    }
                it.start()
            }
        touchKeys =
            TouchKeys.get(this).also {
                it.volumeHook = { step -> changeVolume(step) }
                it.start()
            }
        // The touch driver forgets its calibration at each boot: put back the one saved here (root, off the main thread).
        Thread({ TouchPanel.get(this).applySaved() }, "touch-calibration").start()
        startHardware()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_RESTART) {
            stopHardware()
            startHardware()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopHardware()
        bluetoothModule.stop()
        display.stop()
        obd.stop()
        time.stop()
        touchKeys.stop()
        callbacks.kill()
        super.onDestroy()
    }

    // --- Hardware ------------------------------------------------------------------------------------------------

    /** Volume of the audio chip from the front panel touch keys: +1 / -1 step, 0 = mute toggle. */
    private fun changeVolume(step: Int): Boolean {
        val u = unit ?: return false
        u.changeSettings { s -> if (step == 0) s.copy(muted = !s.muted) else s.copy(volume = s.volume + step, muted = false) }
        return true
    }

    private fun startHardware() {
        if (isIviServicesEnabled(this) && !prefs(this).getBoolean(PREF_FORCE, false)) {
            setLink(Link.BLOCKED_BY_IVI, "com.jancar.services is enabled")
            return
        }
        val profile = ProfileStore.get(this).current()
        protocolName = profile.name
        val protocol = McuProfiles.protocolFor(profile)
        val channel =
            try {
                TtySerialChannel.open(profile.serial.port, profile.serial.baud)
            } catch (e: IOException) {
                setLink(Link.NO_MCU, e.message ?: "")
                return
            }
        val dsp =
            if (profile.board.audioChip == "bd37534") {
                try {
                    Bd37534(I2cDevice.open(profile.board.audioBus, profile.board.audioAddress))
                } catch (e: IOException) {
                    log("Audio chip unavailable: ${e.message}")
                    null
                }
            } else {
                null
            }
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "headunit") }
        val headUnit =
            HeadUnit(
                SocGpio,
                dsp,
                PrefsSettingsStore(this),
                exec,
                unitListener,
                protocol = protocol,
                board = profile.board,
                clockFromMcu = {
                    TimeController
                        .get(this)
                        .settings.value.fromMcu
                },
                clockToMcu = {
                    TimeController
                        .get(this)
                        .settings.value.toMcu
                },
            )
        val t = McuTransport(channel, headUnit, protocol)
        executor = exec
        unit = headUnit
        transport = t
        t.start()
        headUnit.start(t::send)
        setLink(Link.RUNNING, if (dsp == null) "MCU ok, no audio chip" else "MCU + BD37534")
    }

    private fun stopHardware() {
        transport?.close()
        executor?.shutdownNow()
        transport = null
        executor = null
        unit = null
        setLink(Link.STOPPED, "")
    }

    private fun setLink(
        l: Link,
        detail: String,
    ) {
        link = l
        linkDetail = detail
        ServiceState.setLink(ServiceState.Link(l, detail, protocolName))
        log("Link: $l $detail")
    }

    private val unitListener =
        object : HeadUnit.Listener {
            override fun onStateChanged(state: VehicleState) {
                ServiceState.setVehicle(state)
                display.onHeadlights(state.headlight)
                val flags = LibreHu.flagsOf(state)
                broadcastState(state, flags)
                each { it.onVehicleFlags(flags) }
            }

            override fun onSettingsChanged(settings: HeadUnitSettings) = each { it.onAudioChanged() }

            override fun onMcuFrame(
                frame: McuFrame,
                fromMcu: Boolean,
            ) {
                ServiceState.addTraffic((if (fromMcu) "> " else "< ") + frame)
                each { it.onMcuFrame(frame.cmd, frame.data, fromMcu) }
            }

            override fun onKey(key: McuEvent.Key) = each { it.onKey(key.channel, key.values, key.released, key.learning) }

            override fun onCanData(bytes: ByteArray) = each { it.onCanData(bytes) }

            override fun onMcuClock(time: LocalDateTime) = setSystemClock(time)

            override fun onLog(message: String) = log(message)
        }

    private var lastState = VehicleState()

    private fun broadcastState(
        s: VehicleState,
        flags: Int,
    ) {
        val old = lastState
        lastState = s
        sendBroadcast(
            Intent(LibreHu.ACTION_VEHICLE_STATE)
                .putExtra(LibreHu.EXTRA_FLAGS, flags)
                .putExtra(LibreHu.EXTRA_ACC, s.acc)
                .putExtra(LibreHu.EXTRA_REVERSE, s.reverse)
                .putExtra(LibreHu.EXTRA_HANDBRAKE, s.handbrake)
                .putExtra(LibreHu.EXTRA_HEADLIGHT, s.headlight),
        )
        if (old.acc != s.acc) sendBroadcast(Intent(LibreHu.ACTION_ACC).putExtra(LibreHu.EXTRA_ACC, s.acc))
        if (old.reverse != s.reverse) {
            sendBroadcast(Intent(LibreHu.ACTION_REVERSE).putExtra(LibreHu.EXTRA_REVERSE, s.reverse))
        }
    }

    /** The MCU RTC keeps time while the SoC is off: set Android's clock from it once per start (needs SET_TIME). */
    private fun setSystemClock(time: LocalDateTime) {
        val millis = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (Math.abs(millis - System.currentTimeMillis()) < CLOCK_TOLERANCE_MS) return
        try {
            getSystemService(AlarmManager::class.java).setTime(millis)
            log("Clock set from MCU: $time")
        } catch (e: SecurityException) {
            log("Cannot set clock from MCU ($time): ${e.message}")
        }
    }

    private inline fun each(action: (ILibreHuCallback) -> Unit) {
        synchronized(callbacks) {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    try {
                        action(callbacks.getBroadcastItem(i))
                    } catch (_: RemoteException) {
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    // --- API -----------------------------------------------------------------------------------------------------

    private val binder =
        object : ILibreHuService.Stub() {
            private fun settings() = unit?.settings ?: PrefsSettingsStore(this@LibreHuService).load()

            private fun change(transform: (HeadUnitSettings) -> HeadUnitSettings) {
                val u = unit
                if (u != null) {
                    u.changeSettings(transform)
                } else {
                    // Hardware not running: keep the choice for the next start.
                    val store = PrefsSettingsStore(this@LibreHuService)
                    store.save(transform(store.load()))
                    each { it.onAudioChanged() }
                }
            }

            override fun getApiVersion() = LibreHu.API_VERSION

            override fun getStatus() = "$link${if (linkDetail.isEmpty()) "" else " ($linkDetail)"}"

            override fun getMcuVersion() = unit?.state?.mcuVersion ?: ""

            override fun getVehicleFlags() = unit?.state?.let(LibreHu::flagsOf) ?: 0

            override fun getVolume() = settings().volume

            override fun getMaxVolume() = Bd37534.MAX_VOLUME

            override fun setVolume(step: Int) = change { it.copy(volume = step) }

            override fun isMuted() = settings().muted

            override fun setMuted(muted: Boolean) = change { it.copy(muted = muted) }

            override fun getTone() = settings().let { intArrayOf(it.bass, it.middle, it.treble) }

            override fun setTone(
                bass: Int,
                middle: Int,
                treble: Int,
            ) = change { it.copy(bass = bass, middle = middle, treble = treble) }

            override fun getBalanceFade() = settings().let { intArrayOf(it.balance, it.fade) }

            override fun setBalanceFade(
                balance: Int,
                fade: Int,
            ) = change { it.copy(balance = balance, fade = fade) }

            override fun getLoudness() = settings().loudness

            override fun setLoudness(level: Int) = change { it.copy(loudness = level) }

            override fun isSubwooferOn() = settings().subwoofer

            override fun getSubwooferLevel() = settings().subLevel

            override fun setSubwoofer(
                on: Boolean,
                level: Int,
            ) = change { it.copy(subwoofer = on, subLevel = level) }

            override fun isExternalAmpEnabled() = settings().externalAmp

            override fun setExternalAmpEnabled(enabled: Boolean) = change { it.copy(externalAmp = enabled) }

            override fun sendMcuFrame(
                cmd: Int,
                data: ByteArray?,
            ) {
                unit?.sendToMcu(McuFrame(cmd and 0xFF, data ?: ByteArray(0)))
            }

            override fun sendCanData(data: ByteArray?) {
                if (data != null) unit?.sendToMcu(McuFrame(0x10, data))
            }

            override fun registerCallback(callback: ILibreHuCallback?) {
                if (callback != null) callbacks.register(callback)
            }

            override fun unregisterCallback(callback: ILibreHuCallback?) {
                if (callback != null) callbacks.unregister(callback)
            }

            override fun setRadioAntenna(on: Boolean) {
                unit?.setRadioAntenna(on)
            }

            override fun isRadioAntennaOn() = unit?.radioAntennaRequested ?: false

            override fun getBluetooth(): ILibreHuBluetooth = bluetoothModule.binder

            override fun getObdValues(): Bundle = obd.valuesBundle()

            override fun getObdState(): Int = obd.state.value.connection.ordinal

            override fun getMcuProtocol(): String = protocolName
        }

    // --- Foreground ----------------------------------------------------------------------------------------------

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification =
            Notification
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_headunit)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        ServiceState.addLog(message)
    }

    companion object {
        private const val TAG = "LibreHU"
        private const val CHANNEL_ID = "service"
        private const val NOTIFICATION_ID = 1
        private const val CLOCK_TOLERANCE_MS = 5_000L
        const val ACTION_RESTART = "org.librehu.service.RESTART"
        const val PREF_FORCE = "force_with_ivi"
        const val IVI_PACKAGE = "com.jancar.services"

        fun start(
            context: Context,
            restart: Boolean = false,
        ) {
            val intent = Intent(context, LibreHuService::class.java)
            if (restart) intent.action = ACTION_RESTART
            context.startForegroundService(intent)
        }

        fun prefs(context: Context) = context.createDeviceProtectedStorageContext().getSharedPreferences("service", Context.MODE_PRIVATE)

        /** True when Jancar ivi-services is installed and not disabled. */
        fun isIviServicesEnabled(context: Context): Boolean {
            val pm = context.packageManager
            return try {
                val info = pm.getApplicationInfo(IVI_PACKAGE, 0)
                val setting = pm.getApplicationEnabledSetting(IVI_PACKAGE)
                info.enabled &&
                    setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                    setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
