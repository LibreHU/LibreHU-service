package org.librehu.service.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.librehu.core.mcu.JacDebug
import org.librehu.core.mcu.JacFrame
import org.librehu.core.mcu.McuFrame
import org.librehu.core.mcu.toHex
import org.librehu.service.R

/**
 * Sends debug frames to the MCU through the service, with the guards of MCU-tools-app on Jancar MCUs: `80`
 * (bootloader) is never sent, `01` / `0E` / `F1` (power off, SoC reset, sleep time) ask first.
 */
class McuSender(
    private val client: ServiceClient,
    private val jancar: () -> Boolean,
) {
    var pending by mutableStateOf<McuFrame?>(null)
    var blocked by mutableStateOf<McuFrame?>(null)

    fun risk(f: McuFrame): JacDebug.Risk = if (jancar()) JacDebug.risk(f.cmd) else JacDebug.Risk.NONE

    fun send(f: McuFrame) {
        when (risk(f)) {
            JacDebug.Risk.NONE -> sendNow(f)
            JacDebug.Risk.CONFIRM -> pending = f
            JacDebug.Risk.BLOCKED -> blocked = f
        }
    }

    fun sendAll(frames: List<McuFrame>) = frames.forEach(::send)

    fun confirm() {
        pending?.let(::sendNow)
        pending = null
    }

    private fun sendNow(f: McuFrame) = client.call { it.sendMcuFrame(f.cmd, f.data) }

    /** Bytes on the wire (Jancar) or the command and data (other protocols, framed by the profile). */
    fun wire(f: McuFrame): String =
        if (jancar() && f.data.size <= JacFrame.MAX_DATA) JacFrame.encode(f.cmd, f.data).toHex() else f.toString()
}

@Composable
fun rememberMcuSender(
    client: ServiceClient,
    jancar: () -> Boolean,
): McuSender = remember(client) { McuSender(client, jancar) }

/** Confirmation / refusal dialogs of a [McuSender]. */
@Composable
fun McuSenderDialogs(sender: McuSender) {
    sender.pending?.let { f ->
        AlertDialog(
            onDismissRequest = { sender.pending = null },
            title = { Text(stringResource(R.string.risk_confirm_title, JacDebug.nameToMcu(f.cmd))) },
            text = { Text(stringResource(R.string.risk_confirm, sender.wire(f))) },
            confirmButton = { TextButton(onClick = sender::confirm) { Text(stringResource(R.string.diag_send_button)) } },
            dismissButton = { TextButton(onClick = { sender.pending = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    sender.blocked?.let {
        AlertDialog(
            onDismissRequest = { sender.blocked = null },
            title = { Text(stringResource(R.string.risk_blocked_title)) },
            text = { Text(stringResource(R.string.risk_blocked)) },
            confirmButton = { TextButton(onClick = { sender.blocked = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
