package org.librehu.service.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.service.R
import org.librehu.service.phone.IphoneDetails
import org.librehu.service.phone.PhoneInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The connected phones: Bluetooth (hands-free indicators) and the iPhone through the LibreHU CarPlay app. */
@Composable
fun PhoneScreen() {
    val context = LocalContext.current
    val bt by PhoneInfo.bluetooth.collectAsStateWithLifecycle()
    val iphone by PhoneInfo.iphone.collectAsStateWithLifecycle()
    Page(stringResource(R.string.tab_phone)) {
        Card(stringResource(R.string.phone_bt_title)) {
            if (bt.deviceAddress.isEmpty()) {
                StatusLine(stringResource(R.string.phone_none), false)
            } else {
                StatusLine(bt.deviceName.ifEmpty { bt.deviceAddress }, true)
                InfoRow(stringResource(R.string.phone_address), bt.deviceAddress)
                if (bt.battery >=
                    0
                ) {
                    InfoRow(stringResource(R.string.phone_battery), stringResource(R.string.phone_battery_bt, bt.battery * 20))
                }
                if (bt.signal >= 0) InfoRow(stringResource(R.string.phone_signal), "${bt.signal} / 5")
                if (bt.operator.isNotEmpty()) InfoRow(stringResource(R.string.phone_operator), bt.operator)
                if (bt.roaming) InfoRow(stringResource(R.string.phone_roaming), stringResource(R.string.phone_yes))
            }
            Hint(stringResource(R.string.phone_bt_hint))
        }
        IphoneCard(iphone)
        Card(stringResource(R.string.phone_iphone_pair_title)) {
            var auto by remember { mutableStateOf(PhoneInfo.autoPair(context)) }
            SwitchRow(stringResource(R.string.phone_iphone_pair), auto, stringResource(R.string.phone_iphone_pair_hint)) { on ->
                PhoneInfo.setAutoPair(context, on)
                auto = on
            }
        }
    }
}

@Composable
private fun IphoneCard(d: IphoneDetails?) {
    Card(stringResource(R.string.phone_iphone_title)) {
        if (d == null) {
            StatusLine(stringResource(R.string.phone_iphone_none), false)
            Hint(stringResource(R.string.phone_iphone_hint))
            return@Card
        }
        val name = d.name ?: "iPhone"
        if (d.connected) {
            StatusLine(name, true)
        } else {
            val at = if (d.at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(d.at)) else "-"
            StatusLine(stringResource(R.string.phone_iphone_last, name, at), null)
        }
        d.wired?.let {
            val link = stringResource(if (it) R.string.phone_link_wired else R.string.phone_link_wireless)
            InfoRow(stringResource(R.string.phone_link), link)
        }
        d.model?.let { InfoRow(stringResource(R.string.phone_model), it) }
        d.os?.let { InfoRow("iOS", it + (d.build?.let { b -> " ($b)" } ?: "")) }
        d.level?.let { InfoRow(stringResource(R.string.phone_battery), "$it %") }
        d.charging?.let {
            InfoRow(stringResource(R.string.phone_charging), stringResource(if (it) R.string.phone_yes else R.string.phone_no))
        }
        d.externalPower?.let {
            InfoRow(stringResource(R.string.phone_external_power), stringResource(if (it) R.string.phone_yes else R.string.phone_no))
        }
        d.healthPercent?.let { InfoRow(stringResource(R.string.phone_health), "$it %") }
        if (d.designMah != null || d.maxMah != null) {
            InfoRow(stringResource(R.string.phone_capacity), "${d.maxMah ?: "-"} / ${d.designMah ?: "-"} mAh")
        }
        d.cycles?.let { InfoRow(stringResource(R.string.phone_cycles), it.toString()) }
        d.temperatureC?.let { InfoRow(stringResource(R.string.phone_temperature), String.format(Locale.ROOT, "%.1f °C", it)) }
        d.voltageMv?.let { InfoRow(stringResource(R.string.phone_voltage), String.format(Locale.ROOT, "%.2f V", it / 1000.0)) }
        d.currentMa?.let { InfoRow(stringResource(R.string.phone_current), "$it mA") }
        d.bluetooth?.let { InfoRow(stringResource(R.string.phone_bt_address), it) }
        Hint(stringResource(R.string.phone_iphone_hint))
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) = BodyText("$label : $value")
