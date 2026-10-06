package org.librehu.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.librehu.core.mcu.JacDebug
import org.librehu.core.mcu.Mcu
import org.librehu.core.mcu.McuFrame
import org.librehu.core.mcu.McuProfiles
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import java.time.LocalDateTime

/**
 * MCU debug tools ported from LibreHU/MCU-tools-app: what the MCU reports, its commands (outputs, PWM / LED, battery
 * thresholds, clock, key learning, CAN box speed) and the simulation of MCU frames through the service's decoder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McuToolsScreen(client: ServiceClient) {
    val link by ServiceState.link.collectAsStateWithLifecycle()
    val m by ServiceState.mcu.collectAsStateWithLifecycle()
    val jancar by rememberUpdatedState(link.jancar)
    val sender = rememberMcuSender(client) { jancar }
    val running = link.state == LibreHuService.Link.RUNNING
    val scope = rememberCoroutineScope()
    val on = stringResource(R.string.on)
    val off = stringResource(R.string.off)

    fun yesNo(v: Boolean?) =
        when (v) {
            true -> on
            false -> off
            null -> "—"
        }

    Page(stringResource(R.string.tab_mcu_tools)) {
        Card(stringResource(R.string.tools_state)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(stringResource(R.string.tools_version), m.version.ifEmpty { "—" })
                Tile("ACC", yesNo(m.acc))
                Tile(stringResource(R.string.tools_handbrake), yesNo(m.handbrake))
                Tile(stringResource(R.string.tools_lights), yesNo(m.headlight))
                Tile(stringResource(R.string.tools_backlight), yesNo(m.backlight))
                Tile(stringResource(R.string.tools_mute), yesNo(m.mute), alert = m.mute == true)
                Tile(stringResource(R.string.tools_antenna), yesNo(m.antenna))
                Tile("Option +0x11", m.option?.toString() ?: "—")
                Tile(stringResource(R.string.tools_mcu_date), m.date.ifEmpty { "—" })
                Tile(stringResource(R.string.tools_mcu_time), m.time.ifEmpty { "—" })
                Tile(stringResource(R.string.tools_last_key), m.lastKey.ifEmpty { "—" })
                Tile(stringResource(R.string.tools_frames), "${m.received} / ${m.sent} / ${m.acks} / ${m.simulated}")
            }
            Hint(stringResource(R.string.tools_state_hint))
            Actions {
                Pill(stringResource(R.string.tools_query_all), enabled = running && jancar) { sender.sendAll(JacDebug.queryAll()) }
                Pill(stringResource(R.string.diag_clear)) { ServiceState.resetMcu() }
            }
        }
        if (!jancar) {
            Card { Hint(stringResource(R.string.tools_not_jancar)) }
        } else {
            Commands(sender, running)
            Simulation(running, scope) { f -> ServiceState.simulator?.invoke(f) }
        }
    }
    McuSenderDialogs(sender)
}

@Composable
private fun Commands(
    sender: McuSender,
    running: Boolean,
) {
    val turnOn = stringResource(R.string.tools_turn_on)
    val turnOff = stringResource(R.string.tools_turn_off)
    Card(stringResource(R.string.tools_outputs)) {
        Hint(stringResource(R.string.tools_outputs_hint))
        CommandRow(stringResource(R.string.tools_mute_cmd)) {
            Pill(stringResource(R.string.tools_cut), enabled = running) { sender.send(Mcu.mute(true)) }
            Pill(stringResource(R.string.tools_restore), enabled = running) { sender.send(Mcu.mute(false)) }
        }
        CommandRow(stringResource(R.string.tools_antenna_cmd)) {
            Pill(turnOn, enabled = running) { sender.send(Mcu.antenna(true)) }
            Pill(turnOff, enabled = running) { sender.send(Mcu.antenna(false)) }
        }
        CommandRow(stringResource(R.string.tools_rem)) {
            Pill(turnOn, enabled = running) { sender.send(Mcu.externalAmp(true)) }
            Pill(turnOff, enabled = running) { sender.send(Mcu.externalAmp(false)) }
        }
    }
    Card(stringResource(R.string.tools_lighting)) {
        var pwm1 by remember { mutableIntStateOf(60) }
        var pwm2 by remember { mutableIntStateOf(50) }
        SliderRow("PWM CH1 (0F 02)", pwm1, 5..100, { "$it %" }) { v ->
            pwm1 = v
            if (running) sender.send(JacDebug.pwm(1, v))
        }
        SliderRow("PWM CH2 (0F 03)", pwm2, 5..100, { "$it %" }) { v ->
            pwm2 = v
            if (running) sender.send(JacDebug.pwm(2, v))
        }
        Text(stringResource(R.string.tools_led), color = CarColors.Text, fontSize = 18.sp)
        var red by remember { mutableIntStateOf(50) }
        var green by remember { mutableIntStateOf(50) }
        var blue by remember { mutableIntStateOf(50) }
        var mode by remember { mutableIntStateOf(JacDebug.LED_MANUAL) }
        SliderRow(stringResource(R.string.tools_red), red, 0..99) { red = it }
        SliderRow(stringResource(R.string.tools_green), green, 0..99) { green = it }
        SliderRow(stringResource(R.string.tools_blue), blue, 0..99) { blue = it }
        Choices(
            listOf(
                JacDebug.LED_AUTO to stringResource(R.string.tools_led_auto),
                JacDebug.LED_MANUAL to stringResource(R.string.tools_led_manual),
                JacDebug.LED_SEMI_AUTO to stringResource(R.string.tools_led_semi),
            ),
            mode,
        ) { mode = it }
        Actions { Pill(stringResource(R.string.diag_send_button), enabled = running) { sender.send(JacDebug.led(red, green, blue, mode)) } }
        Hint(stringResource(R.string.tools_flash_hint))
    }
    Card(stringResource(R.string.tools_battery)) {
        CommandRow(stringResource(R.string.tools_high)) {
            JacDebug.HIGH_VOLTS.forEachIndexed { i, v -> Pill("$v V", enabled = running) { sender.send(JacDebug.highVoltage(i)) } }
        }
        CommandRow(stringResource(R.string.tools_low)) {
            JacDebug.LOW_VOLTS.forEachIndexed { i, v -> Pill("$v V", enabled = running) { sender.send(JacDebug.lowVoltage(i)) } }
        }
        Hint(stringResource(R.string.tools_flash_hint))
    }
    Card(stringResource(R.string.tools_clock)) {
        CommandRow(stringResource(R.string.tools_clock_cmd)) {
            Pill(stringResource(R.string.tools_send_time), enabled = running) {
                val t = LocalDateTime.now()
                sender.send(Mcu.date(t.year, t.monthValue, t.dayOfMonth))
                sender.send(Mcu.time(t.hour, t.minute, t.second))
            }
            Pill(stringResource(R.string.tools_read_time), enabled = running) { sender.send(Mcu.query(Mcu.CMD_DATE_TIME)) }
        }
        CommandRow(stringResource(R.string.tools_learn_wheel)) {
            Pill(stringResource(R.string.tools_start), enabled = running) { sender.send(JacDebug.learn(panel = false, start = true)) }
            Pill(stringResource(R.string.tools_end), enabled = running) { sender.send(JacDebug.learn(panel = false, start = false)) }
        }
        CommandRow(stringResource(R.string.tools_learn_panel)) {
            Pill(stringResource(R.string.tools_start), enabled = running) { sender.send(JacDebug.learn(panel = true, start = true)) }
            Pill(stringResource(R.string.tools_end), enabled = running) { sender.send(JacDebug.learn(panel = true, start = false)) }
        }
    }
    Card(stringResource(R.string.tools_can)) {
        Actions {
            JacDebug.CAN_BAUDS.forEachIndexed { i, b -> Pill(b.toString(), enabled = running) { sender.send(JacDebug.canBaud(i)) } }
        }
        Hint(stringResource(R.string.tools_can_hint))
    }
}

@Composable
private fun Simulation(
    running: Boolean,
    scope: CoroutineScope,
    one: (McuFrame) -> Unit,
) {
    fun clock() {
        val t = LocalDateTime.now()
        one(Mcu.date(t.year, t.monthValue, t.dayOfMonth))
        one(Mcu.time(t.hour, t.minute, t.second))
    }
    val turnOn = stringResource(R.string.tools_turn_on)
    val turnOff = stringResource(R.string.tools_turn_off)
    Card(stringResource(R.string.tools_sim)) {
        Hint(stringResource(R.string.tools_sim_hint))
        if (!running) Hint(stringResource(R.string.tools_sim_off))
        val states =
            listOf(
                Mcu.CMD_ACC to "ACC (00)",
                Mcu.CMD_HANDBRAKE to stringResource(R.string.tools_sim_handbrake),
                Mcu.CMD_HEADLIGHT to stringResource(R.string.tools_sim_lights),
                Mcu.CMD_MUTE to stringResource(R.string.tools_mute_cmd),
                Mcu.CMD_ANTENNA to stringResource(R.string.tools_antenna_cmd),
            )
        for ((cmd, label) in states) {
            CommandRow(label) {
                Pill(turnOn, enabled = running) { one(JacDebug.simState(cmd, true)) }
                Pill(turnOff, enabled = running) { one(JacDebug.simState(cmd, false)) }
            }
        }
        for ((channel, label) in listOf(5 to R.string.tools_sim_key1, 6 to R.string.tools_sim_key2)) {
            CommandRow(stringResource(label)) {
                Pill(stringResource(R.string.tools_tap), enabled = running) {
                    scope.launch {
                        one(JacDebug.simWheelKey(channel, true))
                        delay(150)
                        one(JacDebug.simWheelKey(channel, false))
                    }
                }
                Pill(stringResource(R.string.tools_press), enabled = running) { one(JacDebug.simWheelKey(channel, true)) }
                Pill(stringResource(R.string.tools_release), enabled = running) { one(JacDebug.simWheelKey(channel, false)) }
            }
        }
        CommandRow(stringResource(R.string.tools_sim_boot)) {
            // Same timing as the MCU (mcu_firmware.md §5): ACC, version 200 ms later, date / time 400 ms later.
            Pill(stringResource(R.string.tools_replay), enabled = running) {
                scope.launch {
                    one(JacDebug.simState(Mcu.CMD_ACC, true))
                    delay(200)
                    one(JacDebug.simVersion())
                    delay(200)
                    clock()
                }
            }
            Pill(stringResource(R.string.tools_sim_version), enabled = running) {
                one(JacDebug.simVersion())
                clock()
            }
        }
        var raw by remember { mutableStateOf("0B 01") }
        var error by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tools_sim_free), color = CarColors.Text, fontSize = 17.sp)
            OutlinedTextField(value = raw, onValueChange = {
                raw = it
                error = false
            }, singleLine = true, isError = error, label = { Text("0B 01") })
            Actions {
                Pill(stringResource(R.string.tools_inject), enabled = running) {
                    try {
                        one(McuProfiles.template(raw, emptyMap(), ByteArray(0)))
                    } catch (_: Exception) {
                        error = true
                    }
                }
            }
        }
    }
}

/** Label above a row of pills. */
@Composable
private fun CommandRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = CarColors.Text, fontSize = 17.sp)
        Actions(content)
    }
}
