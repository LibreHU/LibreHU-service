package org.librehu.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.can.CarPackIndex
import org.librehu.service.can.CanVehicleStore

/**
 * The packs of LibreHU-car-profiles (cars by make, box types), downloaded and installed by the service itself, as
 * LibreHU Apps' « Cars » tab does ([CarPackIndex]): one row per pack, installed or updated with one touch; a pack
 * this engine cannot read says so instead of offering it.
 */
@Composable
internal fun CanPacksCard() {
    val context = LocalContext.current
    val store = CanVehicleStore.get(context)
    val installed by store.packs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf<List<CarPackIndex.Pack>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        problem = null
        index =
            try {
                withContext(Dispatchers.IO) { CarPackIndex.fetch() }
            } catch (e: Exception) {
                problem = e.message ?: e.javaClass.simpleName
                null
            }
    }
    Card(stringResource(R.string.can_packs)) {
        Hint(stringResource(R.string.can_packs_hint))
        Actions { Pill(stringResource(R.string.can_packs_refresh), icon = Icons.Default.Refresh, enabled = running == null) { reload++ } }
        val list = index
        if (list == null && problem == null) BodyText(stringResource(R.string.can_packs_loading))
        problem?.let { Hint(stringResource(R.string.can_packs_failed, it)) }
        if (running != null) BodyText(stringResource(R.string.can_packs_running))
        result?.let { BodyText(it) }
        for (p in list.orEmpty()) {
            val have = installed[p.id]
            val upToDate = have != null && have.sha256.equals(p.sha256, ignoreCase = true)
            val what =
                if (p.id == CarPackIndex.BOXES) {
                    stringResource(R.string.can_packs_boxes, p.profiles.size)
                } else {
                    stringResource(R.string.can_packs_cars, p.cars)
                }
            val makes = p.makes.take(4).joinToString(", ").ifEmpty { null }?.let { it + if (p.makes.size > 4) "…" else "" }
            val status =
                when {
                    !p.readable -> stringResource(R.string.can_packs_needs_service)
                    have == null -> stringResource(R.string.can_packs_not_installed)
                    upToDate -> stringResource(R.string.can_packs_up_to_date, have.revision)
                    else -> stringResource(R.string.can_packs_update_ready, p.revision)
                }
            ListRow(p.name, listOfNotNull(what, makes, status).joinToString("  ·  ")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.readable && !upToDate) {
                        Pill(
                            stringResource(if (have == null) R.string.can_packs_install else R.string.can_packs_update),
                            selected = true,
                            enabled = running == null,
                            icon = Icons.Default.Download,
                        ) {
                            running = p.id
                            result = null
                            scope.launch {
                                val why = withContext(Dispatchers.IO) { CarPackIndex.install(store, p, list.orEmpty()) }
                                result =
                                    if (why == null) context.getString(R.string.can_packs_ok, p.name) else context.getString(R.string.can_packs_failed, why)
                                running = null
                            }
                        }
                    }
                    if (have != null) {
                        Pill(stringResource(R.string.can_packs_remove), enabled = running == null, icon = Icons.Default.Delete) {
                            // The packs read again (hundreds of profiles): off the main thread.
                            scope.launch(Dispatchers.IO) { store.removePack(p.id) }
                            result = null
                        }
                    }
                }
            }
        }
    }
}
