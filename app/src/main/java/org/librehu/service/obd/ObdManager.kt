package org.librehu.service.obd

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.obd.Elm327
import org.librehu.core.obd.ObdPid
import org.librehu.service.LibreHu
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

enum class ObdConnection { OFF, CONNECTING, INITIALISING, CONNECTED, ERROR }

enum class ObdTransport { BLUETOOTH, USB }

data class ObdSettings(
    val enabled: Boolean = false,
    val transport: ObdTransport = ObdTransport.BLUETOOTH,
    val bluetoothAddress: String = "",
    /** `UsbDevice.getDeviceName()`, empty = first USB-serial adapter. */
    val usbDevice: String = "",
    val usbBaud: Int = 38400,
    val pids: Set<ObdPid> = DEFAULT_PIDS,
    /** Four values shown by the widget: [ObdPid] names or [ObdManager.BATTERY]. */
    val widget: List<String> = listOf(ObdPid.SPEED.name, ObdPid.RPM.name, ObdPid.COOLANT_TEMP.name, ObdManager.BATTERY),
) {
    companion object {
        val DEFAULT_PIDS =
            setOf(
                ObdPid.RPM,
                ObdPid.SPEED,
                ObdPid.COOLANT_TEMP,
                ObdPid.INTAKE_TEMP,
                ObdPid.ENGINE_LOAD,
                ObdPid.THROTTLE,
                ObdPid.FUEL_LEVEL,
                ObdPid.MODULE_VOLTAGE,
            )
    }
}

data class ObdState(
    val connection: ObdConnection = ObdConnection.OFF,
    val message: String = "",
    val adapter: String = "",
    /** OBD protocol number reported by `ATDPN` (6 = ISO 15765 CAN 500k…). */
    val protocol: String = "",
    val values: Map<String, Double> = emptyMap(),
    /** PIDs the car answers to (from 0100/0120/0140), empty while unknown. */
    val supported: Set<Int> = emptySet(),
    val dtcs: List<String>? = null,
    val updated: Long = 0,
)

/**
 * ELM327 OBD-II adapter over Bluetooth SPP or USB serial: initialises it, finds the supported PIDs, polls the chosen
 * values (fast ones every round, slow ones every [SLOW_EVERY] rounds) and reads / clears trouble codes on request.
 * Values go to [state], to the widget ([ObdWidget]) and to other apps ([LibreHu.ACTION_OBD], AIDL getObdValues).
 */
class ObdManager private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val usb = app.getSystemService(UsbManager::class.java)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ObdSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state.asStateFlow()

    private val requests = LinkedBlockingQueue<String>()

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private var lastPublish = 0L

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) restart()
            }
        }

    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            app.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(usbReceiver, filter)
        }
        if (_settings.value.enabled) launch()
    }

    fun stop() {
        halt()
        try {
            app.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    fun update(transform: (ObdSettings) -> ObdSettings) {
        val old = _settings.value
        val s = transform(old)
        prefs
            .edit()
            .putBoolean("enabled", s.enabled)
            .putString("transport", s.transport.name)
            .putString("bt", s.bluetoothAddress)
            .putString("usb", s.usbDevice)
            .putInt("baud", s.usbBaud)
            .putString("pids", s.pids.joinToString(",") { it.name })
            .putString("widget", s.widget.joinToString(","))
            .apply()
        _settings.value = s
        val linkChanged =
            old.enabled != s.enabled || old.transport != s.transport || old.bluetoothAddress != s.bluetoothAddress ||
                old.usbDevice != s.usbDevice || old.usbBaud != s.usbBaud
        if (linkChanged) restart()
        if (old.widget != s.widget) ObdWidget.refresh(app)
    }

    /** USB-serial adapters plugged in: device name → description. */
    fun usbAdapters(): List<Pair<String, String>> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usb).map { d ->
            val dev = d.device
            dev.deviceName to
                "%s (%04X:%04X)".format(dev.productName ?: d.javaClass.simpleName.removeSuffix("SerialDriver"), dev.vendorId, dev.productId)
        }

    fun readDtcs() = requests.offer(REQ_READ_DTC)

    fun clearDtcs() = requests.offer(REQ_CLEAR_DTC)

    fun restart() {
        halt()
        if (_settings.value.enabled) launch()
    }

    fun valuesBundle(): Bundle =
        Bundle().apply {
            val st = _state.value
            if (st.connection == ObdConnection.CONNECTED) st.values.forEach { (k, v) -> putDouble(k, v) }
        }

    // --- Session -------------------------------------------------------------------------------------------------

    private fun launch() {
        running = true
        thread = Thread(::loop, "obd").apply { isDaemon = true }.also { it.start() }
    }

    private fun halt() {
        running = false
        thread?.interrupt()
        thread?.join(2000)
        thread = null
        setState { ObdState() }
        ObdWidget.refresh(app)
    }

    private fun loop() {
        while (running) {
            var link: ObdLink? = null
            try {
                setState { it.copy(connection = ObdConnection.CONNECTING, message = "", values = emptyMap()) }
                link = open()
                setState { it.copy(connection = ObdConnection.INITIALISING, adapter = link.description) }
                session(link)
            } catch (e: UsbPermissionNeeded) {
                askUsbPermission(e.deviceName)
                setState { it.copy(connection = ObdConnection.ERROR, message = "USB permission") }
                return
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "OBD: ${e.message}")
                setState { it.copy(connection = ObdConnection.ERROR, message = e.message ?: e.javaClass.simpleName) }
            } finally {
                link?.close()
                ObdWidget.refresh(app)
            }
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun open(): ObdLink {
        val s = _settings.value
        return when (s.transport) {
            ObdTransport.BLUETOOTH -> BluetoothObdLink.open(s.bluetoothAddress)
            ObdTransport.USB -> UsbObdLink.open(usb, s.usbDevice, s.usbBaud)
        }
    }

    private fun session(link: ObdLink) {
        for (cmd in Elm327.INIT) {
            val answer = link.ask(cmd, if (cmd == "ATZ") 3_000 else 1_500)
            if (cmd == "ATZ" && !answer.contains("ELM", ignoreCase = true) &&
                answer.isBlank()
            ) {
                throw IOException("No answer from the adapter")
            }
        }
        val voltage = Elm327.voltage(link.ask(Elm327.READ_VOLTAGE))
        // The first request makes the adapter search the car's protocol: give it time.
        val supported = mutableSetOf<Int>()
        for (base in Elm327.SUPPORT_QUERIES) {
            val raw = link.ask("01%02X".format(base), if (base == 0) 15_000 else 3_000)
            if (base == 0 &&
                Elm327.isError(raw)
            ) {
                throw IOException("Car not answering (${Elm327.lines(raw).firstOrNull() ?: "silence"}): ignition on?")
            }
            supported += Elm327.supported(base, raw)
            if ((base + 0x20) !in supported) break
        }
        val protocol = Elm327.lines(link.ask(Elm327.DESCRIBE_PROTOCOL)).firstOrNull().orEmpty()
        setState {
            it.copy(
                connection = ObdConnection.CONNECTED,
                protocol = protocol,
                supported = supported,
                values = voltage?.let { v -> mapOf(BATTERY to v) }.orEmpty(),
            )
        }
        var round = 0
        while (running) {
            requests.poll()?.let { handleRequest(link, it) }
            val wanted = _settings.value.pids.filter { supported.isEmpty() || it.pid in supported }
            val values = HashMap(_state.value.values)
            for (pid in wanted) {
                if (!pid.fast && round % SLOW_EVERY != 0) continue
                val raw = link.ask(pid.command)
                Elm327.decode(raw, pid)?.let { values[pid.name] = it }
                if (Thread.interrupted()) throw InterruptedException()
            }
            if (round % SLOW_EVERY == 0) Elm327.voltage(link.ask(Elm327.READ_VOLTAGE))?.let { values[BATTERY] = it }
            setState { it.copy(values = values, updated = System.currentTimeMillis()) }
            publish(values)
            round++
            if (wanted.isEmpty()) Thread.sleep(1_000)
        }
    }

    private fun handleRequest(
        link: ObdLink,
        request: String,
    ) {
        when (request) {
            REQ_READ_DTC -> {
                val codes = Elm327.dtcs(link.ask(Elm327.READ_DTC, 5_000)).map { it.code }
                setState { it.copy(dtcs = codes) }
            }

            REQ_CLEAR_DTC -> {
                link.ask(Elm327.CLEAR_DTC, 5_000)
                setState { it.copy(dtcs = emptyList()) }
            }
        }
    }

    /** At most once a second: broadcast for other apps, and the widget. */
    private fun publish(values: Map<String, Double>) {
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        val extras = Bundle().apply { values.forEach { (k, v) -> putDouble(k, v) } }
        app.sendBroadcast(Intent(LibreHu.ACTION_OBD).putExtras(extras))
        ObdWidget.refresh(app)
    }

    private fun askUsbPermission(deviceName: String) {
        val device = usb.deviceList[deviceName] ?: return
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_USB_PERMISSION).setPackage(app.packageName), flags)
        usb.requestPermission(device, pi)
    }

    private inline fun setState(transform: (ObdState) -> ObdState) {
        _state.value = transform(_state.value)
    }

    private fun load(): ObdSettings {
        val d = ObdSettings()
        return ObdSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            transport = runCatching { ObdTransport.valueOf(prefs.getString("transport", null)!!) }.getOrDefault(d.transport),
            bluetoothAddress = prefs.getString("bt", d.bluetoothAddress) ?: "",
            usbDevice = prefs.getString("usb", d.usbDevice) ?: "",
            usbBaud = prefs.getInt("baud", d.usbBaud),
            pids =
                prefs
                    .getString("pids", null)
                    ?.split(',')
                    ?.mapNotNull { ObdPid.byName(it) }
                    ?.toSet() ?: d.pids,
            widget =
                prefs
                    .getString("widget", null)
                    ?.split(',')
                    ?.filter { it == BATTERY || ObdPid.byName(it) != null }
                    ?.takeIf { it.size == WIDGET_SLOTS } ?: d.widget,
        )
    }

    companion object {
        private const val TAG = "LibreHU-OBD"
        private const val ACTION_USB_PERMISSION = "org.librehu.service.OBD_USB_PERMISSION"
        private const val REQ_READ_DTC = "dtc"
        private const val REQ_CLEAR_DTC = "clear"
        private const val RETRY_MS = 10_000L
        private const val PUBLISH_MS = 1_000L
        private const val SLOW_EVERY = 5
        const val WIDGET_SLOTS = 4

        /** Adapter supply voltage (`ATRV`): car battery, available even engine off. */
        const val BATTERY = "BATTERY"

        val BAUD_RATES = listOf(9600, 38400, 115200, 230400, 500000)

        @Volatile
        private var instance: ObdManager? = null

        fun get(context: Context): ObdManager = instance ?: synchronized(this) { instance ?: ObdManager(context).also { instance = it } }

        /** Unit of a value key. */
        fun unit(key: String): String = if (key == BATTERY) "V" else ObdPid.byName(key)?.unit.orEmpty()

        fun format(
            key: String,
            value: Double,
        ): String = ObdPid.byName(key)?.let { Elm327.format(it, value) } ?: "%.1f V".format(value)
    }
}
