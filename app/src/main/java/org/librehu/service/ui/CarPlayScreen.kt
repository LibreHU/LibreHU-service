package org.librehu.service.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.librehu.service.R
import org.librehu.service.projection.CarPlay

/** CarPlay through the LibreHU fork of xcertplay: session, opening, day / night from the headlights. */
@Composable
fun CarPlayScreen() {
    val context = LocalContext.current
    val session by CarPlay.session.collectAsStateWithLifecycle()
    val settings by CarPlay.settings.collectAsStateWithLifecycle()
    var app by remember { mutableStateOf(CarPlay.installed(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            app = CarPlay.installed(context)
            delay(5000)
        }
    }
    Page(stringResource(R.string.tab_carplay)) {
        Card(stringResource(R.string.cp_title)) {
            when (app) {
                CarPlay.PACKAGE -> StatusLine(stringResource(R.string.cp_app_librehu), true)
                CarPlay.UPSTREAM -> StatusLine(stringResource(R.string.cp_app_upstream), true)
                else -> StatusLine(stringResource(R.string.cp_app_none), false)
            }
            if (app == CarPlay.PACKAGE) {
                val state =
                    when (session.state) {
                        "" -> stringResource(R.string.aa_state_unknown)
                        "connecting" -> stringResource(R.string.aa_state_connecting)
                        "active" -> stringResource(R.string.cp_state_active)
                        "ended" -> stringResource(R.string.aa_state_disconnected)
                        "failed" -> stringResource(R.string.aa_state_failed)
                        else -> session.state
                    }
                val ago = if (session.at == 0L) "" else " · " + ((SystemClock.elapsedRealtime() - session.at) / 1000).let { "$it s" }
                StatusLine(
                    listOfNotNull(state, session.detail.takeIf { it.isNotEmpty() }).joinToString(" · ") + ago,
                    if (session.state.isEmpty()) null else session.state != "failed",
                )
            }
            if (app != null) {
                Actions { Pill(stringResource(R.string.aa_open)) { CarPlay.open(context) } }
            }
            Hint(stringResource(R.string.cp_hint))
        }
        if (app == CarPlay.PACKAGE) {
            Card(stringResource(R.string.aa_integration)) {
                SwitchRow(
                    stringResource(R.string.aa_night_headlights),
                    settings.nightFromHeadlights,
                    stringResource(R.string.cp_night_hint),
                ) { on -> CarPlay.update(context) { it.copy(nightFromHeadlights = on) } }
                Hint(stringResource(R.string.cp_integration_hint))
            }
        }
    }
}
