package org.librehu.service.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.OutlinedTextField
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
import org.librehu.core.obd.ObdCars
import org.librehu.service.R
import org.librehu.service.obd.ObdCarStore
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdState

/**
 * Manufacturer values (Renault and others) read through the ELM327 from a car file: a ddt4all ECU file, or a LibreHU
 * car file shared by someone. The values to read are picked by name; read requests only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ObdCarCards(
    st: ObdState,
    actions: AppActions,
) {
    val context = LocalContext.current
    val store = ObdCarStore.get(context)
    val cars by store.cars.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val chosen by store.chosen.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val car = cars.firstOrNull { it.id == selected }

    Ddt4allCard(st)

    Card(stringResource(R.string.obd_car)) {
        Hint(stringResource(R.string.obd_car_hint))
        Choices(listOf("" to stringResource(R.string.obd_car_none)) + cars.map { it.id to it.name }, selected) { store.select(it) }
        Actions {
            Pill(stringResource(R.string.obd_car_import), icon = Icons.Default.FileDownload) { actions.importObdCar(false) }
            if (car != null) {
                Pill(stringResource(R.string.obd_car_add_ecu), icon = Icons.Default.FileDownload) { actions.importObdCar(true) }
                Pill(stringResource(R.string.obd_car_export), icon = Icons.Default.FileUpload) { actions.exportObdCar(car.id) }
                Pill(stringResource(R.string.mcu_delete)) { confirmDelete = car.id }
            }
        }
        if (car != null) {
            if (car.description.isNotEmpty()) BodyText(car.description)
            for (ecu in car.ecus) {
                ListRow(ecu.name, stringResource(R.string.obd_car_ecu, ecu.sendId, ecu.recvId, ecu.requests.sumOf { it.items.size }))
            }
            if (car.elmInit.isNotEmpty()) Hint(stringResource(R.string.obd_car_elm, car.elmInit.joinToString(" ")))
        }
    }

    if (car != null) {
        Card(stringResource(R.string.obd_car_values)) {
            if (st.carMessage.isNotEmpty()) StatusLine(st.carMessage, false)
            val readings = chosen.mapNotNull { st.car[it] }.sortedBy { it.key }
            if (st.connection == ObdConnection.CONNECTED && readings.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in readings) Tile(r.name, r.text, alert = r.error)
                }
            }
            Hint(stringResource(R.string.obd_car_values_hint))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.obd_car_search)) },
            )
            val all = car.items()
            val q = query.trim()
            val shown =
                all
                    .filter { (e, _, i) ->
                        ObdCars.key(e, i) in chosen ||
                            (q.length >= 2 && (i.name.contains(q, ignoreCase = true) || e.name.contains(q, ignoreCase = true)))
                    }.take(MAX_SHOWN)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((e, _, i) in shown) {
                    val key = ObdCars.key(e, i)
                    val label = if (car.ecus.size > 1) "${e.name} · ${i.name}" else i.name
                    Pill(label + if (i.unit.isNotEmpty()) " (${i.unit})" else "", selected = key in chosen) { store.toggle(key) }
                }
            }
            Hint(stringResource(R.string.obd_car_count, chosen.size, all.size))
        }
    }

    confirmDelete?.let { id ->
        CarAlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.mcu_delete)) },
            text = { Text(cars.firstOrNull { it.id == id }?.name ?: id) },
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

private const val MAX_SHOWN = 60

/** Imports a car file (off the main thread: ddt4all files reach megabytes); returns the message to show. */
fun importObdCar(
    context: Context,
    uri: Uri,
    intoSelected: Boolean,
): String =
    try {
        val car = ObdCarStore.get(context).import(uri, intoSelected)
        context.getString(R.string.obd_car_imported, car.name, car.items().size)
    } catch (e: Exception) {
        context.getString(R.string.obd_car_import_error, e.message ?: e.javaClass.simpleName)
    }

fun exportObdCar(
    context: Context,
    id: String,
    uri: Uri,
): String =
    try {
        ObdCarStore.get(context).export(id, uri)
        context.getString(R.string.mcu_exported)
    } catch (e: Exception) {
        context.getString(R.string.obd_car_import_error, e.message ?: e.javaClass.simpleName)
    }
