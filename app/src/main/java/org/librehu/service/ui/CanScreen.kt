package org.librehu.service.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.service.R
import org.librehu.service.can.CanMonitor
import org.librehu.service.can.CanVehicleStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CAN box messages: car profile (decoding), decoded values, one line per command (changed bytes highlighted, decoded
 * when the profile knows it), then the stream.
 */
@Composable
fun CanScreen(actions: AppActions) {
    val context = LocalContext.current
    val store = CanVehicleStore.get(context)
    val vehicles by store.vehicles.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val values by CanMonitor.values.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val byCmd by CanMonitor.byCmd.collectAsStateWithLifecycle()
    val lines by CanMonitor.lines.collectAsStateWithLifecycle()
    val raw by CanMonitor.rawBytes.collectAsStateWithLifecycle()
    var paused by remember { mutableStateOf(CanMonitor.paused) }
    var message by remember { mutableStateOf("") }
    val time = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Page(stringResource(R.string.tab_can)) {
        Card {
            Hint(stringResource(R.string.can_hint))
            BodyText(stringResource(R.string.can_counts, raw, byCmd.values.sumOf { it.count }, CanMonitor.skipped))
            Actions {
                Pill(stringResource(if (paused) R.string.diag_resume else R.string.diag_pause), selected = paused) {
                    paused = !paused
                    CanMonitor.paused = paused
                }
                Pill(stringResource(R.string.diag_clear)) { CanMonitor.clear() }
                Pill(stringResource(R.string.can_export)) {
                    val f = CanMonitor.export(context)
                    message =
                        if (f != null) context.getString(R.string.can_exported, f.path) else context.getString(R.string.can_export_failed)
                }
            }
            if (message.isNotEmpty()) Hint(message)
        }
        Card(stringResource(R.string.can_vehicle)) {
            Hint(stringResource(R.string.can_vehicle_hint))
            for (v in vehicles) {
                val builtIn = store.isBuiltIn(v.id)
                ListRow(
                    v.name,
                    listOfNotNull(
                        v.id,
                        stringResource(R.string.can_vehicle_messages, v.messages.values.count { it.signals.isNotEmpty() }),
                        if (builtIn) stringResource(R.string.mcu_builtin) else null,
                        v.description.ifEmpty { null },
                    ).joinToString("  ·  "),
                    onClick = { store.select(v.id) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(
                            stringResource(if (v.id == selected) R.string.mcu_selected else R.string.mcu_select),
                            selected = v.id == selected,
                        ) { store.select(v.id) }
                        Pill("", icon = Icons.Default.FileUpload) { actions.exportCanVehicle(v.id) }
                        if (!builtIn) Pill(stringResource(R.string.mcu_delete)) { confirmDelete = v.id }
                    }
                }
            }
            Actions { Pill(stringResource(R.string.mcu_import), icon = Icons.Default.FileDownload, onClick = actions.importCanVehicle) }
        }
        Card(stringResource(R.string.can_values)) {
            if (values.isEmpty()) Hint(stringResource(R.string.can_values_none))
            for ((message, list) in values.values.groupBy { it.message }) {
                Text(message, color = CarColors.Accent, fontSize = 16.sp)
                val flags = list.filter { it.flag != null }
                if (flags.isNotEmpty()) {
                    BodyText(flags.joinToString("   ") { (if (it.flag == true) "● " else "○ ") + it.label })
                }
                for (v in list.filter { it.flag == null }) {
                    Row {
                        Text(v.label, color = CarColors.TextDim, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(v.display(), color = CarColors.Text, fontSize = 16.sp)
                    }
                }
            }
        }
        Card(stringResource(R.string.can_by_command)) {
            if (byCmd.isEmpty()) Hint(stringResource(R.string.can_none))
            for (e in byCmd.values.sortedBy { it.cmd }) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = CarColors.Accent)) { append("%02X".format(e.cmd)) }
                        append("  ×${e.count}  ")
                        e.data.forEachIndexed { i, b ->
                            if (i > 0) append(' ')
                            val hex = "%02X".format(b.toInt() and 0xFF)
                            if (i in e.changed) withStyle(SpanStyle(color = Color(0xFFFFB74D))) { append(hex) } else append(hex)
                        }
                        if (!e.checksumOk) withStyle(SpanStyle(color = Color(0xFFF44336))) { append("  CS!") }
                    },
                    color = CarColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                e.text?.let { Row { Hint("   $it") } }
            }
        }
        Card(stringResource(R.string.can_stream)) {
            BodyText(
                lines.take(60).joinToString("\n") { "${time.format(Date(it.at))}  ${it.frame}" }.ifEmpty { "—" },
                mono = true,
            )
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.mcu_delete)) },
            text = { Text(id) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.mcu_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Imports a car profile file; returns the message to show. */
fun importCanVehicle(
    context: Context,
    uri: android.net.Uri,
): String =
    try {
        val v = CanVehicleStore.get(context).import(uri)
        context.getString(R.string.mcu_imported, v.name)
    } catch (e: Exception) {
        context.getString(R.string.mcu_import_error, e.message ?: e.javaClass.simpleName)
    }

fun exportCanVehicle(
    context: Context,
    id: String,
    uri: android.net.Uri,
): String =
    try {
        CanVehicleStore.get(context).export(id, uri)
        context.getString(R.string.mcu_exported)
    } catch (e: Exception) {
        context.getString(R.string.mcu_import_error, e.message ?: e.javaClass.simpleName)
    }
