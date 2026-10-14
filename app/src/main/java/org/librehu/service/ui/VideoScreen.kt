package org.librehu.service.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import org.librehu.service.atc.AtcTools
import org.librehu.service.atc.Mtz
import org.librehu.service.car.CvbsActivity
import org.librehu.service.car.CvbsInput
import org.librehu.service.car.CvbsPosition
import org.librehu.service.car.VideoOutSettings

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
 * VIDEO OUT: Autochips' rear display switch (metazone, read at start-up) and the format / borders Jancar's "Rear output
 * settings" set (root).
 */
@Composable
private fun VideoOutCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val out by CvbsInput.videoOut(context).collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var rebootNeeded by remember { mutableStateOf(false) }
    val noRoot = stringResource(R.string.atc_no_root)
    val applied = stringResource(R.string.cvbs_out_applied)
    LaunchedEffect(Unit) {
        when (val r = withContext(Dispatchers.IO) { AtcTools.read(context, listOf(Mtz.REAR_DISPLAY)) }) {
            is AtcTools.Result.Values -> enabled = r.values[Mtz.REAR_DISPLAY] == 1
            is AtcTools.Result.Error -> status = r.message
            AtcTools.Result.NoRoot -> status = noRoot
        }
    }

    fun change(transform: (VideoOutSettings) -> VideoOutSettings) = CvbsInput.updateVideoOut(context, transform)

    Card(stringResource(R.string.cvbs_out_title)) {
        Hint(stringResource(R.string.cvbs_out_hint))
        val (exists, open) = remember { CvbsInput.composerState() }
        Hint(stringResource(R.string.cvbs_out_state, exists.ifEmpty { "-" }, open.ifEmpty { "-" }))
        enabled?.let { on ->
            SwitchRow(stringResource(R.string.cvbs_out_enable), on, stringResource(R.string.cvbs_out_enable_hint)) { v ->
                scope.launch {
                    val err = withContext(Dispatchers.IO) { AtcTools.write(context, mapOf(Mtz.REAR_DISPLAY to if (v) 1 else 0)) }
                    if (err == null) {
                        enabled = v
                        rebootNeeded = true
                        status = null
                    } else {
                        status = err
                    }
                }
            }
        }
        if (rebootNeeded) {
            Hint(stringResource(R.string.atc_reboot_needed))
            Actions { Pill(stringResource(R.string.atc_reboot)) { scope.launch(Dispatchers.IO) { AtcTools.reboot() } } }
        }
        BodyText(stringResource(R.string.cvbs_out_format))
        Choices(listOf(true to "PAL", false to "NTSC"), out.pal) { pal -> change { it.copy(pal = pal) } }
        val range = 0..VideoOutSettings.MARGIN_MAX
        SliderRow(stringResource(R.string.cvbs_out_top), out.top, range) { v -> change { it.copy(top = v) } }
        SliderRow(stringResource(R.string.cvbs_out_bottom), out.bottom, range) { v -> change { it.copy(bottom = v) } }
        SliderRow(stringResource(R.string.cvbs_out_left), out.left, range) { v -> change { it.copy(left = v) } }
        SliderRow(stringResource(R.string.cvbs_out_right), out.right, range) { v -> change { it.copy(right = v) } }
        SwitchRow(stringResource(R.string.cvbs_out_at_start), out.apply, stringResource(R.string.cvbs_out_at_start_hint)) { on ->
            change { it.copy(apply = on) }
        }
        Actions {
            Pill(stringResource(R.string.cvbs_out_apply)) {
                scope.launch {
                    val err = withContext(Dispatchers.IO) { CvbsInput.applyVideoOut(context, enabled != false) }
                    status = err ?: applied
                }
            }
        }
        if (!CvbsInput.hasCaptureService(context)) Hint(stringResource(R.string.cvbs_out_no_jancar))
        status?.let { StatusLine(it, null) }
    }
}
