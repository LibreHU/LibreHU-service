package org.librehu.service.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

/**
 * "Bluetooth player in the foreground" flag of this ROM's AVRCP controller (Autochips / MediaTek Bluetooth stack).
 * Its A2DP sink never takes the audio focus itself ("audiofocus is not manager by the service and is not in
 * foreground, not update"): a play started on the phone is paused 0.4 s later (MESSAGE_INTERNAL_DELAY_PAUSE_SRC_PLAY
 * → sendAvrcpPause) unless the player app said it is in the foreground. Jancar's btservice does it before every
 * play / pause (`A2dpUtil.setPlayerState`, through `com.autochips.bluetooth.AvrcpControllerProfile.setPlayerState`).
 *
 * Reached by reflection, first on the AVRCP controller profile proxy, then through the Autochips Bluetooth library
 * of the framework; nothing happens on other ROMs.
 */
internal class AvrcpPlayerState(
    private val context: Context,
) {
    private var proxy: BluetoothProfile? = null
    private var pending: Boolean? = null
    private var logged = false

    fun start() {
        try {
            BluetoothAdapter.getDefaultAdapter()?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(
                        profile: Int,
                        p: BluetoothProfile,
                    ) {
                        proxy = p
                        pending?.let { set(it) }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        proxy = null
                    }
                },
                AVRCP_CONTROLLER,
            )
        } catch (e: Exception) {
            Log.w(TAG, "AVRCP controller proxy: ${e.message}")
        }
    }

    fun stop() {
        proxy?.let { p ->
            runCatching { BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(AVRCP_CONTROLLER, p) }
        }
        proxy = null
    }

    /** Tells the Bluetooth stack whether the car's Bluetooth player is the active one. */
    fun set(foreground: Boolean) {
        pending = foreground
        val how = viaProxy(foreground) ?: viaAutochips(foreground)
        if (how != null) pending = null
        if (!logged || how == null) {
            Log.i(TAG, "AVRCP player state $foreground: ${how ?: "not available on this ROM"}")
            logged = how != null
        }
    }

    /** Hidden `setPlayerState` of the AVRCP controller proxy, with or without the device. */
    private fun viaProxy(foreground: Boolean): String? {
        val p = proxy ?: return null
        val methods = p.javaClass.methods.filter { it.name == "setPlayerState" }
        for (m in methods) {
            try {
                val types = m.parameterTypes
                when {
                    types.size == 1 && types[0] == Boolean::class.javaPrimitiveType -> {
                        m.invoke(p, foreground)
                        return "AVRCP controller proxy"
                    }

                    types.size == 2 && types[0] == BluetoothDevice::class.java -> {
                        val devices = p.connectedDevices
                        if (devices.isEmpty()) continue
                        devices.forEach { m.invoke(p, it, foreground) }
                        return "AVRCP controller proxy (per device)"
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setPlayerState on the proxy: ${e.cause ?: e}")
            }
        }
        return null
    }

    /** `LocalBluetoothManager.getInstance(ctx).getProfileManager().getAvrcpCtProfile().setPlayerState(b)`. */
    private fun viaAutochips(foreground: Boolean): String? =
        try {
            val manager =
                Class
                    .forName("com.autochips.bluetooth.LocalBluetoothManager")
                    .getMethod("getInstance", Context::class.java)
                    .invoke(null, context.applicationContext)
            val profiles = manager?.javaClass?.getMethod("getProfileManager")?.invoke(manager)
            val avrcp = profiles?.javaClass?.getMethod("getAvrcpCtProfile")?.invoke(profiles)
            if (avrcp == null) {
                null
            } else {
                avrcp.javaClass.getMethod("setPlayerState", Boolean::class.javaPrimitiveType).invoke(avrcp, foreground)
                "Autochips AvrcpControllerProfile"
            }
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Autochips setPlayerState: ${e.cause ?: e}")
            null
        }

    private companion object {
        const val TAG = "LibreHU-BT"

        /** BluetoothProfile.AVRCP_CONTROLLER (hidden). */
        const val AVRCP_CONTROLLER = 12
    }
}
