package org.librehu.service.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.librehu.core.obd.Elm327
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Text link to an ELM327 adapter: commands end with CR, answers with the `>` prompt. */
interface ObdLink : Closeable {
    val description: String

    fun send(command: String)

    /** Everything received until the prompt, or until [timeoutMs] (then what arrived so far). */
    fun readUntilPrompt(timeoutMs: Long): String

    /** One command and its answer. */
    fun ask(
        command: String,
        timeoutMs: Long = 2_000,
    ): String {
        send(command)
        return readUntilPrompt(timeoutMs)
    }
}

/** Streams of a Bluetooth SPP socket (classic "OBDII" / "V-LINK" adapters). */
@SuppressLint("MissingPermission")
internal class BluetoothObdLink private constructor(
    private val socket: BluetoothSocket,
    override val description: String,
) : ObdLink {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream

    override fun send(command: String) {
        output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            if (input.available() <= 0) {
                Thread.sleep(10)
                continue
            }
            val n = input.read(buf)
            if (n < 0) throw IOException("Bluetooth link closed")
            sb.append(String(buf, 0, n, Charsets.US_ASCII))
            if (sb.contains(Elm327.PROMPT)) break
        }
        return sb.toString()
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        /** Serial Port Profile. */
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun open(address: String): BluetoothObdLink {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("No Bluetooth")
            if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("No adapter chosen")
            val device = adapter.getRemoteDevice(address)
            adapter.cancelDiscovery()
            val attempts =
                listOf<() -> BluetoothSocket>(
                    { device.createRfcommSocketToServiceRecord(SPP) },
                    { device.createInsecureRfcommSocketToServiceRecord(SPP) },
                    // Cheap clones without an SDP record: channel 1 directly.
                    { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
                )
            var last: Exception? = null
            for (make in attempts) {
                try {
                    val s = make()
                    s.connect()
                    return BluetoothObdLink(s, "Bluetooth ${device.name ?: address}")
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IOException("Bluetooth connection to $address failed: ${last?.message}")
        }
    }
}

/** USB-serial adapter (FTDI, CH340, PL2303, CP210x) through usb-serial-for-android. */
internal class UsbObdLink private constructor(
    private val port: UsbSerialPort,
    override val description: String,
    /** `UsbDevice.getDeviceName()` of the adapter. */
    val deviceName: String,
) : ObdLink {
    override fun send(command: String) {
        port.write((command + "\r").toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            if (Thread.interrupted()) throw InterruptedException()
            val n = port.read(buf, 50)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                if (sb.contains(Elm327.PROMPT)) break
            }
        }
        return sb.toString()
    }

    override fun close() {
        try {
            port.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 500

        /**
         * [deviceName] = `UsbDevice.getDeviceName()` chosen in the settings, empty = the first adapter found that is not
         * in [skip] (adapters that did not answer as an ELM327). An adapter another app holds (the TPMS receiver of
         * LibreHU Launcher…) is left to it: usb-serial-for-android claims the interfaces by force, which takes them from
         * the other app's connection and makes both fail in a loop ("Error writing 5 bytes … rc=-1"). So each interface
         * is first claimed without force on our connection; no kernel driver binds these adapters on this ROM
         * (CONFIG_USB_SERIAL off), so a refusal means another app has it.
         */
        fun open(
            usb: UsbManager,
            deviceName: String,
            baud: Int,
            skip: Set<String> = emptySet(),
        ): UsbObdLink {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
            val candidates =
                if (deviceName.isNotEmpty()) {
                    drivers.filter { it.device.deviceName == deviceName }
                } else {
                    drivers.filter { it.device.deviceName !in skip }.ifEmpty { drivers }
                }
            if (candidates.isEmpty()) throw IOException("USB adapter not plugged in")
            var busy: String? = null
            for (driver in candidates) {
                val device = driver.device
                if (!usb.hasPermission(device)) throw UsbPermissionNeeded(device.deviceName)
                val connection = usb.openDevice(device) ?: continue
                val free = (0 until device.interfaceCount).all { connection.claimInterface(device.getInterface(it), false) }
                if (!free) {
                    connection.close()
                    busy = device.productName ?: device.deviceName
                    continue
                }
                try {
                    val port = driver.ports.first()
                    port.open(connection)
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    return UsbObdLink(port, "USB ${device.productName ?: device.deviceName} @ $baud", device.deviceName)
                } catch (e: IOException) {
                    connection.close()
                    throw e
                }
            }
            throw UsbAdapterBusy(busy ?: "USB adapter")
        }
    }
}

/** The adapter is used by another app (the TPMS of LibreHU Launcher, a terminal…): not taken from it. */
class UsbAdapterBusy(
    adapter: String,
) : IOException("$adapter used by another app (TPMS of the launcher?)")

class UsbPermissionNeeded(
    val deviceName: String,
) : IOException("USB permission needed")
