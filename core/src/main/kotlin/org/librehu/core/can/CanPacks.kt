package org.librehu.core.can

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * A pack of car profiles for one make (`"format": "librehu-car-pack"`), built by the LibreHU-car-profiles repository
 * (tools/build_packs.py) and installed by LibreHU Apps: [revision] only moves when that make's profiles change, so
 * fixing a Renault updates the Renault pack and nothing else. [engine] is the highest engine its profiles need.
 */
data class CanPack(
    val id: String,
    val name: String,
    val revision: Int,
    val engine: String,
    val profiles: List<CanVehicle>,
    /** SHA-256 of the pack file (hex), the one of `dist/index.json` and `dist/SHA256SUMS`. */
    val sha256: String = "",
)

object CanPacks {
    const val FORMAT = "librehu-car-pack"
    const val VERSION = 1

    /** Largest pack accepted (the Volkswagen group's is ~60 kB). */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * Reads a pack and every profile in it; all or nothing (one profile this engine cannot read refuses the pack, so
     * a make is never half updated). [sha256]: the digest the index gives for the file, checked when not null.
     */
    fun read(
        bytes: ByteArray,
        sha256: String? = null,
    ): CanPack {
        if (bytes.size > MAX_BYTES) throw CanVehicleException("pack larger than ${MAX_BYTES / 1024} kB")
        val got = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha256 != null && !got.equals(sha256.trim(), ignoreCase = true)) throw CanVehicleException("pack damaged or not the published one (SHA-256 differs)")
        val root =
            try {
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            } catch (e: Exception) {
                throw CanVehicleException("pack: not JSON (${e.message})")
            }
        fun str(k: String) = (root[k] as? JsonPrimitive)?.contentOrNull
        if (str("format") != FORMAT) throw CanVehicleException("not a LibreHU car pack")
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > VERSION) throw CanVehicleException("pack format $version is newer than this service: update LibreHU service")
        val id = str("id")?.takeIf { Regex("[a-z0-9][a-z0-9-]{0,31}").matches(it) } ?: throw CanVehicleException("pack: bad id")
        val engine = str("engine") ?: ">=1.0"
        if (!CanEngine.satisfies(engine)) throw CanVehicleException("pack $id needs the car data engine $engine; this service has ${CanEngine.VERSION}: update LibreHU service")
        val list = root["profiles"] as? JsonArray ?: throw CanVehicleException("pack $id: no profiles")
        val texts = list.mapIndexed { i, e -> (e as? JsonObject)?.toString() ?: throw CanVehicleException("pack $id: profiles[$i] is not an object") }
        // "extends" between profiles of the pack, else built-in ones.
        val byId = texts.associateBy { t -> Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1).orEmpty() }
        val profiles =
            texts.map { t ->
                try {
                    CanVehicles.fromJson(t) { pid -> byId[pid] ?: CanVehicles.builtInText(pid) }
                } catch (e: CanVehicleException) {
                    throw CanVehicleException("pack $id: ${e.message}")
                }
            }
        val dup = profiles.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) throw CanVehicleException("pack $id: profile ${dup.first()} twice")
        for (p in profiles) {
            val failures = p.selfTest()
            if (failures.isNotEmpty()) throw CanVehicleException("pack $id: ${p.id} fails its examples: ${failures.first()}")
        }
        return CanPack(id, str("name") ?: id, (root["revision"] as? JsonPrimitive)?.intOrNull ?: 0, engine, profiles, got)
    }
}
