package org.librehu.service.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.service.R
import org.librehu.service.projection.AndroidAuto
import org.librehu.service.projection.HeadunitApp

/** Android Auto through Open Headunit: session, commands, day / night from the headlights, its settings. */
@Composable
fun AndroidAutoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by AndroidAuto.session.collectAsStateWithLifecycle()
    val settings by AndroidAuto.settings.collectAsStateWithLifecycle()
    var app by remember { mutableStateOf(AndroidAuto.installed(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            app = AndroidAuto.installed(context)
            delay(5000)
        }
    }
    Page(stringResource(R.string.tab_android_auto)) {
        Card(stringResource(R.string.aa_title)) {
            when (app) {
                HeadunitApp.LIBREHU -> StatusLine(stringResource(R.string.aa_app_librehu), true)
                HeadunitApp.UPSTREAM -> StatusLine(stringResource(R.string.aa_app_upstream), true)
                null -> StatusLine(stringResource(R.string.aa_app_none), false)
            }
            if (app != null) {
                val state =
                    when (session.state) {
                        "" -> stringResource(R.string.aa_state_unknown)
                        "connecting" -> stringResource(R.string.aa_state_connecting)
                        "connected" -> stringResource(R.string.aa_state_connected)
                        "projecting" -> stringResource(R.string.aa_state_projecting)
                        "disconnected" -> stringResource(R.string.aa_state_disconnected)
                        "failed" -> stringResource(R.string.aa_state_failed)
                        else -> session.state
                    }
                val ago = if (session.at == 0L) "" else " · " + ((SystemClock.elapsedRealtime() - session.at) / 1000).let { "$it s" }
                StatusLine(
                    listOfNotNull(state, session.transport.takeIf { it.isNotEmpty() }, session.reason.takeIf { it.isNotEmpty() })
                        .joinToString(" · ") + ago,
                    if (session.state.isEmpty()) null else session.state != "failed",
                )
                Actions {
                    Pill(stringResource(R.string.aa_open)) { AndroidAuto.open(context) }
                    Pill(stringResource(R.string.aa_check_usb)) { AndroidAuto.send(context, AndroidAuto.CHECK_USB) }
                    Pill(stringResource(R.string.aa_disconnect), enabled = session.active) {
                        AndroidAuto.send(context, AndroidAuto.DISCONNECT)
                    }
                    Pill(stringResource(R.string.aa_restart_audio), enabled = session.active) {
                        AndroidAuto.send(context, AndroidAuto.RESTART_AUDIO)
                    }
                }
            }
            Hint(stringResource(R.string.aa_hint))
        }
        if (app != null) {
            Card(stringResource(R.string.aa_integration)) {
                SwitchRow(
                    stringResource(R.string.aa_night_headlights),
                    settings.nightFromHeadlights,
                    stringResource(R.string.aa_night_headlights_hint),
                ) { on -> AndroidAuto.update(context) { it.copy(nightFromHeadlights = on) } }
                Hint(stringResource(R.string.aa_integration_hint))
            }
            HeadunitSettingsCard(app == HeadunitApp.LIBREHU, scope)
        }
    }
}

/**
 * Open Headunit's own settings, read and written through its automation commands (ACTION_GET_SETTINGS /
 * ACTION_SET_SETTINGS): every stored setting, edited by its type.
 */
@Composable
private fun HeadunitSettingsCard(
    fork: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    var values by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            AndroidAuto
                .readSettings(context)
                .onSuccess {
                    values = it
                    message = null
                }.onFailure { message = context.getString(R.string.aa_settings_refused, it.message) }
        }
    }

    fun write(
        key: String,
        value: Any,
    ) {
        scope.launch {
            AndroidAuto
                .writeSettings(context, JSONObject().put(key, value))
                .onSuccess { reply ->
                    values = JSONObject(values.toString()).put(key, value)
                    message =
                        context.getString(
                            if (reply.optBoolean("restartNeeded")) R.string.aa_settings_saved_restart else R.string.aa_settings_saved,
                        )
                }.onFailure { message = context.getString(R.string.aa_settings_refused, it.message) }
        }
    }
    LaunchedEffect(Unit) { load() }
    Card(stringResource(R.string.aa_settings_title)) {
        Hint(stringResource(if (fork) R.string.aa_settings_hint_fork else R.string.aa_settings_hint_upstream))
        message?.let { Hint(it) }
        Actions {
            Pill(stringResource(R.string.aa_settings_reload)) { load() }
            Pill(stringResource(R.string.aa_open)) { AndroidAuto.open(context) }
        }
        val v = values ?: return@Card
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            label = { Text(stringResource(R.string.aa_settings_filter)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val keys =
            v
                .keys()
                .asSequence()
                .sorted()
                .filter { filter.isBlank() || it.contains(filter.trim(), ignoreCase = true) }
                .toList()
        if (keys.isEmpty()) Hint(stringResource(R.string.aa_settings_empty))
        for (key in keys) {
            when (val value = v.opt(key)) {
                is Boolean -> {
                    SwitchRow(key, value) { on -> write(key, on) }
                }

                is Int, is Long -> {
                    NumberSetting(key, (value as Number).toLong()) { write(key, it.toInt()) }
                }

                is String -> {
                    TextSetting(key, value) { write(key, it) }
                }

                is JSONArray -> {
                    ListRow(key, (0 until value.length()).joinToString(", ") { value.optString(it) })
                }

                else -> {
                    ListRow(key, value.toString())
                }
            }
        }
    }
}

@Composable
private fun NumberSetting(
    key: String,
    value: Long,
    onSave: (Long) -> Unit,
) {
    var text by remember(key, value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t.filter { it.isDigit() || it == '-' } },
        singleLine = true,
        label = { Text(key) },
        trailingIcon = {
            val n = text.toLongOrNull()
            if (n != null && n != value) Pill(stringResource(R.string.aa_settings_save)) { onSave(n) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextSetting(
    key: String,
    value: String,
    onSave: (String) -> Unit,
) {
    var text by remember(key, value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        label = { Text(key) },
        trailingIcon = {
            if (text != value) Pill(stringResource(R.string.aa_settings_save)) { onSave(text) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
