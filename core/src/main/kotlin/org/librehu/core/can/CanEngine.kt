package org.librehu.core.can

/**
 * The version of LibreHU's car data engine (profile format, decoding, link with the box), in Semantic Versioning 2.0.0
 * (semver.org): MAJOR when a profile written for the previous major could be read differently (old profiles stay
 * readable: [CanVehicles] reads every earlier format), MINOR when the format gains something a profile may use,
 * PATCH for fixes. A profile says the engine it needs (`"engine": ">=2.3"`); a service with an older engine refuses it
 * with a clear message instead of reading it half. [requiredBy] gives the smallest engine a profile needs (written by
 * [CanVehicles.toJson], checked by the profiles repository).
 */
object CanEngine {
    const val VERSION = "2.8.0"

    /** What each version brought (service versionCode in brackets), newest last. */
    val HISTORY =
        listOf(
            "1.0.0" to "format 1: messages, signals (CliOS syntax), link (ack, send), variants, wheel_keys (v17-v162)",
            "2.0.0" to "format 2: roles (COVESA VSS 4.2), mux / cases, when, invalid, labels per language, start_bit, examples (v163)",
            "2.1.0" to "formulas (expr, when as text), extends, per-car patch (v164)",
            "2.2.0" to "framing described in the profile (v165)",
            "2.3.0" to "messages built from the head unit's state, on change, commands (v166)",
            "2.4.0" to "several key sources, knobs (v167)",
            "2.5.0" to "engine requirement of a profile (\"engine\"), CAN box and OBD together (v169)",
            "2.6.0" to "box types as data: the generic profile of each type (\"box_type\" + \"framing\"), built in or from the boxes pack; no car profile built in (v184)",
            "2.7.0" to "framings without CMD, LEN, head or checksum, with a tail, and the head unit's own (\"out\"): CYT's BYD and Ford boxes, Simple's Audi box (v190)",
            "2.8.0" to "keys: event_value (key frames by the value of their event byte) and pressed_for (other press values per code), for the KSW MCU's A1 17 keys (v194)",
        )

    data class Version(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

        override fun toString() = "$major.$minor.$patch"
    }

    val CURRENT: Version = parse(VERSION)!!

    /** "2", "2.1", "2.1.3" (pre-release and build parts are not used here). */
    fun parse(text: String): Version? {
        val m = Regex("(\\d{1,3})(?:\\.(\\d{1,3}))?(?:\\.(\\d{1,4}))?").matchEntire(text.trim()) ?: return null
        return Version(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
    }

    /**
     * True when the engine [v] meets [requirement]: `>=2.1` (that version or newer, any major), `^2.1` or `2.1` (that
     * version or newer within major 2, the usual meaning in package managers).
     */
    fun satisfies(
        requirement: String,
        v: Version = CURRENT,
    ): Boolean {
        val r = requirement.trim()
        return when {
            r.startsWith(">=") -> parse(r.substring(2))?.let { v >= it } ?: false
            r.startsWith("^") -> parse(r.substring(1))?.let { v >= it && v.major == it.major } ?: false
            else -> parse(r)?.let { v >= it && v.major == it.major } ?: false
        }
    }

    /** True when [requirement] is written in a form [satisfies] reads. */
    fun isRequirement(requirement: String): Boolean = Regex("(>=|\\^)?\\s*\\d{1,3}(\\.\\d{1,3}){0,2}").matches(requirement.trim())

    /** The smallest engine [v] needs, from what it uses. */
    fun requiredBy(v: CanVehicle): String {
        val signals = v.messages.values.flatMap { m -> m.signals + m.cases.values.flatMap { it.signals } }
        val needs =
            listOfNotNull(
                "2.0".takeIf {
                    v.examples.isNotEmpty() || v.messages.values.any { it.mux != null } ||
                        signals.any { it.role != null || it.bitRoles.isNotEmpty() || it.condition != null || it.invalid.isNotEmpty() || it.labels.isNotEmpty() }
                },
                "2.1".takeIf { v.variants.any { it.patch != null } || signals.any { it.expr != null || it.whenExpr != null } },
                "2.2".takeIf { v.framing != null },
                "2.3".takeIf { v.link.send.any { it.template != null } || v.link.commands.isNotEmpty() },
                "2.4".takeIf { v.moreKeys.isNotEmpty() },
                "2.6".takeIf { v.boxType != null },
                "2.7".takeIf { v.framing?.needs27 == true },
                "2.8".takeIf { (listOfNotNull(v.wheelKeys) + v.moreKeys).any { it.needs28 } },
            )
        return ">=" + (needs.maxByOrNull { parse(it)!! } ?: "1.0")
    }
}
