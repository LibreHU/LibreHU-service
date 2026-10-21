package org.librehu.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBatteryTest {
    @Test
    fun percentFollowsTheTable() {
        assertEquals(100, CarBattery.percent(12.7))
        assertEquals(75, CarBattery.percent(12.4))
        assertEquals(63, CarBattery.percent(12.3))
        assertEquals(25, CarBattery.percent(12.0))
        assertEquals(0, CarBattery.percent(11.5))
    }

    @Test
    fun engineFromRpmOrVoltage() {
        assertEquals(BatteryStatus.CHARGING, CarBattery.read(14.2, 800.0).status)
        assertEquals(BatteryStatus.NOT_CHARGING, CarBattery.read(12.4, 800.0).status)
        assertEquals(BatteryStatus.OVERVOLTAGE, CarBattery.read(15.4, 800.0).status)
        assertEquals(BatteryStatus.ENGINE_OFF, CarBattery.read(12.4, 0.0).status)
        assertEquals(BatteryStatus.CHARGING, CarBattery.read(14.0, null).status)
        assertNull(CarBattery.read(14.0, null).percent)
        assertEquals(75, CarBattery.read(12.4, null).percent)
    }

    @Test
    fun alarmOnceAfterItsDelay() {
        val alarms = BatteryAlarms()
        val limits = BatteryLimits()
        val low = CarBattery.read(11.9, 0.0)
        assertTrue(alarms.update(low, limits, 0).isEmpty())
        assertTrue(alarms.update(low, limits, 20_000).isEmpty())
        assertEquals(listOf(BatteryAlarm.LOW), alarms.update(low, limits, 31_000))
        assertTrue(alarms.update(low, limits, 60_000).isEmpty())
        // 12.1 V: not low any more, but not cleared yet (hysteresis); 12.3 V clears it.
        alarms.update(CarBattery.read(12.1, 0.0), limits, 61_000)
        alarms.update(low, limits, 62_000)
        assertTrue(alarms.update(low, limits, 100_000).isEmpty())
        alarms.update(CarBattery.read(12.3, 0.0), limits, 101_000)
        alarms.update(low, limits, 102_000)
        assertEquals(listOf(BatteryAlarm.LOW), alarms.update(low, limits, 133_000))
    }
}
