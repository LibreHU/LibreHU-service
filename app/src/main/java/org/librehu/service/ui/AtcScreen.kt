package org.librehu.service.ui

import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.atc.AtcTools
import org.librehu.service.atc.CameraFormat
import org.librehu.service.atc.Mtz

/** Autochips features of the stock ROM: hidden tools, USB mode, ARM2 fast reverse, boot logo. */
@Composable
fun AtcScreen(actions: AppActions) {
    Page(stringResource(R.string.tab_atc)) {
        AtcToolsCard()
        UsbModeCard()
        FastReverseCard()
        LogoCard(actions)
    }
}

@Composable
private fun AtcToolsCard() {
    val context = LocalContext.current
    val tools =
        listOf(
            AtcTools.ENGINEER_MODE to R.string.atc_engineer,
            AtcTools.ATC_SETTINGS to R.string.atc_settings,
            AtcTools.ATC_LOGGER to R.string.atc_logger,
        )
    var present by remember { mutableStateOf<Set<ComponentName>>(emptySet()) }
    LaunchedEffect(Unit) { present = tools.map { it.first }.filter { AtcTools.installed(context, it) }.toSet() }
    val scope = rememberCoroutineScope()
    Card(stringResource(R.string.atc_tools_title)) {
        Hint(stringResource(R.string.atc_tools_hint))
        Actions {
            for ((c, label) in tools) {
                Pill(stringResource(label), enabled = c in present) {
                    scope.launch(Dispatchers.IO) { AtcTools.launch(context, c) }
                }
            }
        }
    }
}

@Composable
private fun UsbModeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val noRoot = stringResource(R.string.atc_no_root)
    LaunchedEffect(Unit) {
        when (val r = withContext(Dispatchers.IO) { AtcTools.read(context, listOf(Mtz.USB_MODE)) }) {
            is AtcTools.Result.Values -> mode = r.values[Mtz.USB_MODE]?.and(15)
            is AtcTools.Result.Error -> status = r.message
            AtcTools.Result.NoRoot -> status = noRoot
        }
    }
    val done = stringResource(R.string.atc_usb_done)
    Card(stringResource(R.string.atc_usb_title)) {
        StatusLine(
            when (mode) {
                2 -> stringResource(R.string.atc_usb_host)
                1 -> stringResource(R.string.atc_usb_device)
                null -> stringResource(R.string.atc_reading)
                else -> stringResource(R.string.atc_usb_unknown, mode ?: 0)
            },
            mode?.let { it == 1 || it == 2 },
        )
        Choices(
            listOf(2 to stringResource(R.string.atc_usb_host_short), 1 to stringResource(R.string.atc_usb_device_short)),
            mode ?: -1,
        ) { m ->
            if (busy) return@Choices
            busy = true
            scope.launch {
                val err = withContext(Dispatchers.IO) { AtcTools.setUsbMode(context, m == 2) }
                if (err == null) mode = m
                status =
                    if (err == null) {
                        done
                    } else if (err == "root") {
                        noRoot
                    } else {
                        err
                    }
                busy = false
            }
        }
        status?.let { Hint(it) }
        Hint(stringResource(R.string.atc_usb_hint))
    }
}

/** Metazone settings of the ARM2 fast reverse (before Android), then sent to the ARM2. */
@Composable
private fun FastReverseCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf<Map<Int, Int>?>(null) }
    var edited by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmArm2 by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    var formatChanged by remember { mutableStateOf(false) }
    var savedFormat by remember { mutableStateOf(false) }
    val noRoot = stringResource(R.string.atc_no_root)
    val saved = stringResource(R.string.atc_saved)
    val applied = stringResource(R.string.atc_arm2_done)

    fun load() =
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { AtcTools.read(context, Mtz.FAST_REVERSE) }) {
                is AtcTools.Result.Values -> {
                    values = r.values
                    edited = emptyMap()
                    status = null
                }

                is AtcTools.Result.Error -> {
                    status = r.message
                }

                AtcTools.Result.NoRoot -> {
                    status = noRoot
                }
            }
        }
    LaunchedEffect(Unit) { load() }

    fun v(a: Int) = edited[a] ?: values?.get(a) ?: 0

    fun set(
        a: Int,
        x: Int,
    ) {
        edited = edited + (a to x)
    }

    Card(stringResource(R.string.atc_fr_title)) {
        Hint(stringResource(R.string.atc_fr_hint))
        status?.let { StatusLine(it, null) }
        if (values != null) {
            BodyText(stringResource(R.string.atc_fr_format))
            val format = CameraFormat.current(values!! + edited)
            Choices(
                listOf(
                    0 to "CVBS",
                    1 to "AHD 720p 25 i/s",
                    2 to "AHD 720p 30 i/s",
                    3 to "AHD 1080p 25 i/s",
                    4 to "AHD 1080p 30 i/s",
                ),
                format ?: -1,
            ) { i ->
                edited = edited + CameraFormat.values(i)
                formatChanged = true
            }
            if (format == null) Hint(stringResource(R.string.atc_fr_format_other))
            Hint(stringResource(R.string.atc_fr_format_hint))
            SwitchRow(stringResource(R.string.atc_fr_raw), raw) { raw = it }
            if (raw) {
                BodyText(stringResource(R.string.atc_fr_source))
                Choices(
                    listOf(
                        1 to "CVBS",
                        2 to stringResource(R.string.atc_fr_source_mipi),
                        3 to stringResource(R.string.atc_fr_source_avm),
                        0 to stringResource(R.string.atc_fr_source_debug),
                    ),
                    v(Mtz.SOURCE),
                ) { set(Mtz.SOURCE, it) }
                val modes = listOf("Auto", "NTSC", "PAL", "720p25", "720p30", "1080p25", "1080p30").mapIndexed { i, l -> i to l }
                BodyText(stringResource(R.string.atc_fr_ahd))
                Choices(modes, v(Mtz.AHD_MODE)) { set(Mtz.AHD_MODE, it) }
                BodyText(stringResource(R.string.atc_fr_avm))
                Choices(modes, v(Mtz.AVM_MODE)) { set(Mtz.AVM_MODE, it) }
            }
            // AHD (sources 2 and 3) uses the MIPI mirror and crop, as Jancar writes them.
            val mipi = v(Mtz.SOURCE) == 2 || v(Mtz.SOURCE) == 3
            val mirrorAddr = if (mipi) Mtz.MIPI_MIRROR else Mtz.CVBS_MIRROR
            val mirror = v(mirrorAddr)
            SwitchRow(stringResource(R.string.rv_mirror), mirror and 1 != 0) { on ->
                set(mirrorAddr, (mirror and 2) or (if (on) 1 else 0))
            }
            SwitchRow(stringResource(R.string.rv_flip), mirror and 2 != 0) { on ->
                set(mirrorAddr, (mirror and 1) or (if (on) 2 else 0))
            }
            BodyText(stringResource(R.string.atc_fr_crop))
            val crop = if (mipi) Mtz.MIPI_CROP else Mtz.CVBS_CROP
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                for ((i, label) in listOf("x", "y", "w", "h").withIndex()) {
                    NumberField(label, v(crop + i), Modifier.weight(1f)) { set(crop + i, it) }
                }
            }
            SwitchRow(stringResource(R.string.atc_fr_sharpness), v(Mtz.SHARPNESS) == 1) { set(Mtz.SHARPNESS, if (it) 1 else 0) }
            SwitchRow(stringResource(R.string.atc_fr_dither), v(Mtz.DITHER) == 1) { set(Mtz.DITHER, if (it) 1 else 0) }
            SwitchRow(stringResource(R.string.atc_fr_fast_avm), v(Mtz.FAST_AVM) == 1) { set(Mtz.FAST_AVM, if (it) 1 else 0) }
            SwitchRow(stringResource(R.string.atc_fr_cvbs_avm), v(Mtz.CVBS_AVM) == 1) { set(Mtz.CVBS_AVM, if (it) 1 else 0) }
            SwitchRow(stringResource(R.string.atc_fr_rear_display), v(Mtz.REAR_DISPLAY) == 1) {
                set(Mtz.REAR_DISPLAY, if (it) 1 else 0)
            }
        }
        Actions {
            Pill(stringResource(R.string.atc_reload)) { load() }
            Pill(stringResource(R.string.atc_save), enabled = edited.isNotEmpty()) {
                val changes = edited.filter { (a, x) -> values?.get(a) != x }
                scope.launch {
                    val err = withContext(Dispatchers.IO) { AtcTools.write(context, changes) }
                    if (err == null) {
                        values = values.orEmpty() + changes
                        edited = emptyMap()
                        if (formatChanged) savedFormat = true
                        formatChanged = false
                    }
                    status =
                        if (err == null) {
                            saved
                        } else if (err == "root") {
                            noRoot
                        } else {
                            err
                        }
                }
            }
            Pill(stringResource(R.string.atc_arm2), enabled = values != null && edited.isEmpty()) { confirmArm2 = true }
            if (savedFormat) {
                Pill(stringResource(R.string.atc_reboot)) { scope.launch(Dispatchers.IO) { AtcTools.reboot() } }
            }
        }
        if (savedFormat) Hint(stringResource(R.string.atc_reboot_needed))
    }
    if (confirmArm2) {
        AlertDialog(
            onDismissRequest = { confirmArm2 = false },
            title = { Text(stringResource(R.string.atc_arm2)) },
            text = { Text(stringResource(R.string.atc_arm2_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmArm2 = false
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { AtcTools.applyArm2(context) }
                        status =
                            if (err == null) {
                                applied
                            } else if (err == "root") {
                                noRoot
                            } else {
                                err
                            }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmArm2 = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onChange)
        },
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun LogoCard(actions: AppActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by AtcLogoState.status
    var confirm by remember { mutableStateOf(false) }
    Card(stringResource(R.string.atc_logo_title)) {
        Hint(stringResource(R.string.atc_logo_hint))
        status?.let { StatusLine(it.first, it.second) }
        Actions {
            Pill(stringResource(R.string.atc_logo_backup)) {
                AtcLogoState.status.value = context.getString(R.string.atc_working) to null
                scope.launch {
                    val path = withContext(Dispatchers.IO) { AtcTools.backupLogo() }
                    AtcLogoState.status.value =
                        if (path != null) {
                            context.getString(R.string.atc_logo_backup_done, path) to true
                        } else {
                            context.getString(R.string.atc_logo_backup_failed) to false
                        }
                }
            }
            Pill(stringResource(R.string.atc_logo_pick)) { confirm = true }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.atc_logo_title)) },
            text = { Text(stringResource(R.string.atc_logo_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    actions.pickLogo()
                }) { Text(stringResource(R.string.atc_logo_pick)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Result of the last logo operation, shown by [LogoCard] (the picture is chosen in the activity). */
object AtcLogoState {
    val status = mutableStateOf<Pair<String, Boolean?>?>(null)
}
