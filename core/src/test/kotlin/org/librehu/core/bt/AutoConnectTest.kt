package org.librehu.core.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoConnectTest {
    private val plan = AutoConnectPlan(delaysMs = listOf(1, 2), repeatMs = 10, maxAttempts = 4)

    @Test
    fun delaysThenRepeatThenGiveUp() {
        assertEquals(1L, plan.delayBefore(0))
        assertEquals(2L, plan.delayBefore(1))
        assertEquals(10L, plan.delayBefore(2))
        assertEquals(10L, plan.delayBefore(3))
        assertNull(plan.delayBefore(4))
        assertNull(plan.delayBefore(-1))
    }

    @Test
    fun alternatesBetweenRememberedPairedPhones() {
        val history = listOf("AA", "BB", "CC")
        val bonded = setOf("AA", "CC", "DD")
        assertEquals("AA", plan.target(0, history, bonded))
        assertEquals("CC", plan.target(1, history, bonded))
        assertEquals("AA", plan.target(2, history, bonded))
        // Paired but never connected: not tried.
        assertNull(plan.target(0, emptyList(), bonded))
    }

    @Test
    fun mruKeepsMostRecentFirstWithoutDuplicates() {
        var l = emptyList<String>()
        l = Mru.push(l, "AA")
        l = Mru.push(l, "BB")
        l = Mru.push(l, "aa")
        assertEquals(listOf("aa", "BB"), l)
        assertEquals(listOf("BB"), Mru.remove(l, "AA"))
        assertEquals(l, Mru.decode(Mru.encode(l)))
        assertEquals(emptyList<String>(), Mru.decode(null))
        assertEquals(listOf("A", "B"), Mru.push(listOf("B", "C", "D"), "A", max = 2))
    }
}
