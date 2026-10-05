package org.librehu.service.ui

import android.location.GnssStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.time.TimeController
import java.text.DateFormat
import java.util.Date

/** GPS test, and where the clock comes from: MCU RTC, Android, GPS. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GpsTimeScreen(actions: AppActions) {
    val time = TimeController.get(LocalContext.current)
    val s by time.settings.collectAsStateWithLifecycle()
    val gps by time.gps.collectAsStateWithLifecycle()
    val link by ServiceState.link.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(Unit) {
        time.watch(true)
        onDispose { time.watch(false) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }
    val mcuRunning = link.state == LibreHuService.Link.RUNNING

    Page(stringResource(R.string.tab_gps)) {
        Card(stringResource(R.string.gps_test)) {
            val used = gps.satellites.count { it.usedInFix }
            val fix = gps.fix
            StatusLine(
                when {
                    !gps.enabled -> stringResource(R.string.gps_disabled)
                    fix != null && System.currentTimeMillis() - fix.time < 10_000 -> stringResource(R.string.gps_fix, used)
                    else -> stringResource(R.string.gps_searching, gps.satellites.size)
                },
                if (!gps.enabled) {
                    false
                } else if (fix != null) {
                    true
                } else {
                    null
                },
            )
            if (gps.message.isNotEmpty()) Hint(gps.message)
            if (!gps.enabled) Actions { Pill(stringResource(R.string.gps_open_settings), onClick = actions.openLocationSettings) }
            Actions { Pill(stringResource(R.string.bt_permissions), onClick = actions.requestBtPermissions) }
            if (fix != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(stringResource(R.string.gps_position), "%.5f, %.5f".format(fix.latitude, fix.longitude))
                    Tile(stringResource(R.string.gps_accuracy), "± %.0f m".format(fix.accuracy))
                    Tile(stringResource(R.string.gps_speed), "%.0f km/h".format(fix.speed * 3.6))
                    Tile(stringResource(R.string.gps_altitude), "%.0f m".format(fix.altitude))
                    Tile(stringResource(R.string.gps_time), DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(fix.time)))
                    if (gps.firstFixMs >= 0) Tile(stringResource(R.string.gps_ttff), "%.1f s".format(gps.firstFixMs / 1000.0))
                }
            }
            if (gps.satellites.isNotEmpty()) {
                Hint(stringResource(R.string.gps_satellites, gps.satellites.size, used))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sat in gps.satellites.take(32)) {
                        Pill("${constellation(sat.constellation)}${sat.svid} · ${sat.cn0.toInt()} dB", selected = sat.usedInFix) {}
                    }
                }
            }
        }

        Card(stringResource(R.string.time_clock)) {
            BodyText(stringResource(R.string.time_android_now, df.format(Date(now))))
            if (gps.lastSync > 0) {
                Hint(stringResource(R.string.time_last_gps, df.format(Date(gps.lastSync)), "%+.1f s".format(gps.lastSyncDeltaMs / 1000.0)))
            }
            SwitchRow(stringResource(R.string.time_from_gps), s.fromGps, stringResource(R.string.time_from_gps_hint)) { on ->
                time.update {
                    it.copy(fromGps = on)
                }
            }
            if (s.fromGps) {
                BodyText(stringResource(R.string.time_interval))
                Choices(
                    TimeController.INTERVALS.map { it to intervalLabel(it) },
                    s.gpsIntervalMin,
                ) { m -> time.update { it.copy(gpsIntervalMin = m) } }
            }
            SwitchRow(stringResource(R.string.time_from_mcu), s.fromMcu, stringResource(R.string.time_from_mcu_hint)) { on ->
                time.update {
                    it.copy(fromMcu = on)
                }
            }
            SwitchRow(stringResource(R.string.time_to_mcu), s.toMcu, stringResource(R.string.time_to_mcu_hint)) { on ->
                time.update {
                    it.copy(toMcu = on)
                }
            }
            Actions {
                Pill(stringResource(R.string.time_sync_gps), enabled = gps.enabled) { time.syncFromGps() }
                Pill(stringResource(R.string.time_sync_to_mcu), enabled = mcuRunning) { time.mcu?.pushToMcu() }
                Pill(stringResource(R.string.time_sync_from_mcu), enabled = mcuRunning) { time.mcu?.pullFromMcu() }
                Pill(stringResource(R.string.time_android_settings), onClick = actions.openDateSettings)
            }
            if (!mcuRunning) Hint(stringResource(R.string.time_mcu_offline))
            Hint(stringResource(R.string.time_timezone_hint))
        }
    }
}

@Composable
private fun intervalLabel(min: Int): String =
    if (min <
        60
    ) {
        stringResource(R.string.time_minutes, min)
    } else {
        stringResource(R.string.time_hours, min / 60)
    }

private fun constellation(type: Int): String =
    when (type) {
        GnssStatus.CONSTELLATION_GPS -> "G"
        GnssStatus.CONSTELLATION_GLONASS -> "R"
        GnssStatus.CONSTELLATION_BEIDOU -> "C"
        GnssStatus.CONSTELLATION_GALILEO -> "E"
        GnssStatus.CONSTELLATION_QZSS -> "J"
        GnssStatus.CONSTELLATION_SBAS -> "S"
        else -> "?"
    }
