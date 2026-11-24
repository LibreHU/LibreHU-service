package org.librehu.service.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeCoverageTest {
    /** Names of the service's pages, read without initialising the enum (its icons are Compose objects). */
    private val tabs =
        Class
            .forName("org.librehu.service.ui.Tab", false, javaClass.classLoader)
            .declaredFields
            .filter { it.isEnumConstant }
            .map { it.name }

    @Test
    fun everyPageOfTheServiceIsOnThePhoneOrMarkedUnitOnly() {
        val missing = tabs.filter { it !in BridgeCoverage.ON_PHONE && it !in BridgeCoverage.UNIT_ONLY }
        assertEquals(
            "pages of the service with no phone page: add their settings to BridgeSettings and to BridgeCoverage.ON_PHONE, " +
                "or say why in BridgeCoverage.UNIT_ONLY",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun theListsNameRealPages() {
        val unknown = (BridgeCoverage.ON_PHONE.keys + BridgeCoverage.UNIT_ONLY.keys).filter { it !in tabs }
        assertEquals("names that are not pages of the service (renamed or removed?)", emptyList<String>(), unknown)
        assertTrue("a page cannot be both", BridgeCoverage.ON_PHONE.keys.intersect(BridgeCoverage.UNIT_ONLY.keys).isEmpty())
    }

    @Test
    fun phonePagesExist() {
        val unknown = BridgeCoverage.ON_PHONE.values.toSet() - BridgeCoverage.PAGE_IDS.toSet()
        assertEquals("phone pages that BridgeSettings does not send", emptySet<String>(), unknown)
    }
}
