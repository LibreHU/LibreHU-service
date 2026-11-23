package org.librehu.core.unit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardProfileTest {
    @Test
    fun a0WithBd37534() {
        val p = BoardProfiles.forBoardId("A0_AN")
        assertEquals(AudioChip.BD37534, p.chip)
        assertEquals("Platform_AutoChips_8257_37534", p.platform)
        assertEquals(11, p.androidInput)
        assertEquals(4, p.androidGainDb)
        assertEquals(-10, p.subBaseDb)
        assertEquals("Audio_A0_AN", p.iviConfigSection)
        assertNull(p.tuner)
        assertTrue(p.known)
    }

    @Test
    fun a1Boards() {
        val bd = BoardProfiles.forBoardId("A1_AN")
        assertEquals(AudioChip.BD37534, bd.chip)
        assertEquals(5, bd.androidGainDb)
        assertEquals(13, bd.radioGainDb)
        assertEquals(-5, bd.subBaseDb)
        assertEquals("Audio_A1", bd.iviConfigSection)
        val ak = BoardProfiles.forBoardId("A1_CW")
        assertEquals(AudioChip.AK7604, ak.chip)
        assertEquals(5, ak.i2cBus)
        assertEquals(16, ak.androidInput)
        assertEquals("W", ak.tuner)
        assertNull(BoardProfiles.forBoardId("A1_BN").chip)
    }

    @Test
    fun otherBoards() {
        assertEquals(AudioChip.BU32107, BoardProfiles.forBoardId("B0_BT").chip)
        assertEquals("T", BoardProfiles.forBoardId("B0_BT").tuner)
        assertEquals(AudioChip.BU32107, BoardProfiles.forBoardId("C0_BN").chip)
        assertEquals(AudioChip.BD37534, BoardProfiles.forBoardId("C1_AN").chip)
        assertEquals("Platform_Ac8257D_37534", BoardProfiles.forBoardId("D0_AT").platform)
        assertEquals("Platform_Ac8257D_32107", BoardProfiles.forBoardId("D0_BN").platform)
        assertEquals(-18, BoardProfiles.forBoardId("D0_BN").subBaseDb)
    }

    @Test
    fun unknownIsA0() {
        for (raw in listOf(null, "", "NULL", "A0")) {
            val p = BoardProfiles.forBoardId(raw)
            assertEquals(AudioChip.BD37534, p.chip)
            assertEquals(4, p.androidGainDb)
            assertFalse(p.known)
        }
    }
}
