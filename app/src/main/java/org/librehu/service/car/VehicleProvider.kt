package org.librehu.service.car

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * `content://org.librehu.service.vehicle/info`: one row with the car's name and description and the head unit's
 * radio names and MAC addresses ([VehicleInfo]); `update` changes the description, and `name` renames the car
 * ([CarName]); `carplay_video_low_latency` (1 / 0) is the CarPlay page's decoder low-latency switch. Read and write need `org.librehu.permission.VEHICLE`, a signature permission: the LibreHU apps only.
 */
class VehicleProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val c = context!!
        val v = VehicleInfo.get(c).value
        val r = VehicleInfo.radios(c)
        val row =
            linkedMapOf<String, Any?>(
                COL_NAME to CarName.name(c),
                COL_MAKE to v.make,
                COL_MODEL to v.model,
                COL_YEAR to v.year,
                COL_COLOR to v.color,
                COL_PLATE to v.plate,
                COL_FUEL to v.fuel,
                COL_DRIVER_SIDE to v.driverSide.name.lowercase(),
                COL_BT_NAME to r.bluetoothName,
                COL_BT_MAC to r.bluetoothMac,
                COL_WIFI_MAC to r.wifiMac,
                COL_HOTSPOT_SSID to r.hotspotSsid,
                COL_WIFI_DIRECT_NAME to r.wifiDirectName,
                COL_CARPLAY_LOW_LATENCY to
                    if (org.librehu.service.LibreHuService.prefs(c).getBoolean(org.librehu.service.projection.CarPlay.PREF_VIDEO_LOW_LATENCY, true)) "1" else "0",
            )
        val cursor = MatrixCursor(row.keys.toTypedArray())
        cursor.addRow(row.values.toTypedArray())
        cursor.setNotificationUri(c.contentResolver, VehicleInfo.URI)
        return cursor
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val c = context!!
        val v = values ?: return 0
        VehicleInfo.update(c) {
            it.copy(
                make = v.getAsString(COL_MAKE) ?: it.make,
                model = v.getAsString(COL_MODEL) ?: it.model,
                year = v.getAsString(COL_YEAR) ?: it.year,
                color = v.getAsString(COL_COLOR) ?: it.color,
                plate = v.getAsString(COL_PLATE) ?: it.plate,
                fuel = v.getAsString(COL_FUEL) ?: it.fuel,
                driverSide =
                    v.getAsString(COL_DRIVER_SIDE)?.let { s ->
                        DriverSide.entries.firstOrNull { d -> d.name.equals(s, ignoreCase = true) }
                    } ?: it.driverSide,
            )
        }
        v.getAsString(COL_NAME)?.takeIf { it.isNotBlank() && it != CarName.name(c) }?.let {
            CarName.set(c, it, CarName.renameHotspot(c))
            c.contentResolver.notifyChange(VehicleInfo.URI, null)
        }
        return 1
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.org.librehu.vehicle"

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    companion object {
        const val COL_NAME = "name"
        const val COL_MAKE = "make"
        const val COL_MODEL = "model"
        const val COL_YEAR = "year"
        const val COL_COLOR = "color"
        const val COL_PLATE = "plate"
        const val COL_FUEL = "fuel"
        const val COL_DRIVER_SIDE = "driver_side"
        const val COL_BT_NAME = "bt_name"
        const val COL_BT_MAC = "bt_mac"
        const val COL_WIFI_MAC = "wifi_mac"
        const val COL_HOTSPOT_SSID = "hotspot_ssid"
        const val COL_WIFI_DIRECT_NAME = "wifi_direct_name"
        const val COL_CARPLAY_LOW_LATENCY = "carplay_video_low_latency"
    }
}
