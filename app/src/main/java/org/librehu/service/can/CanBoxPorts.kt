package org.librehu.service.can

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import org.librehu.service.car.PortBroker

/**
 * Where the CAN box's bytes come from: through the Jancar MCU (frames `10`, the default on this head unit), or
 * straight from the box on a USB-serial adapter, a Bluetooth serial (SPP) adapter, or a serial port of the SoC
 * (`/dev/ttyS…`, other head units and MCUs; root sets its speed). The car profile does not change: the same bytes,
 * the same framing, decoding and dialogue ([CanMonitor], [CanBoxTalk]); only the way to the box changes.
 */
object CanBoxPorts {
    private const val TAG = "LibreHU-CAN"
    private const val RETRY_MS = 5_000L
    private const val ACTION_USB = "org.librehu.service.CAN_USB_PERMISSION"

    enum class Kind { MCU, USB, BLUETOOTH, SERIAL }

    data class Settings(
        val kind: Kind = Kind.MCU,
        /** `UsbDevice.getDeviceName()` of the adapter (chosen: never the OBD adapter by mistake). */
        val usbDevice: String = "",
        val btAddress: String = "",
        val serialPath: String = "/dev/ttyS3",
    )

    /** State for the CAN tab: what is open and the last problem. */
    data class Status(
        val open: String? = null,
        val problem: String? = null,
    )

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private var app: Context? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var port: Port? = null

    /** True when the box is reached through the MCU: frames `10` feed [CanMonitor], the MCU link drives [CanBoxTalk]. */
    val viaMcu: Boolean get() = _settings.value.kind == Kind.MCU

    fun init(context: Context) {
        if (prefs != null) return
        app = context.applicationContext
        val p = context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("can_port", Context.MODE_PRIVATE)
        prefs = p
        _settings.value =
            Settings(
                kind = runCatching { Kind.valueOf(p.getString("kind", Kind.MCU.name)!!) }.getOrDefault(Kind.MCU),
                usbDevice = p.getString("usb", "").orEmpty(),
                btAddress = p.getString("bt", "").orEmpty(),
                serialPath = p.getString("serial", "/dev/ttyS3").orEmpty(),
            )
        restart()
    }

    fun set(s: Settings) {
        prefs?.edit()?.putString("kind", s.kind.name)?.putString("usb", s.usbDevice)?.putString("bt", s.btAddress)?.putString("serial", s.serialPath)?.apply()
        _settings.value = s
        restart()
    }

    /** USB-serial adapters plugged in: device name → product name. */
    fun usbAdapters(context: Context): Map<String, String> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return UsbSerialProber.getDefaultProber().findAllDrivers(usb).associate { it.device.deviceName to (it.device.productName ?: it.device.deviceName) }
    }

    /** Paired Bluetooth devices: address → name. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): Map<String, String> =
        runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().associate { it.address to (it.name ?: it.address) } }.getOrDefault(emptyMap())

    @Synchronized
    private fun restart() {
        worker?.interrupt()
        worker = null
        port?.close()
        port = null
        PortBroker.releaseAll(PortBroker.CANBOX)
        _status.value = Status()
        val s = _settings.value
        if (s.kind == Kind.MCU) return
        // The MCU link no longer talks to the box: this port does.
        CanBoxTalk.stop()
        worker = Thread({ loop(s) }, "can-port").apply {
            isDaemon = true
            start()
        }
    }

    /** Opens the port, feeds the box's bytes to [CanMonitor] and lets [CanBoxTalk] answer through it; again after a failure. */
    private fun loop(s: Settings) {
        val buf = ByteArray(512)
        while (!Thread.currentThread().isInterrupted) {
            val baud = CanMonitor.vehicle.baud ?: CanMonitor.vehicle.family.defaultBaud
            try {
                val p = open(s, baud)
                port = p
                _status.value = Status(open = p.description)
                Log.i(TAG, "CAN box on ${p.description}")
                // One write at a time on the port: the acknowledgements come from this thread, the profile's
                // messages from the box link's, the commands from the screens (CanBoxLink calls out of its lock).
                val writing = Any()
                CanBoxTalk.start(
                    { /* no MCU on this way */ },
                    { bytes -> synchronized(writing) { p.write(bytes) } },
                    baud = { b -> synchronized(writing) { p.setBaud(b) } },
                )
                while (!Thread.currentThread().isInterrupted) {
                    val n = p.read(buf)
                    if (n < 0) throw IOException("closed")
                    if (n > 0) CanMonitor.feed(buf.copyOf(n))
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w(TAG, "CAN box port ${s.kind}: ${e.message}")
                _status.value = Status(problem = e.message ?: e.javaClass.simpleName)
                CanBoxTalk.stop()
            } finally {
                port?.close()
                port = null
                PortBroker.releaseAll(PortBroker.CANBOX)
            }
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun open(
        s: Settings,
        baud: Int,
    ): Port {
        val c = app ?: throw IOException("not started")
        // One owner per port: the OBD adapter keeps its own, the box never takes it (and the other way round).
        val (key, label) =
            when (s.kind) {
                Kind.USB -> PortBroker.usb(s.usbDevice) to "USB ${s.usbDevice}"
                Kind.BLUETOOTH -> PortBroker.bluetooth(s.btAddress) to "Bluetooth ${s.btAddress}"
                Kind.SERIAL -> PortBroker.serial(s.serialPath) to s.serialPath
                Kind.MCU -> throw IOException("MCU")
            }
        PortBroker.claim(key, PortBroker.CANBOX, label)?.let { holder ->
            throw IOException("this port is used by ${PortBroker.what(holder)}: choose another one for the CAN box")
        }
        return when (s.kind) {
            Kind.USB -> UsbPort.open(c, s.usbDevice, baud)
            Kind.BLUETOOTH -> BtPort.open(s.btAddress)
            Kind.SERIAL -> SerialPort.open(s.serialPath, baud)
            Kind.MCU -> throw IOException("MCU")
        }
    }

    /** One way to the box. */
    interface Port : Closeable {
        val description: String

        /** Bytes read (0: none for now, -1: closed); waits a little at most. */
        fun read(buf: ByteArray): Int

        fun write(bytes: ByteArray)

        /** The box's speed changed (another profile); a Bluetooth adapter keeps its own. */
        fun setBaud(baud: Int) {}
    }

    private class UsbPort(
        private val p: UsbSerialPort,
        override val description: String,
    ) : Port {
        override fun read(buf: ByteArray): Int = p.read(buf, 200)

        override fun write(bytes: ByteArray) = p.write(bytes, 500)

        override fun setBaud(baud: Int) = p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

        override fun close() {
            runCatching { p.close() }
        }

        companion object {
            fun open(
                context: Context,
                deviceName: String,
                baud: Int,
            ): UsbPort {
                if (deviceName.isEmpty()) throw IOException("choose the USB adapter of the CAN box")
                val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val driver =
                    UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull { it.device.deviceName == deviceName }
                        ?: throw IOException("USB adapter not plugged in")
                if (!usb.hasPermission(driver.device)) {
                    val flags = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    usb.requestPermission(driver.device, PendingIntent.getBroadcast(context, 7, Intent(ACTION_USB).setPackage(context.packageName), flags))
                    throw IOException("USB permission asked: allow it on the screen")
                }
                val connection = usb.openDevice(driver.device) ?: throw IOException("USB adapter cannot be opened")
                // Never taken by force from another app (the TPMS receiver of LibreHU Launcher…).
                if (!PortBroker.claimUsbInterfaces(driver.device, connection)) {
                    connection.close()
                    throw IOException("USB adapter ${driver.device.productName ?: deviceName} used by another app (TPMS of the launcher?)")
                }
                val port = driver.ports.first()
                try {
                    port.open(connection)
                    port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                } catch (e: IOException) {
                    connection.close()
                    throw e
                }
                return UsbPort(port, "USB ${driver.device.productName ?: deviceName} @ $baud")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private class BtPort(
        private val socket: BluetoothSocket,
        override val description: String,
    ) : Port {
        private val input: InputStream = socket.inputStream
        private val output: OutputStream = socket.outputStream

        override fun read(buf: ByteArray): Int {
            if (input.available() <= 0) {
                Thread.sleep(20)
                return 0
            }
            return input.read(buf)
        }

        override fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        override fun close() {
            runCatching { socket.close() }
        }

        companion object {
            private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

            fun open(address: String): BtPort {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("no Bluetooth")
                if (!BluetoothAdapter.checkBluetoothAddress(address)) throw IOException("choose the Bluetooth adapter of the CAN box")
                val device = adapter.getRemoteDevice(address)
                adapter.cancelDiscovery()
                val tries =
                    listOf<() -> BluetoothSocket>(
                        { device.createRfcommSocketToServiceRecord(SPP) },
                        { device.createInsecureRfcommSocketToServiceRecord(SPP) },
                    )
                var last: Exception? = null
                for (make in tries) {
                    try {
                        val s = make()
                        s.connect()
                        return BtPort(s, "Bluetooth ${device.name ?: address}")
                    } catch (e: Exception) {
                        last = e
                    }
                }
                throw IOException("Bluetooth ${device.name ?: address}: ${last?.message}")
            }
        }
    }

    /** A serial port of the SoC: root sets its speed (stty) and lets the service open it. */
    private class SerialPort(
        private val path: String,
        private val input: FileInputStream,
        private val output: FileOutputStream,
        override val description: String,
    ) : Port {
        override fun read(buf: ByteArray): Int {
            if (input.available() <= 0) {
                Thread.sleep(20)
                return 0
            }
            return input.read(buf)
        }

        override fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        override fun setBaud(baud: Int) {
            configure(path, baud)
        }

        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
        }

        companion object {
            private fun configure(
                path: String,
                baud: Int,
            ) {
                if (!Regex("/dev/tty[A-Za-z0-9]{1,12}").matches(path)) throw IOException("not a serial port: $path")
                val r = org.librehu.service.root.RootShell.run("chmod 666 $path && stty -F $path $baud raw -echo cs8 -cstopb -parenb")
                if (r == null) throw IOException("root needed to open $path")
            }

            fun open(
                path: String,
                baud: Int,
            ): SerialPort {
                if (!File(path).exists()) throw IOException("$path does not exist")
                configure(path, baud)
                return SerialPort(path, FileInputStream(path), FileOutputStream(path), "$path @ $baud")
            }
        }
    }
}
