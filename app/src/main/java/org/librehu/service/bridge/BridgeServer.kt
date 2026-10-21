package org.librehu.service.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.core.bridge.BridgeChannel
import org.librehu.core.bridge.BridgeCrypto
import org.librehu.core.bridge.BridgeException
import org.librehu.core.touch.TouchAction
import org.librehu.core.touch.ZoneAction
import org.librehu.service.LibreHu
import org.librehu.service.car.CarName
import org.librehu.service.obd.BatteryWatch
import org.librehu.service.obd.ObdManager
import org.librehu.service.touch.TouchKeys
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A phone paired with the head unit. */
data class BridgeDevice(
    val id: String,
    val name: String,
    val credential: String,
    val added: Long,
    val lastSeen: Long,
)

/** A Bluetooth phone asking to pair, waiting for the head unit's user. */
data class BridgeRequest(
    val id: String,
    val name: String,
)

/**
 * LibreHU bridge server: the phone app (github.com/LibreHU/LibreHU-bridge-app) changes the settings of the head unit
 * and of the LibreHU apps, and reads its live values (OBD, battery…) for itself and for Android Auto, over Wi-Fi (TCP
 * [PORT], when both are on the same network: the phone's hotspot or the head unit's) or Bluetooth (RFCOMM [UUID_RFCOMM]).
 * Every connection goes through [BridgeChannel] (encrypted, the head unit proves its identity): a phone pairs with the
 * QR code shown here (token valid [TOKEN_MS]) or, already paired in Bluetooth, by a confirmation on the head unit.
 *
 * Requests `{"id":n,"m":method,"p":{…}}` → `{"id":n,"r":…}` or `{"id":n,"e":"…"}`; events `{"ev":topic,"d":…}`.
 * Methods: `hello`, `pages`, `page` (id), `set` (page, item, value), `action` (page, item), `values`,
 * `subscribe` (topics: values), `key` (TouchAction name), `forget`.
 */
class BridgeServer private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val storage = app.createDeviceProtectedStorageContext()
    private val prefs = storage.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private val identity: KeyPair by lazy { loadIdentity() }

    private val _devices = MutableStateFlow(loadDevices())
    val devices: StateFlow<List<BridgeDevice>> = _devices.asStateFlow()

    private val _requests = MutableStateFlow<List<BridgeRequest>>(emptyList())
    val requests: StateFlow<List<BridgeRequest>> = _requests.asStateFlow()
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    private val _connected = MutableStateFlow<List<String>>(emptyList())

    /** Names of the phones connected now. */
    val connected: StateFlow<List<String>> = _connected.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var token: ByteArray? = null

    @Volatile
    private var tokenUntil = 0L

    private var tcp: ServerSocket? = null
    private var rfcomm: BluetoothServerSocket? = null

    @Volatile
    private var running = false

    val fingerprint: String get() = BridgeCrypto.fingerprint(identity.public)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        _enabled.value = on
        if (on) start() else stop()
    }

    fun start() {
        if (running || !_enabled.value) return
        running = true
        Thread(::tcpLoop, "bridge-tcp").apply { isDaemon = true }.start()
        Thread(::rfcommLoop, "bridge-bt").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { tcp?.close() }
        runCatching { rfcomm?.close() }
    }

    /**
     * A new one-time pairing token and the QR code's text: `librehu://pair?v=1&fp=…&t=…&n=…&ip=…&p=…&bt=…`
     * (unit's key fingerprint, token, name, IPv4 addresses, port, Bluetooth address).
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun pairingUri(): String {
        val t = BridgeCrypto.randomBytes(24)
        token = t
        tokenUntil = System.currentTimeMillis() + TOKEN_MS
        start()
        val ips =
            runCatching {
                NetworkInterface
                    .getNetworkInterfaces()
                    .toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
            }.getOrDefault(emptyList())
        val bt = runCatching { BluetoothAdapter.getDefaultAdapter()?.address }.getOrNull()?.takeIf { it != "02:00:00:00:00:00" }
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "librehu://pair?v=1&fp=$fingerprint&t=${BridgeCrypto.b64(t)}&n=${enc(unitName())}" +
            "&ip=${ips.joinToString(",")}&p=$PORT" + (bt?.let { "&bt=$it" } ?: "")
    }

    fun cancelPairing() {
        token = null
    }

    fun answer(
        id: String,
        allow: Boolean,
    ) {
        pending.remove(id)?.complete(allow)
        _requests.value = _requests.value.filter { it.id != id }
    }

    fun forget(id: String) {
        saveDevices(_devices.value.filter { it.id != id })
    }

    private fun unitName(): String = CarName.name(app).ifBlank { "LibreHU" }

    // --- Servers -------------------------------------------------------------------------------------------------

    private fun tcpLoop() {
        while (running) {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                tcp = server
                while (running) {
                    val s = server.accept()
                    s.soTimeout = IDLE_TIMEOUT_MS
                    s.tcpNoDelay = true
                    Thread({ serve(s.getInputStream(), s.getOutputStream(), "wifi", { s.close() }) }, "bridge-client").start()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "tcp: ${e.message}")
            } finally {
                runCatching { tcp?.close() }
            }
            if (running) Thread.sleep(5_000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun rfcommLoop() {
        while (running) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null || !adapter.isEnabled) {
                    Thread.sleep(10_000)
                    continue
                }
                val server = adapter.listenUsingRfcommWithServiceRecord("LibreHU bridge", UUID_RFCOMM)
                rfcomm = server
                while (running) {
                    val s = server.accept()
                    val name = runCatching { s.remoteDevice.name }.getOrNull() ?: s.remoteDevice.address
                    Thread({ serve(s.inputStream, s.outputStream, "bt:$name", { s.close() }) }, "bridge-client-bt").start()
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "rfcomm: ${e.message}")
            } finally {
                runCatching { rfcomm?.close() }
            }
            if (running) Thread.sleep(5_000)
        }
    }

    // --- One phone -----------------------------------------------------------------------------------------------

    private fun serve(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        link: String,
        close: () -> Unit,
    ) {
        var device: BridgeDevice? = null
        var events: Thread? = null
        try {
            val ch = BridgeChannel.accept(input, output, identity)
            device = authenticate(ch, link)
            _connected.value = _connected.value + device.name
            touch(device.id)
            while (running) {
                val req = ch.receive()
                val id = req["id"]
                val method = (req["m"] as? JsonPrimitive)?.contentOrNull ?: continue
                val p = req["p"] as? JsonObject ?: JsonObject(emptyMap())
                val reply =
                    try {
                        if (method == "subscribe" && events == null) {
                            events = Thread({ pushValues(ch) }, "bridge-events").apply { isDaemon = true }.also { it.start() }
                        }
                        if (method == "forget") {
                            forget(device.id)
                            ch.send(buildJsonObject { id?.let { put("id", it) }; put("r", true) })
                            break
                        }
                        buildJsonObject {
                            id?.let { put("id", it) }
                            put("r", handle(method, p))
                        }
                    } catch (e: Exception) {
                        buildJsonObject {
                            id?.let { put("id", it) }
                            put("e", e.message ?: e.javaClass.simpleName)
                        }
                    }
                ch.send(reply)
            }
        } catch (e: Exception) {
            Log.i(TAG, "$link: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            events?.interrupt()
            device?.let { d -> _connected.value = _connected.value - d.name }
            runCatching { close() }
        }
    }

    private fun authenticate(
        ch: BridgeChannel,
        link: String,
    ): BridgeDevice {
        val auth = ch.receive()
        val mode = (auth["mode"] as? JsonPrimitive)?.contentOrNull
        val name = (auth["name"] as? JsonPrimitive)?.contentOrNull?.take(64) ?: "Phone"
        fun deny(why: String): Nothing {
            runCatching { ch.send(buildJsonObject { put("ok", false); put("e", why) }) }
            throw BridgeException(why)
        }
        val proof = (auth["proof"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { BridgeCrypto.unb64(it) }.getOrNull() }
        when (mode) {
            "cred" -> {
                val id = (auth["id"] as? JsonPrimitive)?.contentOrNull
                val d = _devices.value.firstOrNull { it.id == id } ?: deny("unknown phone: pair it again")
                val expected = BridgeCrypto.hmac(BridgeCrypto.unb64(d.credential), ch.transcript)
                if (proof == null || !BridgeCrypto.same(proof, expected)) deny("bad credential")
                ch.send(buildJsonObject { put("ok", true); put("unit", unitName()); put("api", LibreHu.API_VERSION) })
                return d
            }
            "qr" -> {
                val t = token
                if (t == null || System.currentTimeMillis() > tokenUntil) deny("the QR code expired: show it again")
                if (proof == null || !BridgeCrypto.same(proof, BridgeCrypto.hmac(t, ch.transcript))) deny("bad QR code")
                token = null
                return issue(ch, name)
            }
            "bt" -> {
                if (!link.startsWith("bt:")) deny("Bluetooth pairing only over Bluetooth")
                val rid = BridgeCrypto.b64(BridgeCrypto.randomBytes(6))
                val f = CompletableFuture<Boolean>()
                pending[rid] = f
                _requests.value = _requests.value + BridgeRequest(rid, link.removePrefix("bt:"))
                BridgeNotify.ask(app, rid, link.removePrefix("bt:"))
                val ok = runCatching { f.get(CONFIRM_S, TimeUnit.SECONDS) }.getOrDefault(false)
                answer(rid, ok)
                if (!ok) deny("refused on the head unit")
                return issue(ch, name)
            }
            else -> deny("unknown mode")
        }
    }

    private fun issue(
        ch: BridgeChannel,
        name: String,
    ): BridgeDevice {
        val now = System.currentTimeMillis()
        val d = BridgeDevice(BridgeCrypto.b64(BridgeCrypto.randomBytes(9)), name, BridgeCrypto.b64(BridgeCrypto.randomBytes(32)), now, now)
        saveDevices(_devices.value + d)
        ch.send(
            buildJsonObject {
                put("ok", true)
                put("id", d.id)
                put("credential", d.credential)
                put("unit", unitName())
                put("api", LibreHu.API_VERSION)
            },
        )
        Log.i(TAG, "paired ${d.name}")
        return d
    }

    private fun pushValues(ch: BridgeChannel) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                ch.send(buildJsonObject { put("ev", "values"); put("d", values()) })
                Thread.sleep(1_000)
            }
        } catch (_: Exception) {
        }
    }

    // --- API -----------------------------------------------------------------------------------------------------

    private fun handle(
        method: String,
        p: JsonObject,
    ): JsonElement =
        when (method) {
            "hello" -> json(JSONObject().put("unit", unitName()).put("api", LibreHu.API_VERSION).put("fingerprint", fingerprint))
            "pages" -> json(JSONArray(allPages().map { (pkg, o) -> JSONObject().put("id", key(pkg, o.getString("id"))).put("title", o.optString("title")).put("icon", o.optString("icon")) }))
            "page" -> {
                val id = p.s("id")
                val (pkg, o) = allPages().firstOrNull { (pkg, o) -> key(pkg, o.getString("id")) == id } ?: throw BridgeException("no page $id")
                json(o.put("id", key(pkg, o.getString("id"))))
            }
            "set", "action" -> {
                val (pkg, pageId) = split(p.s("page"))
                val item = p.s("item")
                val value = p["value"].toAny()
                if (pkg == SELF) {
                    val page = BridgePages.builtIn(app).firstOrNull { it.id == pageId } ?: throw BridgeException("no page")
                    val it = page.items.firstOrNull { it.id == item } ?: throw BridgeException("no item")
                    it.set(value)
                    json(it.toJson())
                } else {
                    val b = Bundle().apply { putString("value", value?.toString()) }
                    val r = BridgePages.call(app, pkg, method, "$pageId/$item", b) ?: throw BridgeException("$pkg does not answer")
                    r.getString("json")?.let { json(JSONObject(it)) } ?: JsonPrimitive(true)
                }
            }
            "values" -> values()
            "subscribe" -> JsonPrimitive(true)
            "key" -> {
                val a = TouchAction.entries.firstOrNull { it.name == p.s("action") } ?: throw BridgeException("unknown key")
                android.os.Handler(android.os.Looper.getMainLooper()).post { TouchKeys.get(app).run(null, ZoneAction(a)) }
                JsonPrimitive(true)
            }
            else -> throw BridgeException("unknown method $method")
        }

    /** Live values: OBD, car battery, head unit name. */
    private fun values(): JsonObject {
        val obd = ObdManager.get(app).state.value
        val bat = BatteryWatch.get(app).reading.value
        return buildJsonObject {
            put("unit", unitName())
            put("obd", JsonObject(obd.values.mapValues { JsonPrimitive(it.value) }))
            put("car", JsonObject(obd.car.mapValues { JsonPrimitive(it.value.text) }))
            if (bat != null) {
                put(
                    "battery",
                    buildJsonObject {
                        put("voltage", bat.voltage)
                        bat.percent?.let { put("percent", it) }
                        put("status", bat.status.name)
                    },
                )
            }
        }
    }

    private fun allPages(): List<Pair<String, JSONObject>> =
        BridgePages.builtIn(app).map { pg ->
            SELF to JSONObject().put("id", pg.id).put("title", pg.title).put("icon", pg.icon).put("items", JSONArray(pg.items.map { it.toJson() }))
        } + BridgePages.external(app)

    private fun key(
        pkg: String,
        id: String,
    ) = "$pkg:$id"

    private fun split(k: String): Pair<String, String> = k.substringBefore(':') to k.substringAfter(':')

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.contentOrNull ?: throw BridgeException("missing $k")

    private fun JsonElement?.toAny(): Any? =
        when (this) {
            null, JsonNull -> null
            is JsonPrimitive -> if (isString) content else booleanOrNull ?: content.toIntOrNull() ?: doubleOrNull ?: content
            else -> toString()
        }

    /** org.json → kotlinx JSON (the pages are built with org.json, the channel speaks kotlinx). */
    private fun json(o: Any): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(o.toString())

    // --- Storage -------------------------------------------------------------------------------------------------

    private fun loadIdentity(): KeyPair {
        val f = File(storage.filesDir, "bridge_identity")
        runCatching {
            val parts = f.readText().split('\n')
            return KeyPair(BridgeCrypto.publicKey(BridgeCrypto.unb64(parts[0])), BridgeCrypto.privateKey(BridgeCrypto.unb64(parts[1])))
        }
        val k = BridgeCrypto.newKeyPair()
        f.writeText(BridgeCrypto.b64(k.public.encoded) + "\n" + BridgeCrypto.b64(k.private.encoded))
        return k
    }

    private fun loadDevices(): List<BridgeDevice> =
        runCatching {
            val a = JSONArray(prefs.getString("devices", "[]"))
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                BridgeDevice(o.getString("id"), o.getString("name"), o.getString("credential"), o.optLong("added"), o.optLong("seen"))
            }
        }.getOrDefault(emptyList())

    @Synchronized
    private fun saveDevices(list: List<BridgeDevice>) {
        val a = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("credential", it.credential).put("added", it.added).put("seen", it.lastSeen) })
        prefs.edit().putString("devices", a.toString()).apply()
        _devices.value = list
    }

    private fun touch(id: String) = saveDevices(_devices.value.map { if (it.id == id) it.copy(lastSeen = System.currentTimeMillis()) else it })

    companion object {
        private const val TAG = "LibreHU-Bridge"
        const val PORT = 47800
        val UUID_RFCOMM: UUID = UUID.fromString("6c1b2e6a-8f0e-4b8c-9a51-1ebd2d4c0b01")
        const val SELF = "org.librehu.service"
        private const val TOKEN_MS = 5 * 60_000L
        private const val CONFIRM_S = 60L
        private const val IDLE_TIMEOUT_MS = 0

        @Volatile
        private var instance: BridgeServer? = null

        fun get(context: Context): BridgeServer = instance ?: synchronized(this) { instance ?: BridgeServer(context).also { instance = it } }
    }
}
