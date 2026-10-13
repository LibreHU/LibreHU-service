package org.librehu.service.ui

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
        Card(stringResource(R.string.tw_title)) {
            if (loaded == false) StatusLine(noRoot, false)
            if (tweaks.skippedAtBoot) {
                StatusLine(stringResource(R.string.tw_skipped), false)
                Actions { Pill(stringResource(R.string.tw_skipped_ok)) { tweaks.clearSkipped() } }
            }
            SwitchRow(stringResource(R.string.tw_boot), s.applyAtBoot, stringResource(R.string.tw_boot_hint)) { on -> set { it.copy(applyAtBoot = on) } }
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
