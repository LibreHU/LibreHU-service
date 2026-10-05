package org.librehu.service.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.core.touch.TouchAction
import org.librehu.core.touch.TouchSample
import org.librehu.core.touch.TouchZone
import org.librehu.core.touch.ZoneAction
import org.librehu.service.R
import org.librehu.service.root.RootShell
import org.librehu.service.touch.TouchKeys
import org.librehu.service.touch.TouchPanel

/** Touch panel: calibration, and the front panel "buttons" that are part of the touch panel. */
@Composable
fun TouchScreen(actions: AppActions) {
    val context = LocalContext.current
    val panel = TouchPanel.get(context)
    val keys = TouchKeys.get(context)
    val enabled by keys.enabled.collectAsStateWithLifecycle()
    val zones by keys.zones.collectAsStateWithLifecycle()
    val last by panel.last.collectAsStateWithLifecycle()
    val readerError by panel.readerError.collectAsStateWithLifecycle()
    val lastAction by keys.lastAction.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TouchZone?>(null) }
    var matrixText by remember { mutableStateOf("") }
    var root by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf("") }

    // Live raw coordinates while this page is open (learning a zone needs them).
    DisposableEffect(Unit) {
        val l: (TouchSample) -> Unit = {}
        panel.addListener(l)
        Thread {
            root = RootShell.isAvailable()
            matrixText = panel.readMatrix()?.toString() ?: "—"
        }.start()
        onDispose { panel.removeListener(l) }
    }

    Page(stringResource(R.string.tab_touch)) {
        Card(stringResource(R.string.touch_calibration)) {
            Hint(stringResource(R.string.touch_calibration_hint))
            BodyText(stringResource(R.string.touch_matrix, matrixText), mono = true)
            if (root == false) Hint(stringResource(R.string.touch_no_root))
            Actions {
                Pill(stringResource(R.string.touch_calibrate), enabled = root == true, onClick = actions.calibrateTouch)
                Pill(stringResource(R.string.touch_factory), enabled = root == true) {
                    Thread {
                        message =
                            context.getString(if (panel.restoreFactory()) R.string.touch_factory_done else R.string.touch_factory_none)
                        matrixText = panel.readMatrix()?.toString() ?: "—"
                    }.start()
                }
                Pill(stringResource(R.string.touch_restore), enabled = root == true && panel.backupMatrix() != null) {
                    Thread {
                        message = context.getString(if (panel.restoreBackup()) R.string.touch_restored else R.string.touch_write_failed)
                        matrixText = panel.readMatrix()?.toString() ?: "—"
                    }.start()
                }
            }
            if (message.isNotEmpty()) Hint(message)
        }

        Card(stringResource(R.string.touch_keys)) {
            Hint(stringResource(R.string.touch_keys_hint))
            SwitchRow(stringResource(R.string.touch_keys_enable), enabled, stringResource(R.string.touch_keys_ivi)) { keys.setEnabled(it) }
            BodyText(
                if (readerError.isNotEmpty()) {
                    stringResource(R.string.touch_reader_error, readerError)
                } else {
                    last?.let {
                        stringResource(
                            R.string.touch_live,
                            it.x,
                            it.y,
                            stringResource(if (it.down) R.string.touch_down else R.string.touch_up),
                        )
                    }
                        ?: stringResource(R.string.touch_live_none)
                },
                mono = true,
            )
            if (lastAction.isNotEmpty()) Hint(lastAction)
            for (z in zones) {
                ListRow(
                    z.name.ifEmpty { "#${z.id}" },
                    "(${z.x}, ${z.y}) r${z.radius}  ·  ${actionLabel(z.click)}" +
                        if (z.repeat) {
                            " ↻"
                        } else if (z.longPress.action != TouchAction.NONE) {
                            " / ${actionLabel(z.longPress)}"
                        } else {
                            ""
                        },
                    onClick = { editing = z },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) { Pill(stringResource(R.string.touch_test)) { keys.run(z, z.click) } }
                }
            }
            Actions {
                Pill(stringResource(R.string.touch_add_zone), icon = Icons.Default.Add, enabled = last != null) {
                    val s = last ?: return@Pill
                    editing = TouchZone(keys.nextId(), "", s.x, s.y)
                }
            }
            Hint(stringResource(R.string.touch_add_hint))
        }
    }

    editing?.let { z ->
        ZoneDialog(z, last, onSave = {
            keys.upsert(it)
            editing = null
        }, onDelete = {
            keys.delete(z.id)
            editing = null
        }, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneDialog(
    initial: TouchZone,
    last: TouchSample?,
    onSave: (TouchZone) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var z by remember(initial) { mutableStateOf(initial) }
    var pickingLong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.touch_zone)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = z.name, onValueChange = {
                    z = z.copy(name = it)
                }, singleLine = true, label = { Text(stringResource(R.string.touch_zone_name)) })
                Text("(${z.x}, ${z.y})")
                Actions {
                    Pill(stringResource(R.string.touch_zone_here), enabled = last != null) { last?.let { z = z.copy(x = it.x, y = it.y) } }
                }
                SliderRow(stringResource(R.string.touch_zone_radius), z.radius, 10..150) { z = z.copy(radius = it) }
                SwitchRow(stringResource(R.string.touch_zone_repeat), z.repeat) { z = z.copy(repeat = it) }
                Choices(
                    listOf(false to stringResource(R.string.touch_zone_click), true to stringResource(R.string.touch_zone_long)),
                    pickingLong,
                ) {
                    pickingLong =
                        it
                }
                val current = if (pickingLong) z.longPress else z.click
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in TouchAction.entries) {
                        Pill(actionName(a), selected = current.action == a) {
                            val v = current.copy(action = a)
                            z = if (pickingLong) z.copy(longPress = v) else z.copy(click = v)
                        }
                    }
                }
                if (current.action == TouchAction.LAUNCH_APP || current.action == TouchAction.KEYCODE) {
                    OutlinedTextField(
                        value = current.arg,
                        onValueChange = { t ->
                            val v = current.copy(arg = t.trim())
                            z = if (pickingLong) z.copy(longPress = v) else z.copy(click = v)
                        },
                        singleLine = true,
                        label = {
                            Text(
                                stringResource(
                                    if (current.action ==
                                        TouchAction.KEYCODE
                                    ) {
                                        R.string.touch_keycode
                                    } else {
                                        R.string.touch_package
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(z) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.mcu_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

@Composable
fun actionLabel(a: ZoneAction): String = actionName(a.action) + if (a.arg.isNotEmpty()) " ${a.arg}" else ""

@Composable
fun actionName(a: TouchAction): String =
    stringResource(
        when (a) {
            TouchAction.NONE -> R.string.ta_none
            TouchAction.HOME -> R.string.ta_home
            TouchAction.BACK -> R.string.ta_back
            TouchAction.RECENTS -> R.string.ta_recents
            TouchAction.VOLUME_UP -> R.string.ta_vol_up
            TouchAction.VOLUME_DOWN -> R.string.ta_vol_down
            TouchAction.MUTE -> R.string.ta_mute
            TouchAction.PLAY_PAUSE -> R.string.ta_play_pause
            TouchAction.NEXT -> R.string.ta_next
            TouchAction.PREVIOUS -> R.string.ta_previous
            TouchAction.SCREEN_OFF -> R.string.ta_screen_off
            TouchAction.LAUNCH_APP -> R.string.ta_launch
            TouchAction.KEYCODE -> R.string.ta_keycode
        },
    )
