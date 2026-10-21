package org.librehu.service.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.bt.BtStatus

/** The iPhone as the LibreHU CarPlay app reads it (Lockdown and iAP2 over the wired link); null = not given. */
data class IphoneDetails(
    val connected: Boolean = false,
    /** When these values were received (System.currentTimeMillis). */
    val at: Long = 0,
    val name: String? = null,
    /** Apple model identifier (iPhone15,2…). */
    val model: String? = null,
    val os: String? = null,
    val build: String? = null,
    val bluetooth: String? = null,
    val level: Int? = null,
    val charging: Boolean? = null,
    val externalPower: Boolean? = null,
    val cycles: Int? = null,
    val temperatureC: Double? = null,
    val designMah: Int? = null,
    val maxMah: Int? = null,
    val voltageMv: Int? = null,
    val currentMa: Int? = null,
    /** True in wired CarPlay (USB), false in wireless CarPlay. */
    val wired: Boolean? = null,
) {
    /** Maximum capacity against the design one, as iOS shows "battery health" (approximation). */
    val healthPercent: Int?
        get() = if (designMah != null && maxMah != null && designMah > 0) (maxMah * 100 / designMah).coerceIn(0, 120) else null

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("at", at)
            name?.let { put("name", it) }
            model?.let { put("model", it) }
            os?.let { put("os", it) }
            build?.let { put("build", it) }
            bluetooth?.let { put("bt", it) }
            level?.let { put("level", it) }
            charging?.let { put("charging", it) }
            externalPower?.let { put("external_power", it) }
            cycles?.let { put("cycles", it) }
            temperatureC?.let { put("temperature", it) }
            designMah?.let { put("design_mah", it) }
            maxMah?.let { put("max_mah", it) }
            voltageMv?.let { put("voltage_mv", it) }
            currentMa?.let { put("current_ma", it) }
            wired?.let { put("wired", it) }
        }

    companion object {
        fun fromJson(o: JSONObject): IphoneDetails {
            fun int(k: String) = if (o.has(k)) o.optInt(k) else null

            fun bool(k: String) = if (o.has(k)) o.optBoolean(k) else null

            fun str(k: String) = o.optString(k).ifEmpty { null }
            return IphoneDetails(
                connected = false,
                at = o.optLong("at"),
                name = str("name"),
                model = str("model"),
                os = str("os"),
                build = str("build"),
                bluetooth = str("bt"),
                level = int("level"),
                charging = bool("charging"),
                externalPower = bool("external_power"),
                cycles = int("cycles"),
                temperatureC = if (o.has("temperature")) o.optDouble("temperature") else null,
                designMah = int("design_mah"),
                maxMah = int("max_mah"),
                voltageMv = int("voltage_mv"),
                currentMa = int("current_ma"),
                wired = bool("wired"),
            )
        }
    }
}

/**
 * Phone details for the Phone page and widget: the Bluetooth phone (hands-free indicators of [BtStatus]) and the
 * iPhone from the LibreHU CarPlay app (`org.librehu.carplay.PHONE_INFO`, sent to this package only), the last one kept
 * when it is unplugged. When an iPhone is plugged in, Bluetooth is turned on and, once the CarPlay app gave its
 * Bluetooth address, the head unit asks to pair with it if they are not paired yet ([autoPair]).
 *
 * During a wired CarPlay session the iPhone's calls and sound go through CarPlay: its hands-free and music Bluetooth
 * links to the head unit stay off meanwhile ([carPlayHold], used by the Bluetooth module), as head units with wired
 * CarPlay do. The wired identification does not give the iPhone the head unit's Bluetooth address, so it cannot
 * tell itself that both links are the same car.
 */
object PhoneInfo {
    private const val TAG = "LibreHU-Phone"
    private const val ACTION_PHONE_INFO = "org.librehu.carplay.PHONE_INFO"
    private const val ACTION_AA_BATTERY = "org.librehu.headunit.PHONE_BATTERY"
    private const val PREF_LAST_IPHONE = "last_iphone"
    private const val PREF_AUTO_PAIR = "iphone_auto_pair"

    private val _iphone = MutableStateFlow<IphoneDetails?>(null)
    val iphone: StateFlow<IphoneDetails?> = _iphone.asStateFlow()

    private val _bluetooth = MutableStateFlow(BtStatus())

    /** Bluetooth status of the hands-free phone, as the Bluetooth module last reported it. */
    val bluetooth: StateFlow<BtStatus> = _bluetooth.asStateFlow()

    /** Battery of the Android Auto phone (`org.librehu.headunit.PHONE_BATTERY` of the LibreHU Android Auto app), %. */
    private val _aaBattery = MutableStateFlow<Int?>(null)
    val androidAutoBattery: StateFlow<Int?> = _aaBattery.asStateFlow()

    private var aaReceiver: BroadcastReceiver? = null

    private var receiver: BroadcastReceiver? = null
    private var app: Context? = null

    /** Address asked for pairing in this plug, not asked again. */
    private var pairAsked: String? = null

    /** Bluetooth address of the iPhone in a wired CarPlay session, null otherwise. */
    @Volatile
    var carPlayHold: String? = null
        private set

    /** The Bluetooth module, told when [carPlayHold] changes. */
    @Volatile
    var carPlayHoldListener: ((String?) -> Unit)? = null

    private fun setCarPlayHold(address: String?) {
        if (address == carPlayHold) return
        carPlayHold = address
        Log.i(TAG, "Bluetooth of the iPhone ${if (address != null) "held during wired CarPlay: $address" else "released"}")
        carPlayHoldListener?.invoke(address)
    }

    private fun mac(address: String?): String? =
        address?.uppercase()?.replace('-', ':')?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }

    fun autoPair(context: Context): Boolean = LibreHuService.prefs(context).getBoolean(PREF_AUTO_PAIR, true)

    fun setAutoPair(
        context: Context,
        on: Boolean,
    ) = LibreHuService
        .prefs(context)
        .edit()
        .putBoolean(PREF_AUTO_PAIR, on)
        .apply()

    fun start(context: Context) {
        val a = context.applicationContext
        app = a
        if (_iphone.value == null) {
            LibreHuService.prefs(a).getString(PREF_LAST_IPHONE, null)?.let { j ->
                runCatching { IphoneDetails.fromJson(JSONObject(j)) }.getOrNull()?.let { _iphone.value = it }
            }
        }
        if (aaReceiver == null) {
            val aa =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        _aaBattery.value = intent.getIntExtra("level", -1).takeIf { it in 0..100 }
                        PhoneWidget.refresh(a)
                    }
                }
            aaReceiver = aa
            if (Build.VERSION.SDK_INT >= 33) {
                a.registerReceiver(aa, IntentFilter(ACTION_AA_BATTERY), Context.RECEIVER_EXPORTED)
            } else {
                a.registerReceiver(aa, IntentFilter(ACTION_AA_BATTERY))
            }
        }
        if (receiver != null) return
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) = onPhoneInfo(a, intent)
            }
        receiver = r
        if (Build.VERSION.SDK_INT >= 33) {
            a.registerReceiver(r, IntentFilter(ACTION_PHONE_INFO), Context.RECEIVER_EXPORTED)
        } else {
            a.registerReceiver(r, IntentFilter(ACTION_PHONE_INFO))
        }
    }

    /** From the Bluetooth module, on each status change. */
    fun onBluetooth(
        context: Context,
        status: BtStatus,
    ) {
        if (status == _bluetooth.value) return
        _bluetooth.value = status
        PhoneWidget.refresh(context)
    }

    private fun onPhoneInfo(
        context: Context,
        intent: Intent,
    ) {
        if (!intent.getBooleanExtra("connected", false)) {
            pairAsked = null
            setCarPlayHold(null)
            _iphone.value = _iphone.value?.copy(connected = false)
            PhoneWidget.refresh(context)
            return
        }

        fun int(k: String) = if (intent.hasExtra(k)) intent.getIntExtra(k, 0) else null

        fun bool(k: String) = if (intent.hasExtra(k)) intent.getBooleanExtra(k, false) else null
        val d =
            IphoneDetails(
                connected = true,
                at = System.currentTimeMillis(),
                name = intent.getStringExtra("name"),
                model = intent.getStringExtra("model"),
                os = intent.getStringExtra("os"),
                build = intent.getStringExtra("build"),
                bluetooth = intent.getStringExtra("bt"),
                level = int("level"),
                charging = bool("charging"),
                externalPower = bool("external_power"),
                cycles = int("cycles"),
                temperatureC = if (intent.hasExtra("temperature")) intent.getDoubleExtra("temperature", 0.0) else null,
                designMah = int("design_mah"),
                maxMah = int("max_mah"),
                voltageMv = int("voltage_mv"),
                currentMa = int("current_ma"),
                wired = bool("wired"),
            )
        _iphone.value = d
        setCarPlayHold(if (d.wired == true) mac(d.bluetooth) else null)
        LibreHuService
            .prefs(context)
            .edit()
            .putString(PREF_LAST_IPHONE, d.toJson().toString())
            .apply()
        PhoneWidget.refresh(context)
        d.bluetooth?.let { pairIfNeeded(context, it) }
    }

    /** An iPhone was plugged in (USB, Apple vendor id): Bluetooth on, to pair or reconnect it. */
    @SuppressLint("MissingPermission")
    fun onIphonePlugged(context: Context) {
        if (!autoPair(context)) return
        pairAsked = null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            Log.i(TAG, "iPhone plugged in: Bluetooth on")
            @Suppress("DEPRECATION")
            runCatching { adapter.enable() }
        }
    }

    /** An Apple USB device went away: no wired session any more (also when the CarPlay app died without saying). */
    fun onIphoneUnplugged() = setCarPlayHold(null)

    /** Pairing asked by the head unit (the iPhone shows its pairing prompt) when not paired yet. */
    @SuppressLint("MissingPermission")
    private fun pairIfNeeded(
        context: Context,
        address: String,
    ) {
        if (!autoPair(context)) return
        val mac = mac(address) ?: return
        if (pairAsked == mac) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return
        if (device.bondState != BluetoothDevice.BOND_NONE) return
        pairAsked = mac
        Log.i(TAG, "iPhone $mac not paired: asking")
        runCatching { device.createBond() }.onFailure { Log.w(TAG, "createBond: ${it.message}") }
    }
}
