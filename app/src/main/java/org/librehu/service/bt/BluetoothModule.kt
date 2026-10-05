package org.librehu.service.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import org.librehu.core.bt.AutoConnectPlan
import org.librehu.core.bt.Call
import org.librehu.core.bt.CallState
import org.librehu.core.bt.Calls
import org.librehu.core.bt.Mru
import org.librehu.core.bt.PhoneNumbers
import org.librehu.core.bt.PhonePhase

/**
 * Bluetooth of the head unit, the LibreHU counterpart of Jancar's `ivi-btservice` (`com.jancar.btservice`): it drives
 * Android's car profiles (hands-free client, A2DP sink + AVRCP controller, PBAP client) and exposes them through
 * [ILibreHuBluetooth]. All state lives on the main thread; binder calls are posted to it.
 *
 * Passive while Jancar's btservice is enabled (status, calls and commands work, but no auto-connect, ringtone or
 * pairing answers, which btservice already does): see [BtStatus.activeMode].
 */
@SuppressLint("MissingPermission")
class BluetoothModule(
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val callbacks = RemoteCallbackList<ILibreHuBluetoothCallback>()

    private var profiles: CarProfiles? = null
    private val media = BtMedia(context, main) { info -> each { it.onMediaChanged(info) } }
    private val phonebook = BtPhonebook(context, main) { each { it.onPhonebookChanged() } }
    private val ringer = BtRinger(context)

    private val plan = AutoConnectPlan()
    private var attempt = 0
    private var started = false

    /** Phones disconnected on purpose: not reconnected automatically until connected again by hand. */
    private val userDisconnected = mutableSetOf<String>()

    /** Pairing requests waiting for [ILibreHuBluetooth.confirmPairing]: address → variant. */
    private val pendingPairing = HashMap<String, Int>()

    private val found = LinkedHashMap<String, BtDeviceInfo>()
    private var rawCalls: List<Pair<Call, Any>> = emptyList()
    private val activeSince = HashMap<Int, Long>()
    private var autoAnswered = mutableSetOf<Int>()

    private var battery = -1
    private var signal = -1
    private var operator = ""
    private var roaming = false
    private var voiceAssistant = false

    @Volatile
    private var status = BtStatus()

    @Volatile
    private var callInfos: List<BtCallInfo> = emptyList()

    // --- Lifecycle -----------------------------------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        HiddenApi.exempt()
        val a = adapter
        if (a == null) {
            Log.w(TAG, "No Bluetooth adapter")
            return
        }
        register()
        profiles = CarProfiles(context, a) { main.post(::onProfilesChanged) }
        media.start()
        phonebook.start()
        refreshStatus()
        restartAutoConnect()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        media.stop()
        phonebook.stop()
        ringer.release()
        profiles?.close()
        profiles = null
        callbacks.kill()
    }

    private fun register() {
        val filter =
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                addAction(CarProfiles.ACTION_HFP_CONNECTION)
                addAction(CarProfiles.ACTION_HFP_AUDIO)
                addAction(CarProfiles.ACTION_AG_EVENT)
                addAction(CarProfiles.ACTION_CALL_CHANGED)
                addAction(CarProfiles.ACTION_A2DP_SINK_CONNECTION)
                addAction(CarProfiles.ACTION_PBAP_CONNECTION)
                // Answer pairing requests before the Settings dialog does.
                priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
            }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    // --- Events --------------------------------------------------------------------------------------------------

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON) restartAutoConnect()
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        found.clear()
                    }

                    BluetoothDevice.ACTION_FOUND -> {
                        if (device != null) onFound(device, intent)
                    }

                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        if (device != null) onBondChanged(device, intent)
                    }

                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        if (device != null) onPairingRequest(device, intent)
                    }

                    BluetoothDevice.ACTION_NAME_CHANGED -> {
                        each { it.onDevicesChanged() }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (device != null && device.address == status.deviceAddress) restartAutoConnect()
                    }

                    CarProfiles.ACTION_HFP_CONNECTION -> {
                        if (device != null) onHfpConnection(device, intent)
                    }

                    CarProfiles.ACTION_CALL_CHANGED -> {
                        refreshCalls()
                    }

                    CarProfiles.ACTION_AG_EVENT -> {
                        onAgEvent(intent)
                    }

                    CarProfiles.ACTION_A2DP_SINK_CONNECTION -> {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0)
                        media.setA2dpConnected(state == BluetoothProfile.STATE_CONNECTED)
                        each { it.onDevicesChanged() }
                    }

                    CarProfiles.ACTION_PBAP_CONNECTION, CarProfiles.ACTION_HFP_AUDIO -> {
                        Unit
                    }
                }
                refreshStatus()
            }
        }

    private fun onProfilesChanged() {
        val p = profiles ?: return
        // Already connected when the service starts: catch up.
        p.connected(p.a2dpSink).firstOrNull()?.let { media.setA2dpConnected(true) }
        p.connected(p.hfp).firstOrNull()?.let { readAgEvents(it) }
        refreshCalls()
        refreshStatus()
        restartAutoConnect()
    }

    private fun onHfpConnection(
        device: BluetoothDevice,
        intent: Intent,
    ) {
        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0)) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "Hands-free connected: ${device.address}")
                attempt = 0
                main.removeCallbacks(autoConnect)
                userDisconnected.remove(device.address)
                history = Mru.push(history, device.address)
                profiles?.allowPhonebook(device)
                readAgEvents(device)
                if (activeMode()) {
                    // Music and phone book follow the hands-free link (PBAP is refused before HFP on this ROM).
                    main.postDelayed({ connectSecondaryProfiles(device) }, SECONDARY_DELAY_MS)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.i(TAG, "Hands-free disconnected: ${device.address}")
                battery = -1
                signal = -1
                operator = ""
                voiceAssistant = false
                refreshCalls()
                restartAutoConnect()
            }
        }
        each { it.onDevicesChanged() }
    }

    private fun onAgEvent(intent: Intent) {
        if (intent.hasExtra(CarProfiles.EXTRA_BATTERY_LEVEL)) battery = intent.getIntExtra(CarProfiles.EXTRA_BATTERY_LEVEL, -1)
        if (intent.hasExtra(CarProfiles.EXTRA_NETWORK_SIGNAL_STRENGTH)) {
            signal = intent.getIntExtra(CarProfiles.EXTRA_NETWORK_SIGNAL_STRENGTH, -1)
        }
        if (intent.hasExtra(CarProfiles.EXTRA_NETWORK_ROAMING)) roaming = intent.getIntExtra(CarProfiles.EXTRA_NETWORK_ROAMING, 0) == 1
        if (intent.hasExtra(CarProfiles.EXTRA_OPERATOR_NAME)) operator = intent.getStringExtra(CarProfiles.EXTRA_OPERATOR_NAME).orEmpty()
        if (intent.hasExtra(CarProfiles.EXTRA_VOICE_RECOGNITION)) {
            voiceAssistant = intent.getIntExtra(CarProfiles.EXTRA_VOICE_RECOGNITION, 0) == 1
        }
    }

    private fun readAgEvents(device: BluetoothDevice) {
        val b = profiles?.agEvents(device) ?: return
        battery = b.getInt(CarProfiles.EXTRA_BATTERY_LEVEL, battery)
        signal = b.getInt(CarProfiles.EXTRA_NETWORK_SIGNAL_STRENGTH, signal)
        roaming = b.getInt(CarProfiles.EXTRA_NETWORK_ROAMING, if (roaming) 1 else 0) == 1
        operator = b.getString(CarProfiles.EXTRA_OPERATOR_NAME) ?: operator
    }

    private fun onFound(
        device: BluetoothDevice,
        intent: Intent,
    ) {
        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name.orEmpty()
        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
        val info = info(device).copy(name = name, rssi = rssi)
        found[device.address] = info
        each { it.onDeviceFound(info) }
    }

    private fun onBondChanged(
        device: BluetoothDevice,
        intent: Intent,
    ) {
        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
            BluetoothDevice.BOND_BONDED -> {
                pendingPairing.remove(device.address)
                profiles?.allowPhonebook(device)
                if (activeMode()) connectProfiles(device)
            }

            BluetoothDevice.BOND_NONE -> {
                pendingPairing.remove(device.address)
                history = Mru.remove(history, device.address)
            }
        }
        each { it.onDevicesChanged() }
    }

    private fun onPairingRequest(
        device: BluetoothDevice,
        intent: Intent,
    ) {
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
        val passkey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
        Log.i(TAG, "Pairing request ${device.address} variant $variant")
        if (activeMode()) {
            when (variant) {
                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                    device.setPin(pin().toByteArray())
                    return
                }

                PAIRING_VARIANT_PASSKEY_CONFIRMATION, PAIRING_VARIANT_CONSENT -> {
                    // Like stock head units: the phone shows the code, accepting there is enough.
                    if (confirm(device, true)) return
                }
            }
        }
        pendingPairing[device.address] = variant
        val name = device.name.orEmpty()
        each { it.onPairingRequest(device.address, name, variant, passkey) }
    }

    private fun confirm(
        device: BluetoothDevice,
        accept: Boolean,
    ): Boolean =
        try {
            device.setPairingConfirmation(accept)
        } catch (e: SecurityException) {
            Log.w(TAG, "setPairingConfirmation needs BLUETOOTH_PRIVILEGED: ${e.message}")
            false
        }

    // --- Calls ---------------------------------------------------------------------------------------------------

    private fun hfpDevice(): BluetoothDevice? = profiles?.let { it.connected(it.hfp).firstOrNull() }

    private fun refreshCalls() {
        val p = profiles
        val device = hfpDevice()
        rawCalls = if (p != null && device != null) p.calls(device) else emptyList()
        val calls = rawCalls.map { it.first }
        val now = System.currentTimeMillis()
        val ids = calls.map { it.id }.toSet()
        activeSince.keys.retainAll(ids)
        autoAnswered.retainAll(ids)
        for (c in calls) if (c.state == CallState.ACTIVE && c.id !in activeSince) activeSince[c.id] = now
        val infos =
            Calls.live(calls).map {
                BtCallInfo(
                    it.id,
                    it.state.hfp,
                    it.number,
                    phonebook.lookupName(it.number),
                    it.multiParty,
                    it.outgoing,
                    activeSince[it.id] ?: 0,
                )
            }
        val active = activeMode()
        ringer.update(if (active) Calls.phase(calls) else PhonePhase.IDLE, active && Calls.shouldRing(calls))
        if (active && autoAnswer() && Calls.phase(calls) == PhonePhase.RINGING) {
            Calls.primary(calls)?.takeIf { it.id !in autoAnswered }?.let { call ->
                autoAnswered += call.id
                main.postDelayed(
                    { if (rawCalls.any { it.first.id == call.id && it.first.state == CallState.INCOMING }) answerNow() },
                    AUTO_ANSWER_MS,
                )
            }
        }
        if (infos != callInfos) {
            callInfos = infos
            each { it.onCallsChanged(infos) }
        }
    }

    private fun answerNow() {
        val d = hfpDevice() ?: return
        val mode = Calls.answerMode(rawCalls.map { it.first }) ?: return
        profiles?.accept(d, mode.hfpFlag)
    }

    // --- Connections ---------------------------------------------------------------------------------------------

    private fun connectProfiles(device: BluetoothDevice) {
        val p = profiles ?: return
        // One phone at a time for calls (persist.bluetooth.maxhfpdev = 1): let the other one go.
        for (other in p.connected(p.hfp) + p.connected(p.a2dpSink)) {
            if (other.address != device.address) disconnectProfiles(other, byUser = false)
        }
        p.setPriority(p.hfp, device, CarProfiles.PRIORITY_AUTO_CONNECT)
        p.setPriority(p.a2dpSink, device, CarProfiles.PRIORITY_AUTO_CONNECT)
        if (p.state(p.hfp, device) == BluetoothProfile.STATE_DISCONNECTED) p.connect(p.hfp, device)
        if (p.state(p.a2dpSink, device) == BluetoothProfile.STATE_DISCONNECTED) p.connect(p.a2dpSink, device)
    }

    private fun connectSecondaryProfiles(device: BluetoothDevice) {
        val p = profiles ?: return
        if (p.state(p.hfp, device) != BluetoothProfile.STATE_CONNECTED) return
        if (p.state(p.a2dpSink, device) == BluetoothProfile.STATE_DISCONNECTED) p.connect(p.a2dpSink, device)
        if (p.state(p.pbap, device) == BluetoothProfile.STATE_DISCONNECTED) p.connect(p.pbap, device)
    }

    private fun disconnectProfiles(
        device: BluetoothDevice,
        byUser: Boolean,
    ) {
        val p = profiles ?: return
        if (byUser) userDisconnected += device.address
        p.disconnect(p.pbap, device)
        p.disconnect(p.a2dpSink, device)
        p.disconnect(p.hfp, device)
    }

    private val autoConnect =
        Runnable {
            val a = adapter ?: return@Runnable
            if (!canAutoConnect() || isConnected()) return@Runnable
            val bonded =
                a.bondedDevices
                    .orEmpty()
                    .map { it.address }
                    .toSet()
            val target = plan.target(attempt, history.filter { it !in userDisconnected }, bonded)
            attempt++
            if (target != null) {
                Log.i(TAG, "Auto-connect attempt $attempt: $target")
                connectProfiles(a.getRemoteDevice(target))
            }
            scheduleAutoConnect()
        }

    private fun restartAutoConnect() {
        attempt = 0
        main.removeCallbacks(autoConnect)
        scheduleAutoConnect()
    }

    private fun scheduleAutoConnect() {
        main.removeCallbacks(autoConnect)
        if (!canAutoConnect() || isConnected()) return
        val delay = plan.delayBefore(attempt) ?: return
        main.postDelayed(autoConnect, delay)
    }

    private fun canAutoConnect() = started && activeMode() && autoConnectOn() && adapter?.isEnabled == true && profiles?.hfp != null

    private fun isConnected(): Boolean = hfpDevice() != null

    // --- Status --------------------------------------------------------------------------------------------------

    private fun info(device: BluetoothDevice): BtDeviceInfo {
        val p = profiles
        return BtDeviceInfo(
            address = device.address,
            name = device.name.orEmpty(),
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            majorClass = device.bluetoothClass?.majorDeviceClass ?: 0,
            hfpState = p?.state(p.hfp, device) ?: 0,
            a2dpState = p?.state(p.a2dpSink, device) ?: 0,
        )
    }

    private fun refreshStatus() {
        val a = adapter
        val p = profiles
        val device = hfpDevice() ?: p?.let { it.connected(it.a2dpSink).firstOrNull() }
        val next =
            BtStatus(
                enabled = a?.isEnabled == true,
                name = a?.name.orEmpty(),
                pin = pin(),
                activeMode = activeMode(),
                discovering = a?.isDiscovering == true,
                discoverable = a?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                deviceAddress = device?.address.orEmpty(),
                deviceName = device?.name.orEmpty(),
                hfpState = if (device != null && p != null) p.state(p.hfp, device) else 0,
                a2dpState = if (device != null && p != null) p.state(p.a2dpSink, device) else 0,
                pbapState = if (device != null && p != null) p.state(p.pbap, device) else 0,
                audioInCar = device != null && p != null && p.audioState(device) == AUDIO_CONNECTED,
                micMuted = audio.isMicrophoneMute,
                battery = battery,
                signal = signal,
                operator = operator,
                roaming = roaming,
                autoConnect = autoConnectOn(),
                autoAnswer = autoAnswer(),
                voiceAssistant = voiceAssistant,
            )
        if (next != status) {
            status = next
            each { it.onStatusChanged(next) }
        }
    }

    // --- Settings ------------------------------------------------------------------------------------------------

    private var history: List<String>
        get() = Mru.decode(prefs.getString(KEY_HISTORY, null))
        set(value) = prefs.edit().putString(KEY_HISTORY, Mru.encode(value)).apply()

    private fun pin() = prefs.getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    private fun autoConnectOn() = prefs.getBoolean(KEY_AUTO_CONNECT, true)

    private fun autoAnswer() = prefs.getBoolean(KEY_AUTO_ANSWER, false)

    /** Jancar's btservice off (or forced from the diagnostics app): this module answers the Bluetooth events. */
    fun activeMode(): Boolean = prefs.getBoolean(KEY_FORCE_ACTIVE, false) || !isPackageEnabled(JANCAR_BTSERVICE)

    fun setForceActive(force: Boolean) {
        prefs.edit().putBoolean(KEY_FORCE_ACTIVE, force).apply()
        main.post {
            refreshStatus()
            restartAutoConnect()
        }
    }

    private fun isPackageEnabled(pkg: String): Boolean {
        val pm = context.packageManager
        return try {
            val setting = pm.getApplicationEnabledSetting(pkg)
            pm.getApplicationInfo(pkg, 0).enabled &&
                setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } catch (_: Exception) {
            false
        }
    }

    // --- Callbacks -----------------------------------------------------------------------------------------------

    private fun each(action: (ILibreHuBluetoothCallback) -> Unit) {
        synchronized(callbacks) {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    try {
                        action(callbacks.getBroadcastItem(i))
                    } catch (_: RemoteException) {
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun device(address: String?): BluetoothDevice? =
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) adapter?.getRemoteDevice(address) else null

    /** Runs [block] on the main thread, then publishes the new status. */
    private fun post(block: () -> Unit) {
        main.post {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth command failed: $e")
            }
            refreshStatus()
        }
    }

    // --- API -----------------------------------------------------------------------------------------------------

    val binder: ILibreHuBluetooth.Stub =
        object : ILibreHuBluetooth.Stub() {
            override fun getStatus() = status

            override fun setEnabled(on: Boolean) =
                post {
                    @Suppress("DEPRECATION")
                    if (on) adapter?.enable() else adapter?.disable()
                }

            override fun setName(name: String?) = post { if (!name.isNullOrBlank()) adapter?.setName(name.trim()) }

            override fun setPin(pin: String?) =
                post {
                    val p = pin?.trim().orEmpty()
                    if (p.length in 4..16 && p.all { it.isDigit() }) prefs.edit().putString(KEY_PIN, p).apply()
                }

            override fun setDiscoverable(seconds: Int) =
                post {
                    val mode =
                        if (seconds >
                            0
                        ) {
                            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                        } else {
                            BluetoothAdapter.SCAN_MODE_CONNECTABLE
                        }
                    val ints = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                    HiddenApi.call(adapter, "setScanMode", ints, mode, seconds.coerceIn(0, 3600))
                }

            override fun setAutoConnect(on: Boolean) =
                post {
                    prefs.edit().putBoolean(KEY_AUTO_CONNECT, on).apply()
                    restartAutoConnect()
                }

            override fun setAutoAnswer(on: Boolean) = post { prefs.edit().putBoolean(KEY_AUTO_ANSWER, on).apply() }

            override fun getBondedDevices(): List<BtDeviceInfo> =
                adapter
                    ?.bondedDevices
                    .orEmpty()
                    .map(::info)
                    .sortedWith(
                        compareByDescending<BtDeviceInfo> { it.connected }.thenBy {
                            history.indexOf(it.address).let { i ->
                                if (i <
                                    0
                                ) {
                                    99
                                } else {
                                    i
                                }
                            }
                        },
                    )

            override fun getFoundDevices(): List<BtDeviceInfo> = found.values.toList()

            override fun startDiscovery() =
                post {
                    adapter?.cancelDiscovery()
                    found.clear()
                    adapter?.startDiscovery()
                }

            override fun stopDiscovery() = post { adapter?.cancelDiscovery() }

            override fun pair(address: String?) =
                post {
                    adapter?.cancelDiscovery()
                    device(address)?.createBond()
                }

            override fun unpair(address: String?) =
                post {
                    val d = device(address) ?: return@post
                    disconnectProfiles(d, byUser = true)
                    HiddenApi.call(d, "removeBond", arrayOf())
                }

            override fun confirmPairing(
                address: String?,
                accept: Boolean,
                pinOrPasskey: String?,
            ) = post {
                val d = device(address) ?: return@post
                val variant = pendingPairing.remove(d.address) ?: return@post
                val code = pinOrPasskey?.trim().orEmpty()
                when {
                    !accept -> {
                        HiddenApi.call(d, "cancelPairingUserInput", arrayOf())
                    }

                    variant == BluetoothDevice.PAIRING_VARIANT_PIN -> {
                        d.setPin(code.ifEmpty { pin() }.toByteArray())
                    }

                    variant == PAIRING_VARIANT_PASSKEY -> {
                        HiddenApi.call(d, "setPasskey", arrayOf(Int::class.javaPrimitiveType!!), code.toIntOrNull() ?: 0)
                    }

                    else -> {
                        confirm(d, true)
                    }
                }
            }

            override fun connect(address: String?) =
                post {
                    val d = device(address) ?: return@post
                    userDisconnected.remove(d.address)
                    connectProfiles(d)
                }

            override fun disconnect(address: String?) = post { device(address)?.let { disconnectProfiles(it, byUser = true) } }

            override fun getCalls(): List<BtCallInfo> = callInfos

            override fun dial(number: String?) =
                post {
                    val n = number?.trim().orEmpty()
                    val d = hfpDevice() ?: return@post
                    if (PhoneNumbers.isDialable(n)) {
                        prefs.edit().putString(KEY_LAST_DIALED, n).apply()
                        profiles?.dial(d, n)
                    }
                }

            override fun redial() =
                post {
                    val d = hfpDevice() ?: return@post
                    val n = phonebook.lastDialed() ?: prefs.getString(KEY_LAST_DIALED, null) ?: return@post
                    profiles?.dial(d, n)
                }

            override fun answer() = post { answerNow() }

            override fun reject() = post { hfpDevice()?.let { profiles?.reject(it) } }

            override fun hangup() =
                post {
                    val d = hfpDevice() ?: return@post
                    val target = Calls.hangupTarget(rawCalls.map { it.first })
                    if (target == null) {
                        // Only a ringing call: hanging up means rejecting it.
                        if (Calls.isRinging(rawCalls.map { it.first })) profiles?.reject(d)
                        return@post
                    }
                    profiles?.terminate(d, rawCalls.firstOrNull { it.first.id == target.id }?.second)
                }

            override fun swapCalls() = post { hfpDevice()?.let { profiles?.hold(it) } }

            override fun endAndAccept() = post { hfpDevice()?.let { profiles?.accept(it, CALL_ACCEPT_TERMINATE) } }

            override fun sendDtmf(key: Char) =
                post {
                    if (PhoneNumbers.isDtmf(key)) hfpDevice()?.let { profiles?.sendDtmf(it, key) }
                }

            override fun setAudioInCar(inCar: Boolean) =
                post {
                    val d = hfpDevice() ?: return@post
                    if (inCar) profiles?.connectAudio(d) else profiles?.disconnectAudio(d)
                }

            override fun setMicMuted(muted: Boolean) = post { audio.isMicrophoneMute = muted }

            override fun startVoiceAssistant() = post { hfpDevice()?.let { profiles?.startVoiceRecognition(it) } }

            override fun stopVoiceAssistant() = post { hfpDevice()?.let { profiles?.stopVoiceRecognition(it) } }

            override fun getMedia() = media.info

            override fun mediaPlay() = post { media.play() }

            override fun mediaPause() = post { media.pause() }

            override fun mediaPlayPause() = post { media.playPause() }

            override fun mediaNext() = post { media.next() }

            override fun mediaPrevious() = post { media.previous() }

            override fun mediaStop() = post { media.stopPlayback() }

            override fun getContactCount() = phonebook.contactCount()

            override fun getContacts(
                offset: Int,
                limit: Int,
            ): List<BtContact> = phonebook.contacts(offset, limit)

            override fun searchContacts(
                query: String?,
                limit: Int,
            ): List<BtContact> = phonebook.search(query.orEmpty(), limit)

            override fun getCallLog(
                type: Int,
                limit: Int,
            ): List<BtContact> = phonebook.callLog(type, limit)

            override fun lookupName(number: String?) = phonebook.lookupName(number.orEmpty())

            override fun syncPhonebook() =
                post {
                    val d = hfpDevice() ?: return@post
                    val p = profiles ?: return@post
                    // PBAP downloads on connection: reconnect it.
                    p.disconnect(p.pbap, d)
                    main.postDelayed({ p.connect(p.pbap, d) }, SECONDARY_DELAY_MS)
                }

            override fun registerCallback(callback: ILibreHuBluetoothCallback?) {
                if (callback == null) return
                callbacks.register(callback)
                // Current state right away.
                try {
                    callback.onStatusChanged(status)
                    callback.onCallsChanged(callInfos)
                    callback.onMediaChanged(media.info)
                } catch (_: RemoteException) {
                }
            }

            override fun unregisterCallback(callback: ILibreHuBluetoothCallback?) {
                if (callback != null) callbacks.unregister(callback)
            }
        }

    companion object {
        private const val TAG = "LibreHU-BT"
        private const val PREFS = "bluetooth"
        private const val KEY_HISTORY = "history"
        private const val KEY_PIN = "pin"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_AUTO_ANSWER = "auto_answer"
        private const val KEY_FORCE_ACTIVE = "force_active"
        private const val KEY_LAST_DIALED = "last_dialed"
        private const val DEFAULT_PIN = "0000"
        private const val SECONDARY_DELAY_MS = 1_500L
        private const val AUTO_ANSWER_MS = 5_000L
        private const val AUDIO_CONNECTED = 2
        private const val CALL_ACCEPT_TERMINATE = 2

        // BluetoothDevice.PAIRING_VARIANT_* (hidden on Android 9 except PIN).
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2
        private const val PAIRING_VARIANT_CONSENT = 3

        const val JANCAR_BTSERVICE = "com.jancar.btservice"
    }
}
