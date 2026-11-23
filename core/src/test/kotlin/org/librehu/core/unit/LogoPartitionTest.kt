package org.librehu.core.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogoPartitionTest {
    private fun header(
        descW: Int,
        descH: Int,
        panelW: Int = descW,
        panelH: Int = descH,
        bpp: Int = 32,
        defaultCopy: Int = 0x1200 + descW * descH * 4 + 0x200,
        lvds: Boolean = false,
    ): ByteArray {
        val b = ByteArray(LogoHeader.HEADER_BYTES)
        fun put(o: Int, s: String) = s.forEachIndexed { i, c -> b[o + i] = c.code.toByte() }
        fun u32(o: Int, v: Int) = (0..3).forEach { b[o + it] = (v ushr (8 * it)).toByte() }
        put(0x200, "MRFv1.1.1")
        put(0x218, "V2.2.4")
        put(0x300, "8257Pa")
        u32(0x318, panelW)
        u32(0x31C, panelH)
        put(0x500, "Logo")
        u32(0x508, descW * descH * 4)
        u32(0x510, descW)
        u32(0x514, descH)
        u32(0x518, bpp)
        u32(0x524, defaultCopy)
        if (lvds) put(0xE00, "[Lvds_Init_S]")
        return b
    }

    @Test
    fun parsesHeader() {
        val h = LogoHeader.parse(header(720, 1280, defaultCopy = 0x385400, lvds = true))
        assertNotNull(h)
        h!!
        assertEquals("MRFv1.1.1 V2.2.4", h.mrfVersion)
        assertEquals(720, h.logoWidth)
        assertEquals(1280, h.logoHeight)
        assertEquals(0x384000, h.logoBytes)
        assertEquals(32, h.logoBpp)
        assertEquals(0x385400, h.defaultCopy)
        assertTrue(h.hasLvdsTable)
    }

    @Test
    fun parsesOd() {
        val b = header(1024, 600)
        val od = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }.chunked(48).joinToString("\n")
        assertEquals(1024, LogoHeader.parseOd(od)!!.logoWidth)
    }

    @Test
    fun notMrf() {
        assertNull(LogoHeader.parse(ByteArray(LogoHeader.HEADER_BYTES)))
    }

    @Test
    fun portraitPanelMountedSideways() {
        val p = LogoPlan.plan(LogoHeader.parse(header(720, 1280, defaultCopy = 0x385400)), 720, 1280, 90, null)
        assertNull(p.refused)
        assertEquals(720, p.width)
        assertEquals(1280, p.height)
        assertEquals(90, p.rotation)
    }

    @Test
    fun headerDisagreesWithPanel() {
        // Header 1024x600 left from another panel, MetaZone panel 720x1280: the boot loader draws 720x1280.
        val p = LogoPlan.plan(LogoHeader.parse(header(1024, 600)), 720, 1280, 270, null)
        assertEquals("logo_header_size:1024x600/720x1280", p.refused)
    }

    @Test
    fun refusals() {
        assertEquals("logo_header", LogoPlan.plan(null, 720, 1280, 0, null).refused)
        assertEquals("logo_bpp:16", LogoPlan.plan(LogoHeader.parse(header(720, 1280, bpp = 16)), 720, 1280, 0, null).refused)
        assertEquals(
            "logo_space",
            LogoPlan.plan(LogoHeader.parse(header(720, 1280, defaultCopy = 0x100000)), 720, 1280, 0, null).refused,
        )
    }

    @Test
    fun fallbacks() {
        // No MetaZone size: the descriptor's; no MetaZone rotation: the property; plus the user's turn.
        val p = LogoPlan.plan(LogoHeader.parse(header(1280, 720)), null, null, 999, 270, 180)
        assertNull(p.refused)
        assertEquals(1280, p.width)
        assertEquals(90, p.rotation)
    }
}
