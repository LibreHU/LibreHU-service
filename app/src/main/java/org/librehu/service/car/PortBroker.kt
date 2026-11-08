package org.librehu.service.car

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who uses each physical port of the car's data sources (a USB-serial adapter, a Bluetooth serial adapter, a serial
 * port of the SoC), so that the sources run together without taking a port from each other: the CAN box on one, the
 * OBD-II adapter on another, their values combined by role ([CarDataHub]). One owner per port in this service; a USB
 * adapter another app holds (the TPMS receiver of LibreHU Launcher…) is never claimed by force
 * ([claimUsbInterfaces]): taking it made both apps fail in a loop (« Error writing … rc=-1 », 02/11/2026).
 */
object PortBroker {
    /** Owners. */
    const val CANBOX = "canbox"
    const val OBD = "obd"

    data class Claim(
        val key: String,
        val owner: String,
        /** What the CAN tab shows: « USB FT232R », « Bluetooth OBDII »… */
        val label: String,
    )

    private val claims = LinkedHashMap<String, Claim>()
    private val _state = MutableStateFlow<List<Claim>>(emptyList())

    /** The ports in use and by whom. */
    val state: StateFlow<List<Claim>> = _state.asStateFlow()

    fun usb(deviceName: String) = "usb:$deviceName"

    fun bluetooth(address: String) = "bt:" + address.uppercase()

    fun serial(path: String) = "serial:$path"

    /** Takes [key] for [owner]: null when done (or already its own), else the owner that holds it. */
    @Synchronized
    fun claim(
        key: String,
        owner: String,
        label: String,
    ): String? {
        val c = claims[key]
        if (c != null && c.owner != owner) return c.owner
        claims[key] = Claim(key, owner, label)
        _state.value = claims.values.toList()
        return null
    }

    /** Every port [owner] holds is free again. */
    @Synchronized
    fun releaseAll(owner: String) {
        if (claims.values.removeAll { it.owner == owner }) _state.value = claims.values.toList()
    }

    @Synchronized
    fun holder(key: String): String? = claims[key]?.owner

    /** USB device names held by another owner than [owner] (left out of an automatic choice). */
    @Synchronized
    fun usbHeldByOthers(owner: String): Set<String> =
        claims.values.filter { it.owner != owner && it.key.startsWith("usb:") }.map { it.key.removePrefix("usb:") }.toSet()

    /**
     * Claims every interface of [device] on [connection] without force. usb-serial-for-android claims them by force,
     * which takes an adapter from another app's connection; no kernel driver binds these adapters on this ROM
     * (CONFIG_USB_SERIAL off), so a refusal means another app has it.
     */
    fun claimUsbInterfaces(
        device: UsbDevice,
        connection: UsbDeviceConnection,
    ): Boolean = (0 until device.interfaceCount).all { connection.claimInterface(device.getInterface(it), false) }

    /** The owner's name for a message (« the OBD adapter », « the CAN box »). */
    fun what(owner: String): String =
        when (owner) {
            OBD -> "the OBD adapter"
            CANBOX -> "the CAN box"
            else -> owner
        }
}
