package org.librehu.service.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.librehu.service.ILibreHuCallback
import org.librehu.service.ILibreHuService
import org.librehu.service.LibreHuService
import org.librehu.service.bt.BtCallInfo
import org.librehu.service.bt.BtDeviceInfo
import org.librehu.service.bt.BtMediaInfo
import org.librehu.service.bt.BtStatus
import org.librehu.service.bt.ILibreHuBluetooth
import org.librehu.service.bt.ILibreHuBluetoothCallback

data class AudioState(
    val volume: Int = 0,
    val maxVolume: Int = 40,
    val muted: Boolean = false,
    /** Tone gains (dB, 1 dB steps), centre frequencies (Hz) and Q (x100) of the sound processor. */
    val bassDb: Int = 0,
    val middleDb: Int = 0,
    val trebleDb: Int = 0,
    val bassFreq: Int = 80,
    val bassQ: Int = 100,
    val middleFreq: Int = 1500,
    val middleQ: Int = 100,
    val trebleFreq: Int = 12500,
    val trebleQ: Int = 75,
    val balance: Int = 30,
    val fade: Int = 30,
    val loudness: Int = 0,
    val loudnessFreq: Int = 800,
    val loudnessHicut: Int = 1,
    val subwoofer: Boolean = false,
    val subLevel: Int = 6,
    /** Subwoofer low-pass (Hz, 0 = off), phase, output (0 low-pass, 1 front copy, 2 rear copy). */
    val subCutoff: Int = 120,
    val subPhase180: Boolean = false,
    val subOutput: Int = 0,
    /** Own level of FL, FR, RL, RR on top of balance / fader, dB. */
    val trims: List<Int> = listOf(0, 0, 0, 0),
    /** Highest volume step the controls reach. */
    val volumeLimit: Int = 40,
    /** The chip's tone and loudness held flat (equaliser in ViPER4Android). */
    val toneByViper: Boolean = false,
    /** Driver LibreHU runs for the board's sound processor ("BD37534", "BU32107", "" none). */
    val driver: String = "BD37534",
    /** Experimental BU32107 driver turned on, its own settings, and the chip's test noise (0 off, 1 pink, 2 white). */
    val buDriver: Boolean = false,
    val bu: org.librehu.core.audio.Bu32107Settings = org.librehu.core.audio.Bu32107Settings(),
    val testNoise: Int = 0,
    /** Experimental AK7604 driver on, and its settings. */
    val akDriver: Boolean = false,
    val ak: org.librehu.core.audio.Ak7604Settings = org.librehu.core.audio.Ak7604Settings(),
    val externalAmp: Boolean = false,
    /** 0 = Android, 1 = AUX. */
    val source: Int = 0,
    /** Gain of the Android input, dB. */
    val androidInputGain: Int = 0,
    /** Board id ("" when unknown), sound processor, whether LibreHU drives it, Jancar's Android input gain. */
    val boardId: String = "",
    val chip: String = "",
    val chipSupported: Boolean = true,
    val jancarAndroidGain: Int = 4,
)

data class PairingRequest(
    val address: String,
    val name: String,
    val variant: Int,
    val passkey: Int,
)

/**
 * The settings app talks to the service through its public API (the same AIDL other apps use), and exposes the
 * answers as Compose state.
 */
class ServiceClient(
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())

    var api by mutableStateOf<ILibreHuService?>(null)
        private set
    var bt by mutableStateOf<ILibreHuBluetooth?>(null)
        private set
    var audio by mutableStateOf(AudioState())
        private set
    var btStatus by mutableStateOf(BtStatus())
        private set
    var calls by mutableStateOf<List<BtCallInfo>>(emptyList())
        private set
    var media by mutableStateOf(BtMediaInfo())
        private set
    var bonded by mutableStateOf<List<BtDeviceInfo>>(emptyList())
        private set
    var found by mutableStateOf<List<BtDeviceInfo>>(emptyList())
        private set
    var pairing by mutableStateOf<PairingRequest?>(null)
    var phonebookVersion by mutableStateOf(0)
        private set

    private val callback =
        object : ILibreHuCallback.Stub() {
            override fun onVehicleFlags(flags: Int) = Unit

            override fun onAudioChanged() = post { refreshAudio() }

            override fun onMcuFrame(
                cmd: Int,
                data: ByteArray?,
                fromMcu: Boolean,
            ) = Unit

            override fun onKey(
                channel: Int,
                values: IntArray?,
                released: Boolean,
                learning: Boolean,
            ) = Unit

            override fun onCanData(data: ByteArray?) = Unit
        }

    private val btCallback =
        object : ILibreHuBluetoothCallback.Stub() {
            override fun onStatusChanged(status: BtStatus) = post { btStatus = status }

            override fun onCallsChanged(list: MutableList<BtCallInfo>) = post { calls = list.toList() }

            override fun onMediaChanged(info: BtMediaInfo) = post { media = info }

            override fun onDevicesChanged() = post { refreshDevices() }

            override fun onDeviceFound(device: BtDeviceInfo) =
                post {
                    if (!device.bonded && found.none { it.address == device.address }) found = found + device
                }

            override fun onPhonebookChanged() = post { phonebookVersion++ }

            override fun onPairingRequest(
                address: String,
                name: String,
                variant: Int,
                passkey: Int,
            ) = post { pairing = PairingRequest(address, name, variant, passkey) }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val s = ILibreHuService.Stub.asInterface(service)
                api = s
                call { it.registerCallback(callback) }
                try {
                    val b = s.bluetooth
                    bt = b
                    b.registerCallback(btCallback)
                } catch (_: RemoteException) {
                }
                refreshAudio()
                refreshDevices()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                api = null
                bt = null
            }
        }

    fun bind() {
        LibreHuService.start(context)
        context.bindService(Intent(context, LibreHuService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        call { it.unregisterCallback(callback) }
        btCall { it.unregisterCallback(btCallback) }
        context.unbindService(connection)
    }

    fun refreshAudio() {
        call { s ->
            val bf = s.balanceFade
            val adv = s.audioAdvanced
            audio =
                AudioState(
                    volume = s.volume,
                    maxVolume = s.maxVolume,
                    muted = s.isMuted,
                    bassDb = adv.getInt("bassDb"),
                    middleDb = adv.getInt("middleDb"),
                    trebleDb = adv.getInt("trebleDb"),
                    bassFreq = adv.getInt("bassFreq", 80),
                    bassQ = adv.getInt("bassQ", 100),
                    middleFreq = adv.getInt("middleFreq", 1500),
                    middleQ = adv.getInt("middleQ", 100),
                    trebleFreq = adv.getInt("trebleFreq", 12500),
                    trebleQ = adv.getInt("trebleQ", 75),
                    loudnessFreq = adv.getInt("loudnessFreq", 800),
                    loudnessHicut = adv.getInt("loudnessHicut", 1),
                    subCutoff = adv.getInt("subCutoff", 120),
                    subPhase180 = adv.getBoolean("subPhase180"),
                    subOutput = adv.getInt("subOutput"),
                    trims = adv.getIntArray("trims")?.toList() ?: listOf(0, 0, 0, 0),
                    volumeLimit = adv.getInt("maxVolume", s.maxVolume),
                    toneByViper = adv.getBoolean("toneByViper"),
                    driver = adv.getString("driver") ?: "",
                    buDriver = adv.getBoolean("bu32107Driver"),
                    bu = org.librehu.core.audio.Bu32107Settings.decode(adv.getString("bu32107")),
                    testNoise = adv.getInt("testNoise"),
                    akDriver = adv.getBoolean("ak7604Driver"),
                    ak = org.librehu.core.audio.Ak7604Settings.decode(adv.getString("ak7604")),
                    balance = bf[0],
                    fade = bf[1],
                    loudness = s.loudness,
                    subwoofer = s.isSubwooferOn,
                    subLevel = s.subwooferLevel,
                    externalAmp = s.isExternalAmpEnabled,
                    source = s.audioSource,
                    androidInputGain = s.androidInputGain,
                    boardId = s.boardInfo.getString("boardId").orEmpty(),
                    chip = s.boardInfo.getString("chip").orEmpty(),
                    chipSupported = s.boardInfo.getBoolean("supported", true),
                    jancarAndroidGain = s.boardInfo.getInt("jancarAndroidGain", 4),
                )
        }
    }

    /** One setting of getAudioAdvanced (API 10). */
    fun adv(
        key: String,
        value: Any,
    ) {
        val b = android.os.Bundle()
        when (value) {
            is Int -> b.putInt(key, value)
            is Boolean -> b.putBoolean(key, value)
            is String -> b.putString(key, value)
            else -> return
        }
        call { it.setAudioAdvanced(b) }
    }

    /** Level of one speaker (0 FL, 1 FR, 2 RL, 3 RR), the others unchanged. */
    fun advTrims(
        trims: List<Int>,
        index: Int,
        db: Int,
    ) {
        val t = IntArray(4) { trims.getOrElse(it) { 0 } }
        t[index] = db
        call { it.setAudioAdvanced(android.os.Bundle().apply { putIntArray("trims", t) }) }
    }

    fun refreshDevices() {
        btCall { bonded = it.bondedDevices }
    }

    fun clearFound() {
        found = emptyList()
    }

    fun call(block: (ILibreHuService) -> Unit) {
        val s = api ?: return
        try {
            block(s)
        } catch (_: RemoteException) {
        }
    }

    fun btCall(block: (ILibreHuBluetooth) -> Unit) {
        val b = bt ?: return
        try {
            block(b)
        } catch (_: RemoteException) {
        }
    }

    fun <T> btGet(
        default: T,
        block: (ILibreHuBluetooth) -> T,
    ): T {
        val b = bt ?: return default
        return try {
            block(b)
        } catch (_: RemoteException) {
            default
        }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }
}
