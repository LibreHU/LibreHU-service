package org.librehu.core.touch

/**
 * Factory front panel keys of Jancar units: `/jancar/config/touch_key.xml` read by ivi-services, one
 * `<item xPos yPos KeyType KeyType_Long Radius Repetitive ActiveTime />` per printed button, in driver coordinates
 * (the buttons are above the LCD: negative y).
 */
object JancarTouchKeys {
    const val PATH = "/jancar/config/touch_key.xml"

    /** touch_key.xml of the UJC201 (dump of this unit), used when the file cannot be read. */
    val UJC201 =
        """
        <key>
        <item xPos="586" yPos="-46" KeyType="mute_or_powup" KeyType_Long="power" Radius="40" Repetitive="0" ActiveTime="500" />
        <item xPos="226" yPos="-51" KeyType="del_volume" KeyType_Long="prev" Radius="40" Repetitive="0" ActiveTime="500" />
        <item xPos="511" yPos="-53" KeyType="home" KeyType_Long="all_app" Radius="40" Repetitive="0" ActiveTime="500" />
        <item xPos="406" yPos="-48" KeyType="back" KeyType_Long="play_pause" Radius="40" Repetitive="0" ActiveTime="500" />
        <item xPos="326" yPos="-55" KeyType="add_volume" KeyType_Long="next" Radius="40" Repetitive="0" ActiveTime="500" />
        <item xPos="132" yPos="-52" KeyType="bl_add" KeyType_Long="none" Radius="40" Repetitive="0" ActiveTime="500" />
        <item xPos="55" yPos="-35" KeyType="bl_del" KeyType_Long="none" Radius="40" Repetitive="0" ActiveTime="500" />
        </key>
        """.trimIndent()

    /** LibreHU action of a factory `KeyType` (NONE when there is no equivalent). */
    fun action(keyType: String?): TouchAction =
        when (keyType?.trim()?.lowercase()) {
            "mute", "mute_or_powup" -> TouchAction.MUTE
            "power", "power_off", "powerdown" -> TouchAction.POWER_MENU
            "add_volume", "vol_add", "volume_up" -> TouchAction.VOLUME_UP
            "del_volume", "vol_del", "volume_down" -> TouchAction.VOLUME_DOWN
            "home" -> TouchAction.HOME
            "back" -> TouchAction.BACK
            "all_app", "all_apps", "apps" -> TouchAction.ALL_APPS
            "play_pause" -> TouchAction.PLAY_PAUSE
            "next" -> TouchAction.NEXT
            "prev", "previous" -> TouchAction.PREVIOUS
            "bl_add" -> TouchAction.BRIGHTNESS_UP
            "bl_del" -> TouchAction.BRIGHTNESS_DOWN
            "recent", "recents" -> TouchAction.RECENTS
            else -> TouchAction.NONE
        }

    private fun name(keyType: String?): String =
        when (action(keyType)) {
            TouchAction.MUTE -> "Mute / power"
            TouchAction.VOLUME_UP -> "Volume +"
            TouchAction.VOLUME_DOWN -> "Volume -"
            TouchAction.HOME -> "Home"
            TouchAction.BACK -> "Back"
            TouchAction.BRIGHTNESS_UP -> "Brightness +"
            TouchAction.BRIGHTNESS_DOWN -> "Brightness -"
            else -> keyType.orEmpty()
        }

    /** Zones of a touch_key.xml, ids from 1. Volume keys repeat when the factory long press is not used. */
    fun parse(xml: String): List<TouchZone> =
        Regex("""<item\b([^>]*)/?>""")
            .findAll(xml)
            .mapIndexedNotNull { i, m ->
                val a = Regex("""(\w+)\s*=\s*"([^"]*)"""").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                val x = a["xPos"]?.trim()?.toIntOrNull() ?: return@mapIndexedNotNull null
                val y = a["yPos"]?.trim()?.toIntOrNull() ?: return@mapIndexedNotNull null
                val click = action(a["KeyType"])
                val long = action(a["KeyType_Long"])
                TouchZone(
                    id = i + 1,
                    name = name(a["KeyType"]),
                    x = x,
                    y = y,
                    radius = a["Radius"]?.trim()?.toIntOrNull() ?: 40,
                    click = ZoneAction(click),
                    longPress = ZoneAction(long),
                    repeat = a["Repetitive"]?.trim() == "1",
                    longMs = a["ActiveTime"]?.trim()?.toIntOrNull()?.coerceIn(200, 3000) ?: 600,
                )
            }.toList()
}
