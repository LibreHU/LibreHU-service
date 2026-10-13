package org.librehu.service.car

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Name of the car, given to everything that shows one: Bluetooth (what the phone sees), Android's device name,
 * the network host name (DHCP, hotspot clients), the Wi-Fi hotspot SSID and the Wi-Fi Direct name (wireless
 * Android Auto / CarPlay).
 */
object CarName {
    private const val TAG = "LibreHU-CarName"
    private const val PREF_NAME = "car_name"
    private const val PREF_HOTSPOT = "car_name_hotspot"

    /** Hotspot settings of Android 9 (WifiApConfigStore), read by system_server at boot. */
    private const val SOFTAP_CONF = "/data/misc/wifi/softap.conf"

    /** Result of a rename, one line per target. */
    data class Result(
        val bluetooth: Boolean,
        val deviceName: Boolean,
        val hostname: Boolean,
        val hotspot: Hotspot,
        /** Wi-Fi Direct device name (`wifi_p2p_device_name`, used from the next start of Wi-Fi Direct). */
        val wifiDirect: Boolean = false,
    )

    enum class Hotspot { SKIPPED, DONE, AFTER_REBOOT, FAILED }

    fun name(context: Context): String = LibreHuService.prefs(context).getString(PREF_NAME, "") ?: ""

    fun renameHotspot(context: Context): Boolean = LibreHuService.prefs(context).getBoolean(PREF_HOTSPOT, true)

    /** Saves [name] and applies it now (root for the device name, host name and, without privileges, the hotspot). */
    fun set(
        context: Context,
        name: String,
        hotspot: Boolean,
    ): Result {
        val clean = sanitize(name)
        LibreHuService
            .prefs(context)
            .edit()
            .putString(PREF_NAME, clean)
            .putBoolean(PREF_HOTSPOT, hotspot)
            .apply()
        return apply(context, clean, hotspot)
    }

    /** At each start of the service: the host name property does not survive a reboot. */
    fun applySaved(context: Context) {
        val n = name(context)
        if (n.isNotEmpty()) setHostname(n)
    }

    private fun apply(
        context: Context,
        name: String,
        hotspot: Boolean,
    ): Result {
        if (name.isEmpty()) return Result(false, false, false, Hotspot.SKIPPED, false)
        val bt =
            try {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()?.setName(name) == true
            } catch (e: SecurityException) {
                Log.w(TAG, "Bluetooth name: ${e.message}")
                false
            }
        val device =
            try {
                Settings.Global.putString(context.contentResolver, "device_name", name)
            } catch (_: SecurityException) {
                RootShell.run("settings put global device_name ${quote(name)}") != null
            }
        val host = setHostname(name)
        val ap = if (hotspot) setHotspotSsid(context, name) else Hotspot.SKIPPED
        val p2p =
            try {
                Settings.Global.putString(context.contentResolver, "wifi_p2p_device_name", name.take(32))
            } catch (_: SecurityException) {
                RootShell.run("settings put global wifi_p2p_device_name ${quote(name.take(32))}") != null
            }
        context.contentResolver.notifyChange(VehicleInfo.URI, null)
        return Result(bt, device, host, ap, p2p)
    }

    /** DHCP host name: letters, digits and dashes, 63 characters at most. */
    fun hostnameOf(name: String): String =
        name
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(63)
            .ifEmpty { "librehu" }

    private fun setHostname(name: String): Boolean =
        RootShell.isAvailable() && RootShell.run("setprop net.hostname ${hostnameOf(name)}") != null

    /**
     * Hotspot SSID: the system API needs OVERRIDE_WIFI_CONFIG (not requested: a privileged permission missing from the
     * privapp whitelist stops the boot on ROMs that enforce it), so it usually fails and, as root, the SSID is
     * rewritten in softap.conf and taken into account at the next boot.
     */
    private fun setHotspotSsid(
        context: Context,
        name: String,
    ): Hotspot {
        val ssid = name.take(32)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        try {
            val get = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val set = WifiManager::class.java.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
            val config = get.invoke(wifi) as? WifiConfiguration
            if (config != null) {
                config.SSID = ssid
                if (set.invoke(wifi, config) == true) return Hotspot.DONE
            }
        } catch (e: Exception) {
            Log.i(TAG, "Hotspot API: ${e.cause?.message ?: e.message}")
        }
        return if (rewriteSoftApConf(ssid)) Hotspot.AFTER_REBOOT else Hotspot.FAILED
    }

    /** Version, SSID (modified UTF-8) then the rest kept as is: valid for the Android 8 / 9 formats. */
    private fun rewriteSoftApConf(ssid: String): Boolean {
        if (!RootShell.isAvailable()) return false
        val tmp = File.createTempFile("softap", ".conf")
        try {
            if (RootShell.run("cat $SOFTAP_CONF > '${tmp.path}' && chmod 666 '${tmp.path}'") == null) return false
            val bytes = tmp.readBytes()
            if (bytes.isEmpty()) return false
            val input = DataInputStream(bytes.inputStream())
            val version = input.readInt()
            input.readUTF()
            val rest = input.readBytes()
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeInt(version)
                writeUTF(ssid)
                write(rest)
                flush()
            }
            tmp.writeBytes(out.toByteArray())
            return RootShell.run("cat '${tmp.path}' > $SOFTAP_CONF && chown system:wifi $SOFTAP_CONF && chmod 660 $SOFTAP_CONF") != null
        } catch (e: Exception) {
            Log.w(TAG, "softap.conf: ${e.message}")
            return false
        } finally {
            tmp.delete()
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[\\r\\n\\t]"), " ").trim().take(64)

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
