package org.librehu.service.bridge

/**
 * Every page of the service's rail ([org.librehu.service.ui.Tab], by name) and where the phone app shows it: the
 * [BridgeSettings] page that carries its settings, or why it stays on the head unit only. BridgeCoverageTest fails the
 * build when a new page is in neither list, so a page added to the service is not forgotten on the phone. Names, not
 * the enum itself: the test reads them without loading the screens.
 */
object BridgeCoverage {
    /** Page of the service → id of the [BridgeSettings] page with its settings. */
    val ON_PHONE: Map<String, String> =
        mapOf(
            "VEHICLE" to "vehicle",
            "REVERSE" to "reverse",
            "VIDEO" to "video",
            "OBD" to "obd",
            "CAN" to "vehicle",
            "AUDIO" to "audio",
            "BLUETOOTH" to "bluetooth",
            "PHONE" to "bluetooth",
            "ANDROID_AUTO" to "projection",
            "CARPLAY" to "projection",
            "DISPLAY" to "display",
            "TOUCH" to "vehicle",
            "LIGHTS" to "lights",
            "SYSTEM" to "system",
            "GPS" to "time",
            "NETWORK" to "bluetooth",
            "TWEAKS" to "tweaks",
        )

    /** Page of the service → why the phone app does not show it. */
    val UNIT_ONLY: Map<String, String> =
        mapOf(
            "HOME" to "dashboard: the phone app has its own (values method)",
            "MCU" to "writes the MCU and its profiles",
            "MCU_TOOLS" to "MCU diagnostics",
            "AUDIO_DIAG" to "audio diagnostics",
            "DIAG" to "diagnostics, traffic",
            "ATC" to "Autochips tools (root, engineer mode)",
            "INFO" to "the phone app has its own Infos screen",
        )

    /** Ids of the pages [BridgeSettings.pages] sends, in its order. */
    val PAGE_IDS = listOf("audio", "vehicle", "reverse", "video", "obd", "bluetooth", "projection", "display", "lights", "system", "time", "tweaks")
}
