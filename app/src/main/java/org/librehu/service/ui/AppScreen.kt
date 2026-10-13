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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    OBD(Icons.Default.Speed, R.string.tab_obd),
    CAN(Icons.Default.Cable, R.string.tab_can),
    AUDIO(Icons.Default.Equalizer, R.string.tab_audio),
    BLUETOOTH(Icons.Default.Bluetooth, R.string.tab_bluetooth),
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
}

/** Entries of the rail, Android Auto style; a section with several pages shows them as pills on top. */
enum class Section(
    val icon: ImageVector,
    val label: Int,
    val tabs: List<Tab>,
) {
    HOME(Icons.Default.Home, R.string.tab_home, listOf(Tab.HOME)),
    VEHICLE(Icons.Default.DirectionsCar, R.string.section_vehicle, listOf(Tab.VEHICLE, Tab.REVERSE, Tab.OBD, Tab.CAN)),
    AUDIO(Icons.Default.Equalizer, R.string.tab_audio, listOf(Tab.AUDIO)),
    BLUETOOTH(Icons.Default.Bluetooth, R.string.tab_bluetooth, listOf(Tab.BLUETOOTH)),
    PROJECTION(Icons.Default.Smartphone, R.string.section_projection, listOf(Tab.ANDROID_AUTO, Tab.CARPLAY)),
    DISPLAY(Icons.Default.Brightness6, R.string.section_display, listOf(Tab.DISPLAY, Tab.TOUCH, Tab.LIGHTS)),
    SYSTEM(Icons.Default.Settings, R.string.tab_system, listOf(Tab.SYSTEM, Tab.GPS, Tab.NETWORK, Tab.TWEAKS)),
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
            if (section.tabs.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (t in section.tabs) Pill(stringResource(t.label), selected = t == tab, icon = t.icon) { onTab(t) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.HOME -> HomeScreen(client, onTab)
                    Tab.VEHICLE -> VehicleScreen()
                    Tab.REVERSE -> ReverseScreen(actions)
                    Tab.OBD -> ObdScreen(client)
                    Tab.CAN -> CanScreen(actions)
                    Tab.AUDIO -> AudioScreen(client)
                    Tab.BLUETOOTH -> BluetoothScreen(client, actions)
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
                }
            }
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
                .clip(RoundedCornerShape(20.dp))
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
    val link by ServiceState.link.collectAsStateWithLifecycle()
    Page(stringResource(R.string.tab_audio)) {
        if (link.state != LibreHuService.Link.RUNNING) Card { Hint(stringResource(R.string.audio_offline)) }
        Card(stringResource(R.string.section_audio)) {
            SliderRow(stringResource(R.string.volume), a.volume, 0..a.maxVolume) { v -> client.call { it.setVolume(v) } }
            SwitchRow(stringResource(R.string.mute), a.muted) { on -> client.call { it.setMuted(on) } }
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
        Card(stringResource(R.string.audio_tone)) {
            SliderRow(stringResource(R.string.bass), a.bass, 0..20, ::db) { v -> client.call { it.setTone(v, a.middle, a.treble) } }
            SliderRow(stringResource(R.string.middle), a.middle, 0..20, ::db) { v -> client.call { it.setTone(a.bass, v, a.treble) } }
            SliderRow(stringResource(R.string.treble), a.treble, 0..20, ::db) { v -> client.call { it.setTone(a.bass, a.middle, v) } }
            SliderRow(stringResource(R.string.loudness), a.loudness, 0..15, { "$it dB" }) { v -> client.call { it.setLoudness(v) } }
        }
        Card(stringResource(R.string.audio_balance)) {
            SliderRow(stringResource(R.string.balance), a.balance, 0..60, ::centred) { v -> client.call { it.setBalanceFade(v, a.fade) } }
            SliderRow(stringResource(R.string.fade), a.fade, 0..60, ::centred) { v -> client.call { it.setBalanceFade(a.balance, v) } }
        }
        Card(stringResource(R.string.audio_outputs)) {
            SwitchRow(stringResource(R.string.subwoofer), a.subwoofer) { on -> client.call { it.setSubwoofer(on, a.subLevel) } }
            SliderRow(stringResource(R.string.sub_level), a.subLevel, 0..12, {
                "${it - 5} dB"
            }) { v -> client.call { it.setSubwoofer(a.subwoofer, v) } }
            SwitchRow(stringResource(R.string.external_amp), a.externalAmp, stringResource(R.string.external_amp_hint)) { on ->
                client.call { it.setExternalAmpEnabled(on) }
            }
        }
    }
}

private fun db(step: Int) = "%+d dB".format((step - 10) * 2)

private fun centred(v: Int) = (v - 30).let { if (it == 0) "0" else "%+d".format(it) }

/** Microphone gain and echo / noise reduction of Jancar's factory settings (see Microphone). */
@Composable
private fun MicrophoneCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mic by org.librehu.service.Microphone
        .get(context)
        .collectAsStateWithLifecycle()
    var now by remember { mutableStateOf<Pair<Int?, Boolean?>>(null to null) }
    LaunchedEffect(mic) {
        kotlinx.coroutines.delay(300)
        now =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.librehu.service.Microphone
                    .current()
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
    }
}
