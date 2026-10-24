package org.librehu.service.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.tweaks.TweakSettings
import org.librehu.service.tweaks.Tweaks
import java.util.Locale

/** Kernel tuning (root): CPU, GPU, storage, memory, network, temperatures; applied at once, and at start-up if asked. */
@Composable
fun TweaksScreen() {
    val context = LocalContext.current
    val tweaks = remember { Tweaks.get(context) }
    val scope = rememberCoroutineScope()
    val st by tweaks.state.collectAsStateWithLifecycle()
    val s by tweaks.settings.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf<Boolean?>(null) }
    val noRoot = stringResource(R.string.atc_no_root)
    LaunchedEffect(Unit) {
        // Current frequencies and temperatures every few seconds while the page is shown.
        while (true) {
            loaded = withContext(Dispatchers.IO) { tweaks.refresh() } != null
            delay(3000)
        }
    }

    fun set(transform: (TweakSettings) -> TweakSettings) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { tweaks.update(transform) }
            status = if (err == null) null else if (err == "root") noRoot else err
        }
    }

    fun mhz(khz: Int) = if (khz <= 0) "—" else "${khz / 1000} MHz"

    Page(stringResource(R.string.tab_tweaks)) {
        CompileCard()
        ZygoteCard()
        Card(stringResource(R.string.tw_title)) {
            if (loaded == false) StatusLine(noRoot, false)
            if (tweaks.skippedAtBoot) {
                StatusLine(stringResource(R.string.tw_skipped), false)
                Actions { Pill(stringResource(R.string.tw_skipped_ok)) { tweaks.clearSkipped() } }
            }
            SwitchRow(stringResource(R.string.tw_boot), s.applyAtBoot, stringResource(R.string.tw_boot_hint)) { on -> set { it.copy(applyAtBoot = on) } }
            val boost = remember { org.librehu.service.tweaks.TouchBoost.get(context) }
            val boostOn by boost.enabled.collectAsStateWithLifecycle()
            SwitchRow(stringResource(R.string.tw_touch_boost), boostOn, stringResource(R.string.tw_touch_boost_hint)) { boost.setEnabled(it) }
            status?.let { Hint(it) }
            Actions {
                Pill(stringResource(R.string.tw_factory)) {
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { tweaks.restoreFactory() }
                        status = if (err == null) null else if (err == "root") noRoot else err
                    }
                }
            }
            Hint(stringResource(R.string.tw_hint))
        }
        for (p in st.cpu) {
            Card(stringResource(R.string.tw_cpu, p.cpus.ifEmpty { p.id })) {
                StatusLine(stringResource(R.string.tw_cpu_now, mhz(p.curKhz), p.governor, mhz(p.minKhz), mhz(p.maxKhz)), null)
                if (p.governors.isNotEmpty()) {
                    BodyText(stringResource(R.string.tw_governor))
                    Choices(p.governors.map { it to it }, p.governor) { g -> set { it.copy(governor = it.governor + (p.id to g)) } }
                }
                if (p.freqs.isNotEmpty()) {
                    BodyText(stringResource(R.string.tw_min))
                    Choices(p.freqs.map { it to mhz(it) }, p.minKhz) { f -> set { it.copy(minKhz = it.minKhz + (p.id to f)) } }
                    BodyText(stringResource(R.string.tw_max))
                    Choices(p.freqs.map { it to mhz(it) }, p.maxKhz) { f -> set { it.copy(maxKhz = it.maxKhz + (p.id to f)) } }
                }
            }
        }
        if (st.gpuOpps.isNotEmpty()) {
            Card(stringResource(R.string.tw_gpu)) {
                Choices(
                    listOf(0 to stringResource(R.string.tw_gpu_auto)) + st.gpuOpps.map { it to mhz(it) },
                    st.gpuKeptKhz,
                ) { f -> set { it.copy(gpuKhz = f) } }
                Hint(stringResource(R.string.tw_gpu_hint))
            }
        }
        if (st.ioDevice.isNotEmpty()) {
            Card(stringResource(R.string.tw_io, st.ioDevice)) {
                BodyText(stringResource(R.string.tw_io_scheduler))
                Choices(st.ioSchedulers.map { it to it }, st.ioScheduler) { v -> set { it.copy(ioScheduler = v) } }
                BodyText(stringResource(R.string.tw_read_ahead))
                Choices(listOf(128, 256, 512, 1024, 2048).map { it to "$it kB" }, st.readAheadKb) { v -> set { it.copy(readAheadKb = v) } }
            }
        }
        Card(stringResource(R.string.tw_memory_network)) {
            if (st.swappiness >= 0) {
                BodyText(stringResource(R.string.tw_swappiness))
                Choices(listOf(0, 20, 40, 60, 80, 100).map { it to "$it" }, st.swappiness) { v -> set { it.copy(swappiness = v) } }
            }
            if (st.tcpAvailable.isNotEmpty()) {
                BodyText(stringResource(R.string.tw_tcp))
                Choices(st.tcpAvailable.map { it to it }, st.tcp) { v -> set { it.copy(tcp = v) } }
            }
        }
        if (st.temps.isNotEmpty()) {
            Card(stringResource(R.string.tw_temps)) {
                for ((type, c) in st.temps) ListRow(type, String.format(Locale.ROOT, "%.1f °C", c))
            }
        }
    }
}

/** Native compilation ([AppCompiler]): the list of apps with their compiler filter, automatic, one or all now. */
@Composable
private fun CompileCard() {
    val context = LocalContext.current
    val compiler = org.librehu.service.perf.AppCompiler.get(context)
    val auto by compiler.auto.collectAsStateWithLifecycle()
    val apps by compiler.apps.collectAsStateWithLifecycle()
    val noRoot by compiler.rootMissing.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { compiler.refresh() }
    Card(stringResource(R.string.aot_title)) {
        SwitchRow(stringResource(R.string.aot_auto), auto, stringResource(R.string.aot_hint)) { compiler.setAuto(it) }
        if (noRoot) StatusLine(stringResource(R.string.atc_no_root), false)
        val native = apps.count { it.filter == "speed" || it.filter == "everything" }
        if (apps.isNotEmpty()) StatusLine(stringResource(R.string.aot_count, native, apps.size), native == apps.size)
        for (a in apps) {
            val state =
                when {
                    a.compiling -> stringResource(R.string.aot_compiling)
                    a.error != null -> stringResource(R.string.aot_failed, a.error)
                    a.filter == null -> stringResource(R.string.aot_unknown)
                    a.filter == "speed" || a.filter == "everything" -> stringResource(R.string.aot_native, a.filter)
                    else -> stringResource(R.string.aot_not_native, a.filter)
                }
            ListRow(a.label, "${a.pkg} · $state") {
                Pill(stringResource(R.string.aot_one), enabled = !a.compiling) { compiler.compileOne(a.pkg) }
                if (a.librehu) {
                    androidx.compose.material3.Switch(a.enabled, { compiler.setEnabled(a.pkg, it) })
                } else {
                    Pill(stringResource(R.string.aot_remove)) { compiler.removeExtra(a.pkg) }
                }
            }
        }
        Hint(stringResource(R.string.aot_list_hint))
        Actions {
            Pill(stringResource(R.string.aot_now)) { compiler.compileAll() }
            Pill(stringResource(R.string.aot_add)) { adding = true }
            Pill(stringResource(R.string.aot_refresh)) { compiler.refresh() }
        }
    }
    if (adding) AddAppDialog(compiler) { adding = false }
}

/** Installed apps not in the list yet, with a search field: the one chosen is added (and compiled). */
@Composable
private fun AddAppDialog(
    compiler: org.librehu.service.perf.AppCompiler,
    onDismiss: () -> Unit,
) {
    var all by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { compiler.candidates() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text(stringResource(R.string.aot_add)) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    singleLine = true,
                    label = { androidx.compose.material3.Text(stringResource(R.string.aot_search)) },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                )
                val list =
                    all.orEmpty().filter { (pkg, label) ->
                        filter.isBlank() || label.contains(filter.trim(), true) || pkg.contains(filter.trim(), true)
                    }
                androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.heightIn(max = 420.dp)) {
                    items(list.size) { i ->
                        val (pkg, label) = list[i]
                        ListRow(label, pkg, onClick = {
                            compiler.addExtra(pkg)
                            onDismiss()
                        })
                    }
                }
                if (all == null) Hint(stringResource(R.string.aot_loading))
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { androidx.compose.material3.Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Zygote's class preload of the Jancar ROM ([org.librehu.service.perf.ZygotePreload]), for the next start. */
@Composable
private fun ZygoteCard() {
    val zp = org.librehu.service.perf.ZygotePreload
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf("") }
    var next by remember { mutableStateOf<String?>(null) }
    var modules by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    suspend fun load() =
        withContext(Dispatchers.IO) {
            now = zp.current()
            next = zp.next()
            modules = zp.modulesAvailable()
        }
    LaunchedEffect(Unit) { load() }
    Card(stringResource(R.string.zyg_title)) {
        Hint(stringResource(R.string.zyg_hint))
        StatusLine(stringResource(R.string.zyg_now, now.ifEmpty { "—" }), null)
        StatusLine(
            next?.let { stringResource(R.string.zyg_next, it) } ?: stringResource(R.string.zyg_next_rom),
            null,
        )
        when (modules) {
            false -> StatusLine(stringResource(R.string.zyg_no_modules), false)
            true -> {
                val on = (next ?: now) == "1"
                SwitchRow(stringResource(R.string.zyg_switch), on, stringResource(R.string.zyg_switch_hint)) { v ->
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { zp.set(if (v) "1" else "0") }
                        message = if (ok) null else "root"
                        load()
                    }
                }
                Actions {
                    Pill(stringResource(R.string.zyg_rom)) {
                        scope.launch {
                            withContext(Dispatchers.IO) { zp.set(null) }
                            load()
                        }
                    }
                }
            }
            null -> {}
        }
        message?.let { StatusLine(stringResource(R.string.atc_no_root), false) }
        Hint(stringResource(R.string.zyg_reboot))
    }
}
