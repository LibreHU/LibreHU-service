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
    val bass: Int = 10,
    val middle: Int = 10,
    val treble: Int = 10,
    val balance: Int = 30,
    val fade: Int = 30,
    val loudness: Int = 0,
    val subwoofer: Boolean = false,
    val subLevel: Int = 6,
    val externalAmp: Boolean = false,
    /** 0 = Android, 1 = AUX. */
    val source: Int = 0,
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
            val t = s.tone
            val bf = s.balanceFade
            audio =
                AudioState(
                    volume = s.volume,
                    maxVolume = s.maxVolume,
                    muted = s.isMuted,
                    bass = t[0],
                    middle = t[1],
                    treble = t[2],
                    balance = bf[0],
                    fade = bf[1],
                    loudness = s.loudness,
                    subwoofer = s.isSubwooferOn,
                    subLevel = s.subwooferLevel,
                    externalAmp = s.isExternalAmpEnabled,
                    source = s.audioSource,
                )
        }
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
