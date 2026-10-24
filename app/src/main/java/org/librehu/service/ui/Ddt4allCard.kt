package org.librehu.service.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.core.obd.Ddt4allDb
import org.librehu.service.R
import org.librehu.service.obd.ObdCarStore
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdManager
import org.librehu.service.obd.ObdState

/**
 * ddt4all's whole ECU database (its `ecu.zip`) imported once: the car is searched by name in its vehicles, then one
 * ECU file is kept per ECU of that vehicle, identified on the car through the adapter (read requests only) or picked
 * by hand, and the car is made from them ([ObdCarStore.createFromDatabase]).
 */
@Composable
internal fun Ddt4allCard(st: ObdState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = ObdCarStore.get(context)
    val obd = ObdManager.get(context)
    val projects by store.projects.collectAsStateWithLifecycle()
    val idents by obd.idents.collectAsStateWithLifecycle()
    val identifying by obd.identifying.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    // Address → file kept for the car (absent = that ECU is left out).
    var files by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(store.downloadUrl()) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.loadDatabase() } }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            message = context.getString(R.string.ddt_importing)
            scope.launch {
                message =
                    withContext(Dispatchers.IO) {
                        runCatching { store.importDatabase(uri) }.fold(
                            { context.getString(R.string.ddt_imported, it.size) },
                            { context.getString(R.string.obd_car_import_error, it.message ?: it.javaClass.simpleName) },
                        )
                    }
                code = null
                busy = false
            }
        }
    val download = {
        busy = true
        message = context.getString(R.string.ddt_downloading, "0")
        scope.launch {
            message =
                withContext(Dispatchers.IO) {
                    runCatching {
                        store.downloadDatabase(url) { done, total ->
                            val mb = String.format(java.util.Locale.ROOT, "%.0f", done / 1e6)
                            message =
                                if (total > 0) {
                                    context.getString(R.string.ddt_downloading, "$mb / ${total / 1_000_000}")
                                } else {
                                    context.getString(R.string.ddt_downloading, mb)
                                }
                        }
                    }.fold(
                        { context.getString(R.string.ddt_imported, it.size) },
                        { context.getString(R.string.obd_car_import_error, it.message ?: it.javaClass.simpleName) },
                    )
                }
            code = null
            busy = false
        }
    }
    val project = projects?.firstOrNull { it.code == code }
    // An identification picks the matching file of each ECU found.
    LaunchedEffect(idents, code) {
        val p = project ?: return@LaunchedEffect
        val found = idents.mapNotNull { (address, ident) -> ident?.let { Ddt4allDb.match(p.ecus[address].orEmpty(), it)?.first?.let { f -> address to f.file } } }
        if (found.isNotEmpty()) files = files + found
    }

    Card(stringResource(R.string.ddt_title)) {
        Hint(stringResource(R.string.ddt_hint))
        val list = projects
        if (list == null) {
            Actions {
                Pill(stringResource(R.string.ddt_import), enabled = !busy, icon = Icons.Default.FileDownload) {
                    open.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
            }
        } else {
            Hint(stringResource(R.string.ddt_count, list.size))
            if (project == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ddt_search)) },
                )
                val q = query.trim()
                val shown = list.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true) }.take(MAX_CARS)
                for (p in shown) {
                    ListRow(p.name, stringResource(R.string.ddt_car, p.code, p.ecus.size), onClick = {
                        code = p.code
                        files = emptyMap()
                    })
                }
            } else {
                ListRow(project.name, stringResource(R.string.ddt_car, project.code, project.ecus.size), onClick = { code = null })
                Hint(stringResource(R.string.ddt_pick_hint))
                for ((address, candidates) in project.ecus) {
                    val group = candidates.first().group.ifEmpty { address }
                    val ident = idents[address]
                    val state =
                        when {
                            address !in idents -> ""
                            ident == null -> " · " + stringResource(R.string.ddt_no_answer)
                            else -> " · " + stringResource(R.string.ddt_found, ident.supplier.trim(), ident.soft, ident.version)
                        }
                    BodyText("$group ($address)$state")
                    Choices(
                        listOf("" to stringResource(R.string.ddt_leave_out)) + candidates.map { it.file to it.name.ifEmpty { it.file.removeSuffix(".json") } },
                        files[address].orEmpty(),
                    ) { f -> files = if (f.isEmpty()) files - address else files + (address to f) }
                }
                Actions {
                    Pill(
                        stringResource(if (identifying) R.string.ddt_identifying else R.string.ddt_identify),
                        enabled = !busy && !identifying && st.connection == ObdConnection.CONNECTED,
                    ) {
                        busy = true
                        scope.launch {
                            val targets =
                                withContext(Dispatchers.IO) {
                                    project.ecus.mapNotNull { (address, c) -> store.canIds(c.first().file)?.let { address to it } }.toMap()
                                }
                            obd.identify(targets)
                            busy = false
                        }
                    }
                    Pill(stringResource(R.string.ddt_create, files.size), enabled = !busy && files.isNotEmpty()) {
                        busy = true
                        scope.launch {
                            message =
                                withContext(Dispatchers.IO) {
                                    runCatching { store.createFromDatabase(project, files) }.fold(
                                        { context.getString(R.string.obd_car_imported, it.name, it.items().size) },
                                        { context.getString(R.string.obd_car_import_error, it.message ?: it.javaClass.simpleName) },
                                    )
                                }
                            busy = false
                        }
                    }
                }
                if (st.connection != ObdConnection.CONNECTED) Hint(stringResource(R.string.ddt_connect_first))
            }
            Actions {
                Pill(stringResource(R.string.ddt_replace), enabled = !busy, icon = Icons.Default.FileDownload) {
                    open.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
                Pill(stringResource(R.string.ddt_delete), enabled = !busy) {
                    store.deleteDatabase()
                    code = null
                }
            }
        }
        // Download: ddt4all's releases do not carry ecu.zip, the address is the user's (kept)
        if (project == null) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                singleLine = true,
                label = { Text(stringResource(R.string.ddt_url)) },
            )
            Hint(stringResource(R.string.ddt_url_hint))
            Actions {
                Pill(
                    stringResource(if (list == null) R.string.ddt_download else R.string.ddt_download_again),
                    enabled = !busy && url.startsWith("http"),
                    icon = Icons.Default.FileDownload,
                ) { download() }
            }
        }
        if (message.isNotEmpty()) StatusLine(message, null)
    }
}

private const val MAX_CARS = 40
