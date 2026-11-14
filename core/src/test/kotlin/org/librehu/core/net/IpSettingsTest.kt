package org.librehu.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.librehu.core.net.IpSettings.Problem

class IpSettingsTest {
    @Test
    fun dhcpHasNoProblemAndRoundTrips() {
        val s = IpSettings()
        assertTrue(s.problems().isEmpty())
        assertEquals("dhcp", s.encode())
        assertEquals(s, IpSettings.decode("dhcp"))
    }

    @Test
    fun staticRoundTrips() {
        val s = IpSettings(true, "192.168.1.50", 24, "192.168.1.1", listOf("1.1.1.1", "9.9.9.9"))
        assertTrue(s.problems().isEmpty())
        assertEquals("static;192.168.1.50/24;192.168.1.1;1.1.1.1,9.9.9.9", s.encode())
        assertEquals(s, IpSettings.decode(s.encode()))
    }

    @Test
    fun staticWithoutGatewayNorDns() {
        val s = IpSettings(true, "10.0.0.2", 8)
        assertTrue(s.problems().isEmpty())
        assertEquals(s, IpSettings.decode(s.encode()))
    }

    @Test
    fun problemsAreFound() {
        assertEquals(listOf(Problem.ADDRESS), IpSettings(true, "192.168.1.256", 24).problems())
        assertEquals(listOf(Problem.PREFIX), IpSettings(true, "192.168.1.5", 33).problems())
        assertEquals(listOf(Problem.GATEWAY), IpSettings(true, "192.168.1.5", 24, "router").problems())
        assertEquals(listOf(Problem.GATEWAY_OUTSIDE), IpSettings(true, "192.168.1.5", 24, "192.168.2.1").problems())
        assertEquals(listOf(Problem.DNS), IpSettings(true, "192.168.1.5", 24, dns = listOf("8.8.8")).problems())
        assertEquals(listOf(Problem.DNS), IpSettings(true, "192.168.1.5", 24, dns = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")).problems())
    }

    @Test
    fun ipv4Syntax() {
        assertTrue(IpSettings.isIpv4("0.0.0.0"))
        assertTrue(IpSettings.isIpv4("255.255.255.255"))
        assertFalse(IpSettings.isIpv4("1.2.3"))
        assertFalse(IpSettings.isIpv4("1.2.3.4.5"))
        assertFalse(IpSettings.isIpv4("1.2.3.-4"))
        assertFalse(IpSettings.isIpv4("1.2.3.1000"))
        assertFalse(IpSettings.isIpv4(" 1.2.3.4"))
    }

    @Test
    fun subnets() {
        assertTrue(IpSettings.sameSubnet("172.20.10.2", "172.20.10.1", 28))
        assertFalse(IpSettings.sameSubnet("172.20.10.20", "172.20.10.1", 28))
        assertTrue(IpSettings.sameSubnet("10.1.2.3", "10.200.0.1", 8))
        assertTrue(IpSettings.sameSubnet("1.2.3.4", "200.1.1.1", 0))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(IpSettings.decode(null))
        assertNull(IpSettings.decode(""))
        assertNull(IpSettings.decode("static;192.168.1.5;;"))
        assertNull(IpSettings.decode("static;192.168.1.5/x;;"))
        assertNull(IpSettings.decode("manual"))
    }
}
