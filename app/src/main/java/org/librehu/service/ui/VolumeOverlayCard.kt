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
import org.librehu.core.audio.Bd37534
import org.librehu.service.R
import org.librehu.service.overlay.VolumeEdge
import org.librehu.service.overlay.VolumeOverlay
import org.librehu.service.overlay.VolumeOverlaySettings
import org.librehu.service.overlay.VolumeStyle

/** Settings of the volume panel shown over the apps when the volume of the audio chip changes. */
@Composable
fun VolumeOverlayCard(
    volume: Int,
    muted: Boolean,
) {
    val context = LocalContext.current
    val overlay = VolumeOverlay.get(context)
    val s by overlay.settings.collectAsStateWithLifecycle()
    // Permission granted in Android's screen: read again on return.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    Card(stringResource(R.string.vol_overlay)) {
        Hint(stringResource(R.string.vol_overlay_hint))
        SwitchRow(stringResource(R.string.vol_overlay_enable), s.enabled) { on -> overlay.update { it.copy(enabled = on) } }
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
                VolumeStyle.VERTICAL to stringResource(R.string.vol_style_vertical),
                VolumeStyle.HORIZONTAL to stringResource(R.string.vol_style_horizontal),
            ),
            s.style,
        ) { st ->
            overlay.update {
                val edge =
                    when {
                        st == VolumeStyle.VERTICAL && (it.edge == VolumeEdge.TOP || it.edge == VolumeEdge.BOTTOM) -> VolumeEdge.RIGHT
                        st == VolumeStyle.HORIZONTAL && (it.edge == VolumeEdge.LEFT || it.edge == VolumeEdge.RIGHT) -> VolumeEdge.TOP
                        else -> it.edge
                    }
                it.copy(style = st, edge = edge)
            }
        }
        BodyText(stringResource(R.string.vol_overlay_edge))
        val edges =
            if (s.style == VolumeStyle.VERTICAL) {
                listOf(
                    VolumeEdge.RIGHT to stringResource(R.string.vol_edge_right),
                    VolumeEdge.LEFT to stringResource(R.string.vol_edge_left),
                )
            } else {
                listOf(
                    VolumeEdge.TOP to stringResource(R.string.vol_edge_top),
                    VolumeEdge.BOTTOM to stringResource(R.string.vol_edge_bottom),
                )
            }
        Choices(edges, s.edge) { e -> overlay.update { it.copy(edge = e) } }
        BodyText(stringResource(R.string.vol_overlay_size))
        Choices(
            listOf(
                0 to stringResource(R.string.vol_size_small),
                1 to stringResource(R.string.vol_size_normal),
                2 to stringResource(R.string.vol_size_large),
            ),
            s.size,
        ) { z -> overlay.update { it.copy(size = z) } }
        BodyText(stringResource(R.string.vol_overlay_timeout))
        Choices(VolumeOverlaySettings.TIMEOUTS.map { it to "%.1f s".format(it / 1000.0) }, s.timeoutMs) { t ->
            overlay.update { it.copy(timeoutMs = t) }
        }
        SwitchRow(stringResource(R.string.vol_overlay_number), s.showNumber) { on -> overlay.update { it.copy(showNumber = on) } }
        SwitchRow(stringResource(R.string.vol_overlay_touch), s.touch, stringResource(R.string.vol_overlay_touch_hint)) { on ->
            overlay.update { it.copy(touch = on) }
        }
        Actions { Pill(stringResource(R.string.vol_overlay_preview)) { overlay.show(volume, Bd37534.MAX_VOLUME, muted, force = true) } }
    }
}
