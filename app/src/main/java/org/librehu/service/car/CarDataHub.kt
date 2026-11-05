package org.librehu.service.car

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.librehu.core.can.CarData
import org.librehu.core.can.CarDataValue
import org.librehu.service.can.CanMonitor
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdManager

/**
 * The car's values by role (org.librehu.core.can.CanRoles, aligned on COVESA VSS), from every source the head unit
 * has at once: the CAN box (the selected car profile) and the OBD-II adapter (ELM327, USB or Bluetooth). The box comes
 * first, the adapter fills in what it does not give ([CarData]); one source stopping leaves the other's values. New
 * features read [values] rather than one source.
 */
object CarDataHub {
    private val data = CarData()
    private val _values = MutableStateFlow<Map<String, CarDataValue>>(emptyMap())

    /** One value per role, with the source it came from. */
    val values: StateFlow<Map<String, CarDataValue>> = _values.asStateFlow()

    @Volatile
    private var scope: CoroutineScope? = null

    fun start(obd: ObdManager) {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch {
            CanMonitor.roles.collect { r ->
                if (r.isEmpty()) data.clear(CarData.CANBOX) else data.put(CarData.CANBOX, r, SystemClock.elapsedRealtime())
                publish()
            }
        }
        s.launch {
            obd.state.collect { st ->
                if (st.connection == ObdConnection.CONNECTED && st.values.isNotEmpty()) {
                    data.put(CarData.OBD, CarData.obdValues(st.values), SystemClock.elapsedRealtime())
                } else {
                    data.clear(CarData.OBD)
                }
                publish()
            }
        }
    }

    private fun publish() {
        _values.value = data.merged()
    }
}
