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
import org.librehu.core.obd.Ddt4allDb
import org.librehu.core.obd.DdtIdent
import org.librehu.core.obd.EcuAnswer
import org.librehu.core.obd.Elm327
import org.librehu.core.obd.ObdCarEcu
import org.librehu.core.obd.ObdCarReading
import org.librehu.core.obd.ObdCars
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
    /** Manufacturer values of the selected car ([ObdCarStore]), and the last problem reading them. */
    val car: Map<String, ObdCarReading> = emptyMap(),
    val carMessage: String = "",
)

/**
 * ELM327 OBD-II adapter over Bluetooth SPP or USB serial: initialises it, finds the supported PIDs, polls the chosen
 * values (fast ones every round, slow ones every [SLOW_EVERY] rounds) and reads / clears trouble codes on request.
 * Values go to [state], to the widget ([ObdWidget]) and to other apps ([LibreHu.ACTION_OBD], AIDL getObdValues).
 * With a car selected ([ObdCarStore]), its chosen manufacturer values are read too, every [CAR_EVERY] rounds, with
 * ddt4all's addressing (header, flow control and receive filter of the ECU), read requests only.
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

    /** ECUs to identify ([identify]): address → (send id, receive id). */
    @Volatile
    private var identifyTargets: Map<String, Pair<String, String>> = emptyMap()

    private val _idents = MutableStateFlow<Map<String, DdtIdent?>>(emptyMap())

    /** Identifications read by [identify]: address → identification, null = no answer (or refused outside a session). */
    val idents: StateFlow<Map<String, DdtIdent?>> = _idents.asStateFlow()

    private val _identifying = MutableStateFlow(false)
    val identifying: StateFlow<Boolean> = _identifying.asStateFlow()

    /** CAN protocol of the session (6..9), null while unknown or not CAN. */
    @Volatile
    private var sessionCan: Int? = null
    private val cars by lazy { ObdCarStore.get(app) }
    private val battery by lazy { BatteryWatch.get(app) }

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

    /**
     * Reads the identification of [targets] (address → send / receive CAN ids) as ddt4all's scanner does, with read
     * requests only (`2180`, else `22F1A0` / `F18A` / `F194` / `F195`): results in [idents]. Needs the adapter
     * connected on a CAN protocol.
     */
    fun identify(targets: Map<String, Pair<String, String>>) {
        if (_state.value.connection != ObdConnection.CONNECTED || targets.isEmpty()) return
        identifyTargets = targets
        _identifying.value = true
        requests.offer(REQ_IDENTIFY)
    }

    fun restart() {
        halt()
        if (_settings.value.enabled) launch()
    }

    /** Chosen manufacturer values: key → Bundle(name, ecu, text, unit, number when scaled, error). */
    fun carBundle(): Bundle =
        Bundle().apply {
            val st = _state.value
            if (st.connection != ObdConnection.CONNECTED) return@apply
            for (r in st.car.values) {
                putBundle(
                    r.key,
                    Bundle().apply {
                        putString("name", r.name)
                        putString("ecu", r.ecu)
                        putString("text", r.text)
                        putString("unit", r.unit)
                        r.number?.let { putDouble("number", it) }
                        putBoolean("error", r.error)
                    },
                )
            }
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
        _identifying.value = false
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
                battery.onDisconnected()
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
        // The car's own adapter commands (protocol…), before the protocol search.
        cars.current()?.elmInit?.forEach { link.ask(it, 1_500) }
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
        // 6 / 8 = CAN 11-bit (500 / 250 kbit/s), 7 / 9 = 29-bit; "A6" = found automatically.
        val can = protocol.removePrefix("A").toIntOrNull()?.takeIf { it in 6..9 }
        sessionCan = can
        setState {
            it.copy(
                connection = ObdConnection.CONNECTED,
                protocol = protocol,
                supported = supported,
                values = voltage?.let { v -> mapOf(BATTERY to v) }.orEmpty(),
            )
        }
        voltage?.let { battery.onVoltage(it, null) }
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
            if (round % SLOW_EVERY == 0) {
                Elm327.voltage(link.ask(Elm327.READ_VOLTAGE))?.let {
                    values[BATTERY] = it
                    battery.onVoltage(it, values[ObdPid.RPM.name])
                }
            }
            if (round % CAR_EVERY == 0) readCar(link, can)
            setState { it.copy(values = values, updated = System.currentTimeMillis()) }
            publish(values)
            round++
            if (wanted.isEmpty()) Thread.sleep(1_000)
        }
    }

    /** The chosen values of the selected car: each ECU addressed in turn, then the adapter back to standard OBD. */
    private fun readCar(
        link: ObdLink,
        can: Int?,
    ) {
        val car = cars.current()
        val wanted = cars.chosen.value
        if (car == null || wanted.isEmpty()) {
            if (_state.value.car.isNotEmpty() || _state.value.carMessage.isNotEmpty()) setState { it.copy(car = emptyMap(), carMessage = "") }
            return
        }
        if (can == null) {
            setState { it.copy(carMessage = "Not a CAN protocol: manufacturer values need the diagnostic CAN bus") }
            return
        }
        val out = HashMap<String, ObdCarReading>()
        var message = ""
        try {
            for (ecu in car.ecus) {
                val reqs = ecu.requests.filter { r -> r.items.any { ObdCars.key(ecu, it) in wanted } }
                if (reqs.isEmpty()) continue
                address(link, ecu)
                for (r in reqs) {
                    val answer = ObdCars.answer(link.ask(r.send, CAR_TIMEOUT_MS), r.service)
                    for (item in r.items) {
                        val key = ObdCars.key(ecu, item)
                        if (key !in wanted) continue
                        out[key] =
                            if (answer is EcuAnswer.Positive && answer.bytes.size >= r.minBytes) {
                                ObdCars.decode(key, ecu.name, item, answer.bytes)
                            } else {
                                ObdCarReading(key, ecu.name, item.name, "—", error = true)
                            }
                    }
                    when (answer) {
                        is EcuAnswer.Negative -> message = "${ecu.name} · ${r.name}: refused (NRC ${"%02X".format(answer.code)})"
                        is EcuAnswer.None -> message = "${ecu.name} · ${r.name}: ${answer.message}"
                        is EcuAnswer.Positive -> {}
                    }
                    if (Thread.interrupted()) throw InterruptedException()
                }
            }
        } finally {
            standardAddress(link, can)
        }
        setState { it.copy(car = out, carMessage = message) }
    }

    /** ddt4all's addressing of a CAN ECU (elm.py set_can_addr): header, flow control to the ECU, receive filter. */
    private fun address(
        link: ObdLink,
        ecu: ObdCarEcu,
    ) {
        if (ecu.extended) {
            link.ask("ATCP" + ecu.sendId.take(2))
            link.ask("ATSH" + ecu.sendId.drop(2))
        } else {
            link.ask("ATSH" + ecu.sendId)
        }
        link.ask("ATFCSH" + ecu.sendId)
        link.ask("ATFCSD300000")
        link.ask("ATFCSM1")
        link.ask("ATCRA" + ecu.recvId)
    }

    /** Back to the functional OBD address (7DF, or 18DB33F1 on 29-bit) and no filter. */
    private fun standardAddress(
        link: ObdLink,
        can: Int,
    ) {
        link.ask("ATCRA")
        link.ask("ATFCSM0")
        if (can == 7 || can == 9) {
            link.ask("ATCP18")
            link.ask("ATSHDB33F1")
        } else {
            link.ask("ATSH7DF")
        }
    }

    private fun identifyNow(link: ObdLink) {
        val can = sessionCan ?: return
        val out = HashMap<String, DdtIdent?>()
        try {
            for ((address, ids) in identifyTargets) {
                address(link, ObdCarEcu(address, ids.first, ids.second, emptyList()))
                fun read(send: String): IntArray? = (ObdCars.answer(link.ask(send, CAR_TIMEOUT_MS), send.take(2).toInt(16)) as? EcuAnswer.Positive)?.bytes
                out[address] =
                    read(Ddt4allDb.KWP_IDENT)?.let { Ddt4allDb.ident2180(it) }
                        ?: read(Ddt4allDb.UDS_IDENT[0])?.let { diag ->
                            Ddt4allDb.identUds(diag, read(Ddt4allDb.UDS_IDENT[1]), read(Ddt4allDb.UDS_IDENT[2]), read(Ddt4allDb.UDS_IDENT[3]))
                        }
                _idents.value = HashMap(out)
                if (Thread.interrupted()) throw InterruptedException()
            }
        } finally {
            standardAddress(link, can)
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

            REQ_IDENTIFY -> {
                try {
                    identifyNow(link)
                } finally {
                    _identifying.value = false
                }
            }
        }
    }

    /** At most once a second: broadcast for other apps, and the widget. */
    private fun publish(values: Map<String, Double>) {
        val now = System.currentTimeMillis()
        if (now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        val extras =
            Bundle().apply {
                values.forEach { (k, v) -> putDouble(k, v) }
                _state.value.car.values.forEach { r ->
                    r.number?.let { putDouble("car:" + r.key, it) }
                    putString("cartext:" + r.key, r.text)
                }
                battery.reading.value?.let { b ->
                    putInt(BATTERY_PERCENT, b.percent ?: -1)
                    putString(BATTERY_STATUS, b.status.name)
                }
            }
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
        private const val REQ_IDENTIFY = "identify"
        private const val RETRY_MS = 10_000L
        private const val PUBLISH_MS = 1_000L
        private const val SLOW_EVERY = 5
        private const val CAR_EVERY = 2
        private const val CAR_TIMEOUT_MS = 2_000L
        const val WIDGET_SLOTS = 4

        /** Adapter supply voltage (`ATRV`): car battery, available even engine off. */
        const val BATTERY = "BATTERY"

        /** Broadcast extras of [BatteryWatch]: charge (int, −1 engine running) and status name. */
        const val BATTERY_PERCENT = "BATTERY_PERCENT"
        const val BATTERY_STATUS = "BATTERY_STATUS"

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
