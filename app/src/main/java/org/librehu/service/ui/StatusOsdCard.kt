package org.librehu.service.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.service.R
import org.librehu.service.overlay.StatusOsd

/** On-screen indicators of the vehicle inputs (headlights, reverse, parking brake, turn signals). */
@Composable
fun StatusOsdCard() {
    val osd = StatusOsd.get(LocalContext.current)
    val s by osd.settings.collectAsStateWithLifecycle()
    Card(stringResource(R.string.osd_title)) {
        SwitchRow(stringResource(R.string.osd_enable), s.enabled, stringResource(R.string.osd_hint)) { on ->
            osd.update { it.copy(enabled = on) }
        }
        if (s.enabled) {
            SwitchRow(stringResource(R.string.osd_headlights), s.headlights) { on -> osd.update { it.copy(headlights = on) } }
            SwitchRow(stringResource(R.string.osd_reverse), s.reverse) { on -> osd.update { it.copy(reverse = on) } }
            SwitchRow(stringResource(R.string.osd_handbrake), s.handbrake) { on -> osd.update { it.copy(handbrake = on) } }
            SwitchRow(stringResource(R.string.osd_turn), s.turnSignals, stringResource(R.string.osd_turn_hint)) { on ->
                osd.update { it.copy(turnSignals = on) }
            }
            Choices(
                listOf(true to stringResource(R.string.osd_top), false to stringResource(R.string.osd_bottom)),
                s.top,
            ) { top -> osd.update { it.copy(top = top) } }
            SliderRow(stringResource(R.string.osd_duration), s.durationMs / 500, 2..12, { "${it * 0.5} s" }) { v ->
                osd.update { it.copy(durationMs = v * 500) }
            }
            Actions { Pill(stringResource(R.string.osd_test)) { osd.test() } }
        }
    }
}
