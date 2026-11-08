package org.librehu.core.can

/**
 * The car profiles of LibreHU-car-profiles (`brands/`), copied in the test resources (`cars/<id>.json`): no car
 * profile is built into the service any more, the engine's tests still read real ones.
 */
object TestProfiles {
    fun text(id: String): String? = TestProfiles::class.java.getResourceAsStream("cars/$id.json")?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun load(id: String): CanVehicle = CanVehicles.fromJson(text(id) ?: error("test profile $id missing"))

    val RENAULT_CLIO3_HIWORLD: CanVehicle by lazy { load("renault-clio3-hiworld") }
    val HYUNDAI_KIA_HIWORLD: CanVehicle by lazy { load("hyundai-kia-hiworld") }
    val TOYOTA_HIWORLD: CanVehicle by lazy { load("toyota-hiworld") }
    val HONDA_HIWORLD: CanVehicle by lazy { load("honda-hiworld") }
    val VW_MQB_HIWORLD: CanVehicle by lazy { load("vw-mqb-hiworld") }
    val VW_PQ_HIWORLD: CanVehicle by lazy { load("vw-pq-hiworld") }
    val VW_MQB_RAISE: CanVehicle by lazy { load("vw-mqb-raise") }
    val VW_PQ_RAISE: CanVehicle by lazy { load("vw-pq-raise") }
    val PSA_RAISE: CanVehicle by lazy { load("psa-raise") }
    val PSA_RAISE_KEEP: CanVehicle by lazy { load("psa-raise-keep") }

    /** The ten cars, in the order they were built into the service up to versionCode 183. */
    val ALL: List<CanVehicle> by lazy {
        listOf(RENAULT_CLIO3_HIWORLD, HYUNDAI_KIA_HIWORLD, TOYOTA_HIWORLD, HONDA_HIWORLD, VW_MQB_HIWORLD, VW_PQ_HIWORLD, VW_MQB_RAISE, VW_PQ_RAISE, PSA_RAISE, PSA_RAISE_KEEP)
    }
}
