package org.librehu.service.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.service.R
import org.librehu.service.overlay.LevelOverlay
import org.librehu.service.overlay.LevelOverlaySettings
import org.librehu.service.overlay.OverlayEdge
import org.librehu.service.overlay.OverlayKind
import org.librehu.service.overlay.OverlayStyle

/**
 * Settings of a level panel shown over the apps (volume of the audio chip, screen brightness): on / off, style,
 * edge, shift along the edge, distance, size, opacity, duration, number, touch, preview.
 */
@Composable
fun LevelOverlayCard(
    kind: OverlayKind,
    onPreview: (LevelOverlay) -> Unit,
) {
    val context = LocalContext.current
    val overlay = LevelOverlay.get(context, kind)
    val s by overlay.settings.collectAsStateWithLifecycle()
    val volume = kind == OverlayKind.VOLUME
    // Permission granted in Android's screen: read again on return.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    Card(stringResource(if (volume) R.string.vol_overlay else R.string.bright_overlay)) {
        Hint(stringResource(if (volume) R.string.vol_overlay_hint else R.string.bright_overlay_hint))
        SwitchRow(stringResource(if (volume) R.string.vol_overlay_enable else R.string.bright_overlay_enable), s.enabled) { on ->
            overlay.update { it.copy(enabled = on) }
        }
        if (resumed >= 0 && !overlay.canShow()) {
            Hint(stringResource(R.string.vol_overlay_permission))
            Actions {
                Pill(stringResource(R.string.vol_overlay_allow)) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }
        BodyText(stringResource(R.string.vol_overlay_style))
        Choices(
            listOf(
                OverlayStyle.VERTICAL to stringResource(R.string.vol_style_vertical),
                OverlayStyle.HORIZONTAL to stringResource(R.string.vol_style_horizontal),
            ),
            s.style,
        ) { st ->
            overlay.update {
                val edge =
                    when {
                        st == OverlayStyle.VERTICAL && (it.edge == OverlayEdge.TOP || it.edge == OverlayEdge.BOTTOM) -> OverlayEdge.RIGHT
                        st == OverlayStyle.HORIZONTAL && (it.edge == OverlayEdge.LEFT || it.edge == OverlayEdge.RIGHT) -> OverlayEdge.TOP
                        else -> it.edge
                    }
                it.copy(style = st, edge = edge)
            }
        }
        BodyText(stringResource(R.string.vol_overlay_edge))
        val edges =
            if (s.style == OverlayStyle.VERTICAL) {
                listOf(
                    OverlayEdge.RIGHT to stringResource(R.string.vol_edge_right),
                    OverlayEdge.LEFT to stringResource(R.string.vol_edge_left),
                )
            } else {
                listOf(
                    OverlayEdge.TOP to stringResource(R.string.vol_edge_top),
                    OverlayEdge.BOTTOM to stringResource(R.string.vol_edge_bottom),
                )
            }
        Choices(edges, s.edge) { e -> overlay.update { it.copy(edge = e) } }
        val side = s.style == OverlayStyle.VERTICAL
        val centre = stringResource(R.string.overlay_centre)
        SliderRow(
            stringResource(if (side) R.string.overlay_offset_vertical else R.string.overlay_offset_horizontal),
            s.offset,
            -40..40,
            { if (it == 0) centre else "%+d %%".format(it) },
        ) { v -> overlay.update { it.copy(offset = v) } }
        SliderRow(stringResource(R.string.overlay_margin), s.margin, 0..120, { "$it dp" }) { v -> overlay.update { it.copy(margin = v) } }
        BodyText(stringResource(R.string.vol_overlay_size))
        Choices(
            listOf(
                0 to stringResource(R.string.overlay_size_xs),
                1 to stringResource(R.string.vol_size_small),
                2 to stringResource(R.string.vol_size_normal),
                3 to stringResource(R.string.vol_size_large),
                4 to stringResource(R.string.overlay_size_xl),
            ),
            s.size,
        ) { z -> overlay.update { it.copy(size = z) } }
        SliderRow(
            stringResource(R.string.overlay_opacity),
            s.opacity,
            40..100,
            { "$it %" },
        ) { v -> overlay.update { it.copy(opacity = v) } }
        BodyText(stringResource(R.string.vol_overlay_timeout))
        Choices(LevelOverlaySettings.TIMEOUTS.map { it to "%.1f s".format(it / 1000.0) }, s.timeoutMs) { t ->
            overlay.update { it.copy(timeoutMs = t) }
        }
        SwitchRow(stringResource(if (volume) R.string.vol_overlay_number else R.string.bright_overlay_number), s.showNumber) { on ->
            overlay.update { it.copy(showNumber = on) }
        }
        SwitchRow(
            stringResource(R.string.vol_overlay_touch),
            s.touch,
            stringResource(if (volume) R.string.vol_overlay_touch_hint else R.string.bright_overlay_touch_hint),
        ) { on -> overlay.update { it.copy(touch = on) } }
        Actions { Pill(stringResource(R.string.vol_overlay_preview)) { onPreview(overlay) } }
    }
}
