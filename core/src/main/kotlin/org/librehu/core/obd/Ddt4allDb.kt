package org.librehu.core.obd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** Identification of an ECU as ddt4all compares it (`ecu_ident.py`): diagnostic version, supplier, soft, version. */
data class DdtIdent(
    val diag: String,
    val supplier: String,
    val soft: String,
    val version: String,
)

/** One ECU file of the database: its address on the car (`address`, e.g. `7A`), group (`Injection`…), projects. */
data class DdtEcuFile(
    val file: String,
    val name: String,
    val group: String,
    val address: String,
    val protocol: String,
    val projects: List<String>,
    val idents: List<DdtIdent>,
) {
    val can: Boolean get() = protocol.contains("CAN", ignoreCase = true)
}

/** A vehicle project of the database (`X85`…) and its CAN ECU files, by address. */
data class DdtProject(
    val code: String,
    val name: String,
    val ecus: Map<String, List<DdtEcuFile>>,
)

/**
 * ddt4all's ECU database as its `ecu.zip` holds it (github.com/cedricp/ddt4all, `core/parameters/helpers.py`
 * zipConvertXML): one JSON file per ECU and `db.json`, the index (file → address, group, protocol, projects, ECU
 * name, auto-identifications). The ECU files themselves are read with [ObdCars.ddt4allEcu]. Identification follows
 * `ecu_scanner.py`: the `2180` answer (older Renault ECUs) or the UDS identifiers F1A0 / F18A / F194 / F195, both
 * read requests only (no diagnostic session is opened here, so an ECU that only answers in a session is picked by
 * hand).
 */
object Ddt4allDb {
    const val INDEX = "db.json"

    /** Read requests of the UDS identification (diagnostic version, supplier, soft, soft version). */
    val UDS_IDENT = listOf("22F1A0", "22F18A", "22F194", "22F195")
    const val KWP_IDENT = "2180"

    fun parse(dbJson: String): List<DdtEcuFile> {
        val root =
            try {
                Json.parseToJsonElement(dbJson) as? JsonObject
            } catch (e: Exception) {
                throw ObdCarException("db.json: ${e.message}")
            } ?: throw ObdCarException("db.json is not an object")
        return root.mapNotNull { (file, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            DdtEcuFile(
                file = file,
                name = o.str("ecuname").orEmpty().ifEmpty { file.removeSuffix(".json") },
                group = o.str("group").orEmpty(),
                address = o.str("address").orEmpty().uppercase(Locale.ROOT),
                protocol = o.str("protocol").orEmpty(),
                projects = (o["projects"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase(Locale.ROOT) },
                idents =
                    (o["autoidents"] as? JsonArray).orEmpty().mapNotNull { a ->
                        val ai = a as? JsonObject ?: return@mapNotNull null
                        DdtIdent(
                            ai.str("diagnostic_version").orEmpty(),
                            ai.str("supplier_code").orEmpty(),
                            ai.str("soft_version").orEmpty(),
                            ai.str("version").orEmpty(),
                        )
                    },
            )
        }
    }

    /** Every project with at least one CAN ECU (the ELM327 reads CAN ECUs only here), sorted by name. */
    fun projects(files: List<DdtEcuFile>): List<DdtProject> {
        val byProject = HashMap<String, MutableList<DdtEcuFile>>()
        for (f in files) {
            if (!f.can || f.address.isEmpty()) continue
            for (p in f.projects) byProject.getOrPut(p) { ArrayList() }.add(f)
        }
        return byProject
            .map { (code, list) ->
                DdtProject(code, Ddt4allProjects.name(code), list.groupBy { it.address }.toSortedMap())
            }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    /**
     * `61 80 …` answer (whole answer, service byte first) → identification, as `check_ecu` reads it: the long answer
     * (20 bytes and more) and the short one (8 to 19 bytes) keep the fields at different places.
     */
    fun ident2180(answer: IntArray): DdtIdent? {
        if (answer.size < 8 || answer[0] != 0x61 || answer[1] != 0x80) return null
        return if (answer.size >= 20) {
            DdtIdent(answer[7].toString(), text(answer, 8, 3), hex(answer, 16, 2), hex(answer, 18, 2))
        } else {
            if (answer.size < 12) return null
            DdtIdent(answer[2].toString(), text(answer, 3, 3), hex(answer, 6, 3), hex(answer, 9, 3))
        }
    }

    private fun text(
        b: IntArray,
        from: Int,
        count: Int,
    ) = (from until from + count).map { b[it].toChar() }.joinToString("")

    /** Answers to [UDS_IDENT] (whole answers, `62 F1 xx …`) → identification, as `identify_new` reads them. */
    fun identUds(
        diag: IntArray?,
        supplier: IntArray?,
        soft: IntArray?,
        version: IntArray?,
    ): DdtIdent? {
        if (diag == null || diag.size < 4) return null
        return DdtIdent(
            diag = diag[3].toString(),
            supplier = ascii(supplier, 63),
            soft = ascii(soft, Int.MAX_VALUE),
            version = ascii(version, 16),
        )
    }

    /**
     * The ECU file of [candidates] (same address) for [ident]: an exact match (`checkWith`), else the closest version
     * with the same supplier and soft (`checkApproximate`), else null. Second = exact.
     */
    fun match(
        candidates: List<DdtEcuFile>,
        ident: DdtIdent,
    ): Pair<DdtEcuFile, Boolean>? {
        var best: DdtEcuFile? = null
        var bestDelta = Long.MAX_VALUE
        for (f in candidates) {
            for (t in f.idents) {
                if (t.diag.isEmpty()) continue
                if (exact(t, ident)) return f to true
                if (t.supplier.trim() == ident.supplier.trim() && t.soft.trim() == ident.soft.trim()) {
                    val a = ident.version.toLongOrNull(16) ?: continue
                    val b = t.version.toLongOrNull(16) ?: continue
                    val delta = kotlin.math.abs(a - b)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        best = f
                    }
                }
            }
        }
        return best?.let { it to false }
    }

    private fun exact(
        t: DdtIdent,
        id: DdtIdent,
    ): Boolean {
        // ddt4all reads both diagnostic versions as hexadecimal.
        if (t.diag.toLongOrNull(16) == null || t.diag.toLongOrNull(16) != id.diag.toLongOrNull(16)) return false
        val supplier = t.supplier.trim()
        val soft = t.soft.trim()
        val version = t.version.trim()
        return supplier == id.supplier.trim().take(supplier.length) &&
            soft == id.soft.trim().take(soft.length) &&
            version == id.version.trim().take(version.length)
    }

    private fun hex(
        b: IntArray,
        from: Int,
        count: Int,
    ) = (from until from + count).joinToString("") { "%02X".format(b[it]) }

    /** Data after `62 F1 xx`, as text, without trailing 00 / FF / spaces. */
    private fun ascii(
        b: IntArray?,
        max: Int,
    ): String {
        if (b == null || b.size <= 3) return ""
        val data = b.drop(3).take(max).dropLastWhile { it == 0x00 || it == 0xFF || it == 0x20 }
        return data.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
}
