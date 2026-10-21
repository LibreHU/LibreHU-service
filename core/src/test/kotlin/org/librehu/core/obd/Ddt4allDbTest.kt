package org.librehu.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ddt4allDbTest {
    private val db =
        """
        {
         "UCH_X85.json": {"address": "26", "group": "UCH", "protocol": "CAN", "projects": ["X85", "X65"], "ecuname": "UCH X85",
           "autoidents": [{"diagnostic_version": "8", "supplier_code": "213", "soft_version": "0009", "version": "0600"}]},
         "UCH_X85_v2.json": {"address": "26", "group": "UCH", "protocol": "CAN", "projects": ["X85"], "ecuname": "UCH X85 v2",
           "autoidents": [{"diagnostic_version": "8", "supplier_code": "213", "soft_version": "0009", "version": "0602"}]},
         "INJ_KWP.json": {"address": "7A", "group": "Injection", "protocol": "KWP2000", "projects": ["X85"], "ecuname": "Inj",
           "autoidents": []}
        }
        """.trimIndent()

    @Test
    fun projectsKeepCanEcusByAddress() {
        val projects = Ddt4allDb.projects(Ddt4allDb.parse(db))
        val clio = projects.first { it.code == "X85" }
        assertEquals("Renault Clio III", clio.name)
        assertEquals(setOf("26"), clio.ecus.keys)
        assertEquals(2, clio.ecus.getValue("26").size)
        assertTrue(projects.any { it.code == "X65" })
    }

    @Test
    fun ident2180LongAnswer() {
        // Sample answer of ddt4all's scanner (ecu_scanner.py, simulation of address 51).
        val hex = "61 80 82 00 45 15 05 08 32 31 33 21 11 31 39 09 00 09 06 02 05 01 0D 8D 39 00"
        val ident = Ddt4allDb.ident2180(hex.split(" ").map { it.toInt(16) }.toIntArray())!!
        assertEquals(DdtIdent("8", "213", "0009", "0602"), ident)
        val (file, exact) = Ddt4allDb.match(Ddt4allDb.parse(db).filter { it.address == "26" }, ident)!!
        assertEquals("UCH_X85_v2.json", file.file)
        assertTrue(exact)
    }

    @Test
    fun approximateKeepsClosestVersion() {
        val (file, exact) = Ddt4allDb.match(Ddt4allDb.parse(db), DdtIdent("9", "213", "0009", "0601"))!!
        assertEquals(false, exact)
        assertTrue(file.file.startsWith("UCH_X85"))
        assertNull(Ddt4allDb.match(Ddt4allDb.parse(db), DdtIdent("8", "999", "0009", "0600")))
    }

    @Test
    fun udsIdent() {
        fun b(s: String) = s.split(" ").map { it.toInt(16) }.toIntArray()
        val ident = Ddt4allDb.identUds(b("62 F1 A0 08"), b("62 F1 8A 43 41 50 20 20"), b("62 F1 94 41 42 00 FF"), b("62 F1 95 31 32"))!!
        assertEquals(DdtIdent("8", "CAP", "AB", "12"), ident)
    }
}
