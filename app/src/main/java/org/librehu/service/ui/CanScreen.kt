package org.librehu.service.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.service.R
import org.librehu.service.can.CanMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CAN box messages: one line per command (changed bytes highlighted, decoded when known), then the stream. */
@Composable
fun CanScreen() {
    val context = LocalContext.current
    val byCmd by CanMonitor.byCmd.collectAsStateWithLifecycle()
    val lines by CanMonitor.lines.collectAsStateWithLifecycle()
    val raw by CanMonitor.rawBytes.collectAsStateWithLifecycle()
    var paused by remember { mutableStateOf(CanMonitor.paused) }
    var message by remember { mutableStateOf("") }
    val time = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    Page(stringResource(R.string.tab_can)) {
        Card {
            Hint(stringResource(R.string.can_hint))
            BodyText(stringResource(R.string.can_counts, raw, byCmd.values.sumOf { it.count }, CanMonitor.skipped))
            Actions {
                Pill(stringResource(if (paused) R.string.diag_resume else R.string.diag_pause), selected = paused) {
                    paused = !paused
                    CanMonitor.paused = paused
                }
                Pill(stringResource(R.string.diag_clear)) { CanMonitor.clear() }
                Pill(stringResource(R.string.can_export)) {
                    val f = CanMonitor.export(context)
                    message =
                        if (f != null) context.getString(R.string.can_exported, f.path) else context.getString(R.string.can_export_failed)
                }
            }
            if (message.isNotEmpty()) Hint(message)
        }
        Card(stringResource(R.string.can_by_command)) {
            if (byCmd.isEmpty()) Hint(stringResource(R.string.can_none))
            for (e in byCmd.values.sortedBy { it.cmd }) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = CarColors.Accent)) { append("%02X".format(e.cmd)) }
                        append("  ×${e.count}  ")
                        e.data.forEachIndexed { i, b ->
                            if (i > 0) append(' ')
                            val hex = "%02X".format(b.toInt() and 0xFF)
                            if (i in e.changed) withStyle(SpanStyle(color = Color(0xFFFFB74D))) { append(hex) } else append(hex)
                        }
                        if (!e.checksumOk) withStyle(SpanStyle(color = Color(0xFFF44336))) { append("  CS!") }
                    },
                    color = CarColors.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                )
                e.text?.let { Row { Hint("   $it") } }
            }
        }
        Card(stringResource(R.string.can_stream)) {
            BodyText(
                lines.take(60).joinToString("\n") { "${time.format(Date(it.at))}  ${it.frame}" }.ifEmpty { "—" },
                mono = true,
            )
        }
    }
}
