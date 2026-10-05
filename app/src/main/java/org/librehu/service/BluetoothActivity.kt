package org.librehu.service

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.librehu.core.bt.CallState
import org.librehu.service.bt.BtCallInfo
import org.librehu.service.bt.BtDeviceInfo
import org.librehu.service.bt.BtMediaInfo
import org.librehu.service.bt.BtStatus
import org.librehu.service.bt.ILibreHuBluetooth
import org.librehu.service.bt.ILibreHuBluetoothCallback
import java.text.DateFormat
import java.util.Date

/** Bluetooth test screen: everything goes through the public API (ILibreHuBluetooth), like a client app would. */
class BluetoothActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var bt: ILibreHuBluetooth? = null
    private var updating = false

    private lateinit var status: TextView
    private lateinit var enabled: Switch
    private lateinit var autoConnect: Switch
    private lateinit var autoAnswer: Switch
    private lateinit var paired: LinearLayout
    private lateinit var found: LinearLayout
    private lateinit var calls: TextView
    private lateinit var media: TextView
    private lateinit var phonebook: TextView
    private lateinit var number: EditText

    private val foundDevices = LinkedHashMap<String, BtDeviceInfo>()

    private val callback =
        object : ILibreHuBluetoothCallback.Stub() {
            override fun onStatusChanged(s: BtStatus) = post { showStatus(s) }

            override fun onCallsChanged(list: MutableList<BtCallInfo>) = post { showCalls(list) }

            override fun onMediaChanged(m: BtMediaInfo) = post { showMedia(m) }

            override fun onDevicesChanged() = post { showPaired() }

            override fun onDeviceFound(device: BtDeviceInfo) =
                post {
                    if (!device.bonded) foundDevices[device.address] = device
                    showFound()
                }

            override fun onPhonebookChanged() = post { showPhonebook() }

            override fun onPairingRequest(
                address: String,
                name: String,
                variant: Int,
                passkey: Int,
            ) = post { askPairing(address, name, variant, passkey) }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                try {
                    val b = ILibreHuService.Stub.asInterface(service).bluetooth
                    bt = b
                    b.registerCallback(callback)
                    showPaired()
                    showPhonebook()
                } catch (e: RemoteException) {
                    status.text = e.toString()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bt = null
                status.text = getString(R.string.disconnected)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        status = text(root, bold = true)
        enabled = switch(root, R.string.bt_enabled) { b, on -> b.setEnabled(on) }
        autoConnect = switch(root, R.string.bt_auto_connect) { b, on -> b.setAutoConnect(on) }
        autoAnswer = switch(root, R.string.bt_auto_answer) { b, on -> b.setAutoAnswer(on) }
        row(
            root,
            R.string.bt_scan to { call { it.startDiscovery() } },
            R.string.bt_discoverable to { call { it.setDiscoverable(120) } },
            R.string.bt_permissions to ::askPermissions,
        )
        row(
            root,
            R.string.bt_rename to { askText(R.string.bt_rename, status.tag as? String ?: "") { v -> call { it.setName(v) } } },
            R.string.bt_pin to { askText(R.string.bt_pin, "", numeric = true) { v -> call { it.setPin(v) } } },
        )

        section(root, R.string.bt_paired)
        paired = column(root)
        section(root, R.string.bt_found)
        found = column(root)

        section(root, R.string.bt_calls)
        calls = text(root)
        number =
            EditText(this).apply {
                hint = getString(R.string.bt_number)
                inputType = InputType.TYPE_CLASS_PHONE
                root.addView(this)
            }
        row(
            root,
            R.string.bt_dial to { call { it.dial(number.text.toString()) } },
            R.string.bt_redial to { call { it.redial() } },
            R.string.bt_answer to { call { it.answer() } },
            R.string.bt_reject to { call { it.reject() } },
        )
        row(
            root,
            R.string.bt_hangup to { call { it.hangup() } },
            R.string.bt_swap to { call { it.swapCalls() } },
            R.string.bt_audio_car to { call { it.setAudioInCar(true) } },
            R.string.bt_audio_phone to { call { it.setAudioInCar(false) } },
        )
        row(
            root,
            R.string.bt_mic_mute to { call { it.setMicMuted(!it.status.micMuted) } },
            R.string.bt_voice to { call { it.startVoiceAssistant() } },
        )

        section(root, R.string.bt_music)
        media = text(root)
        row(
            root,
            R.string.bt_prev to { call { it.mediaPrevious() } },
            R.string.bt_play_pause to { call { it.mediaPlayPause() } },
            R.string.bt_next to { call { it.mediaNext() } },
        )

        section(root, R.string.bt_phonebook)
        row(root, R.string.bt_sync to { call { it.syncPhonebook() } })
        phonebook = text(root)

        setContentView(ScrollView(this).apply { addView(root) })
        LibreHuService.start(this)
        bindService(Intent(this, LibreHuService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        try {
            bt?.unregisterCallback(callback)
        } catch (_: RemoteException) {
        }
        unbindService(connection)
        super.onDestroy()
    }

    // --- Display -------------------------------------------------------------------------------------------------

    private fun showStatus(s: BtStatus) {
        status.tag = s.name
        status.text =
            getString(
                R.string.bt_status,
                s.name,
                if (s.activeMode) getString(R.string.bt_mode_active) else getString(R.string.bt_mode_passive),
                s.deviceName.ifEmpty { "-" },
                stateLabel(s.hfpState),
                stateLabel(s.a2dpState),
                stateLabel(s.pbapState),
                if (s.battery < 0) "-" else "${s.battery}/5",
                if (s.signal < 0) "-" else "${s.signal}/5",
                s.operator.ifEmpty { "-" },
            ) + (if (s.discovering) "\n" + getString(R.string.bt_discovering) else "") +
            (if (s.audioInCar) "\n" + getString(R.string.bt_audio_in_car) else "")
        updating = true
        enabled.isChecked = s.enabled
        autoConnect.isChecked = s.autoConnect
        autoAnswer.isChecked = s.autoAnswer
        updating = false
        if (!s.discovering && foundDevices.isEmpty()) found.removeAllViews()
    }

    private fun showCalls(list: List<BtCallInfo>) {
        calls.text =
            if (list.isEmpty()) {
                getString(R.string.bt_no_call)
            } else {
                list.joinToString("\n") { c ->
                    val who = if (c.name.isEmpty()) c.number else "${c.name} (${c.number})"
                    val since = if (c.activeSince > 0) "  " + elapsed(c.activeSince) else ""
                    "${CallState.ofHfp(c.state)}  $who$since"
                }
            }
    }

    private fun showMedia(m: BtMediaInfo) {
        media.text =
            if (!m.connected) {
                getString(R.string.bt_music_off)
            } else {
                (if (m.playing) "▶ " else "⏸ ") + listOf(m.title, m.artist, m.album).filter { it.isNotEmpty() }.joinToString(" — ")
            }
    }

    private fun showPaired() {
        val b = bt ?: return
        val list =
            try {
                b.bondedDevices
            } catch (_: RemoteException) {
                return
            }
        paired.removeAllViews()
        for (d in list) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(
                TextView(this).apply {
                    text = getString(R.string.bt_device_line, d.name.ifEmpty { d.address }, stateLabel(d.hfpState), stateLabel(d.a2dpState))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            line.addView(
                button(if (d.connected) R.string.bt_disconnect else R.string.bt_connect) {
                    call { if (d.connected) it.disconnect(d.address) else it.connect(d.address) }
                },
            )
            line.addView(button(R.string.bt_unpair) { call { it.unpair(d.address) } })
            paired.addView(line)
        }
        if (list.isEmpty()) paired.addView(TextView(this).apply { text = getString(R.string.bt_none) })
    }

    private fun showFound() {
        found.removeAllViews()
        for (d in foundDevices.values) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(
                TextView(this).apply {
                    text = "${d.name.ifEmpty { d.address }}  ${d.rssi} dBm"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            line.addView(button(R.string.bt_pair) { call { it.pair(d.address) } })
            found.addView(line)
        }
    }

    private fun showPhonebook() {
        val b = bt ?: return
        try {
            val count = b.contactCount
            val log = b.getCallLog(0, 10)
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            phonebook.text =
                getString(R.string.bt_contacts, count) + "\n" +
                log.joinToString("\n") { e ->
                    val kind =
                        when (e.type) {
                            1 -> "↙"
                            2 -> "↗"
                            3 -> "✕"
                            else -> "·"
                        }
                    "$kind ${df.format(Date(e.date))}  ${e.name.ifEmpty { e.number }}"
                }
        } catch (_: RemoteException) {
        }
    }

    private fun askPairing(
        address: String,
        name: String,
        variant: Int,
        passkey: Int,
    ) {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                // PIN (0) and passkey (1) are typed; the others are only confirmed.
                visibility = if (variant == 0 || variant == 1) android.view.View.VISIBLE else android.view.View.GONE
            }
        AlertDialog
            .Builder(this)
            .setTitle(getString(R.string.bt_pairing_title, name.ifEmpty { address }))
            .setMessage(if (passkey >= 0) getString(R.string.bt_pairing_code, "%06d".format(passkey)) else null)
            .setView(input)
            .setPositiveButton(R.string.bt_accept) { _, _ -> call { it.confirmPairing(address, true, input.text.toString()) } }
            .setNegativeButton(R.string.bt_refuse) { _, _ -> call { it.confirmPairing(address, false, "") } }
            .show()
    }

    private fun askText(
        title: Int,
        initial: String,
        numeric: Boolean = false,
        done: (String) -> Unit,
    ) {
        val input =
            EditText(this).apply {
                setText(initial)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> done(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            1,
        )
    }

    private fun stateLabel(state: Int) =
        getString(
            when (state) {
                2 -> R.string.bt_state_connected
                1 -> R.string.bt_state_connecting
                3 -> R.string.bt_state_disconnecting
                else -> R.string.bt_state_off
            },
        )

    private fun elapsed(since: Long): String {
        val s = (System.currentTimeMillis() - since) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // --- Widgets -------------------------------------------------------------------------------------------------

    private fun call(block: (ILibreHuBluetooth) -> Unit) {
        val b = bt ?: return
        try {
            block(b)
        } catch (_: RemoteException) {
        }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun switch(
        root: LinearLayout,
        label: Int,
        write: (ILibreHuBluetooth, Boolean) -> Unit,
    ): Switch =
        Switch(this).apply {
            text = getString(label)
            minimumHeight = dp(48)
            setOnCheckedChangeListener { _, checked -> if (!updating) call { write(it, checked) } }
            root.addView(this)
        }

    private fun row(
        root: LinearLayout,
        vararg buttons: Pair<Int, () -> Unit>,
    ) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in buttons) line.addView(button(label, action))
        root.addView(line)
    }

    private fun button(
        label: Int,
        action: () -> Unit,
    ) = Button(this).apply {
        text = getString(label)
        setOnClickListener { action() }
    }

    private fun column(root: LinearLayout) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }

    private fun section(
        root: LinearLayout,
        label: Int,
    ) {
        text(root, bold = true).apply {
            text = getString(label)
            setPadding(0, dp(16), 0, dp(4))
        }
    }

    private fun text(
        root: LinearLayout,
        bold: Boolean = false,
    ): TextView =
        TextView(this).apply {
            if (bold) setTypeface(typeface, Typeface.BOLD)
            root.addView(this)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
