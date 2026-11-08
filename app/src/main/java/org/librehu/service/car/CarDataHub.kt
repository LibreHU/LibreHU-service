package org.librehu.service.car

import android.content.Context
import android.content.Intent
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
import org.librehu.service.LibreHu
import org.librehu.service.can.CanMonitor
import org.librehu.service.obd.ObdConnection
import org.librehu.service.obd.ObdManager

/**
 * The car's values by role (org.librehu.core.can.CanRoles, aligned on COVESA VSS), from every source the head unit
 * has at once: the CAN box (the selected car profile) and the OBD-II adapter (ELM327, USB or Bluetooth). The box comes
 * first, the adapter fills in what it does not give ([CarData]); one source stopping leaves the other's values. New
 * features read [values] rather than one source; other apps get them in [LibreHu.ACTION_CAR_DATA] (at most once a
 * second). The sources run together on their own ports ([PortBroker]: one owner per adapter).
 */
object CarDataHub {
    private val data = CarData()
    private val _values = MutableStateFlow<Map<String, CarDataValue>>(emptyMap())

    /** One value per role, with the source it came from. */
    val values: StateFlow<Map<String, CarDataValue>> = _values.asStateFlow()

    @Volatile
    private var scope: CoroutineScope? = null

    private var app: Context? = null
    private var lastSent = 0L

    fun start(
        context: Context,
        obd: ObdManager,
    ) {
        if (scope != null) return
        app = context.applicationContext
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
        val merged = data.merged()
        _values.value = merged
        broadcast(merged)
    }

    /** [LibreHu.ACTION_CAR_DATA] for other apps: at most once a second, nothing while no source gives values. */
    private fun broadcast(merged: Map<String, CarDataValue>) {
        val c = app ?: return
        val now = SystemClock.elapsedRealtime()
        if (merged.isEmpty() || now - lastSent < SEND_MS) return
        lastSent = now
        val i = Intent(LibreHu.ACTION_CAR_DATA)
        for ((role, v) in merged) {
            v.value.number?.let { i.putExtra("role:$role", it) }
            v.value.flag?.let { i.putExtra("role:$role", if (it) 1.0 else 0.0) }
            i.putExtra("text:$role", v.value.display())
            i.putExtra("src:$role", v.source)
        }
        i.putExtra("sources", data.sources().toTypedArray())
        runCatching { c.sendBroadcast(i, LibreHu.PERMISSION_VEHICLE) }
    }

    private const val SEND_MS = 1_000L
}
