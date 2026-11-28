package org.librehu.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.core.audio.Bd37534
import org.librehu.core.audio.Bu32107
import org.librehu.core.audio.Bu32107Settings
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdLabels
import org.librehu.service.obd.ObdManager
import org.librehu.service.overlay.OverlayKind

/** A settings page; [Section] groups them in the rail. */
enum class Tab(
    val icon: ImageVector,
    val label: Int,
) {
    HOME(Icons.Default.Home, R.string.tab_home),
    VEHICLE(Icons.Default.DirectionsCar, R.string.tab_vehicle),
    REVERSE(Icons.Default.Videocam, R.string.tab_reverse),
    VIDEO(Icons.Default.Tv, R.string.tab_video),
    OBD(Icons.Default.Speed, R.string.tab_obd),
    CAN(Icons.Default.Cable, R.string.tab_can),
    AUDIO(Icons.Default.Equalizer, R.string.tab_audio),
    BLUETOOTH(Icons.Default.Bluetooth, R.string.tab_bluetooth),
    PHONE(Icons.Default.Smartphone, R.string.tab_phone),
    ANDROID_AUTO(Icons.Default.Smartphone, R.string.tab_android_auto),
    CARPLAY(Icons.Default.PhoneIphone, R.string.tab_carplay),
    DISPLAY(Icons.Default.Brightness6, R.string.tab_display),
    TOUCH(Icons.Default.TouchApp, R.string.tab_touch),
    LIGHTS(Icons.Default.Lightbulb, R.string.tab_lights),
    SYSTEM(Icons.Default.PowerSettingsNew, R.string.tab_system),
    GPS(Icons.Default.GpsFixed, R.string.tab_gps),
    NETWORK(Icons.Default.Lan, R.string.tab_network),
    TWEAKS(Icons.Default.Speed, R.string.tab_tweaks),
    MCU(Icons.Default.Memory, R.string.tab_mcu),
    MCU_TOOLS(Icons.Default.Build, R.string.tab_mcu_tools),
    AUDIO_DIAG(Icons.Default.GraphicEq, R.string.tab_audio_diag),
    DIAG(Icons.Default.BugReport, R.string.tab_diag),
    ATC(Icons.Default.DeveloperBoard, R.string.tab_atc),
    INFO(Icons.Default.Info, R.string.info_title),
}

/** Entries of the rail, Android Auto style; a section with several pages shows them as pills on top. */
enum class Section(
    val icon: ImageVector,
    val label: Int,
    val tabs: List<Tab>,
) {
    HOME(Icons.Default.Home, R.string.tab_home, listOf(Tab.HOME)),
    VEHICLE(Icons.Default.DirectionsCar, R.string.section_vehicle, listOf(Tab.VEHICLE, Tab.REVERSE, Tab.VIDEO, Tab.OBD, Tab.CAN)),
    AUDIO(Icons.Default.Equalizer, R.string.tab_audio, listOf(Tab.AUDIO)),
    BLUETOOTH(Icons.Default.Bluetooth, R.string.tab_bluetooth, listOf(Tab.BLUETOOTH, Tab.PHONE)),
    PROJECTION(Icons.Default.Smartphone, R.string.section_projection, listOf(Tab.ANDROID_AUTO, Tab.CARPLAY)),
    DISPLAY(Icons.Default.Brightness6, R.string.section_display, listOf(Tab.DISPLAY, Tab.TOUCH, Tab.LIGHTS)),
    SYSTEM(Icons.Default.Settings, R.string.tab_system, listOf(Tab.SYSTEM, Tab.GPS, Tab.NETWORK, Tab.TWEAKS, Tab.INFO)),
    ADVANCED(Icons.Default.Tune, R.string.section_advanced, listOf(Tab.MCU, Tab.MCU_TOOLS, Tab.AUDIO_DIAG, Tab.ATC, Tab.DIAG)),
    ;

    companion object {
        fun of(tab: Tab) = entries.first { tab in it.tabs }
    }
}

/** Callbacks into the activity (pickers, Android screens). */
class AppActions(
    val importProfile: () -> Unit,
    val exportProfile: (String) -> Unit,
    val requestBtPermissions: () -> Unit,
    val openWriteSettings: () -> Unit,
    val restartLink: () -> Unit,
    val openLocationSettings: () -> Unit,
    val openDateSettings: () -> Unit,
    val calibrateTouch: () -> Unit,
    val exportLog: () -> Unit,
    val importCanVehicle: () -> Unit,
    val exportCanVehicle: (String) -> Unit,
    /** Car file for the ELM327 (ddt4all ECU or LibreHU car); true = its ECUs added to the selected car. */
    val importObdCar: (Boolean) -> Unit = {},
    val exportObdCar: (String) -> Unit = {},
    val requestAudioPermission: () -> Unit = {},
    val openNotificationAccess: () -> Unit = {},
    val requestCameraPermission: () -> Unit = {},
    val pickReverseSound: () -> Unit = {},
    val pickLogo: () -> Unit = {},
)

/** Android Auto-like layout: sections on the left, the selected page (and its sibling pages) on the right. */
@Composable
fun AppScreen(
    tab: Tab,
    onTab: (Tab) -> Unit,
    client: ServiceClient,
    actions: AppActions,
) {
    val section = Section.of(tab)
    // Search of the menus and settings: typed text replaces the page with the results.
    var query by rememberSaveable { mutableStateOf("") }
    val content: @Composable () -> Unit = {
        when (tab) {
            Tab.HOME -> HomeScreen(client, onTab)
            Tab.VEHICLE -> VehicleScreen()
            Tab.REVERSE -> ReverseScreen(actions)
            Tab.VIDEO -> VideoScreen()
            Tab.OBD -> ObdScreen(client, actions)
            Tab.CAN -> CanScreen(actions)
            Tab.AUDIO -> AudioScreen(client)
            Tab.BLUETOOTH -> BluetoothScreen(client, actions)
            Tab.PHONE -> PhoneScreen()
            Tab.ANDROID_AUTO -> AndroidAutoScreen()
            Tab.CARPLAY -> CarPlayScreen()
            Tab.DISPLAY -> DisplayScreen(actions)
            Tab.TOUCH -> TouchScreen(actions)
            Tab.LIGHTS -> LightsScreen(actions)
            Tab.SYSTEM -> SystemScreen()
            Tab.GPS -> GpsTimeScreen(actions)
            Tab.NETWORK -> NetworkScreen()
            Tab.TWEAKS -> TweaksScreen()
            Tab.MCU -> McuScreen(actions)
            Tab.MCU_TOOLS -> McuToolsScreen(client)
            Tab.AUDIO_DIAG -> AudioDiagScreen(client, actions)
            Tab.DIAG -> DiagScreen(client, actions)
            Tab.ATC -> AtcScreen(actions)
            Tab.INFO -> InfoContent()
        }
    }
    // LibreHU Launcher's car styles (the launcher asks the app for them): the sections as soft keys or tabs.
    if (carStyled) return StyledAppScreen(section, tab, onTab, query, { query = it }, content)
    Row(Modifier.fillMaxSize().background(CarColors.Background)) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(104.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in Section.entries) RailItem(s.icon, s.label, s == section) { if (s != section) onTab(s.tabs.first()) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 12.dp, bottom = 12.dp)) {
            SearchField(query, { query = it }, Modifier.padding(bottom = 12.dp))
            if (query.isNotBlank()) {
                SearchResults(query, onTab, { query = "" }, Modifier.weight(1f))
                return@Column
            }
            if (section.tabs.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (t in section.tabs) Pill(stringResource(t.label), selected = t == tab, icon = t.icon) { onTab(t) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(92.dp)
                .clip(carShape(20.dp))
                .background(if (selected) CarColors.SurfaceHigh else CarColors.Background)
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) CarColors.Accent else CarColors.Background)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Icon(icon, null, tint = if (selected) CarColors.OnAccent else CarColors.Text, modifier = Modifier.size(28.dp))
        }
        Text(
            stringResource(label),
            color = if (selected) CarColors.Text else CarColors.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** Scrolling page with a title. */
@Composable
fun Page(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle(title)
        content()
    }
}

// --- Home --------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    client: ServiceClient,
    onTab: (Tab) -> Unit,
) {
    val link by ServiceState.link.collectAsStateWithLifecycle()
    val v by ServiceState.vehicle.collectAsStateWithLifecycle()
    val obd = ObdManager.get(LocalContext.current)
    val obdState by obd.state.collectAsStateWithLifecycle()
    val obdSettings by obd.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Page(stringResource(R.string.app_name)) {
        Card(stringResource(R.string.home_link), Modifier.clickable { onTab(Tab.MCU) }) {
            StatusLine(
                linkLabel(link.state) + if (link.detail.isNotEmpty()) " — ${link.detail}" else "",
                link.state == LibreHuService.Link.RUNNING,
            )
            if (link.protocol.isNotEmpty()) Hint(stringResource(R.string.home_protocol, link.protocol))
            if (link.state == LibreHuService.Link.RUNNING) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(stringResource(R.string.flag_acc), onOff(v.acc))
                    Tile(stringResource(R.string.flag_powered), onOff(v.powered))
                    Tile(stringResource(R.string.flag_handbrake), onOff(v.handbrake))
                    Tile(stringResource(R.string.flag_headlight), onOff(v.headlight))
                    Tile(stringResource(R.string.flag_reverse), onOff(v.reverse))
                    Tile(stringResource(R.string.home_mcu_version), v.mcuVersion.ifEmpty { "—" })
                }
            }
        }
        Card(stringResource(R.string.tab_bluetooth), Modifier.clickable { onTab(Tab.BLUETOOTH) }) {
            val s = client.btStatus
            StatusLine(s.deviceName.ifEmpty { stringResource(R.string.bt_no_phone) }, if (s.deviceAddress.isEmpty()) null else true)
            if (s.deviceAddress.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(stringResource(R.string.bt_battery), if (s.battery < 0) "—" else "${s.battery * 20} %")
                    Tile(stringResource(R.string.bt_signal), if (s.signal < 0) "—" else "${s.signal}/5")
                    Tile(stringResource(R.string.bt_operator), s.operator.ifEmpty { "—" })
                }
            }
            val m = client.media
            if (m.connected &&
                m.title.isNotEmpty()
            ) {
                Hint((if (m.playing) "▶ " else "⏸ ") + listOf(m.title, m.artist).filter { it.isNotEmpty() }.joinToString(" — "))
            }
        }
        Card(stringResource(R.string.tab_obd), Modifier.clickable { onTab(Tab.OBD) }) {
            StatusLine(obdLabel(obdState.connection, obdSettings.enabled), obdState.connection == ObdConnection.CONNECTED)
            if (obdState.connection == ObdConnection.CONNECTED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (k in obdSettings.widget) {
                        Tile(ObdLabels.label(context, k), obdState.values[k]?.let { ObdManager.format(k, it) } ?: "—")
                    }
                }
            }
        }
    }
}

@Composable
fun linkLabel(l: LibreHuService.Link): String =
    stringResource(
        when (l) {
            LibreHuService.Link.STOPPED -> R.string.link_stopped
            LibreHuService.Link.BLOCKED_BY_IVI -> R.string.link_blocked
            LibreHuService.Link.NO_MCU -> R.string.link_no_mcu
            LibreHuService.Link.RUNNING -> R.string.link_running
        },
    )

@Composable
fun obdLabel(
    c: ObdConnection,
    enabled: Boolean,
): String =
    stringResource(
        when (c) {
            ObdConnection.OFF -> if (enabled) R.string.obd_off else R.string.obd_disabled
            ObdConnection.CONNECTING -> R.string.obd_connecting
            ObdConnection.INITIALISING -> R.string.obd_initialising
            ObdConnection.CONNECTED -> R.string.obd_connected
            ObdConnection.ERROR -> R.string.obd_error
        },
    )

@Composable
fun onOff(on: Boolean) = stringResource(if (on) R.string.on else R.string.off)

// --- Audio -------------------------------------------------------------------------------------------------------

@Composable
fun AudioScreen(client: ServiceClient) {
    val a = client.audio
    // Board with a ROHM BU32107: its own settings, some BD37534 ones hidden.
    val isBu = a.chip.contains("BU32107")
    val link by ServiceState.link.collectAsStateWithLifecycle()
    Page(stringResource(R.string.tab_audio)) {
        if (link.state != LibreHuService.Link.RUNNING) Card { Hint(stringResource(R.string.audio_offline)) }
        Card(stringResource(R.string.section_audio)) {
            SliderRow(stringResource(R.string.volume), a.volume, 0..a.volumeLimit) { v -> client.call { it.setVolume(v) } }
            SwitchRow(stringResource(R.string.mute), a.muted) { on -> client.call { it.setMuted(on) } }
            SliderRow(stringResource(R.string.volume_limit), a.volumeLimit, 1..a.maxVolume) { v -> client.adv("maxVolume", v) }
            Hint(stringResource(R.string.volume_limit_hint))
        }
        Card(stringResource(R.string.audio_source)) {
            Hint(stringResource(R.string.audio_source_hint))
            Choices(
                listOf(0 to stringResource(R.string.audio_source_android), 1 to stringResource(R.string.audio_source_aux)),
                a.source,
            ) { v ->
                client.call { it.setAudioSource(v) }
                client.refreshAudio()
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val mix by org.librehu.service.AuxMix
                .get(context)
                .collectAsStateWithLifecycle()
            SwitchRow(stringResource(R.string.aux_mix), mix.enabled, stringResource(R.string.aux_mix_hint)) { on ->
                org.librehu.service.AuxMix
                    .update(context) { it.copy(enabled = on) }
            }
            if (mix.enabled) {
                SliderRow(stringResource(R.string.aux_mix_level), mix.offsetDb, -30..7, { "${if (it > 0) "+" else ""}$it dB" }) { v ->
                    org.librehu.service.AuxMix
                        .update(context) { it.copy(offsetDb = v) }
                }
            }
        }
        MicrophoneCard()
        LevelOverlayCard(OverlayKind.VOLUME) { it.show(a.volume, Bd37534.MAX_VOLUME, a.muted, force = true) }
        SoundOsdCard()
        Card(stringResource(R.string.audio_tone)) {
            SwitchRow(stringResource(R.string.tone_by_viper), a.toneByViper, stringResource(R.string.tone_by_viper_hint)) { on ->
                client.adv("toneByViper", on)
            }
            if (!a.toneByViper) {
                // BU32107: ±12 dB in 2 dB steps, its own frequencies (BU32107 card); BD37534: ±20 dB, frequency and Q here.
                val toneMax = if (isBu) Bu32107.TONE_MAX_DB else 20
                SliderRow(stringResource(R.string.bass), a.bassDb, -toneMax..toneMax, ::signedDb) { v -> client.adv("bassDb", v) }
                if (!isBu) ToneBand(a.bassFreq, Bd37534.BASS_FREQS, a.bassQ, Bd37534.BASS_QS, { client.adv("bassFreq", it) }) { client.adv("bassQ", it) }
                SliderRow(stringResource(R.string.middle), a.middleDb, -toneMax..toneMax, ::signedDb) { v -> client.adv("middleDb", v) }
                if (!isBu) {
                    ToneBand(a.middleFreq, Bd37534.MIDDLE_FREQS, a.middleQ, Bd37534.MIDDLE_QS, { client.adv("middleFreq", it) }) {
                        client.adv("middleQ", it)
                    }
                }
                SliderRow(stringResource(R.string.treble), a.trebleDb, -toneMax..toneMax, ::signedDb) { v -> client.adv("trebleDb", v) }
                if (!isBu) {
                    ToneBand(a.trebleFreq, Bd37534.TREBLE_FREQS, a.trebleQ, Bd37534.TREBLE_QS, { client.adv("trebleFreq", it) }) {
                        client.adv("trebleQ", it)
                    }
                }
                val loudMax = if (isBu) Bu32107.LOUDNESS_MAX_DB else Bd37534.LOUDNESS_MAX
                SliderRow(stringResource(R.string.loudness), a.loudness, 0..loudMax, { "$it dB" }) { v -> client.adv("loudness", v) }
                if (!isBu) {
                    BodyText(stringResource(R.string.loudness_freq))
                    Choices(Bd37534.LOUDNESS_FREQS.map { it to hz(it) }, a.loudnessFreq) { client.adv("loudnessFreq", it) }
                    BodyText(stringResource(R.string.loudness_hicut))
                    Choices((1..4).map { it to "$it" }, a.loudnessHicut) { client.adv("loudnessHicut", it) }
                }
            }
        }
        Card(stringResource(R.string.audio_balance)) {
            SliderRow(stringResource(R.string.balance), a.balance, 0..60, ::centred) { v -> client.call { it.setBalanceFade(v, a.fade) } }
            SliderRow(stringResource(R.string.fade), a.fade, 0..60, ::centred) { v -> client.call { it.setBalanceFade(a.balance, v) } }
            BodyText(stringResource(R.string.speaker_trims))
            Hint(stringResource(R.string.speaker_trims_hint))
            val names = listOf(R.string.trim_fl, R.string.trim_fr, R.string.trim_rl, R.string.trim_rr)
            for (i in 0..3) {
                SliderRow(stringResource(names[i]), a.trims.getOrElse(i) { 0 }, Bd37534.TRIM_MIN_DB..Bd37534.TRIM_MAX_DB, ::signedDb) { v ->
                    client.advTrims(a.trims, i, v)
                }
            }
        }
        Card(stringResource(R.string.audio_outputs)) {
            SwitchRow(stringResource(R.string.subwoofer), a.subwoofer) { on -> client.call { it.setSubwoofer(on, a.subLevel) } }
            SliderRow(stringResource(R.string.sub_level), a.subLevel, 0..12, {
                "${it - 5} dB"
            }) { v -> client.call { it.setSubwoofer(a.subwoofer, v) } }
            if (a.subwoofer && !isBu) {
                Hint(stringResource(R.string.sub_tuning_hint))
                BodyText(stringResource(R.string.sub_cutoff))
                Choices(
                    Bd37534.SUB_CUTOFFS.map { it to hz(it) } + (0 to stringResource(R.string.sub_cutoff_off)),
                    a.subCutoff,
                ) { client.adv("subCutoff", it) }
                BodyText(stringResource(R.string.sub_phase))
                Choices(listOf(false to "0°", true to "180°"), a.subPhase180) { client.adv("subPhase180", it) }
                BodyText(stringResource(R.string.sub_output))
                Choices(
                    listOf(
                        Bd37534.SUB_OUTPUT_LPF to stringResource(R.string.sub_output_lpf),
                        Bd37534.SUB_OUTPUT_FRONT to stringResource(R.string.sub_output_front),
                        Bd37534.SUB_OUTPUT_REAR to stringResource(R.string.sub_output_rear),
                    ),
                    a.subOutput,
                ) { client.adv("subOutput", it) }
            }
            SwitchRow(stringResource(R.string.external_amp), a.externalAmp, stringResource(R.string.external_amp_hint)) { on ->
                client.call { it.setExternalAmpEnabled(on) }
            }
        }
        if (isBu) Bu32107Cards(a, client)
        Card(stringResource(R.string.android_input_gain)) {
            if (!a.chipSupported) {
                BodyText(stringResource(R.string.chip_not_supported, a.chip.ifEmpty { "?" }, a.boardId.ifEmpty { "?" }))
            } else {
                SliderRow(stringResource(R.string.android_input_gain), a.androidInputGain, 0..Bd37534.ANDROID_GAIN_MAX, {
                    "+$it dB"
                }) { v -> client.call { it.setAndroidInputGain(v) } }
                BodyText(stringResource(R.string.android_input_gain_hint, a.jancarAndroidGain, a.boardId.ifEmpty { "A0_AN ?" }))
            }
        }
    }
}

private fun signedDb(v: Int) = "%+d dB".format(v)

private fun hz(v: Int) = if (v == 31) "31.5 Hz" else if (v >= 1000) "%s kHz".format(if (v % 1000 == 0) "${v / 1000}" else "%.1f".format(v / 1000.0)) else "$v Hz"

/** Centre frequency and Q of one band of the sound processor. */
@Composable
private fun ToneBand(
    freq: Int,
    freqs: IntArray,
    q: Int,
    qs: IntArray,
    onFreq: (Int) -> Unit,
    onQ: (Int) -> Unit,
) {
    BodyText(stringResource(R.string.tone_freq))
    Choices(freqs.map { it to hz(it) }, freq, onFreq)
    BodyText(stringResource(R.string.tone_q))
    Choices(qs.map { it to "%.2f".format(it / 100.0).trimEnd('0').trimEnd('.') }, q, onQ)
}

private fun centred(v: Int) = (v - 30).let { if (it == 0) "0" else "%+d".format(it) }

/** Microphone gain and echo / noise reduction of Jancar's factory settings (see Microphone). */
@Composable
private fun MicrophoneCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mic by org.librehu.service.Microphone
        .get(context)
        .collectAsStateWithLifecycle()
    var now by remember { mutableStateOf<Pair<Int?, Boolean?>>(null to null) }
    var library by remember { mutableStateOf<org.librehu.service.EcnrLibrary?>(null) }
    LaunchedEffect(mic) {
        kotlinx.coroutines.delay(300)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            now =
                org.librehu.service.Microphone
                    .current()
            library =
                org.librehu.service.Microphone
                    .ecnrLibrary()
        }
    }
    Card(stringResource(R.string.mic_title)) {
        BodyText(stringResource(R.string.mic_gain))
        Choices(
            listOf(0 to stringResource(R.string.mic_rom)) +
                org.librehu.service.Microphone.GAINS
                    .mapIndexed { i, g -> g to "${i + 1}" },
            mic.gain,
        ) { g ->
            org.librehu.service.Microphone
                .update(context) { it.copy(gain = g) }
        }
        BodyText(stringResource(R.string.mic_ecnr))
        Choices(
            listOf(-1 to stringResource(R.string.mic_rom), 0 to stringResource(R.string.off), 1 to stringResource(R.string.on)),
            mic.ecnr?.let { if (it) 1 else 0 } ?: -1,
        ) { v ->
            org.librehu.service.Microphone
                .update(context) { it.copy(ecnr = if (v < 0) null else v == 1) }
        }
        Hint(
            stringResource(
                R.string.mic_hint,
                now.first
                    ?.let {
                        org.librehu.service.Microphone.GAINS
                            .indexOf(it) + 1
                    }?.toString() ?: "?",
                now.second?.let { stringResource(if (it) R.string.on else R.string.off) } ?: "?",
            ),
        )
        BodyText(stringResource(R.string.mic_webrtc))
        Hint(
            stringResource(
                when (library) {
                    org.librehu.service.EcnrLibrary.WEBRTC -> R.string.mic_webrtc_installed
                    org.librehu.service.EcnrLibrary.OTHER -> R.string.mic_webrtc_other
                    org.librehu.service.EcnrLibrary.MISSING -> R.string.mic_webrtc_missing
                    org.librehu.service.EcnrLibrary.NO_HOOK -> R.string.mic_webrtc_no_hook
                    null -> R.string.mic_webrtc_checking
                },
            ),
        )
        if (library == org.librehu.service.EcnrLibrary.WEBRTC) {
            BodyText(stringResource(R.string.mic_webrtc_aec))
            Choices(
                listOf(0 to stringResource(R.string.off), 1 to "AEC3", 2 to stringResource(R.string.mic_webrtc_aecm)),
                mic.aec,
            ) { v ->
                org.librehu.service.Microphone
                    .update(context) { it.copy(aec = v) }
            }
            BodyText(stringResource(R.string.mic_webrtc_ns))
            Choices(
                listOf(
                    0 to stringResource(R.string.off),
                    1 to stringResource(R.string.mic_webrtc_low),
                    2 to stringResource(R.string.mic_webrtc_moderate),
                    3 to stringResource(R.string.mic_webrtc_high),
                    4 to stringResource(R.string.mic_webrtc_very_high),
                ),
                mic.ns,
            ) { v ->
                org.librehu.service.Microphone
                    .update(context) { it.copy(ns = v) }
            }
            BodyText(stringResource(R.string.mic_webrtc_agc))
            Choices(listOf(false to stringResource(R.string.off), true to stringResource(R.string.on)), mic.agc) { v ->
                org.librehu.service.Microphone
                    .update(context) { it.copy(agc = v) }
            }
            BodyText(stringResource(R.string.mic_webrtc_channel))
            Choices(
                listOf(
                    0 to stringResource(R.string.mic_webrtc_left),
                    1 to stringResource(R.string.mic_webrtc_right),
                    2 to stringResource(R.string.mic_webrtc_both),
                ),
                mic.micChannel,
            ) { v ->
                org.librehu.service.Microphone
                    .update(context) { it.copy(micChannel = v) }
            }
            Hint(stringResource(R.string.mic_webrtc_hint))
        }
    }
}

/**
 * The settings in LibreHU Launcher's car styles. Factory (Modularer Infotainment-Baukasten): the page's name over the
 * accent line, its sibling pages as framed keys, the sections as soft keys. Ambient (LHU-Link): the sections as
 * framed tabs, the sibling pages as keys under them, the bar Back / Options.
 */
@Composable
private fun StyledAppScreen(
    section: Section,
    tab: Tab,
    onTab: (Tab) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val pages: @Composable () -> Unit = {
        SearchField(query, onQuery, Modifier.padding(bottom = 10.dp))
        if (query.isBlank() && section.tabs.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (t in section.tabs) Pill(stringResource(t.label), selected = t == tab, icon = t.icon) { onTab(t) }
            }
        }
    }
    if (RetroLook.factory) {
        FactoryScreen(
            title = stringResource(tab.label),
            back = false,
            keys = Section.entries.map { s -> SoftKey(stringResource(s.label), s.icon, s == section) { if (s != section) onTab(s.tabs.first()) } },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                pages()
                Box(Modifier.weight(1f).fillMaxWidth()) { if (query.isBlank()) content() else SearchResults(query, onTab, { onQuery("") }) }
            }
        }
    } else {
        AmbientScreen(stringResource(tab.label), keys = emptyList(), options = emptyList()) {
            Column(Modifier.fillMaxSize()) {
                AmbientTabs(Section.entries.map { stringResource(it.label) }, section.ordinal) { i ->
                    val s = Section.entries[i]
                    if (s != section) onTab(s.tabs.first())
                }
                Column(Modifier.fillMaxSize().padding(10.dp)) {
                    pages()
                    Box(Modifier.weight(1f).fillMaxWidth()) { if (query.isBlank()) content() else SearchResults(query, onTab, { onQuery("") }) }
                }
            }
        }
    }
}

/** Driver switch and every setting of the ROHM BU32107 (Table mode of the datasheet). */
@Composable
private fun Bu32107Cards(
    a: AudioState,
    client: ServiceClient,
) {
    val b = a.bu

    fun set(t: (Bu32107Settings) -> Bu32107Settings) = client.adv("bu32107", t(b).encode())
    Card(stringResource(R.string.bu_title)) {
        SwitchRow(stringResource(R.string.bu_driver), a.buDriver, stringResource(R.string.bu_driver_hint)) { on ->
            client.adv("bu32107Driver", on)
        }
    }
    if (a.driver != "BU32107") return
    Card(stringResource(R.string.bu_eq)) {
        for (i in 0 until Bu32107.EQ_BANDS) {
            SliderRow(hz(Bu32107.EQ_BANDS_HZ[i]), b.eqFront[i], -Bu32107.EQ_MAX_DB..Bu32107.EQ_MAX_DB, ::signedDb) { v ->
                set { s -> s.copy(eqFront = s.eqFront.toMutableList().also { it[i] = v / 2 * 2 }) }
            }
        }
        BodyText(stringResource(R.string.bu_eq_q))
        Choices(listOf(false to "2.2", true to "4.7"), b.eqNarrow) { v -> set { it.copy(eqNarrow = v) } }
        SwitchRow(stringResource(R.string.bu_eq_headroom), b.eqHeadroom, stringResource(R.string.bu_eq_headroom_hint)) { v ->
            set { it.copy(eqHeadroom = v) }
        }
        SwitchRow(stringResource(R.string.bu_eq_rear_same), b.eqRearSame) { v -> set { it.copy(eqRearSame = v) } }
        if (!b.eqRearSame) {
            BodyText(stringResource(R.string.bu_eq_rear))
            for (i in 0 until Bu32107.EQ_BANDS) {
                SliderRow(hz(Bu32107.EQ_BANDS_HZ[i]), b.eqRear[i], -Bu32107.EQ_MAX_DB..Bu32107.EQ_MAX_DB, ::signedDb) { v ->
                    set { s -> s.copy(eqRear = s.eqRear.toMutableList().also { it[i] = v / 2 * 2 }) }
                }
            }
        }
    }
    Card(stringResource(R.string.bu_tone_loudness)) {
        BodyText(stringResource(R.string.bass))
        Choices(Bu32107.BASS_FC.map { it to hz(it) }, b.bassFc) { v -> set { it.copy(bassFc = v) } }
        BodyText(stringResource(R.string.middle))
        Choices(Bu32107.MIDDLE_F0.map { it to hz(it) }, b.middleF0) { v -> set { it.copy(middleF0 = v) } }
        BodyText(stringResource(R.string.treble))
        Choices(Bu32107.TREBLE_FC.map { it to hz(it) }, b.trebleFc) { v -> set { it.copy(trebleFc = v) } }
        BodyText(stringResource(R.string.bu_loud_lpf))
        Choices(Bu32107.LOUD_LPF.map { it to hz(it) }, b.loudLpf) { v -> set { it.copy(loudLpf = v) } }
        BodyText(stringResource(R.string.bu_loud_hpf))
        Choices(Bu32107.LOUD_HPF.map { it to hz(it) }, b.loudHpf) { v -> set { it.copy(loudHpf = v) } }
        BodyText(stringResource(R.string.bu_hiboost))
        Choices(Bu32107.LOUD_HIBOOST.map { it to if (it == 0) "0" else "0.$it" }, b.loudHiBoost) { v -> set { it.copy(loudHiBoost = v) } }
    }
    Card(stringResource(R.string.bu_filters)) {
        Hint(stringResource(R.string.bu_filters_hint))
        val off = stringResource(R.string.sub_cutoff_off)

        fun opts(list: IntArray) = list.map { it to if (it == 0) off else hz(it) }
        BodyText(stringResource(R.string.bu_hpf_front))
        Choices(opts(Bu32107.HPF_FC), b.hpfFront) { v -> set { it.copy(hpfFront = v) } }
        SwitchRow(stringResource(R.string.bu_steep), b.hpfFrontSteep) { v -> set { it.copy(hpfFrontSteep = v) } }
        SwitchRow(stringResource(R.string.bu_invert), b.hpfFrontInvert) { v -> set { it.copy(hpfFrontInvert = v) } }
        BodyText(stringResource(R.string.bu_hpf_rear))
        Choices(opts(Bu32107.HPF_FC), b.hpfRear) { v -> set { it.copy(hpfRear = v) } }
        SwitchRow(stringResource(R.string.bu_steep), b.hpfRearSteep) { v -> set { it.copy(hpfRearSteep = v) } }
        SwitchRow(stringResource(R.string.bu_invert), b.hpfRearInvert) { v -> set { it.copy(hpfRearInvert = v) } }
        BodyText(stringResource(R.string.bu_sub_lpf))
        Choices(opts(Bu32107.SUB_LPF_FC), b.subLpf) { v -> set { it.copy(subLpf = v) } }
        SwitchRow(stringResource(R.string.bu_steep), b.subLpfSteep) { v -> set { it.copy(subLpfSteep = v) } }
        SwitchRow(stringResource(R.string.sub_phase), a.subPhase180) { v -> client.adv("subPhase180", v) }
        BodyText(stringResource(R.string.bu_sub_hpf))
        Choices(opts(Bu32107.SUB_HPF_FC), b.subHpf) { v -> set { it.copy(subHpf = v) } }
    }
    Card(stringResource(R.string.bu_p2bass)) {
        Hint(stringResource(R.string.bu_p2bass_hint))
        val off = stringResource(R.string.sub_cutoff_off)
        BodyText(stringResource(R.string.bu_front))
        Choices(Bu32107.P2BASS_FC.map { it to if (it == 0) off else hz(it) }, b.p2BassFrontHz) { v -> set { it.copy(p2BassFrontHz = v) } }
        SliderRow(stringResource(R.string.bu_gain), b.p2BassFrontDb, 0..Bu32107.P2BASS_MAX_DB, { "+$it dB" }) { v ->
            set { it.copy(p2BassFrontDb = v) }
        }
        BodyText(stringResource(R.string.bu_rear))
        Choices(Bu32107.P2BASS_FC.map { it to if (it == 0) off else hz(it) }, b.p2BassRearHz) { v -> set { it.copy(p2BassRearHz = v) } }
        SliderRow(stringResource(R.string.bu_gain), b.p2BassRearDb, 0..Bu32107.P2BASS_MAX_DB, { "+$it dB" }) { v ->
            set { it.copy(p2BassRearDb = v) }
        }
    }
    Card(stringResource(R.string.bu_delays)) {
        Hint(stringResource(R.string.bu_delays_hint))
        val names = listOf(R.string.trim_fl, R.string.trim_fr, R.string.trim_rl, R.string.trim_rr, R.string.subwoofer)
        for (i in 0 until 5) {
            SliderRow(stringResource(names[i]), b.delays[i], 0..Bu32107.DELAY_MAX_TENTHS, { t ->
                "%.1f ms · %d cm".format(t / 10.0, (t * 3.43).toInt())
            }) { v -> set { s -> s.copy(delays = s.delays.toMutableList().also { it[i] = v }) } }
        }
    }
    Card(stringResource(R.string.bu_noise)) {
        Hint(stringResource(R.string.bu_noise_hint))
        Choices(
            listOf(0 to stringResource(R.string.bu_noise_off), 1 to stringResource(R.string.bu_noise_pink), 2 to stringResource(R.string.bu_noise_white)),
            a.testNoise,
        ) { v ->
            client.adv("testNoise", v)
            client.refreshAudio()
        }
    }
}
