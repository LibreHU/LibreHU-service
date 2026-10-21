package org.librehu.service.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import org.librehu.service.R
import org.librehu.service.bridge.BridgeServer
import java.text.DateFormat
import java.util.Date

/** QR code of [text] (ZXing), black on white, [size] pixels. */
fun qrBitmap(
    text: String,
    size: Int = 512,
): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/**
 * The LibreHU phone app (LibreHU-bridge): QR code to pair a phone (valid 5 minutes, once), phones waiting for a
 * Bluetooth confirmation, paired phones (forget), the server on or off ([BridgeServer]).
 */
@Composable
internal fun BridgeCard() {
    val context = LocalContext.current
    val server = BridgeServer.get(context)
    val enabled by server.enabled.collectAsStateWithLifecycle()
    val devices by server.devices.collectAsStateWithLifecycle()
    val requests by server.requests.collectAsStateWithLifecycle()
    val connected by server.connected.collectAsStateWithLifecycle()
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(qr) {
        if (qr != null) {
            delay(5 * 60_000L)
            qr = null
        }
    }
    Card(stringResource(R.string.bridge_title)) {
        SwitchRow(stringResource(R.string.bridge_enable), enabled, stringResource(R.string.bridge_hint)) { server.setEnabled(it) }
        if (enabled) {
            if (connected.isNotEmpty()) StatusLine(stringResource(R.string.bridge_connected, connected.joinToString(", ")), true)
            val code = qr
            if (code == null) {
                Actions { Pill(stringResource(R.string.bridge_pair)) { qr = qrBitmap(server.pairingUri()) } }
            } else {
                Image(
                    code.asImageBitmap(),
                    null,
                    Modifier.size(280.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                )
                Hint(stringResource(R.string.bridge_qr_hint, server.fingerprint.take(8)))
                Actions {
                    Pill(stringResource(R.string.bridge_qr_close)) {
                        server.cancelPairing()
                        qr = null
                    }
                }
            }
            for (r in requests) {
                BodyText(stringResource(R.string.bridge_request_title, r.name))
                Actions {
                    Pill(stringResource(R.string.access_allow), selected = true) { server.answer(r.id, true) }
                    Pill(stringResource(R.string.access_deny)) { server.answer(r.id, false) }
                }
            }
            for (d in devices) {
                ListRow(d.name, stringResource(R.string.bridge_device, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.lastSeen)))) {
                    Pill(stringResource(R.string.bridge_forget)) { server.forget(d.id) }
                }
            }
        }
    }
}
