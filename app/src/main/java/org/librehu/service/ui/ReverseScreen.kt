package org.librehu.service.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.car.EngageSound
import org.librehu.service.car.GuideLines
import org.librehu.service.car.MediaDuring
import org.librehu.service.car.RearCameraInfo
import org.librehu.service.car.ReverseCamera
import org.librehu.service.car.ReverseExit
import org.librehu.service.car.ReverseMode
import org.librehu.service.car.ReverseView
import org.librehu.service.car.ReverseViewSettings
import org.librehu.service.car.WidgetLive

/** Rear view: LibreHU's own (camera, guide lines, sensors, sounds) or a camera app, opened with the reverse gear. */
@Composable
fun ReverseScreen(actions: AppActions) {
    val context = LocalContext.current
    val camera = ReverseCamera.get(context)
    val s by camera.settings.collectAsStateWithLifecycle()
    Page(stringResource(R.string.tab_reverse)) {
        ReverseCameraCard(actions)
        if (s.mode == ReverseMode.BUILT_IN) {
            val view = ReverseView.get(context)
            val v by view.settings.collectAsStateWithLifecycle()
            ReverseImageCard(view, v)
            ReverseWidgetCard(view, v)
            GuideLinesCard(view, v)
            ReverseSoundCard(view, v, actions)
            ReverseAlertsCard(view, v)
        }
    }
}

@Composable
private fun ReverseCameraCard(actions: AppActions) {
    val context = LocalContext.current
    val camera = ReverseCamera.get(context)
    val view = ReverseView.get(context)
    val s by camera.settings.collectAsStateWithLifecycle()
    val vehicle by ServiceState.vehicle.collectAsStateWithLifecycle()
    val showing by view.showing.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    // Checked again when coming back from Android's permission prompt or settings.
    var cameraOk by remember { mutableStateOf(view.hasCameraPermission()) }
    var overlayOk by remember { mutableStateOf(view.canShow()) }
    LifecycleResumeEffect(Unit) {
        cameraOk = view.hasCameraPermission()
        overlayOk = view.canShow()
        onPauseOrDispose {}
    }
    val pm = context.packageManager
    val chosen = s.packageName.ifEmpty { camera.detect().orEmpty() }
    val label =
        remember(chosen) {
            if (chosen.isEmpty()) {
                null
            } else {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(chosen, 0)).toString() }.getOrDefault(chosen)
            }
        }
    Card(stringResource(R.string.reverse_title)) {
        StatusLine(
            stringResource(if (vehicle.reverse) R.string.reverse_engaged else R.string.reverse_released),
            if (vehicle.reverse) true else null,
        )
        SwitchRow(stringResource(R.string.reverse_enable), s.enabled, stringResource(R.string.reverse_enable_hint)) { on ->
            camera.update { it.copy(enabled = on) }
        }
        BodyText(stringResource(R.string.reverse_mode))
        Choices(
            listOf(
                ReverseMode.BUILT_IN to stringResource(R.string.reverse_mode_builtin),
                ReverseMode.APP to stringResource(R.string.reverse_mode_app),
            ),
            s.mode,
        ) { m -> camera.update { it.copy(mode = m) } }
        if (s.mode == ReverseMode.APP) {
            ListRow(
                stringResource(R.string.reverse_app),
                label?.let { if (s.packageName.isEmpty()) stringResource(R.string.reverse_app_auto, it) else it }
                    ?: stringResource(R.string.reverse_app_none),
                onClick = { picking = true },
            )
            BodyText(stringResource(R.string.reverse_exit))
            Choices(
                listOf(
                    ReverseExit.CLOSE to stringResource(R.string.reverse_exit_close),
                    ReverseExit.HOME to stringResource(R.string.reverse_exit_home),
                    ReverseExit.NOTHING to stringResource(R.string.reverse_exit_nothing),
                ),
                s.exit,
            ) { e -> camera.update { it.copy(exit = e) } }
        }
        SliderRow(stringResource(R.string.reverse_delay), s.delayMs / 100, 0..30, { "${it * 100} ms" }) { d ->
            camera.update { it.copy(delayMs = d * 100) }
        }
        if (s.mode == ReverseMode.BUILT_IN) {
            if (!cameraOk) StatusLine(stringResource(R.string.rv_no_permission_short), false)
            if (!overlayOk) StatusLine(stringResource(R.string.rv_no_overlay), false)
            Hint(stringResource(R.string.reverse_builtin_hint))
        } else {
            Hint(stringResource(R.string.reverse_hint))
        }
        Actions {
            if (s.mode == ReverseMode.BUILT_IN) {
                if (!cameraOk) Pill(stringResource(R.string.rv_allow_camera), onClick = actions.requestCameraPermission)
                if (showing) {
                    Pill(stringResource(R.string.rv_close_preview)) { view.hide() }
                } else {
                    Pill(stringResource(R.string.rv_preview_button)) { camera.test() }
                }
            } else {
                Pill(stringResource(R.string.reverse_test), enabled = chosen.isNotEmpty()) { camera.test() }
            }
        }
    }
    if (picking) {
        AppPicker(
            onDismiss = { picking = false },
            onPick = { pkg ->
                picking = false
                camera.update { it.copy(packageName = pkg) }
            },
        )
    }
}

/** Camera and picture placement. */
@Composable
private fun ReverseImageCard(
    view: ReverseView,
    v: ReverseViewSettings,
) {
    val status by view.status.collectAsStateWithLifecycle()
    var list by remember { mutableStateOf<List<RearCameraInfo>?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { view.cameraList() } }
    val auto = stringResource(R.string.rv_camera_auto)
    Card(stringResource(R.string.rv_image_title)) {
        if (status.isNotEmpty()) StatusLine(status, null)
        ListRow(
            stringResource(R.string.rv_camera),
            if (v.cameraId.isEmpty()) auto else list?.firstOrNull { it.id == v.cameraId }?.let { "${it.id} · ${it.label}" } ?: v.cameraId,
            onClick = { picking = true },
        )
        SwitchRow(stringResource(R.string.rv_mirror), v.mirror) { on -> view.update { it.copy(mirror = on) } }
        SwitchRow(stringResource(R.string.rv_flip), v.flip) { on -> view.update { it.copy(flip = on) } }
        BodyText(stringResource(R.string.rv_rotation))
        Choices(listOf(0, 90, 180, 270).map { it to "$it°" }, v.rotation) { r -> view.update { it.copy(rotation = r) } }
        SwitchRow(
            stringResource(R.string.rv_fill),
            v.fill,
            stringResource(R.string.rv_fill_hint),
        ) { on -> view.update { it.copy(fill = on) } }
        SliderRow(stringResource(R.string.rv_zoom), v.zoom, 50..300, { "$it %" }) { z -> view.update { it.copy(zoom = z) } }
        SliderRow(stringResource(R.string.rv_offset_x), v.offsetX, -30..30, { "$it %" }) { x -> view.update { it.copy(offsetX = x) } }
        SliderRow(stringResource(R.string.rv_offset_y), v.offsetY, -30..30, { "$it %" }) { y -> view.update { it.copy(offsetY = y) } }
        SwitchRow(stringResource(R.string.rv_stop_dvr), v.stopDvr, stringResource(R.string.rv_stop_dvr_hint)) { on ->
            view.update { it.copy(stopDvr = on) }
        }
        SliderRow(stringResource(R.string.rv_close_delay), v.closeDelayMs / 500, 0..20, { "${it * 0.5} s" }) { d ->
            view.update { it.copy(closeDelayMs = d * 500) }
        }
        SwitchRow(stringResource(R.string.rv_show_info), v.showInfo) { on -> view.update { it.copy(showInfo = on) } }
        Hint(stringResource(R.string.rv_image_hint))
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.rv_camera)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    item {
                        ListRow(auto, onClick = {
                            picking = false
                            view.update { it.copy(cameraId = "") }
                        })
                    }
                    items(list.orEmpty(), key = { it.id }) { c ->
                        ListRow(stringResource(R.string.rv_camera_id, c.id), c.label, onClick = {
                            picking = false
                            view.update { it.copy(cameraId = c.id) }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Home screen widget: live picture of the camera, when and how often. */
@Composable
private fun ReverseWidgetCard(
    view: ReverseView,
    v: ReverseViewSettings,
) {
    Card(stringResource(R.string.rv_widget_title)) {
        BodyText(stringResource(R.string.rv_widget_live))
        Choices(
            listOf(
                WidgetLive.OFF to stringResource(R.string.rv_widget_off),
                WidgetLive.LAUNCHER to stringResource(R.string.rv_widget_launcher),
                WidgetLive.ALWAYS to stringResource(R.string.rv_widget_always),
            ),
            v.widgetLive,
        ) { m -> view.update { it.copy(widgetLive = m) } }
        if (v.widgetLive != WidgetLive.OFF) {
            BodyText(stringResource(R.string.rv_widget_fps))
            Choices(listOf(5, 10, 15, 20).map { it to "$it" }, v.widgetFps) { f -> view.update { it.copy(widgetFps = f) } }
        }
        Hint(stringResource(R.string.rv_widget_hint))
    }
}

/** Guide lines: their place on the picture (perspective), the distance marks and the trajectory. */
@Composable
private fun GuideLinesCard(
    view: ReverseView,
    v: ReverseViewSettings,
) {
    val g = v.lines

    fun set(transform: (GuideLines) -> GuideLines) = view.update { it.copy(lines = transform(it.lines)) }
    Card(stringResource(R.string.rv_lines_title)) {
        SwitchRow(stringResource(R.string.rv_lines_show), g.show) { on -> set { it.copy(show = on) } }
        if (g.show) {
            Hint(stringResource(R.string.rv_lines_hint))
            SliderRow(stringResource(R.string.rv_lines_center), g.centerX, 20..80, { "$it %" }) { x -> set { it.copy(centerX = x) } }
            SliderRow(stringResource(R.string.rv_lines_bottom), g.bottomY, 50..100, { "$it %" }) { y -> set { it.copy(bottomY = y) } }
            SliderRow(stringResource(R.string.rv_lines_bottom_width), g.bottomWidth, 20..120, { "$it %" }) { w ->
                set { it.copy(bottomWidth = w) }
            }
            SliderRow(stringResource(R.string.rv_lines_top), g.topY, 10..90, { "$it %" }) { y -> set { it.copy(topY = y) } }
            SliderRow(stringResource(R.string.rv_lines_top_width), g.topWidth, 4..100, { "$it %" }) { w -> set { it.copy(topWidth = w) } }
            SliderRow(stringResource(R.string.rv_lines_length), g.lengthCm / 10, 10..60, { "${it * 10} cm" }) { l ->
                set { it.copy(lengthCm = l * 10) }
            }
            SliderRow(stringResource(R.string.rv_lines_mark1), g.mark1Cm / 10, 0..30, { "${it * 10} cm" }) { m ->
                set { it.copy(mark1Cm = m * 10) }
            }
            SliderRow(stringResource(R.string.rv_lines_mark2), g.mark2Cm / 10, 0..40, { "${it * 10} cm" }) { m ->
                set { it.copy(mark2Cm = m * 10) }
            }
            SliderRow(stringResource(R.string.rv_lines_mark3), g.mark3Cm / 10, 0..60, { "${it * 10} cm" }) { m ->
                set { it.copy(mark3Cm = m * 10) }
            }
            SliderRow(stringResource(R.string.rv_lines_thickness), g.thickness, 1..14, { "$it dp" }) { t -> set { it.copy(thickness = t) } }
            SliderRow(stringResource(R.string.rv_lines_opacity), g.opacity, 10..100, { "$it %" }) { o -> set { it.copy(opacity = o) } }
            SwitchRow(stringResource(R.string.rv_lines_dynamic), g.dynamic, stringResource(R.string.rv_lines_dynamic_hint)) { on ->
                set { it.copy(dynamic = on) }
            }
            if (g.dynamic) {
                SwitchRow(stringResource(R.string.rv_lines_invert), g.invertSteering) { on -> set { it.copy(invertSteering = on) } }
                SliderRow(stringResource(R.string.rv_lines_wheelbase), g.wheelbaseCm, 180..360, { "$it cm" }) { w ->
                    set { it.copy(wheelbaseCm = w) }
                }
                SliderRow(stringResource(R.string.rv_lines_width), g.carWidthCm, 120..230, { "$it cm" }) { w ->
                    set { it.copy(carWidthCm = w) }
                }
                SliderRow(stringResource(R.string.rv_lines_ratio), g.steeringRatio, 10..24, { "$it:1" }) { r ->
                    set { it.copy(steeringRatio = r) }
                }
            }
            Actions { Pill(stringResource(R.string.rv_lines_reset)) { set { GuideLines() } } }
        }
    }
}

@Composable
private fun ReverseSoundCard(
    view: ReverseView,
    v: ReverseViewSettings,
    actions: AppActions,
) {
    Card(stringResource(R.string.rv_sound_title)) {
        BodyText(stringResource(R.string.rv_sound_engage))
        Choices(
            listOf(
                EngageSound.NONE to stringResource(R.string.rv_sound_none),
                EngageSound.BEEP to stringResource(R.string.rv_sound_beep),
                EngageSound.DOUBLE to stringResource(R.string.rv_sound_double),
                EngageSound.CHIME to stringResource(R.string.rv_sound_chime),
                EngageSound.CUSTOM to stringResource(R.string.rv_sound_custom),
            ),
            v.sound,
        ) { e ->
            if (e == EngageSound.CUSTOM && v.soundUri.isEmpty()) actions.pickReverseSound() else view.update { it.copy(sound = e) }
        }
        if (v.sound == EngageSound.CUSTOM) {
            ListRow(
                stringResource(R.string.rv_sound_file),
                v.soundUri.substringAfterLast('/').ifEmpty { "—" },
                onClick = actions.pickReverseSound,
            )
        }
        SliderRow(stringResource(R.string.rv_sound_volume), v.soundVolume, 0..100, { "$it %" }) { vol ->
            view.update { it.copy(soundVolume = vol) }
        }
        BodyText(stringResource(R.string.rv_media))
        Choices(
            listOf(
                MediaDuring.KEEP to stringResource(R.string.rv_media_keep),
                MediaDuring.DUCK to stringResource(R.string.rv_media_duck),
                MediaDuring.PAUSE to stringResource(R.string.rv_media_pause),
            ),
            v.media,
        ) { m -> view.update { it.copy(media = m) } }
    }
}

/** Parking sensors of the CAN box and the picture's movement detection. */
@Composable
private fun ReverseAlertsCard(
    view: ReverseView,
    v: ReverseViewSettings,
) {
    Card(stringResource(R.string.rv_alerts_title)) {
        SwitchRow(stringResource(R.string.rv_radar), v.radar, stringResource(R.string.rv_radar_hint)) { on ->
            view.update { it.copy(radar = on) }
        }
        if (v.radar) {
            SwitchRow(stringResource(R.string.rv_radar_beep), v.radarBeep) { on -> view.update { it.copy(radarBeep = on) } }
            SwitchRow(stringResource(R.string.rv_radar_invert), v.radarInvert, stringResource(R.string.rv_radar_invert_hint)) { on ->
                view.update { it.copy(radarInvert = on) }
            }
        }
        SwitchRow(stringResource(R.string.rv_motion), v.motion, stringResource(R.string.rv_motion_hint)) { on ->
            view.update { it.copy(motion = on) }
        }
        if (v.motion) {
            SliderRow(stringResource(R.string.rv_motion_sensitivity), v.motionSensitivity, 1..100, { "$it" }) { x ->
                view.update { it.copy(motionSensitivity = x) }
            }
            SliderRow(
                stringResource(R.string.rv_motion_top),
                v.motionTop,
                0..90,
                { "$it %" },
            ) { x -> view.update { it.copy(motionTop = x) } }
            SwitchRow(stringResource(R.string.rv_motion_beep), v.motionBeep) { on -> view.update { it.copy(motionBeep = on) } }
        }
        SwitchRow(stringResource(R.string.rv_approach), v.approach, stringResource(R.string.rv_approach_hint)) { on ->
            view.update { it.copy(approach = on) }
        }
    }
}

/** Launchable apps, with an "automatic" entry (empty package). */
@Composable
private fun AppPicker(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps =
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm
                    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reverse_app)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                item { ListRow(stringResource(R.string.reverse_app_auto_short), onClick = { onPick("") }) }
                items(apps, key = { it.first }) { (pkg, label) -> ListRow(label, pkg, onClick = { onPick(pkg) }) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
