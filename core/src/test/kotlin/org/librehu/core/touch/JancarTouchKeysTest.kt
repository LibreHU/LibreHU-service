package org.librehu.core.touch

import org.junit.Assert.assertEquals
import org.junit.Test

class JancarTouchKeysTest {
    @Test
    fun factoryFileOfTheUjc201() {
        val zones = JancarTouchKeys.parse(JancarTouchKeys.UJC201)
        assertEquals(7, zones.size)
        val mute = zones[0]
        assertEquals(586, mute.x)
        assertEquals(-46, mute.y)
        assertEquals(TouchAction.MUTE, mute.click.action)
        assertEquals(TouchAction.POWER_MENU, mute.longPress.action)
        assertEquals(500, mute.longMs)
        assertEquals(TouchAction.ALL_APPS, zones[2].longPress.action)
        assertEquals(TouchAction.BRIGHTNESS_UP, zones[5].click.action)
        assertEquals(TouchAction.BRIGHTNESS_DOWN, zones[6].click.action)
        assertEquals(zones, TouchZoneEngine.fromJson(TouchZoneEngine.toJson(zones)))
    }

    @Test
    fun overlappingZonesGoToTheNearest() {
        // bl_del (55,-35) and bl_add (132,-52) overlap with radius 40: a touch near bl_add must not fire bl_del.
        val zones = JancarTouchKeys.parse(JancarTouchKeys.UJC201)
        val fired = mutableListOf<TouchAction>()
        val e = TouchZoneEngine(zones) { _, a -> fired += a.action }
        e.onSample(TouchSample(true, 100, -45, 0))
        e.onSample(TouchSample(false, 100, -45, 50))
        assertEquals(listOf(TouchAction.BRIGHTNESS_UP), fired)
    }

    @Test
    fun longPressUsesTheZoneTime() {
        val zones = JancarTouchKeys.parse(JancarTouchKeys.UJC201)
        val fired = mutableListOf<TouchAction>()
        val e = TouchZoneEngine(zones) { _, a -> fired += a.action }
        e.onSample(TouchSample(true, 586, -46, 0))
        e.onTick(450)
        e.onTick(520)
        e.onSample(TouchSample(false, 586, -46, 600))
        assertEquals(listOf(TouchAction.POWER_MENU), fired)
    }
}
