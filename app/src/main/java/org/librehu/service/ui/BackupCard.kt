package org.librehu.service.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.librehu.service.R
import org.librehu.service.backup.ConfigBackup
import java.io.File

/**
 * Settings of every LibreHU app (and forks) in one file, and back ([ConfigBackup]): apps ticked, export to
 * Download/LibreHU; import of an archive, its apps ticked, restore (root).
 */
@Composable
internal fun BackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<ConfigBackup.App>>(emptyList()) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // Archive chosen for a restore: its copy, its manifest's apps and the ones ticked
    var archive by remember { mutableStateOf<File?>(null) }
    var archiveApps by remember { mutableStateOf<List<ConfigBackup.App>>(emptyList()) }
    var restorePicked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val noRoot = stringResource(R.string.atc_no_root)

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { ConfigBackup.apps(context) }
        picked = apps.map { it.pkg }.toSet()
    }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            busy = true
            scope.launch {
                val r =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val f = File(context.cacheDir, "restore.tar")
                            context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                            f to ConfigBackup.inspect(f).getOrThrow()
                        }
                    }
                r
                    .onSuccess { (f, manifest: JSONObject) ->
                        archive = f
                        archiveApps = ConfigBackup.appsOf(context, manifest)
                        restorePicked = archiveApps.filter { it.installed }.map { it.pkg }.toSet()
                        message = context.getString(R.string.backup_archive, manifest.optString("device"), archiveApps.size)
                    }.onFailure { message = context.getString(R.string.backup_bad_file, it.message ?: "") }
                busy = false
            }
        }

    Card(stringResource(R.string.backup_title)) {
        Hint(stringResource(R.string.backup_hint))
        if (archive == null) {
            for (a in apps) {
                SwitchRow(a.label, a.pkg in picked, "${a.pkg} ${a.version}") { on -> picked = if (on) picked + a.pkg else picked - a.pkg }
            }
            Actions {
                Pill(stringResource(R.string.backup_export), enabled = !busy && picked.isNotEmpty()) {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { ConfigBackup.export(context, apps.map { it.pkg }.filter { it in picked }) }
                        message =
                            r.fold(
                                { context.getString(R.string.backup_done, it.removePrefix("/sdcard/")) },
                                { if (it.message == "root") noRoot else context.getString(R.string.backup_failed, it.message ?: "") },
                            )
                        busy = false
                    }
                }
                Pill(stringResource(R.string.backup_import), enabled = !busy) { open.launch(arrayOf("application/x-tar", "application/octet-stream", "*/*")) }
            }
        } else {
            Hint(stringResource(R.string.backup_restore_hint))
            for (a in archiveApps) {
                SwitchRow(
                    a.label,
                    a.pkg in restorePicked,
                    a.pkg + " " + a.version + if (!a.installed) " · " + stringResource(R.string.backup_not_installed) else "",
                    enabled = a.installed,
                ) { on -> restorePicked = if (on) restorePicked + a.pkg else restorePicked - a.pkg }
            }
            Actions {
                Pill(stringResource(R.string.backup_restore), enabled = !busy && restorePicked.isNotEmpty()) {
                    val f = archive ?: return@Pill
                    busy = true
                    scope.launch {
                        val chosen = archiveApps.map { it.pkg }.filter { it in restorePicked }
                        val r = withContext(Dispatchers.IO) { ConfigBackup.restore(context, f, chosen) }
                        message =
                            r.fold(
                                {
                                    context.getString(R.string.backup_restored, it.size) +
                                        if ("org.librehu.service" in chosen) " " + context.getString(R.string.backup_restored_self) else ""
                                },
                                { if (it.message == "root") noRoot else context.getString(R.string.backup_failed, it.message ?: "") },
                            )
                        busy = false
                    }
                }
                Pill(stringResource(android.R.string.cancel), enabled = !busy) {
                    archive?.delete()
                    archive = null
                    message = ""
                }
            }
        }
        if (message.isNotEmpty()) Hint(message)
    }
}
