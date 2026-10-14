package org.librehu.service.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.car.CvbsActivity
import org.librehu.service.car.CvbsInput
import org.librehu.service.car.CvbsPosition
import org.librehu.service.car.VideoOut

/** CVBS IN 1 / 2 (video only), the CVBS rear camera's input and the rear video output (VIDEO OUT / AUDIO OUT). */
@Composable
fun VideoScreen() {
    Page(stringResource(R.string.tab_video)) {
        CvbsInputsCard()
        CvbsReverseCard()
        VideoOutCard()
        Card(stringResource(R.string.cvbs_audio_out_title)) { Hint(stringResource(R.string.cvbs_audio_out_hint)) }
    }
}

@Composable
private fun CvbsInputsCard() {
    val context = LocalContext.current
    val s by CvbsInput.get(context).collectAsStateWithLifecycle()
    val positions = CvbsPosition.entries.map { it to stringResource(it.label) }
    Card(stringResource(R.string.cvbs_inputs_title)) {
        Hint(stringResource(R.string.cvbs_inputs_hint))
        Actions {
            Pill(stringResource(R.string.cvbs_in_n, 1)) { CvbsActivity.open(context, 1) }
            Pill(stringResource(R.string.cvbs_in_n, 2)) { CvbsActivity.open(context, 2) }
            Pill(stringResource(R.string.cvbs_test)) { CvbsActivity.open(context, 1, test = true) }
        }
        BodyText(stringResource(R.string.cvbs_in_position, 1))
        Choices(positions, s.in1) { p -> CvbsInput.update(context) { it.copy(in1 = p) } }
        BodyText(stringResource(R.string.cvbs_in_position, 2))
        Choices(positions, s.in2) { p -> CvbsInput.update(context) { it.copy(in2 = p) } }
        Hint(stringResource(R.string.cvbs_position_hint))
        SwitchRow(stringResource(R.string.cvbs_handbrake), s.handbrakeOnly, stringResource(R.string.cvbs_handbrake_hint)) { on ->
            CvbsInput.update(context) { it.copy(handbrakeOnly = on) }
        }
        BodyText(stringResource(R.string.cvbs_camera))
        Choices((0..15).map { it.toString() to it.toString() }, s.cameraId) { id -> CvbsInput.update(context) { it.copy(cameraId = id) } }
        Hint(stringResource(R.string.cvbs_camera_hint))
    }
}

@Composable
private fun CvbsReverseCard() {
    val context = LocalContext.current
    val s by CvbsInput.get(context).collectAsStateWithLifecycle()
    Card(stringResource(R.string.cvbs_reverse_title)) {
        SwitchRow(stringResource(R.string.cvbs_reverse_switch), s.switchForReverse, stringResource(R.string.cvbs_reverse_hint)) { on ->
            CvbsInput.update(context) { it.copy(switchForReverse = on) }
        }
        if (s.switchForReverse) {
            Choices(CvbsPosition.entries.map { it to stringResource(it.label) }, s.reverse) { p ->
                CvbsInput.update(context) { it.copy(reverse = p) }
            }
        }
    }
}

/**
 * VIDEO OUT: a USB display chip fed by Jancar's `com.jancar.usbdisplay` ([VideoOut]): chip detection, on / off
 * (`key_rear_display`, root) and Jancar's own output settings.
 */
@Composable
private fun VideoOutCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val installed = remember(revision) { VideoOut.installed(context) }
    val enabled = remember(revision) { VideoOut.enabled(context) }
    val devices = remember(revision) { VideoOut.usbDevices(context) }
    val sending = remember(revision) { VideoOut.sending() }
    val chip = devices.any { it.second }
    val noRoot = stringResource(R.string.atc_no_root)
    Card(stringResource(R.string.cvbs_out_title)) {
        Hint(stringResource(R.string.cvbs_out_hint))
        StatusLine(
            stringResource(if (installed) R.string.cvbs_out_app_ok else R.string.cvbs_out_app_missing),
            installed,
        )
        StatusLine(stringResource(if (chip) R.string.cvbs_out_chip_ok else R.string.cvbs_out_chip_missing), chip)
        if (devices.isEmpty()) {
            Hint(stringResource(R.string.cvbs_out_usb_none))
        } else {
            for ((d, isChip) in devices) BodyText((if (isChip) "▶ " else "· ") + d, mono = true)
        }
        sending?.let { Hint(stringResource(if (it) R.string.cvbs_out_sending else R.string.cvbs_out_stopped)) }
        SwitchRow(
            stringResource(R.string.cvbs_out_enable),
            enabled,
            stringResource(R.string.cvbs_out_enable_hint),
            enabled = installed,
        ) { on ->
            scope.launch {
                val err = withContext(Dispatchers.IO) { VideoOut.setEnabled(context, on) }
                status = if (err == "root") noRoot else err
                // CaptureService needs a moment to find the chip and start sending.
                kotlinx.coroutines.delay(2500)
                revision++
            }
        }
        Actions {
            Pill(stringResource(R.string.cvbs_out_settings), enabled = installed) {
                if (!VideoOut.openSettings(context)) status = context.getString(R.string.cvbs_out_app_missing)
            }
            Pill(stringResource(R.string.cvbs_out_search), enabled = installed) {
                VideoOut.refresh(context)
                scope.launch {
                    kotlinx.coroutines.delay(1500)
                    revision++
                }
            }
            Pill(stringResource(R.string.cvbs_out_reload)) { revision++ }
        }
        if (!chip) Hint(stringResource(R.string.cvbs_out_chip_hint))
        status?.let { StatusLine(it, false) }
    }
}
