package org.librehu.service.can

import org.json.JSONObject
import org.librehu.core.can.CanEngine
import org.librehu.core.can.CanPacks
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The packs of car profiles by make, and the box types, of LibreHU-car-profiles (`dist/`), downloaded by the service
 * itself (CAN tab), as LibreHU Apps' « Cars » tab does: the index gives each pack's revision, the engine it needs and
 * the SHA-256 of its file; a pack is downloaded, checked (size, SHA-256) and installed ([CanVehicleStore.installPack],
 * which reads it again all or nothing). The box types (pack [BOXES]) go first: a make's profiles may name one of them.
 */
object CarPackIndex {
    const val INDEX_URL = "https://raw.githubusercontent.com/LibreHU/LibreHU-car-profiles/main/dist/index.json"
    private const val BASE_URL = "https://raw.githubusercontent.com/LibreHU/LibreHU-car-profiles/main/dist/"

    /** The pack of the box types (generic profiles, `boxes/` of the repository). */
    const val BOXES = "boxes"

    data class Pack(
        val id: String,
        val name: String,
        val revision: Int,
        val engine: String,
        val makes: List<String>,
        val profiles: List<String>,
        val cars: Int,
        val file: String,
        val size: Int,
        val sha256: String,
    ) {
        /** This service's engine reads it. */
        val readable: Boolean get() = CanEngine.satisfies(engine)
    }

    fun parse(text: String): List<Pack> {
        val root = JSONObject(text)
        if (root.optString("format") != "librehu-car-packs") throw IllegalStateException("not a car pack index")
        val arr = root.getJSONArray("packs")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Pack(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                revision = o.optInt("revision"),
                engine = o.optString("engine", ">=1.0"),
                makes = list("makes"),
                profiles = list("profiles"),
                cars = o.optInt("cars"),
                file = o.getString("file"),
                size = o.optInt("size"),
                sha256 = o.getString("sha256"),
            )
        }
    }

    /** The index (network: call off the main thread). */
    fun fetch(): List<Pack> = parse(get(INDEX_URL).toString(Charsets.UTF_8))

    /** The pack that has the profile [profileId], or null. */
    fun packFor(
        index: List<Pack>,
        profileId: String,
    ): Pack? = index.firstOrNull { profileId in it.profiles }

    /** True when the installed [store] has [p] exactly as published (same SHA-256). */
    fun upToDate(
        store: CanVehicleStore,
        p: Pack,
    ): Boolean = store.packs.value[p.id]?.sha256?.equals(p.sha256, ignoreCase = true) == true

    /**
     * Downloads [p], checks it and installs it; the box types first when the index has a newer pack of them. Returns
     * null when done, else why (network). Off the main thread.
     */
    fun install(
        store: CanVehicleStore,
        p: Pack,
        index: List<Pack>,
    ): String? {
        if (!p.readable) return "needs the car data engine ${p.engine}, this service has ${CanEngine.VERSION}: update LibreHU service"
        if (p.id != BOXES) {
            index.firstOrNull { it.id == BOXES && it.readable && !upToDate(store, it) }?.let { b -> installOne(store, b)?.let { return it } }
        }
        return installOne(store, p)
    }

    private fun installOne(
        store: CanVehicleStore,
        p: Pack,
    ): String? =
        try {
            val bytes = get(BASE_URL + p.file)
            if (bytes.size > CanPacks.MAX_BYTES) throw IllegalStateException("pack too large (${bytes.size / 1024} kB)")
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!sha.equals(p.sha256, ignoreCase = true)) throw IllegalStateException("download damaged (SHA-256 differs)")
            store.installPack(bytes, p.sha256)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "LibreHU-service")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > CanPacks.MAX_BYTES) throw IllegalStateException("file too large")
                }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}
