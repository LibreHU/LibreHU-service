package org.librehu.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.core.obd.ObdPid
import org.librehu.service.R
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdLabels
import org.librehu.service.obd.ObdManager
import org.librehu.service.obd.ObdTransport

/** ELM327 adapter: connection, live values, values to read, widget, trouble codes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObdScreen(client: ServiceClient) {
    val context = LocalContext.current
    val obd = ObdManager.get(context)
    val s by obd.settings.collectAsStateWithLifecycle()
    val st by obd.state.collectAsStateWithLifecycle()
    var usbList by remember { mutableStateOf(obd.usbAdapters()) }
    var widgetSlot by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Page(stringResource(R.string.tab_obd)) {
        Card {
            SwitchRow(stringResource(R.string.obd_enable), s.enabled, stringResource(R.string.obd_enable_hint)) { on ->
                obd.update {
                    it.copy(enabled = on)
                }
            }
            StatusLine(
                obdLabel(st.connection, s.enabled) + if (st.message.isNotEmpty()) " — ${st.message}" else "",
                if (st.connection ==
                    ObdConnection.ERROR
                ) {
                    false
                } else {
                    st.connection == ObdConnection.CONNECTED
                },
            )
            if (st.adapter.isNotEmpty()) {
                Hint(st.adapter + if (st.protocol.isNotEmpty()) "  ·  " + stringResource(R.string.obd_protocol, st.protocol) else "")
            }
        }

        Card(stringResource(R.string.obd_adapter)) {
            Choices(
                listOf(
                    ObdTransport.BLUETOOTH to stringResource(R.string.obd_bluetooth),
                    ObdTransport.USB to stringResource(R.string.obd_usb),
                ),
                s.transport,
            ) { t -> obd.update { it.copy(transport = t) } }
            when (s.transport) {
                ObdTransport.BLUETOOTH -> {
                    Hint(stringResource(R.string.obd_bt_hint))
                    if (client.bonded.isEmpty()) Hint(stringResource(R.string.bt_none))
                    Choices(client.bonded.map { it.address to it.name.ifEmpty { it.address } }, s.bluetoothAddress) { a ->
                        obd.update { it.copy(bluetoothAddress = a) }
                    }
                }

                ObdTransport.USB -> {
                    Hint(stringResource(R.string.obd_usb_hint))
                    Choices(
                        listOf("" to stringResource(R.string.obd_usb_first)) + usbList,
                        s.usbDevice,
                    ) { d -> obd.update { it.copy(usbDevice = d) } }
                    Actions { Pill(stringResource(R.string.obd_usb_refresh)) { usbList = obd.usbAdapters() } }
                    BodyText(stringResource(R.string.obd_baud))
                    Choices(ObdManager.BAUD_RATES.map { it to it.toString() }, s.usbBaud) { b -> obd.update { it.copy(usbBaud = b) } }
                }
            }
            Actions { Pill(stringResource(R.string.obd_reconnect)) { obd.restart() } }
        }

        if (st.connection == ObdConnection.CONNECTED) {
            Card(stringResource(R.string.obd_live)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((k, v) in st.values.toSortedMap()) Tile(ObdLabels.label(context, k), ObdManager.format(k, v))
                }
            }
        }

        Card(stringResource(R.string.obd_values)) {
            Hint(stringResource(R.string.obd_values_hint))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pid in ObdPid.entries) {
                    val unsupported = st.supported.isNotEmpty() && pid.pid !in st.supported
                    Pill(ObdLabels.label(context, pid.name), selected = pid in s.pids, enabled = !unsupported) {
                        obd.update { it.copy(pids = if (pid in it.pids) it.pids - pid else it.pids + pid) }
                    }
                }
            }
        }

        Card(stringResource(R.string.obd_widget)) {
            Hint(stringResource(R.string.obd_widget_hint))
            Actions {
                s.widget.forEachIndexed { i, k -> Pill("${i + 1}. " + ObdLabels.label(context, k)) { widgetSlot = i } }
            }
        }

        Card(stringResource(R.string.obd_dtc)) {
            val codes = st.dtcs
            when {
                codes == null -> Hint(stringResource(R.string.obd_dtc_hint))
                codes.isEmpty() -> BodyText(stringResource(R.string.obd_dtc_none))
                else -> codes.forEach { BodyText(it, mono = true) }
            }
            Actions {
                Pill(stringResource(R.string.obd_dtc_read), enabled = st.connection == ObdConnection.CONNECTED) { obd.readDtcs() }
                Pill(stringResource(R.string.obd_dtc_clear), enabled = st.connection == ObdConnection.CONNECTED) { confirmClear = true }
            }
        }
    }

    widgetSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { widgetSlot = null },
            title = { Text(stringResource(R.string.obd_widget_slot, slot + 1)) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf(ObdManager.BATTERY) + ObdPid.entries.map { it.name }) {
                        Pill(ObdLabels.label(context, k), selected = s.widget.getOrNull(slot) == k) {
                            obd.update { it.copy(widget = it.widget.toMutableList().also { w -> w[slot] = k }) }
                            widgetSlot = null
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { widgetSlot = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.obd_dtc_clear)) },
            text = { Text(stringResource(R.string.obd_dtc_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    obd.clearDtcs()
                    confirmClear = false
                }) { Text(stringResource(R.string.obd_dtc_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
