package org.librehu.service.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.librehu.service.R
import org.librehu.service.can.CanBoxPorts
import org.librehu.service.car.CarDataHub
import org.librehu.service.car.PortBroker
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdManager

/**
 * The car's data sources running together (CAN box and OBD-II adapter, [CarDataHub]): what each one gives now, the
 * ports they hold ([PortBroker]: one owner per adapter, none taken from another app), and how they combine.
 */
@Composable
internal fun CarSourcesCard() {
    val context = LocalContext.current
    val values by CarDataHub.values.collectAsStateWithLifecycle()
    val ports by PortBroker.state.collectAsStateWithLifecycle()
    val box by CanBoxPorts.settings.collectAsStateWithLifecycle()
    val boxStatus by CanBoxPorts.status.collectAsStateWithLifecycle()
    val obd by ObdManager.get(context).state.collectAsStateWithLifecycle()
    Card(stringResource(R.string.car_sources)) {
        Hint(stringResource(R.string.car_sources_hint))
        val fromBox = values.values.count { it.source == org.librehu.core.can.CarData.CANBOX }
        val fromObd = values.values.count { it.source == org.librehu.core.can.CarData.OBD }
        val boxLink =
            when (box.kind) {
                CanBoxPorts.Kind.MCU -> stringResource(R.string.car_sources_mcu)
                else -> boxStatus.open ?: boxStatus.problem ?: box.kind.name
            }
        BodyText(stringResource(R.string.car_sources_box, boxLink, fromBox))
        val obdLink =
            when (obd.connection) {
                ObdConnection.CONNECTED -> obd.adapter
                else -> obd.message.ifEmpty { obd.connection.name.lowercase() }
            }
        BodyText(stringResource(R.string.car_sources_obd, obdLink, fromObd))
        for (p in ports) {
            val who =
                when (p.owner) {
                    PortBroker.OBD -> stringResource(R.string.car_sources_owner_obd)
                    PortBroker.CANBOX -> stringResource(R.string.car_sources_owner_box)
                    else -> p.owner
                }
            BodyText(stringResource(R.string.car_sources_port, p.label, who))
        }
    }
}
