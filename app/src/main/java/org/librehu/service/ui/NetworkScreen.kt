package org.librehu.service.ui

import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.librehu.service.R
import org.librehu.service.net.UsbNet
import org.librehu.service.net.cable.CableInternet

/** Wired networks over USB: adapters and phones plugged in, their driver, the interfaces, the zip's modules. */
@Composable
fun NetworkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UsbNet.State?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val openOta = {
        context.packageManager.getLaunchIntentForPackage("org.librehu.ota")?.let {
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { UsbNet.scan(context) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(3000)
        }
    }

    Page(stringResource(R.string.tab_network)) {
        val s = state
        if (s == null) {
            Card { Hint(stringResource(R.string.net_reading)) }
            return@Page
        }
        Card(stringResource(R.string.net_status)) {
            when {
                s.ethernetValidated -> StatusLine(stringResource(R.string.net_internet_ok), true)
                s.ethernetUp -> StatusLine(stringResource(R.string.net_no_internet), null)
                else -> StatusLine(stringResource(R.string.net_none), false)
            }
            if (!s.root) Hint(stringResource(R.string.net_no_root))
            Hint(stringResource(R.string.net_status_hint))
        }

        // The phone's Internet over the cable while it runs wired Android Auto (adb, net/cable/CableInternet).
        val cable by CableInternet.status.collectAsState()
        Card(stringResource(R.string.cable_title)) {
            var on by remember { mutableStateOf(CableInternet.enabled(context)) }
            SwitchRow(stringResource(R.string.cable_switch), on, stringResource(R.string.cable_switch_hint)) {
                // Android's VPN consent (asked once; with root the service gives it itself).
                if (it) VpnService.prepare(context)?.let { consent -> runCatching { context.startActivity(consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                on = it
                CableInternet.setEnabled(context, it)
            }
            if (on) {
                val (text, ok) =
                    when (cable.phase) {
                        CableInternet.Phase.OFF, CableInternet.Phase.NO_PHONE -> stringResource(R.string.cable_no_phone) to null
                        CableInternet.Phase.NO_DEBUGGING -> stringResource(R.string.cable_no_debugging) to false
                        CableInternet.Phase.PERMISSION, CableInternet.Phase.CONNECTING -> stringResource(R.string.cable_connecting, cable.device) to null
                        CableInternet.Phase.AUTHORIZE -> stringResource(R.string.cable_authorize, cable.device) to null
                        CableInternet.Phase.RELAY -> stringResource(R.string.cable_relay, cable.device) to null
                        CableInternet.Phase.CONNECTED ->
                            stringResource(R.string.cable_connected, cable.detail.ifEmpty { cable.device }, bytes(cable.received), bytes(cable.sent)) to true
                        CableInternet.Phase.ERROR -> stringResource(R.string.cable_error, cable.detail) to false
                    }
                StatusLine(text, ok)
            }
            Hint(stringResource(R.string.cable_hint))
        }

        Card(stringResource(R.string.net_devices)) {
            val devices = s.netDevices
            if (devices.isEmpty()) Hint(stringResource(R.string.net_devices_none))
            for (d in devices) {
                val title =
                    d.chip?.name
                        ?: listOf(d.manufacturer, d.product).filter { it.isNotBlank() }.joinToString(" ").ifBlank { d.id }
                val kind =
                    when (d.kind) {
                        UsbNet.Kind.ETHERNET -> stringResource(R.string.net_kind_ethernet)
                        UsbNet.Kind.ANDROID_PHONE -> stringResource(R.string.net_kind_android)
                        UsbNet.Kind.IPHONE -> stringResource(R.string.net_kind_iphone)
                        UsbNet.Kind.OTHER -> stringResource(R.string.net_kind_other)
                    }
                val driver = d.netDriver
                val needed = d.chip?.driver ?: if (d.kind == UsbNet.Kind.IPHONE) "ipheth" else if (d.kind == UsbNet.Kind.ANDROID_PHONE) "rndis_host" else null
                val status =
                    when {
                        driver != null -> stringResource(R.string.net_driver_bound, driver)
                        needed != null && needed !in s.installed && d.chip?.builtIn != true -> stringResource(R.string.net_driver_missing, needed)
                        needed != null && needed !in s.loaded && d.chip?.builtIn != true -> stringResource(R.string.net_driver_not_loaded, needed)
                        d.kind == UsbNet.Kind.IPHONE -> stringResource(R.string.net_iphone_config)
                        else -> stringResource(R.string.net_driver_none)
                    }
                ListRow(title, "$kind · ${d.id}")
                StatusLine(status, driver != null)
                if (d.kind == UsbNet.Kind.IPHONE && driver == null && "ipheth" in s.loaded) {
                    Actions {
                        Pill(stringResource(R.string.net_iphone_switch)) {
                            scope.launch {
                                message =
                                    withContext(Dispatchers.IO) { UsbNet.iphoneTethering(d) }.fold(
                                        { context.getString(R.string.net_iphone_switched, it) },
                                        { context.getString(R.string.net_iphone_failed, it.message) },
                                    )
                                refresh()
                            }
                        }
                    }
                }
            }
            var auto by remember { mutableStateOf(UsbNet.iphoneAuto(context)) }
            SwitchRow(stringResource(R.string.net_iphone_auto), auto, stringResource(R.string.net_iphone_auto_hint)) {
                auto = it
                UsbNet.setIphoneAuto(context, it)
            }
            Hint(stringResource(R.string.net_devices_hint))
        }

        Card(stringResource(R.string.net_interfaces)) {
            if (s.interfaces.isEmpty()) Hint(stringResource(R.string.net_interfaces_none))
            for (i in s.interfaces) {
                val details =
                    listOfNotNull(
                        i.driver.takeIf { it.isNotEmpty() },
                        i.mac.takeIf { it.isNotEmpty() },
                        if (i.speed > 0) "${i.speed} Mb/s" else null,
                        i.ipv4.joinToString(", ").takeIf { it.isNotEmpty() },
                    ).joinToString(" · ")
                ListRow("${i.name} · ${i.state}", details)
                // Android 9 takes eth* only: an Android phone's tethering named usb0 needs the zip usb-tethering.
                if (s.takenByAndroid(i.name) == false) {
                    StatusLine(stringResource(R.string.net_iface_ignored, i.name, s.ifaceFilter.orEmpty()), false)
                    Hint(stringResource(R.string.net_iface_ignored_hint))
                    Actions { Pill(stringResource(R.string.net_open_ota)) { openOta() } }
                }
            }
            Hint(stringResource(R.string.net_interfaces_hint, s.ifaceFilter ?: "?"))
        }

        Card(stringResource(R.string.net_modules)) {
            if (s.installed.isEmpty()) {
                StatusLine(stringResource(R.string.net_modules_absent), false)
                Hint(stringResource(R.string.net_modules_install))
                Actions {
                    Pill(stringResource(R.string.net_open_ota)) { openOta() }
                }
            } else {
                StatusLine(
                    stringResource(R.string.net_modules_loaded, s.loaded.size, s.installed.size),
                    s.loaded.isNotEmpty(),
                )
                if (s.loaded.isNotEmpty()) Hint(s.loaded.joinToString(", "))
                StatusLine(
                    stringResource(if (s.off) R.string.net_modules_off else R.string.net_modules_on),
                    !s.off,
                )
                Actions {
                    Pill(stringResource(R.string.net_modules_load), enabled = s.root) {
                        scope.launch {
                            message =
                                context.getString(
                                    if (withContext(Dispatchers.IO) { UsbNet.load() }) R.string.net_modules_load_done else R.string.net_failed,
                                )
                            delay(1500)
                            refresh()
                        }
                    }
                    Pill(stringResource(R.string.net_modules_disable), enabled = s.root && !s.off) {
                        scope.launch {
                            message =
                                context.getString(
                                    if (withContext(Dispatchers.IO) { UsbNet.disableAtStart() }) R.string.net_modules_disabled else R.string.net_failed,
                                )
                            refresh()
                        }
                    }
                }
                Hint(stringResource(R.string.net_modules_hint))
            }
            message?.let { Hint(it) }
        }
    }
}

/** A byte count for people: kB below a megabyte, MB with one decimal above. */
private fun bytes(n: Long): String = if (n < 1_000_000) "${n / 1000} kB" else "%.1f MB".format(n / 1_000_000.0)
