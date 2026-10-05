package org.librehu.service

import org.librehu.core.unit.VehicleState

/** Public constants of the LibreHU-service API (AIDL [ILibreHuService] and broadcasts). */
object LibreHu {
    const val API_VERSION = 4
    const val PACKAGE = "org.librehu.service"
    const val ACTION_BIND = "org.librehu.service.BIND"
    const val PERMISSION = "org.librehu.permission.HEADUNIT"

    const val FLAG_MCU_ONLINE = 1 shl 0
    const val FLAG_ACC = 1 shl 1
    const val FLAG_HANDBRAKE = 1 shl 2
    const val FLAG_HEADLIGHT = 1 shl 3
    const val FLAG_REVERSE = 1 shl 4
    const val FLAG_TURN_LEFT = 1 shl 5
    const val FLAG_TURN_RIGHT = 1 shl 6

    /** Sent on every vehicle state change, with [EXTRA_FLAGS] and one boolean extra per flag. */
    const val ACTION_VEHICLE_STATE = "org.librehu.action.VEHICLE_STATE"

    /** Sent when ACC changes, with the boolean extra [EXTRA_ACC]. */
    const val ACTION_ACC = "org.librehu.action.ACC"

    /** Sent when reverse gear changes, with the boolean extra [EXTRA_REVERSE]. */
    const val ACTION_REVERSE = "org.librehu.action.REVERSE"

    /** Sent about once a second while the ELM327 is connected; extras as [ILibreHuService.getObdValues]. */
    const val ACTION_OBD = "org.librehu.action.OBD"

    const val EXTRA_FLAGS = "flags"
    const val EXTRA_ACC = "acc"
    const val EXTRA_REVERSE = "reverse"
    const val EXTRA_HANDBRAKE = "handbrake"
    const val EXTRA_HEADLIGHT = "headlight"

    fun flagsOf(s: VehicleState): Int {
        var f = 0
        if (s.mcuOnline) f = f or FLAG_MCU_ONLINE
        if (s.acc) f = f or FLAG_ACC
        if (s.handbrake) f = f or FLAG_HANDBRAKE
        if (s.headlight) f = f or FLAG_HEADLIGHT
        if (s.reverse) f = f or FLAG_REVERSE
        if (s.turnLeft) f = f or FLAG_TURN_LEFT
        if (s.turnRight) f = f or FLAG_TURN_RIGHT
        return f
    }
}
