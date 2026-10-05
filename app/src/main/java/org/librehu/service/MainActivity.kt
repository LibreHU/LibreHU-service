package org.librehu.service

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.librehu.core.mcu.toHex

/** Diagnostic screen: link state, vehicle inputs, audio settings and the MCU traffic, all through the public API. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var api: ILibreHuService? = null
    private var updating = false

    private lateinit var status: TextView
    private lateinit var vehicle: TextView
    private lateinit var traffic: TextView
    private val trafficLines = ArrayDeque<String>()
    private val refreshers = mutableListOf<(ILibreHuService) -> Unit>()

    private val callback =
        object : ILibreHuCallback.Stub() {
            override fun onVehicleFlags(flags: Int) = post { refresh() }

            override fun onAudioChanged() = post { refresh() }

            override fun onMcuFrame(
                cmd: Int,
                data: ByteArray,
                fromMcu: Boolean,
            ) = post { addTraffic((if (fromMcu) "MCU > " else "MCU < ") + (byteArrayOf(cmd.toByte()) + data).toHex()) }

            override fun onKey(
                channel: Int,
                values: IntArray,
                released: Boolean,
                learning: Boolean,
            ) = post { addTraffic(getString(R.string.key_event, channel, values.joinToString(" "), released)) }

            override fun onCanData(data: ByteArray) = post { addTraffic("CAN > " + data.toHex()) }
        }

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                val s = ILibreHuService.Stub.asInterface(service)
                api = s
                try {
                    s.registerCallback(callback)
                } catch (_: RemoteException) {
                }
                refresh()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                api = null
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
        vehicle = text(root)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.restart)
                setOnClickListener { LibreHuService.start(this@MainActivity, restart = true) }
            },
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.force_start)
                setOnClickListener {
                    LibreHuService
                        .prefs(this@MainActivity)
                        .edit()
                        .putBoolean(LibreHuService.PREF_FORCE, true)
                        .apply()
                    LibreHuService.start(this@MainActivity, restart = true)
                }
            },
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.bt_title)
                setOnClickListener { startActivity(Intent(this@MainActivity, BluetoothActivity::class.java)) }
            },
        )
        root.addView(buttons)

        section(root, R.string.section_audio)
        slider(root, R.string.volume, 0..40, { it.volume }) { s, v -> s.setVolume(v) }
        switch(root, R.string.mute, { it.isMuted }) { s, v -> s.setMuted(v) }
        slider(root, R.string.bass, 0..20, { it.tone[0] }) { s, v -> s.tone.let { t -> s.setTone(v, t[1], t[2]) } }
        slider(root, R.string.middle, 0..20, { it.tone[1] }) { s, v -> s.tone.let { t -> s.setTone(t[0], v, t[2]) } }
        slider(root, R.string.treble, 0..20, { it.tone[2] }) { s, v -> s.tone.let { t -> s.setTone(t[0], t[1], v) } }
        slider(root, R.string.balance, 0..60, { it.balanceFade[0] }) { s, v -> s.setBalanceFade(v, s.balanceFade[1]) }
        slider(root, R.string.fade, 0..60, { it.balanceFade[1] }) { s, v -> s.setBalanceFade(s.balanceFade[0], v) }
        slider(root, R.string.loudness, 0..15, { it.loudness }) { s, v -> s.setLoudness(v) }
        switch(root, R.string.subwoofer, { it.isSubwooferOn }) { s, v -> s.setSubwoofer(v, s.subwooferLevel) }
        slider(root, R.string.sub_level, 0..12, { it.subwooferLevel }) { s, v -> s.setSubwoofer(s.isSubwooferOn, v) }
        switch(root, R.string.external_amp, { it.isExternalAmpEnabled }) { s, v -> s.setExternalAmpEnabled(v) }

        section(root, R.string.section_traffic)
        traffic = text(root, mono = true)

        setContentView(ScrollView(this).apply { addView(root) })
        LibreHuService.start(this)
        bindService(Intent(this, LibreHuService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        try {
            api?.unregisterCallback(callback)
        } catch (_: RemoteException) {
        }
        unbindService(connection)
        super.onDestroy()
    }

    private fun refresh() {
        val s = api ?: return
        try {
            status.text = getString(R.string.status_line, s.status, s.mcuVersion.ifEmpty { "-" })
            val f = s.vehicleFlags
            vehicle.text =
                listOf(
                    R.string.flag_mcu to LibreHu.FLAG_MCU_ONLINE,
                    R.string.flag_acc to LibreHu.FLAG_ACC,
                    R.string.flag_reverse to LibreHu.FLAG_REVERSE,
                    R.string.flag_handbrake to LibreHu.FLAG_HANDBRAKE,
                    R.string.flag_headlight to LibreHu.FLAG_HEADLIGHT,
                    R.string.flag_turn_left to LibreHu.FLAG_TURN_LEFT,
                    R.string.flag_turn_right to LibreHu.FLAG_TURN_RIGHT,
                ).joinToString("   ") { (label, bit) -> getString(label) + (if (f and bit != 0) " ●" else " ○") }
            updating = true
            refreshers.forEach { it(s) }
        } catch (_: RemoteException) {
        } finally {
            updating = false
        }
    }

    private fun addTraffic(line: String) {
        trafficLines.addFirst(line)
        while (trafficLines.size > 40) trafficLines.removeLast()
        traffic.text = trafficLines.joinToString("\n")
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private fun call(block: (ILibreHuService) -> Unit) {
        val s = api ?: return
        try {
            block(s)
        } catch (_: RemoteException) {
        }
    }

    private fun slider(
        root: LinearLayout,
        label: Int,
        range: IntRange,
        read: (ILibreHuService) -> Int,
        write: (ILibreHuService, Int) -> Unit,
    ) {
        val title = text(root)
        val bar = SeekBar(this).apply { max = range.last - range.first }
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    title.text = getString(R.string.value_line, getString(label), progress + range.first)
                    if (fromUser && !updating) call { write(it, progress + range.first) }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            },
        )
        root.addView(bar)
        refreshers += { s ->
            val v = read(s)
            bar.progress = v - range.first
            title.text = getString(R.string.value_line, getString(label), v)
        }
    }

    private fun switch(
        root: LinearLayout,
        label: Int,
        read: (ILibreHuService) -> Boolean,
        write: (ILibreHuService, Boolean) -> Unit,
    ) {
        val sw =
            Switch(this).apply {
                text = getString(label)
                minimumHeight = dp(48)
                setOnCheckedChangeListener { _, checked -> if (!updating) call { write(it, checked) } }
            }
        root.addView(sw)
        refreshers += { s -> sw.isChecked = read(s) }
    }

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
        mono: Boolean = false,
    ): TextView =
        TextView(this).apply {
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            root.addView(this)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
