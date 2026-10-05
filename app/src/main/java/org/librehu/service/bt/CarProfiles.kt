package org.librehu.service.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.librehu.core.bt.Call
import org.librehu.core.bt.CallState

/**
 * Proxies of Android's car-side Bluetooth profiles and the hidden calls the head unit needs on them. Profile ids and
 * method signatures: `android.bluetooth.BluetoothProfile`, `BluetoothHeadsetClient`, `BluetoothA2dpSink`,
 * `BluetoothPbapClient` of Android 9 (the same as Jancar's btservice uses through settingslib).
 */
internal class CarProfiles(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val onChanged: () -> Unit,
) {
    @Volatile
    var hfp: BluetoothProfile? = null
        private set

    @Volatile
    var a2dpSink: BluetoothProfile? = null
        private set

    @Volatile
    var pbap: BluetoothProfile? = null
        private set

    private val listener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                when (profile) {
                    HEADSET_CLIENT -> hfp = proxy
                    A2DP_SINK -> a2dpSink = proxy
                    PBAP_CLIENT -> pbap = proxy
                }
                Log.i(TAG, "Profile $profile ready")
                onChanged()
            }

            override fun onServiceDisconnected(profile: Int) {
                when (profile) {
                    HEADSET_CLIENT -> hfp = null
                    A2DP_SINK -> a2dpSink = null
                    PBAP_CLIENT -> pbap = null
                }
                onChanged()
            }
        }

    init {
        for (p in listOf(HEADSET_CLIENT, A2DP_SINK, PBAP_CLIENT)) {
            if (!adapter.getProfileProxy(context, listener, p)) Log.w(TAG, "Profile $p not supported by this ROM")
        }
    }

    fun close() {
        hfp?.let { adapter.closeProfileProxy(HEADSET_CLIENT, it) }
        a2dpSink?.let { adapter.closeProfileProxy(A2DP_SINK, it) }
        pbap?.let { adapter.closeProfileProxy(PBAP_CLIENT, it) }
        hfp = null
        a2dpSink = null
        pbap = null
    }

    // --- Any profile ---------------------------------------------------------------------------------------------

    fun state(
        profile: BluetoothProfile?,
        device: BluetoothDevice,
    ): Int =
        try {
            profile?.getConnectionState(device) ?: BluetoothProfile.STATE_DISCONNECTED
        } catch (_: Exception) {
            BluetoothProfile.STATE_DISCONNECTED
        }

    fun connected(profile: BluetoothProfile?): List<BluetoothDevice> =
        try {
            profile?.connectedDevices.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

    fun connect(
        profile: BluetoothProfile?,
        device: BluetoothDevice,
    ): Boolean = HiddenApi.call(profile, "connect", DEVICE, device) == true

    fun disconnect(
        profile: BluetoothProfile?,
        device: BluetoothDevice,
    ): Boolean = HiddenApi.call(profile, "disconnect", DEVICE, device) == true

    /** `PRIORITY_AUTO_CONNECT` (1000) lets the stack reconnect by itself too, `PRIORITY_OFF` (0) stops it. */
    fun setPriority(
        profile: BluetoothProfile?,
        device: BluetoothDevice,
        priority: Int,
    ) {
        HiddenApi.call(
            profile,
            "setPriority",
            arrayOf<Class<*>>(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!),
            device,
            priority,
        )
    }

    // --- Hands-free client -------------------------------------------------------------------------------------

    /** Current calls of [device], with the stack's call objects (needed by terminateCall). */
    fun calls(device: BluetoothDevice): List<Pair<Call, Any>> {
        val list = HiddenApi.call(hfp, "getCurrentCalls", DEVICE, device) as? List<*> ?: return emptyList()
        return list.mapNotNull { raw -> raw?.let { toCall(it)?.to(it) } }
    }

    fun dial(
        device: BluetoothDevice,
        number: String,
    ): Boolean = HiddenApi.call(hfp, "dial", arrayOf<Class<*>>(BluetoothDevice::class.java, String::class.java), device, number) != null

    fun accept(
        device: BluetoothDevice,
        flag: Int,
    ): Boolean =
        HiddenApi.call(hfp, "acceptCall", arrayOf<Class<*>>(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!), device, flag) ==
            true

    fun reject(device: BluetoothDevice): Boolean = HiddenApi.call(hfp, "rejectCall", DEVICE, device) == true

    fun hold(device: BluetoothDevice): Boolean = HiddenApi.call(hfp, "holdCall", DEVICE, device) == true

    fun terminate(
        device: BluetoothDevice,
        rawCall: Any?,
    ): Boolean {
        val callClass = HiddenApi.classOrNull(CALL_CLASS) ?: return false
        return HiddenApi.call(hfp, "terminateCall", arrayOf<Class<*>>(BluetoothDevice::class.java, callClass), device, rawCall) == true
    }

    fun sendDtmf(
        device: BluetoothDevice,
        code: Char,
    ): Boolean =
        HiddenApi.call(
            hfp,
            "sendDTMF",
            arrayOf<Class<*>>(BluetoothDevice::class.java, Byte::class.javaPrimitiveType!!),
            device,
            code.code.toByte(),
        ) ==
            true

    /** `STATE_AUDIO_*`: 0 disconnected, 1 connecting, 2 connected. */
    fun audioState(device: BluetoothDevice): Int = HiddenApi.call(hfp, "getAudioState", DEVICE, device) as? Int ?: 0

    fun connectAudio(device: BluetoothDevice): Boolean = HiddenApi.call(hfp, "connectAudio", DEVICE, device) == true

    fun disconnectAudio(device: BluetoothDevice): Boolean = HiddenApi.call(hfp, "disconnectAudio", DEVICE, device) == true

    fun startVoiceRecognition(device: BluetoothDevice): Boolean = HiddenApi.call(hfp, "startVoiceRecognition", DEVICE, device) == true

    fun stopVoiceRecognition(device: BluetoothDevice): Boolean = HiddenApi.call(hfp, "stopVoiceRecognition", DEVICE, device) == true

    /** Indicators known at connection time (battery, signal, operator…), keys of [EXTRA_BATTERY_LEVEL] & co. */
    fun agEvents(device: BluetoothDevice): Bundle? = HiddenApi.call(hfp, "getCurrentAgEvents", DEVICE, device) as? Bundle

    // --- Phone book ----------------------------------------------------------------------------------------------

    /** Allows the phone book and messages without asking on the phone side (`BluetoothDevice.ACCESS_ALLOWED`). */
    fun allowPhonebook(device: BluetoothDevice) {
        val int = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!)
        HiddenApi.call(device, "setPhonebookAccessPermission", int, ACCESS_ALLOWED)
        HiddenApi.call(device, "setMessageAccessPermission", int, ACCESS_ALLOWED)
    }

    companion object {
        private const val TAG = "LibreHU-BT"
        private val DEVICE = arrayOf<Class<*>>(BluetoothDevice::class.java)

        const val A2DP_SINK = 11
        const val HEADSET_CLIENT = 16
        const val PBAP_CLIENT = 17
        const val PRIORITY_OFF = 0
        const val PRIORITY_AUTO_CONNECT = 1000
        const val ACCESS_ALLOWED = 1

        const val CALL_CLASS = "android.bluetooth.BluetoothHeadsetClientCall"

        const val ACTION_HFP_CONNECTION = "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_HFP_AUDIO = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
        const val ACTION_AG_EVENT = "android.bluetooth.headsetclient.profile.action.AG_EVENT"
        const val ACTION_CALL_CHANGED = "android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"
        const val ACTION_A2DP_SINK_CONNECTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        const val ACTION_PBAP_CONNECTION = "android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED"

        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.headsetclient.extra.BATTERY_LEVEL"
        const val EXTRA_NETWORK_SIGNAL_STRENGTH = "android.bluetooth.headsetclient.extra.NETWORK_SIGNAL_STRENGTH"
        const val EXTRA_NETWORK_ROAMING = "android.bluetooth.headsetclient.extra.NETWORK_ROAMING"
        const val EXTRA_OPERATOR_NAME = "android.bluetooth.headsetclient.extra.OPERATOR_NAME"
        const val EXTRA_VOICE_RECOGNITION = "android.bluetooth.headsetclient.extra.VOICE_RECOGNITION"
        const val EXTRA_IN_BAND_RING = "android.bluetooth.headsetclient.extra.IN_BAND_RING"

        /** `BluetoothHeadsetClientCall` → [Call] (getId, getState, getNumber, isMultiParty, isOutgoing). */
        fun toCall(raw: Any): Call? {
            val c = raw.javaClass
            val none = arrayOf<Class<*>>()
            val id = HiddenApi.method(c, "getId")?.let { HiddenApi.call(raw, "getId", none) as? Int } ?: return null
            val state = HiddenApi.call(raw, "getState", none) as? Int ?: return null
            return Call(
                id = id,
                state = CallState.ofHfp(state),
                number = HiddenApi.call(raw, "getNumber", none) as? String ?: "",
                multiParty = HiddenApi.call(raw, "isMultiParty", none) == true,
                outgoing = HiddenApi.call(raw, "isOutgoing", none) == true,
            )
        }
    }
}
