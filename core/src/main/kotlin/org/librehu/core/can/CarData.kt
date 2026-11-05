package org.librehu.core.can

import org.librehu.core.obd.ObdPid

/** A value of the car from one source ([CarData]): what it is ([value], with its role) and where it came from. */
data class CarDataValue(
    val source: String,
    val value: CanValue,
    /** When it was received (ms, monotonic). */
    val at: Long,
)

/**
 * The car's values by role ([CanRoles]), from every source at once: the CAN box (its profile), an OBD-II adapter
 * (ELM327 over USB or Bluetooth), later others. Each source gives its own values; for a role several sources give,
 * the first source of [priority] that has it wins (default: the box first, the OBD adapter fills in what the box does
 * not give, as the engine speed of a Clio 3); a source that stops ([clear]) takes its values away, the others stay.
 * Pure logic, no Android: the service feeds it.
 */
class CarData(
    val priority: List<String> = DEFAULT_PRIORITY,
) {
    private val bySource = LinkedHashMap<String, Map<String, CarDataValue>>()

    /** Replaces the values of [source] (role → value). */
    @Synchronized
    fun put(
        source: String,
        values: Map<String, CanValue>,
        now: Long,
    ) {
        bySource[source] = values.filterKeys { it.isNotEmpty() }.mapValues { (_, v) -> CarDataValue(source, v, now) }
    }

    /** Adds or replaces some values of [source]. */
    @Synchronized
    fun update(
        source: String,
        values: Map<String, CanValue>,
        now: Long,
    ) {
        bySource[source] = (bySource[source] ?: emptyMap()) + values.mapValues { (_, v) -> CarDataValue(source, v, now) }
    }

    @Synchronized
    fun clear(source: String) {
        bySource.remove(source)
    }

    /** One value per role, from the source that wins it. */
    @Synchronized
    fun merged(): Map<String, CarDataValue> {
        val order = priority.filter { it in bySource } + bySource.keys.filter { it !in priority }
        val out = LinkedHashMap<String, CarDataValue>()
        for (src in order) for ((role, v) in bySource[src].orEmpty()) out.putIfAbsent(role, v)
        return out
    }

    /** The sources giving values now. */
    @Synchronized
    fun sources(): Set<String> = bySource.keys.toSet()

    companion object {
        const val CANBOX = "canbox"
        const val OBD = "obd"
        val DEFAULT_PRIORITY = listOf(CANBOX, OBD)

        /** OBD-II mode 01 PIDs (SAE J1979) and the adapter's own voltage reading (ELM327 `ATRV`) → roles. */
        val OBD_ROLES: Map<String, String> =
            mapOf(
                ObdPid.SPEED.name to "vehicle.speed",
                ObdPid.RPM.name to "engine.rpm",
                ObdPid.COOLANT_TEMP.name to "engine.coolant_temp",
                ObdPid.OIL_TEMP.name to "engine.oil_temp",
                ObdPid.FUEL_LEVEL.name to "fuel.level",
                ObdPid.AMBIENT_TEMP.name to "outside.temp",
                ObdPid.ENGINE_LOAD.name to "engine.load",
                ObdPid.INTAKE_PRESSURE.name to "engine.map",
                ObdPid.MAF.name to "engine.maf",
                ObdPid.THROTTLE.name to "engine.throttle",
                ObdPid.INTAKE_TEMP.name to "engine.intake_temp",
                ObdPid.TIMING_ADVANCE.name to "engine.timing_advance",
                ObdPid.RUN_TIME.name to "engine.run_time",
                ObdPid.FUEL_RATE.name to "fuel.rate",
                ObdPid.BAROMETRIC.name to "outside.pressure",
                ObdPid.DISTANCE_MIL.name to "obd.distance_mil",
                ObdPid.DISTANCE_SINCE_CLEAR.name to "obd.distance_since_clear",
                // The ECU's supply (PID 0x42) and the adapter's reading of the socket (ATRV): the battery, near enough.
                ObdPid.MODULE_VOLTAGE.name to "battery.voltage",
                "BATTERY" to "battery.voltage",
            )

        /** OBD values (name → number, as the OBD manager reads them) as values by role. */
        fun obdValues(values: Map<String, Double>): Map<String, CanValue> {
            val out = LinkedHashMap<String, CanValue>()
            // The adapter's own reading first, so that PID 0x42 (when the car answers it) wins.
            for ((name, v) in values.entries.sortedBy { if (it.key == "BATTERY") 0 else 1 }) {
                val role = OBD_ROLES[name] ?: continue
                val r = CanRoles.byId(role)
                out[role] = CanValue(name, r?.en ?: role, "OBD-II", v, unit = r?.unit.orEmpty(), role = role)
            }
            return out
        }
    }
}
