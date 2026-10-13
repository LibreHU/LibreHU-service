package org.librehu.service.car

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.root.RootShell

/** Driver's side, for the projection apps (Android Auto and CarPlay draw their bar on that side). */
enum class DriverSide { LEFT, RIGHT }

/** Description of the car, typed once and given to the LibreHU apps (SOS screen, Android Auto, CarPlay). */
data class Vehicle(
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val color: String = "",
    val plate: String = "",
    /** Petrol, diesel, electric, hybrid, LPG… as typed. */
    val fuel: String = "",
    val driverSide: DriverSide = DriverSide.LEFT,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("make", make)
            .put("model", model)
            .put("year", year)
            .put("color", color)
            .put("plate", plate)
            .put("fuel", fuel)
            .put("driverSide", driverSide.name)

    companion object {
        fun fromJson(o: JSONObject) =
            Vehicle(
                make = o.optString("make"),
                model = o.optString("model"),
                year = o.optString("year"),
                color = o.optString("color"),
                plate = o.optString("plate"),
                fuel = o.optString("fuel"),
                driverSide = DriverSide.entries.firstOrNull { it.name == o.optString("driverSide") } ?: DriverSide.LEFT,
            )
    }
}

/** Names and addresses of the head unit as the phone sees them. */
data class Radios(
    val bluetoothName: String = "",
    val bluetoothMac: String = "",
    val wifiMac: String = "",
    val hotspotSsid: String = "",
    val wifiDirectName: String = "",
)

/**
 * The car's description ([Vehicle]) and the head unit's names and addresses ([Radios]), shared with the LibreHU apps
 * through [VehicleProvider] (signature permission `org.librehu.permission.VEHICLE`): the launcher's first setup and
 * SOS screen, Open Headunit LibreHU and CarPlay LibreHU, so that nothing has to be typed twice.
 */
object VehicleInfo {
    private const val PREF = "vehicle"
    private const val PREF_MACS = "vehicle_macs"
    val URI: Uri = Uri.parse("content://org.librehu.service.vehicle/info")

    private val _vehicle = MutableStateFlow(Vehicle())
    val vehicle: StateFlow<Vehicle> = _vehicle.asStateFlow()
    private var loaded = false

    fun get(context: Context): StateFlow<Vehicle> {
        load(context)
        return vehicle
    }

    fun update(
        context: Context,
        transform: (Vehicle) -> Vehicle,
    ) {
        load(context)
        val v = transform(_vehicle.value).let { it.copy(plate = it.plate.uppercase().trim()) }
        _vehicle.value = v
        LibreHuService
            .prefs(context)
            .edit()
            .putString(PREF, v.toJson().toString())
            .apply()
        context.contentResolver.notifyChange(URI, null)
    }

    /**
     * Current names and addresses. Android hides the MAC addresses from ordinary apps (02:00:00:00:00:00): read once
     * as root (sysfs, the Bluetooth setting) and kept.
     */
    fun radios(context: Context): Radios {
        val app = context.applicationContext
        val bt =
            runCatching {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()
            }.getOrNull()
        val btName = runCatching { bt?.name }.getOrNull().orEmpty()
        val macs = macs(app)
        return Radios(
            bluetoothName = btName,
            bluetoothMac = macs.first,
            wifiMac = macs.second,
            hotspotSsid = hotspotSsid(app),
            wifiDirectName = runCatching { Settings.Global.getString(app.contentResolver, "wifi_p2p_device_name") }.getOrNull().orEmpty(),
        )
    }

    /** Bluetooth and Wi-Fi MAC addresses, empty when unknown. */
    private fun macs(context: Context): Pair<String, String> {
        val p = LibreHuService.prefs(context)
        p.getString(PREF_MACS, null)?.split('|')?.takeIf { it.size == 2 && it.all { m -> m.isNotEmpty() } }?.let {
            return it[0] to it[1]
        }
        var bt = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull().orEmpty()
        var wifi = ""
        if (RootShell.isAvailable()) {
            if (!valid(bt)) {
                bt =
                    RootShell
                        .run("settings get secure bluetooth_address")
                        ?.output
                        ?.trim()
                        .orEmpty()
            }
            wifi =
                RootShell
                    .run("cat /sys/class/net/wlan0/address")
                    ?.output
                    ?.trim()
                    .orEmpty()
        }
        bt = bt.uppercase().takeIf(::valid).orEmpty()
        wifi = wifi.uppercase().takeIf(::valid).orEmpty()
        if (bt.isNotEmpty() && wifi.isNotEmpty()) p.edit().putString(PREF_MACS, "$bt|$wifi").apply()
        return bt to wifi
    }

    private fun valid(mac: String) = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(mac) && !mac.startsWith("02:00:00")

    private fun hotspotSsid(context: Context): String =
        runCatching {
            val wifi = context.getSystemService(WifiManager::class.java)
            (WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration)?.SSID
        }.getOrNull() ?: CarName.name(context).take(32)

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        LibreHuService.prefs(context).getString(PREF, null)?.let {
            runCatching { _vehicle.value = Vehicle.fromJson(JSONObject(it)) }
        }
    }
}
