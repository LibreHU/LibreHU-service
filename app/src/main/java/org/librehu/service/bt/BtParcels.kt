package org.librehu.service.bt

import android.os.Parcel
import android.os.Parcelable

/*
 * Data classes of the Bluetooth API (ILibreHuBluetooth). Clients copy this file and the .aidl files of
 * org.librehu.service.bt. Fields are only ever appended, so that older clients keep reading newer parcels.
 */

/** Connection states, as `BluetoothProfile.STATE_*`: 0 disconnected, 1 connecting, 2 connected, 3 disconnecting. */
data class BtStatus(
    /** Bluetooth adapter on. */
    val enabled: Boolean = false,
    /** Name shown to phones. */
    val name: String = "",
    /** PIN for phones that still ask for one (legacy pairing). */
    val pin: String = "",
    /**
     * True when the service drives Bluetooth itself (auto-connect, ringtone, pairing answers). False while Jancar's
     * btservice is enabled: both would answer the same events.
     */
    val activeMode: Boolean = false,
    val discovering: Boolean = false,
    val discoverable: Boolean = false,
    /** Phone connected for calls (or for music when no hands-free link), empty when none. */
    val deviceAddress: String = "",
    val deviceName: String = "",
    val hfpState: Int = 0,
    val a2dpState: Int = 0,
    val pbapState: Int = 0,
    /** Call audio in the car (SCO up) rather than on the phone. */
    val audioInCar: Boolean = false,
    val micMuted: Boolean = false,
    /** Phone battery 0..5, -1 unknown (HFP indicator). */
    val battery: Int = -1,
    /** Network signal 0..5, -1 unknown (HFP indicator). */
    val signal: Int = -1,
    val operator: String = "",
    val roaming: Boolean = false,
    val autoConnect: Boolean = true,
    val autoAnswer: Boolean = false,
    /** Voice assistant of the phone listening (HFP voice recognition). */
    val voiceAssistant: Boolean = false,
) : Parcelable {
    override fun writeToParcel(
        p: Parcel,
        flags: Int,
    ) {
        p.writeBool(enabled)
        p.writeString(name)
        p.writeString(pin)
        p.writeBool(activeMode)
        p.writeBool(discovering)
        p.writeBool(discoverable)
        p.writeString(deviceAddress)
        p.writeString(deviceName)
        p.writeInt(hfpState)
        p.writeInt(a2dpState)
        p.writeInt(pbapState)
        p.writeBool(audioInCar)
        p.writeBool(micMuted)
        p.writeInt(battery)
        p.writeInt(signal)
        p.writeString(operator)
        p.writeBool(roaming)
        p.writeBool(autoConnect)
        p.writeBool(autoAnswer)
        p.writeBool(voiceAssistant)
    }

    override fun describeContents() = 0

    companion object {
        @JvmField
        val CREATOR =
            creator { p ->
                BtStatus(
                    enabled = p.readBool(),
                    name = p.readStr(),
                    pin = p.readStr(),
                    activeMode = p.readBool(),
                    discovering = p.readBool(),
                    discoverable = p.readBool(),
                    deviceAddress = p.readStr(),
                    deviceName = p.readStr(),
                    hfpState = p.readInt(),
                    a2dpState = p.readInt(),
                    pbapState = p.readInt(),
                    audioInCar = p.readBool(),
                    micMuted = p.readBool(),
                    battery = p.readInt(),
                    signal = p.readInt(),
                    operator = p.readStr(),
                    roaming = p.readBool(),
                    autoConnect = p.readBool(),
                    autoAnswer = p.readBool(),
                    voiceAssistant = p.readBool(),
                )
            }
    }
}

/** A paired or discovered device. */
data class BtDeviceInfo(
    val address: String = "",
    val name: String = "",
    val bonded: Boolean = false,
    /** `BluetoothClass.Device.Major` value (0x200 phone), 0 unknown. */
    val majorClass: Int = 0,
    val hfpState: Int = 0,
    val a2dpState: Int = 0,
    /** Signal strength while discovering, 0 when unknown. */
    val rssi: Int = 0,
) : Parcelable {
    val connected: Boolean get() = hfpState == 2 || a2dpState == 2

    override fun writeToParcel(
        p: Parcel,
        flags: Int,
    ) {
        p.writeString(address)
        p.writeString(name)
        p.writeBool(bonded)
        p.writeInt(majorClass)
        p.writeInt(hfpState)
        p.writeInt(a2dpState)
        p.writeInt(rssi)
    }

    override fun describeContents() = 0

    companion object {
        @JvmField
        val CREATOR =
            creator { p ->
                BtDeviceInfo(p.readStr(), p.readStr(), p.readBool(), p.readInt(), p.readInt(), p.readInt(), p.readInt())
            }
    }
}

/** A call: [state] is the HFP call state (`org.librehu.core.bt.CallState.hfp`: 0 active, 4 incoming … 7 ended). */
data class BtCallInfo(
    val id: Int = 0,
    val state: Int = 7,
    val number: String = "",
    /** Contact name from the phone book, empty when unknown. */
    val name: String = "",
    val multiParty: Boolean = false,
    val outgoing: Boolean = false,
    /** When the call became active (System.currentTimeMillis), 0 while not answered. */
    val activeSince: Long = 0,
) : Parcelable {
    override fun writeToParcel(
        p: Parcel,
        flags: Int,
    ) {
        p.writeInt(id)
        p.writeInt(state)
        p.writeString(number)
        p.writeString(name)
        p.writeBool(multiParty)
        p.writeBool(outgoing)
        p.writeLong(activeSince)
    }

    override fun describeContents() = 0

    companion object {
        @JvmField
        val CREATOR =
            creator { p -> BtCallInfo(p.readInt(), p.readInt(), p.readStr(), p.readStr(), p.readBool(), p.readBool(), p.readLong()) }
    }
}

/** Bluetooth music (A2DP sink + AVRCP controller). */
data class BtMediaInfo(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val positionMs: Long = 0,
) : Parcelable {
    override fun writeToParcel(
        p: Parcel,
        flags: Int,
    ) {
        p.writeBool(connected)
        p.writeBool(playing)
        p.writeString(title)
        p.writeString(artist)
        p.writeString(album)
        p.writeLong(durationMs)
        p.writeLong(positionMs)
    }

    override fun describeContents() = 0

    companion object {
        @JvmField
        val CREATOR =
            creator { p -> BtMediaInfo(p.readBool(), p.readBool(), p.readStr(), p.readStr(), p.readStr(), p.readLong(), p.readLong()) }
    }
}

/**
 * Phone book entry or call log entry. [type]: phone number type (`Phone.TYPE_*`) for contacts, call type
 * (`CallLog.Calls.TYPE`: 1 incoming, 2 outgoing, 3 missed) for the call log. [date]: call time, 0 for contacts.
 */
data class BtContact(
    val name: String = "",
    val number: String = "",
    val type: Int = 0,
    val date: Long = 0,
) : Parcelable {
    override fun writeToParcel(
        p: Parcel,
        flags: Int,
    ) {
        p.writeString(name)
        p.writeString(number)
        p.writeInt(type)
        p.writeLong(date)
    }

    override fun describeContents() = 0

    companion object {
        @JvmField
        val CREATOR = creator { p -> BtContact(p.readStr(), p.readStr(), p.readInt(), p.readLong()) }
    }
}

private fun Parcel.writeBool(v: Boolean) = writeInt(if (v) 1 else 0)

private fun Parcel.readBool() = readInt() != 0

private fun Parcel.readStr() = readString() ?: ""

private inline fun <reified T> creator(crossinline read: (Parcel) -> T): Parcelable.Creator<T> =
    object : Parcelable.Creator<T> {
        override fun createFromParcel(source: Parcel): T = read(source)

        override fun newArray(size: Int): Array<T?> = arrayOfNulls(size)
    }
