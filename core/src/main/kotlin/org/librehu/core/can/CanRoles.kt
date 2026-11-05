package org.librehu.core.can

/**
 * What a decoded value means, whatever the car and the box: a profile signal names its [CanRole] (`"role"`), and
 * LibreHU's features read the values by role (the reverse camera takes `steering.angle` and the `radar.*` roles from
 * any profile). The ids are short; [vss] is the matching path of the COVESA Vehicle Signal Specification 4.2
 * (github.com/COVESA/vehicle_signal_specification, release v4.2 `vss_rel_4.2.csv`) when VSS has one, with VSS's unit
 * and sign (steering angle: positive to the left). VSS names doors by driver / passenger side: the paths here are
 * those of a left-hand drive car. Roles VSS lacks (parking sensors, wheel keys, box version…) have no [vss].
 */
data class CanRole(
    val id: String,
    val kind: Kind,
    val unit: String = "",
    val vss: String? = null,
    val en: String,
    val fr: String,
) {
    enum class Kind { NUMBER, FLAG, TEXT }
}

object CanRoles {
    private fun n(
        id: String,
        unit: String,
        vss: String?,
        en: String,
        fr: String,
    ) = CanRole(id, CanRole.Kind.NUMBER, unit, vss, en, fr)

    private fun f(
        id: String,
        vss: String?,
        en: String,
        fr: String,
    ) = CanRole(id, CanRole.Kind.FLAG, "", vss, en, fr)

    private fun t(
        id: String,
        en: String,
        fr: String,
    ) = CanRole(id, CanRole.Kind.TEXT, "", null, en, fr)

    private const val ENGINE = "Vehicle.Powertrain.CombustionEngine"
    private const val FUEL = "Vehicle.Powertrain.FuelSystem"
    private const val LIGHTS = "Vehicle.Body.Lights"
    private const val HVAC = "Vehicle.Cabin.HVAC"
    private const val AXLE = "Vehicle.Chassis.Axle"

    private fun radar(
        zone: String,
        zoneFr: String,
        spots: List<Pair<String, String>>,
    ) = spots.map { (s, sFr) -> n("radar.$zone.$s", "%", null, "$zone sensor $s (proximity, 100 = touching)", "capteur $zoneFr $sFr (proximité, 100 = contact)") }

    private val SPOTS = listOf("left" to "gauche", "left_center" to "centre gauche", "right_center" to "centre droit", "right" to "droit")
    private val SIDE_SPOTS = listOf("front" to "avant", "front_center" to "avant centre", "rear_center" to "arrière centre", "rear" to "arrière")

    private fun tyre(
        pos: String,
        posFr: String,
        axle: String,
        side: String,
    ) = listOf(
        n("tpms.$pos.pressure", "kPa", "$AXLE.$axle.Wheel.$side.Tire.Pressure", "tyre pressure, $pos", "pression du pneu, $posFr"),
        n("tpms.$pos.temperature", "°C", "$AXLE.$axle.Wheel.$side.Tire.Temperature", "tyre temperature, $pos", "température du pneu, $posFr"),
        f("tpms.$pos.warning", null, "tyre warning, $pos", "alerte pneu, $posFr"),
    )

    val ALL: List<CanRole> =
        listOf(
            // Driving.
            n("vehicle.speed", "km/h", "Vehicle.Speed", "speed", "vitesse"),
            n("vehicle.odometer", "km", "Vehicle.TraveledDistance", "odometer", "kilométrage"),
            n("vehicle.range", "km", "Vehicle.Powertrain.Range", "range", "autonomie"),
            f("ignition.acc", null, "ignition (accessories)", "contact (accessoires)"),
            f("ignition.on", null, "ignition on", "contact mis"),
            f("gear.reverse", null, "reverse gear", "marche arrière"),
            f("gear.park", "Vehicle.Powertrain.Transmission.IsParkLockEngaged", "gear lever on P", "levier sur P"),
            n("gear.current", "", "Vehicle.Powertrain.Transmission.CurrentGear", "gear (0 neutral, -1 reverse)", "rapport (0 point mort, -1 marche arrière)"),
            f("handbrake", "Vehicle.Chassis.ParkingBrake.IsEngaged", "handbrake", "frein à main"),
            f("seatbelt.driver", "Vehicle.Cabin.Seat.Row1.DriverSide.IsBelted", "driver's seat belt fastened", "ceinture conducteur bouclée"),
            f("seatbelt.warning", null, "seat belt warning", "alerte ceinture"),
            // Steering: degrees (VSS: positive to the left) or, when a box gives no degrees, -1..1 of full lock.
            n("steering.angle", "°", "Vehicle.Chassis.SteeringWheel.Angle", "steering wheel angle (positive to the left)", "angle du volant (positif à gauche)"),
            n("steering.position", "", null, "steering, -1..1 of full lock (positive to the left)", "volant, -1..1 de la butée (positif à gauche)"),
            // Engine.
            n("engine.rpm", "rpm", "$ENGINE.Speed", "engine speed", "régime moteur"),
            n("engine.coolant_temp", "°C", "$ENGINE.EngineCoolant.Temperature", "coolant temperature", "température d'eau"),
            n("engine.oil_temp", "°C", "$ENGINE.EngineOil.Temperature", "oil temperature", "température d'huile"),
            f("engine.oil_warning", null, "engine oil warning", "alerte huile moteur"),
            n("battery.voltage", "V", "Vehicle.LowVoltageBattery.CurrentVoltage", "battery voltage", "tension batterie"),
            // OBD-II mode 01 values (SAE J1979) without a role above: VSS has them in Vehicle.OBD or CombustionEngine.
            n("engine.load", "%", "Vehicle.OBD.EngineLoad", "engine load", "charge moteur"),
            n("engine.map", "kPa", "$ENGINE.MAP", "intake manifold pressure", "pression d'admission"),
            n("engine.maf", "g/s", "$ENGINE.MAF", "air flow", "débit d'air"),
            n("engine.throttle", "%", "$ENGINE.TPS", "throttle position", "position du papillon"),
            n("engine.intake_temp", "°C", "Vehicle.OBD.IntakeTemp", "intake air temperature", "température d'admission"),
            n("engine.timing_advance", "°", "Vehicle.OBD.TimingAdvance", "timing advance", "avance à l'allumage"),
            n("engine.run_time", "s", "Vehicle.OBD.RunTime", "time since engine start", "temps depuis le démarrage"),
            n("fuel.rate", "L/h", "Vehicle.OBD.FuelRate", "fuel rate", "débit de carburant"),
            n("outside.pressure", "kPa", "Vehicle.OBD.BarometricPressure", "barometric pressure", "pression atmosphérique"),
            n("obd.distance_mil", "km", "Vehicle.OBD.DistanceWithMIL", "distance with the engine light on", "distance avec voyant moteur"),
            n("obd.distance_since_clear", "km", "Vehicle.OBD.DistanceSinceDTCClear", "distance since codes cleared", "distance depuis l'effacement des codes"),
            f("battery.warning", null, "low battery warning", "alerte batterie faible"),
            // Fuel and trip.
            n("fuel.level", "%", "$FUEL.RelativeLevel", "fuel left (% of the tank)", "carburant restant (% du réservoir)"),
            n("fuel.liters", "L", "$FUEL.AbsoluteLevel", "fuel left (litres)", "carburant restant (litres)"),
            n("fuel.range", "km", "$FUEL.Range", "range on fuel", "autonomie carburant"),
            n("fuel.instant", "L/100 km", "$FUEL.InstantConsumption", "instant consumption", "consommation instantanée"),
            n("fuel.average", "L/100 km", "$FUEL.AverageConsumption", "average consumption", "consommation moyenne"),
            f("fuel.warning", null, "low fuel warning", "alerte carburant bas"),
            f("washer.warning", null, "low washer fluid", "lave-glace bas"),
            n("trip.distance", "km", "Vehicle.TraveledDistanceSinceStart", "trip distance", "distance du trajet"),
            n("trip.average_speed", "km/h", "Vehicle.AverageSpeed", "average speed", "vitesse moyenne"),
            n("trip.time", "min", null, "trip time", "durée du trajet"),
            n("trip.fuel_used", "L", null, "fuel used on the trip", "carburant consommé sur le trajet"),
            n("service.distance", "km", "Vehicle.Service.DistanceToService", "distance to the next service", "distance avant la révision"),
            // Doors (left-hand drive in VSS).
            f("door.front_left", "Vehicle.Cabin.Door.Row1.DriverSide.IsOpen", "front left door open", "porte avant gauche ouverte"),
            f("door.front_right", "Vehicle.Cabin.Door.Row1.PassengerSide.IsOpen", "front right door open", "porte avant droite ouverte"),
            f("door.rear_left", "Vehicle.Cabin.Door.Row2.DriverSide.IsOpen", "rear left door open", "porte arrière gauche ouverte"),
            f("door.rear_right", "Vehicle.Cabin.Door.Row2.PassengerSide.IsOpen", "rear right door open", "porte arrière droite ouverte"),
            f("door.trunk", "Vehicle.Body.Trunk.Rear.IsOpen", "trunk open", "coffre ouvert"),
            f("door.hood", "Vehicle.Body.Hood.IsOpen", "hood open", "capot ouvert"),
            // Lights.
            f("lights.on", null, "lights on (dashboard lit: night mode)", "feux allumés (tableau de bord éclairé : mode nuit)"),
            f("lights.parking", "$LIGHTS.Parking.IsOn", "side lights", "veilleuses"),
            f("lights.low_beam", "$LIGHTS.Beam.Low.IsOn", "low beam", "feux de croisement"),
            f("lights.high_beam", "$LIGHTS.Beam.High.IsOn", "high beam", "feux de route"),
            f("lights.fog_front", "$LIGHTS.Fog.Front.IsOn", "front fog lights", "antibrouillards avant"),
            f("lights.fog_rear", "$LIGHTS.Fog.Rear.IsOn", "rear fog light", "antibrouillard arrière"),
            f("lights.brake", "$LIGHTS.Brake.IsActive", "brake lights", "feux stop"),
            f("lights.reverse", "$LIGHTS.Backup.IsOn", "reversing lights", "feux de recul"),
            f("turn.left", "$LIGHTS.DirectionIndicator.Left.IsSignaling", "left turn signal", "clignotant gauche"),
            f("turn.right", "$LIGHTS.DirectionIndicator.Right.IsSignaling", "right turn signal", "clignotant droit"),
            f("turn.hazard", "$LIGHTS.Hazard.IsSignaling", "hazard lights", "feux de détresse"),
            n("lights.dashboard_level", "%", null, "dashboard brightness", "luminosité du tableau de bord"),
            // Climate.
            n("outside.temp", "°C", "Vehicle.Exterior.AirTemperature", "outside temperature", "température extérieure"),
            n("inside.temp", "°C", "$HVAC.AmbientAirTemperature", "inside temperature", "température intérieure"),
            f("hvac.power", null, "climate on", "clim allumée"),
            f("hvac.ac", "$HVAC.IsAirConditioningActive", "air conditioning", "climatisation"),
            f("hvac.auto", null, "climate auto", "clim auto"),
            f("hvac.dual", null, "dual zone", "bizone"),
            f("hvac.recirculation", "$HVAC.IsRecirculationActive", "recirculation", "recyclage"),
            f("hvac.defrost_front", "$HVAC.IsFrontDefrosterActive", "front defrost", "désembuage avant"),
            f("hvac.defrost_rear", "$HVAC.IsRearDefrosterActive", "rear defrost", "dégivrage arrière"),
            n("hvac.temp_left", "°C", "$HVAC.Station.Row1.Driver.Temperature", "set temperature, left", "température réglée, gauche"),
            n("hvac.temp_right", "°C", "$HVAC.Station.Row1.Passenger.Temperature", "set temperature, right", "température réglée, droite"),
            n("hvac.fan", "", null, "fan speed (box steps)", "ventilation (crans du boîtier)"),
            n("hvac.airflow", "", null, "air distribution (box code)", "répartition de l'air (code du boîtier)"),
            n("hvac.seat_heat_left", "", null, "seat heating, left (level)", "siège chauffant, gauche (niveau)"),
            n("hvac.seat_heat_right", "", null, "seat heating, right (level)", "siège chauffant, droite (niveau)"),
            // Parking sensors: proximity 0..100 (0 = nothing, 100 = touching), whatever the box's scale.
        ) + radar("rear", "arrière", SPOTS) + radar("front", "avant", SPOTS) +
            radar("left", "gauche", SIDE_SPOTS) + radar("right", "droit", SIDE_SPOTS) +
            listOf(f("radar.shown", null, "parking sensors shown", "radar affiché")) +
            tyre("front_left", "avant gauche", "Row1", "Left") + tyre("front_right", "avant droit", "Row1", "Right") +
            tyre("rear_left", "arrière gauche", "Row2", "Left") + tyre("rear_right", "arrière droit", "Row2", "Right") +
            listOf(
                t("box.version", "box version", "version du boîtier"),
                t("vehicle.vin", "VIN", "VIN"),
            )

    private val BY_ID: Map<String, CanRole> = ALL.associateBy { it.id }

    fun byId(id: String): CanRole? = BY_ID[id]

    /** A role a profile may name: one of [ALL], or its own under `x.` (a value no LibreHU feature reads yet). */
    fun isValid(id: String): Boolean = id in BY_ID || Regex("x\\.[a-z0-9_.]{1,60}").matches(id)
}
