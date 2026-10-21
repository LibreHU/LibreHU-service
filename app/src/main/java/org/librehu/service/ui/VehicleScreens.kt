package org.librehu.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.core.unit.VehicleInput
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.car.CarName
import org.librehu.service.car.FrontLed
import org.librehu.service.car.LedMode
import org.librehu.service.root.RootShell

// --- Vehicle -----------------------------------------------------------------------------------------------------

@Composable
fun VehicleScreen() {
    Page(stringResource(R.string.tab_vehicle)) {
        CarNameCard()
        VehicleDetailsCard()
        VehicleInputsCard()
    }
}

/** One name for Bluetooth, Android, the network host name and the hotspot. */
@Composable
private fun CarNameCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(CarName.name(context)) }
    var hotspot by remember { mutableStateOf(CarName.renameHotspot(context)) }
    var result by remember { mutableStateOf<CarName.Result?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card(stringResource(R.string.car_name_title)) {
        Hint(stringResource(R.string.car_name_hint))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            singleLine = true,
            label = { Text(stringResource(R.string.car_name_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (name.isNotBlank()) Hint(stringResource(R.string.car_name_host, CarName.hostnameOf(name)))
        SwitchRow(stringResource(R.string.car_name_hotspot), hotspot, stringResource(R.string.car_name_hotspot_hint)) { hotspot = it }
        Actions {
            Pill(stringResource(R.string.car_name_apply), enabled = name.isNotBlank() && !busy) {
                busy = true
                scope.launch {
                    result = withContext(Dispatchers.IO) { CarName.set(context, name, hotspot) }
                    busy = false
                }
            }
        }
        result?.let { r ->
            StatusLine(stringResource(R.string.car_name_bt), r.bluetooth)
            StatusLine(stringResource(R.string.car_name_device), r.deviceName)
            StatusLine(stringResource(R.string.car_name_hostname), r.hostname)
            StatusLine(stringResource(R.string.car_name_wifi_direct), r.wifiDirect)
            if (r.hotspot != CarName.Hotspot.SKIPPED) {
                StatusLine(
                    stringResource(
                        when (r.hotspot) {
                            CarName.Hotspot.DONE -> R.string.car_name_ap_done
                            CarName.Hotspot.AFTER_REBOOT -> R.string.car_name_ap_reboot
                            else -> R.string.car_name_ap_failed
                        },
                    ),
                    r.hotspot != CarName.Hotspot.FAILED,
                )
            }
            if (!r.deviceName || !r.hostname) Hint(stringResource(R.string.car_name_root))
        }
    }
}

/**
 * Make, model, year, colour, plate, fuel and driver's side, and what the phone sees of the head unit (names and MAC
 * addresses): given to the LibreHU apps (launcher SOS screen, Android Auto, CarPlay) by VehicleProvider.
 */
@Composable
private fun VehicleDetailsCard() {
    val context = LocalContext.current
    val v by org.librehu.service.car.VehicleInfo
        .get(context)
        .collectAsStateWithLifecycle()
    var radios by remember { mutableStateOf<org.librehu.service.car.Radios?>(null) }
    LaunchedEffect(Unit) {
        radios =
            withContext(Dispatchers.IO) {
                org.librehu.service.car.VehicleInfo
                    .radios(context)
            }
    }

    fun set(transform: (org.librehu.service.car.Vehicle) -> org.librehu.service.car.Vehicle) =
        org.librehu.service.car.VehicleInfo
            .update(context, transform)
    Card(stringResource(R.string.vehicle_title)) {
        Hint(stringResource(R.string.vehicle_hint))
        VehicleField(stringResource(R.string.vehicle_make), v.make) { t -> set { it.copy(make = t) } }
        VehicleField(stringResource(R.string.vehicle_model), v.model) { t -> set { it.copy(model = t) } }
        VehicleField(stringResource(R.string.vehicle_year), v.year) { t -> set { it.copy(year = t.filter(Char::isDigit).take(4)) } }
        VehicleField(stringResource(R.string.vehicle_color), v.color) { t -> set { it.copy(color = t) } }
        VehicleField(stringResource(R.string.vehicle_plate), v.plate) { t -> set { it.copy(plate = t) } }
        VehicleField(stringResource(R.string.vehicle_fuel), v.fuel) { t -> set { it.copy(fuel = t) } }
        BodyText(stringResource(R.string.vehicle_driver_side))
        Choices(
            listOf(
                org.librehu.service.car.DriverSide.LEFT to stringResource(R.string.vehicle_driver_left),
                org.librehu.service.car.DriverSide.RIGHT to stringResource(R.string.vehicle_driver_right),
            ),
            v.driverSide,
        ) { d -> set { it.copy(driverSide = d) } }
        radios?.let { r ->
            BodyText(stringResource(R.string.vehicle_radios))
            ListRow(
                stringResource(R.string.vehicle_bt),
                listOf(r.bluetoothName, r.bluetoothMac)
                    .filter {
                        it.isNotEmpty()
                    }.joinToString(" · ")
                    .ifEmpty { "?" },
            )
            ListRow(stringResource(R.string.vehicle_wifi), r.wifiMac.ifEmpty { "?" })
            ListRow(stringResource(R.string.vehicle_hotspot), r.hotspotSsid.ifEmpty { "?" })
            ListRow(stringResource(R.string.vehicle_wifi_direct), r.wifiDirectName.ifEmpty { "?" })
            if (r.bluetoothMac.isEmpty() || r.wifiMac.isEmpty()) Hint(stringResource(R.string.vehicle_macs_root))
        }
    }
}

@Composable
private fun VehicleField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.take(40)
            onChange(text)
        },
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Inputs wired the other way round, and the turn signals on SoC GPIOs. */
@Composable
private fun VehicleInputsCard() {
    val context = LocalContext.current
    Card(stringResource(R.string.vehicle_inputs)) {
        var inverted by remember { mutableStateOf(LibreHuService.invertedInputs(context)) }
        Hint(stringResource(R.string.invert_hint))
        for (
        (input, label) in
        listOf(
            VehicleInput.REVERSE to R.string.invert_reverse,
            VehicleInput.HANDBRAKE to R.string.invert_handbrake,
            VehicleInput.HEADLIGHT to R.string.invert_headlight,
            VehicleInput.TURN_LEFT to R.string.invert_turn_left,
            VehicleInput.TURN_RIGHT to R.string.invert_turn_right,
        )
        ) {
            SwitchRow(stringResource(label), input in inverted) { on ->
                LibreHuService.setInverted(context, input, on)
                inverted = LibreHuService.invertedInputs(context)
            }
        }
        var turn by remember { mutableStateOf(LibreHuService.prefs(context).getBoolean(LibreHuService.PREF_TURN_GPIO, true)) }
        SwitchRow(stringResource(R.string.turn_gpio), turn, stringResource(R.string.turn_gpio_hint)) { on ->
            turn = on
            LibreHuService
                .prefs(context)
                .edit()
                .putBoolean(LibreHuService.PREF_TURN_GPIO, on)
                .apply()
        }
    }
}

// --- Front panel LEDs --------------------------------------------------------------------------------------------

@Composable
fun LightsScreen(actions: AppActions) {
    val context = LocalContext.current
    val led = FrontLed.get(context)
    val s by led.settings.collectAsStateWithLifecycle()
    val link by ServiceState.link.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(led.hasNotificationAccess()) }
    Page(stringResource(R.string.tab_lights)) {
        if (!link.jancar || link.state != LibreHuService.Link.RUNNING) Card { Hint(stringResource(R.string.led_offline)) }
        Card(stringResource(R.string.led_title)) {
            SwitchRow(stringResource(R.string.led_managed), s.managed, stringResource(R.string.led_managed_hint)) { on ->
                led.update { it.copy(managed = on) }
            }
            if (s.managed) {
                Choices(
                    listOf(
                        LedMode.MANUAL to stringResource(R.string.led_mode_manual),
                        LedMode.AUTO to stringResource(R.string.led_mode_auto),
                        LedMode.SEMI_AUTO to stringResource(R.string.led_mode_semi),
                    ),
                    s.mode,
                ) { m -> led.update { it.copy(mode = m) } }
                Hint(
                    stringResource(
                        when (s.mode) {
                            LedMode.MANUAL -> R.string.led_mode_manual_hint
                            LedMode.AUTO -> R.string.led_mode_auto_hint
                            LedMode.SEMI_AUTO -> R.string.led_mode_semi_hint
                        },
                    ),
                )
                if (s.mode == LedMode.MANUAL) {
                    ColourChoices(Triple(s.red, s.green, s.blue)) { (r, g, b) -> led.update { it.copy(red = r, green = g, blue = b) } }
                }
            }
        }
        Card(stringResource(R.string.led_blink_title)) {
            SwitchRow(stringResource(R.string.led_blink), s.blinkOnNotification, stringResource(R.string.led_blink_hint)) { on ->
                led.update { it.copy(blinkOnNotification = on) }
            }
            if (s.blinkOnNotification) {
                ColourChoices(Triple(s.blinkRed, s.blinkGreen, s.blinkBlue)) { (r, g, b) ->
                    led.update { it.copy(blinkRed = r, blinkGreen = g, blinkBlue = b) }
                }
                SliderRow(stringResource(R.string.led_blink_count), s.blinkCount, 1..10) { n -> led.update { it.copy(blinkCount = n) } }
                StatusLine(stringResource(if (access) R.string.led_access_ok else R.string.led_access_missing), access)
                Actions {
                    if (!access) {
                        Pill(stringResource(R.string.led_access_open)) { actions.openNotificationAccess() }
                        Pill(stringResource(R.string.led_access_root)) {
                            scope.launch {
                                withContext(Dispatchers.IO) { led.grantNotificationAccessAsRoot() }
                                access = led.hasNotificationAccess()
                            }
                        }
                    }
                    Pill(stringResource(R.string.led_blink_test)) { led.blink() }
                }
            }
        }
    }
}

/** Colour presets on the MCU's 0..99 scale. */
@Composable
private fun ColourChoices(
    selected: Triple<Int, Int, Int>,
    onSelect: (Triple<Int, Int, Int>) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (c in FrontLed.PRESETS) {
            val colour = Color(c.first * 255 / 99, c.second * 255 / 99, c.third * 255 / 99)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colour)
                    .border(if (c == selected) 4.dp else 1.dp, if (c == selected) CarColors.Accent else CarColors.TextDim, CircleShape)
                    .clickable { onSelect(c) },
            )
        }
    }
}

// --- System ------------------------------------------------------------------------------------------------------

@Composable
fun SystemScreen() {
    Page(stringResource(R.string.tab_system)) {
        RootCard()
        StandbyCard()
        BootAppsCard()
        AppDataCard()
        BackupCard()
        WatchdogCard()
    }
}

/** Root status and which manager provides it (Magisk, KernelSU, KernelSU Next…). */
@Composable
fun RootCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf<Boolean?>(null) }
    val manager = remember { RootShell.manager(context) }
    LaunchedEffect(Unit) { granted = withContext(Dispatchers.IO) { RootShell.isAvailable() } }
    Card(stringResource(R.string.root_title)) {
        StatusLine(
            when (granted) {
                null -> stringResource(R.string.root_checking)
                true -> stringResource(R.string.root_granted, manager ?: "su")
                false -> if (manager == null) stringResource(R.string.root_none) else stringResource(R.string.root_denied, manager)
            },
            granted,
        )
        if (granted == false && manager != null) {
            Hint(
                stringResource(
                    if (manager.startsWith("KernelSU") || manager == "APatch") R.string.root_hint_ksu else R.string.root_hint_prompt,
                    manager,
                ),
            )
        }
        Hint(stringResource(R.string.root_uses))
        Actions {
            Pill(stringResource(R.string.root_recheck)) {
                granted = null
                scope.launch { granted = withContext(Dispatchers.IO) { RootShell.recheck() } }
            }
        }
    }
}
