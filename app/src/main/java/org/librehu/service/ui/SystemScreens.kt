package org.librehu.service.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
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
import org.librehu.core.mcu.McuProfiles
import org.librehu.core.mcu.ProfileException
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.display.DarkMode
import org.librehu.service.display.DisplayController
import org.librehu.service.mcu.ProfileStore

// --- Display -----------------------------------------------------------------------------------------------------

@Composable
fun DisplayScreen(actions: AppActions) {
    val display = DisplayController.get(LocalContext.current)
    val s by display.settings.collectAsStateWithLifecycle()
    val lights by display.headlights.collectAsStateWithLifecycle()
    val darkAllowed by display.darkModeAllowed.collectAsStateWithLifecycle()
    val brightnessAllowed by display.brightnessAllowed.collectAsStateWithLifecycle()
    Page(stringResource(R.string.tab_display)) {
        Card {
            StatusLine(
                stringResource(
                    when (lights) {
                        true -> R.string.display_lights_on
                        false -> R.string.display_lights_off
                        null -> R.string.display_lights_unknown
                    },
                ),
                lights?.let { !it },
            )
        }
        Card(stringResource(R.string.display_dark)) {
            Choices(
                listOf(
                    DarkMode.UNCHANGED to stringResource(R.string.display_dark_unchanged),
                    DarkMode.LIGHT to stringResource(R.string.display_dark_light),
                    DarkMode.DARK to stringResource(R.string.display_dark_dark),
                    DarkMode.HEADLIGHTS to stringResource(R.string.display_dark_lights),
                ),
                s.darkMode,
            ) { m -> display.update { it.copy(darkMode = m) } }
            Hint(stringResource(R.string.display_dark_hint))
            if (!darkAllowed) Hint(stringResource(R.string.display_dark_denied))
        }
        Card(stringResource(R.string.display_brightness)) {
            SwitchRow(stringResource(R.string.display_dim), s.dimWithHeadlights, stringResource(R.string.display_dim_hint)) { on ->
                display.update { it.copy(dimWithHeadlights = on) }
            }
            SliderRow(stringResource(R.string.display_day), s.dayBrightness, 10..255, {
                "${it * 100 / 255} %"
            }) { v -> display.update { it.copy(dayBrightness = v) } }
            SliderRow(stringResource(R.string.display_night), s.nightBrightness, 10..255, {
                "${it * 100 / 255} %"
            }) { v -> display.update { it.copy(nightBrightness = v) } }
            if (!brightnessAllowed || !display.canWriteSettings()) {
                Hint(stringResource(R.string.display_write_denied))
                Actions { Pill(stringResource(R.string.display_grant), onClick = actions.openWriteSettings) }
            }
        }
    }
}

// --- MCU ---------------------------------------------------------------------------------------------------------

@Composable
fun McuScreen(actions: AppActions) {
    val context = LocalContext.current
    val store = ProfileStore.get(context)
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val selected by store.selected.collectAsStateWithLifecycle()
    val link by ServiceState.link.collectAsStateWithLifecycle()
    var forced by remember { mutableStateOf(LibreHuService.prefs(context).getBoolean(LibreHuService.PREF_FORCE, false)) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Page(stringResource(R.string.tab_mcu)) {
        Card {
            StatusLine(
                linkLabel(link.state) + if (link.detail.isNotEmpty()) " — ${link.detail}" else "",
                link.state == LibreHuService.Link.RUNNING,
            )
            if (link.protocol.isNotEmpty()) Hint(stringResource(R.string.home_protocol, link.protocol))
            SwitchRow(stringResource(R.string.force_start), forced, stringResource(R.string.mcu_force_hint)) { on ->
                forced = on
                LibreHuService
                    .prefs(context)
                    .edit()
                    .putBoolean(LibreHuService.PREF_FORCE, on)
                    .apply()
                actions.restartLink()
            }
            Actions { Pill(stringResource(R.string.restart), onClick = actions.restartLink) }
        }
        Card(stringResource(R.string.mcu_profiles)) {
            Hint(stringResource(R.string.mcu_profiles_hint))
            for (p in profiles) {
                val builtIn = store.isBuiltIn(p.id)
                ListRow(
                    p.name,
                    listOfNotNull(
                        p.id,
                        "${p.serial.port} @ ${p.serial.baud}",
                        if (builtIn) stringResource(R.string.mcu_builtin) else null,
                        p.description.ifEmpty { null },
                    ).joinToString("  ·  "),
                    onClick = { store.select(p.id) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(
                            stringResource(
                                if (p.id ==
                                    selected
                                ) {
                                    R.string.mcu_selected
                                } else {
                                    R.string.mcu_select
                                },
                            ),
                            selected = p.id == selected,
                        ) { store.select(p.id) }
                        Pill("", icon = Icons.Default.FileUpload) { actions.exportProfile(p.id) }
                        if (!builtIn) Pill(stringResource(R.string.mcu_delete)) { confirmDelete = p.id }
                    }
                }
            }
            Actions {
                Pill(stringResource(R.string.mcu_import), icon = Icons.Default.FileDownload, onClick = actions.importProfile)
                if (selected != link.protocolId(profiles)) Pill(stringResource(R.string.mcu_apply), onClick = actions.restartLink)
            }
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

/** Id of the profile the running link uses (matched by name), to offer "apply" after another choice. */
private fun ServiceState.Link.protocolId(profiles: List<org.librehu.core.mcu.McuProfile>): String? =
    profiles
        .firstOrNull {
            it.name ==
                protocol
        }?.id

/** Imports a profile file; returns the message to show. */
fun importProfile(
    context: Context,
    uri: android.net.Uri,
): String =
    try {
        val p = ProfileStore.get(context).import(uri)
        context.getString(R.string.mcu_imported, p.name)
    } catch (e: ProfileException) {
        context.getString(R.string.mcu_import_error, e.message)
    } catch (e: Exception) {
        context.getString(R.string.mcu_import_error, e.message ?: e.javaClass.simpleName)
    }

fun exportProfile(
    context: Context,
    id: String,
    uri: android.net.Uri,
): String =
    try {
        ProfileStore.get(context).export(id, uri)
        context.getString(R.string.mcu_exported)
    } catch (e: Exception) {
        context.getString(R.string.mcu_import_error, e.message ?: e.javaClass.simpleName)
    }

fun profileFileName(id: String) = "$id.${McuProfiles.FILE_EXTENSION}"

// --- Diagnostics -------------------------------------------------------------------------------------------------

@Composable
fun DiagScreen(
    client: ServiceClient,
    actions: AppActions,
) {
    val link by ServiceState.link.collectAsStateWithLifecycle()
    val v by ServiceState.vehicle.collectAsStateWithLifecycle()
    val traffic by ServiceState.traffic.collectAsStateWithLifecycle()
    val log by ServiceState.log.collectAsStateWithLifecycle()
    var paused by remember { mutableStateOf(ServiceState.trafficPaused) }
    var raw by remember { mutableStateOf("") }
    var rawError by remember { mutableStateOf(false) }
    Page(stringResource(R.string.tab_diag)) {
        Card(stringResource(R.string.diag_state)) {
            BodyText(
                listOf(
                    "link=${link.state} ${link.detail}",
                    "protocol=${link.protocol}",
                    "api=${client.api != null} bt=${client.bt != null}",
                    "vehicle=$v",
                ).joinToString("\n"),
                mono = true,
            )
            Actions { Pill(stringResource(R.string.restart), onClick = actions.restartLink) }
        }
        Card(stringResource(R.string.diag_send)) {
            Hint(stringResource(R.string.diag_send_hint))
            OutlinedTextField(value = raw, onValueChange = {
                raw = it
                rawError = false
            }, singleLine = true, isError = rawError, label = { Text("1F 01") })
            Actions {
                Pill(stringResource(R.string.diag_send_button), enabled = link.state == LibreHuService.Link.RUNNING) {
                    try {
                        val f = McuProfiles.template(raw, emptyMap(), ByteArray(0))
                        client.call { it.sendMcuFrame(f.cmd, f.data) }
                    } catch (_: Exception) {
                        rawError = true
                    }
                }
            }
        }
        Card(stringResource(R.string.section_traffic)) {
            Actions {
                Pill(stringResource(if (paused) R.string.diag_resume else R.string.diag_pause), selected = paused) {
                    paused = !paused
                    ServiceState.trafficPaused = paused
                }
                Pill(stringResource(R.string.diag_clear)) { ServiceState.clearTraffic() }
            }
            BodyText(traffic.take(80).joinToString("\n").ifEmpty { "—" }, mono = true)
        }
        Card(stringResource(R.string.diag_log)) {
            BodyText(log.take(80).joinToString("\n").ifEmpty { "—" }, mono = true)
        }
    }
}
