package org.librehu.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.librehu.core.bt.CallState
import org.librehu.service.R
import org.librehu.service.bt.BtContact
import java.text.DateFormat
import java.util.Date

@Composable
fun BluetoothScreen(
    client: ServiceClient,
    actions: AppActions,
) {
    val s = client.btStatus
    var ask by remember { mutableStateOf<Pair<Int, (String) -> Unit>?>(null) }
    var number by remember { mutableStateOf("") }
    Page(stringResource(R.string.tab_bluetooth)) {
        Card {
            StatusLine(
                s.deviceName.ifEmpty { stringResource(R.string.bt_no_phone) },
                if (s.deviceAddress.isEmpty()) null else true,
            )
            Hint(
                stringResource(
                    R.string.bt_status_short,
                    s.name.ifEmpty { "—" },
                    stringResource(if (s.activeMode) R.string.bt_mode_active else R.string.bt_mode_passive),
                    stateLabel(s.hfpState),
                    stateLabel(s.a2dpState),
                    stateLabel(s.pbapState),
                ),
            )
            SwitchRow(stringResource(R.string.bt_enabled), s.enabled) { on -> client.btCall { it.setEnabled(on) } }
            SwitchRow(stringResource(R.string.bt_auto_connect), s.autoConnect) { on -> client.btCall { it.setAutoConnect(on) } }
            SwitchRow(stringResource(R.string.bt_auto_answer), s.autoAnswer) { on -> client.btCall { it.setAutoAnswer(on) } }
            Actions {
                Pill(stringResource(R.string.bt_discoverable)) { client.btCall { it.setDiscoverable(120) } }
                Pill(stringResource(R.string.bt_rename)) { ask = R.string.bt_rename to { v: String -> client.btCall { it.setName(v) } } }
                Pill(stringResource(R.string.bt_pin)) { ask = R.string.bt_pin to { v: String -> client.btCall { it.setPin(v) } } }
                Pill(stringResource(R.string.bt_permissions), onClick = actions.requestBtPermissions)
            }
        }

        Card(stringResource(R.string.bt_paired)) {
            if (client.bonded.isEmpty()) Hint(stringResource(R.string.bt_none))
            for (d in client.bonded) {
                ListRow(
                    d.name.ifEmpty { d.address },
                    stringResource(R.string.bt_device_line_short, stateLabel(d.hfpState), stateLabel(d.a2dpState)),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.connected) {
                            Pill(stringResource(R.string.bt_disconnect)) { client.btCall { it.disconnect(d.address) } }
                        } else {
                            Pill(stringResource(R.string.bt_connect)) { client.btCall { it.connect(d.address) } }
                        }
                        Pill(stringResource(R.string.bt_unpair)) { client.btCall { it.unpair(d.address) } }
                    }
                }
            }
        }

        Card(stringResource(R.string.bt_found)) {
            Actions {
                Pill(stringResource(R.string.bt_scan), icon = Icons.Default.Search) {
                    client.clearFound()
                    client.btCall { it.startDiscovery() }
                }
                if (s.discovering) Hint(stringResource(R.string.bt_discovering))
            }
            for (d in client.found) {
                ListRow(d.name.ifEmpty { d.address }, "${d.address}  ${d.rssi} dBm") {
                    Pill(stringResource(R.string.bt_pair)) { client.btCall { it.pair(d.address) } }
                }
            }
        }

        Card(stringResource(R.string.bt_calls)) {
            if (client.calls.isEmpty()) Hint(stringResource(R.string.bt_no_call))
            for (c in client.calls) {
                val who = if (c.name.isEmpty()) c.number else "${c.name} · ${c.number}"
                ListRow(who, callStateLabel(CallState.ofHfp(c.state)))
            }
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text(stringResource(R.string.bt_number)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            Actions {
                Pill(stringResource(R.string.bt_dial), icon = Icons.Default.Call) { client.btCall { it.dial(number) } }
                Pill(stringResource(R.string.bt_redial)) { client.btCall { it.redial() } }
                Pill(stringResource(R.string.bt_answer)) { client.btCall { it.answer() } }
                Pill(stringResource(R.string.bt_reject)) { client.btCall { it.reject() } }
                Pill(stringResource(R.string.bt_hangup), icon = Icons.Default.CallEnd) { client.btCall { it.hangup() } }
                Pill(stringResource(R.string.bt_swap)) { client.btCall { it.swapCalls() } }
                Pill(stringResource(if (s.audioInCar) R.string.bt_audio_phone else R.string.bt_audio_car)) {
                    client.btCall { it.setAudioInCar(!s.audioInCar) }
                }
                Pill(stringResource(R.string.bt_mic_mute), selected = s.micMuted) { client.btCall { it.setMicMuted(!s.micMuted) } }
                Pill(stringResource(R.string.bt_voice)) { client.btCall { it.startVoiceAssistant() } }
            }
        }

        Card(stringResource(R.string.bt_music)) {
            val m = client.media
            BodyText(
                if (!m.connected) {
                    stringResource(R.string.bt_music_off)
                } else {
                    listOf(m.title, m.artist, m.album).filter { it.isNotEmpty() }.joinToString(" — ").ifEmpty { "—" }
                },
            )
            Actions {
                Pill("", icon = Icons.Default.SkipPrevious) { client.btCall { it.mediaPrevious() } }
                Pill("", icon = if (m.playing) Icons.Default.Pause else Icons.Default.PlayArrow) { client.btCall { it.mediaPlayPause() } }
                Pill("", icon = Icons.Default.SkipNext) { client.btCall { it.mediaNext() } }
            }
        }

        Card(stringResource(R.string.bt_phonebook)) {
            var count by remember { mutableStateOf(0) }
            var log by remember { mutableStateOf<List<BtContact>>(emptyList()) }
            LaunchedEffect(client.bt, client.phonebookVersion) {
                count = client.btGet(0) { it.contactCount }
                log = client.btGet(emptyList()) { it.getCallLog(0, 10) }
            }
            Hint(stringResource(R.string.bt_contacts, count))
            val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
            for (e in log) {
                val kind =
                    when (e.type) {
                        1 -> "↙"
                        2 -> "↗"
                        3 -> "✕"
                        else -> "·"
                    }
                ListRow("$kind  ${e.name.ifEmpty { e.number }}", df.format(Date(e.date)), onClick = { number = e.number })
            }
            Actions { Pill(stringResource(R.string.bt_sync)) { client.btCall { it.syncPhonebook() } } }
        }
    }

    ask?.let { (title, done) ->
        var text by remember(title) { mutableStateOf(if (title == R.string.bt_rename) s.name else "") }
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions =
                        if (title ==
                            R.string.bt_pin
                        ) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else {
                            KeyboardOptions.Default
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    done(text)
                    ask = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    client.pairing?.let { p ->
        var code by remember(p) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.bt_pairing_title, p.name.ifEmpty { p.address })) },
            text = {
                if (p.variant == 0 || p.variant == 1) {
                    OutlinedTextField(value = code, onValueChange = {
                        code = it
                    }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                } else if (p.passkey >= 0) {
                    Text(stringResource(R.string.bt_pairing_code, "%06d".format(p.passkey)))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    client.btCall { it.confirmPairing(p.address, true, code) }
                    client.pairing = null
                }) { Text(stringResource(R.string.bt_accept)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    client.btCall { it.confirmPairing(p.address, false, "") }
                    client.pairing = null
                }) { Text(stringResource(R.string.bt_refuse)) }
            },
        )
    }
}

@Composable
fun stateLabel(state: Int) =
    stringResource(
        when (state) {
            2 -> R.string.bt_state_connected
            1 -> R.string.bt_state_connecting
            3 -> R.string.bt_state_disconnecting
            else -> R.string.bt_state_off
        },
    )

@Composable
private fun callStateLabel(s: CallState) =
    stringResource(
        when (s) {
            CallState.ACTIVE -> R.string.call_active
            CallState.HELD, CallState.HELD_BY_RESPONSE_AND_HOLD -> R.string.call_held
            CallState.DIALING, CallState.ALERTING -> R.string.call_dialing
            CallState.INCOMING -> R.string.call_incoming
            CallState.WAITING -> R.string.call_waiting
            CallState.TERMINATED -> R.string.call_ended
        },
    )
