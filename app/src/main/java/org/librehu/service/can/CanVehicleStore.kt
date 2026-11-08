package org.librehu.service.can

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.can.CanBoxFamilies
import org.librehu.core.can.CanPack
import org.librehu.core.can.CanPacks
import org.librehu.core.can.CanVehicle
import org.librehu.core.can.CanVehicleException
import org.librehu.core.can.CanVehicles
import org.librehu.core.can.TwrpCanConf
import java.io.File

/**
 * Car profiles for the CAN box decoding: the packs by make of LibreHU-car-profiles, downloaded in the CAN tab
 * ([CarPackIndex]) or by LibreHU Apps ([CarPackReceiver]), the profiles imported from JSON files (device-protected
 * storage, like the MCU profiles), and built in only the generic profile of each box type ([CanVehicles.BUILT_IN]:
 * raw messages, the fallback). The `boxes` pack brings the box types ([CanBoxFamilies.register]) before the makes'
 * packs are read. The selected one decodes the messages right away ([CanMonitor.vehicle]); a selected profile that
 * is not here (its make's pack not installed yet, the cars built in up to versionCode 183) is replaced by the generic
 * profile of its box type until its pack is downloaded ([missing], [fetchMissing]).
 */
class CanVehicleStore private constructor(
    context: Context,
) {
    private val storage = context.applicationContext.createDeviceProtectedStorageContext()
    private val app = context.applicationContext
    private val dir = File(storage.filesDir, "can-vehicles").apply { mkdirs() }

    /** Packs by make (`<id>.json`, as published), see [installPack]. */
    private val packDir = File(storage.filesDir, "can-packs").apply { mkdirs() }

    private val _packs = MutableStateFlow(loadPacks())

    /** Installed packs by id (makes, and `boxes`). */
    val packs: StateFlow<Map<String, CanPack>> = _packs.asStateFlow()
    private val prefs = storage.getSharedPreferences("can", Context.MODE_PRIVATE)

    private val _vehicles = MutableStateFlow(load())
    val vehicles: StateFlow<List<CanVehicle>> = _vehicles.asStateFlow()

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null) ?: CanVehicles.DEFAULT_ID)
    val selected: StateFlow<String> = _selected.asStateFlow()

    private val _missing = MutableStateFlow<String?>(null)

    /** The selected profile when it is not here (its pack not installed): the generic profile of its box type decodes meanwhile. */
    val missing: StateFlow<String?> = _missing.asStateFlow()

    /** What downloading the missing profile's pack gives ([fetchMissing]). */
    sealed interface Fetch {
        data object Running : Fetch

        data class Failed(
            val why: String,
        ) : Fetch
    }

    private val _fetch = MutableStateFlow<Fetch?>(null)
    val fetch: StateFlow<Fetch?> = _fetch.asStateFlow()

    @Volatile
    private var fetching = false

    /** What LibreHU's TWRP reads (`/sdcard/TWRP/ujc201_can.conf`, [TwrpCanConf]), shown in the CAN tab. */
    data class TwrpSync(
        val profile: String,
        val box: String,
        /** TWRP can read the box's framing. */
        val readable: Boolean,
        /** Keys of the profile with a TWRP action. */
        val keys: Int,
        val written: Boolean,
    )

    private val _twrp = MutableStateFlow<TwrpSync?>(null)
    val twrp: StateFlow<TwrpSync?> = _twrp.asStateFlow()

    init {
        applySelected()
        // A head unit coming from a service with built-in cars: the chosen one's pack is downloaded at once.
        if (_missing.value != null) fetchMissing(retry = true)
    }

    /** The selected profile decodes the box now, and TWRP answers the box and reads its keys the same way. */
    private fun applySelected() {
        val v = current()
        _missing.value = _selected.value.takeIf { id -> _vehicles.value.none { it.id == id } }
        CanMonitor.vehicle = v
        Thread({ writeTwrpConf(v) }, "twrp-can-conf").start()
    }

    /**
     * `/sdcard/TWRP/ujc201_can.conf` for LibreHU's TWRP ([TwrpCanConf]), written when it changes: directly, else as
     * root (the storage permission may not be granted).
     */
    private fun writeTwrpConf(
        v: CanVehicle,
        force: Boolean = false,
    ) {
        val text = TwrpCanConf.of(v)
        val f = File(android.os.Environment.getExternalStorageDirectory(), "TWRP/ujc201_can.conf")
        val same = !force && runCatching { f.readText() }.getOrNull() == text
        val ok =
            same ||
                runCatching {
                f.parentFile?.mkdirs()
                f.writeText(text)
            }.isSuccess ||
                (
                    org.librehu.service.root.RootShell.isAvailable() &&
                        org.librehu.service.root.RootShell.run("mkdir -p /data/media/0/TWRP") != null &&
                        org.librehu.service.root.RootShell.write("/data/media/0/TWRP/ujc201_can.conf", text)
                )
        _twrp.value = TwrpSync(v.name, v.family.name, TwrpCanConf.framing(v) != null, text.lineSequence().count { it.startsWith("key ") }, ok)
        if (!same) android.util.Log.i("LibreHU-CAN", if (ok) "TWRP CAN dialogue written for ${v.id}" else "TWRP CAN dialogue not written (storage)")
    }

    /** Writes TWRP's file again now (CAN tab). */
    fun writeTwrpNow() {
        val v = current()
        Thread({ writeTwrpConf(v, force = true) }, "twrp-can-conf").start()
    }

    /** Built in (a box type's generic profile), or from an installed pack (not removable one by one, not replaceable by an import). */
    fun isBuiltIn(id: String) = CanVehicles.BUILT_IN.any { it.id == id } || _packs.value.values.any { p -> p.profiles.any { it.id == id } }

    /** A box type's generic profile (raw messages): built in, or from the `boxes` pack. */
    fun isGeneric(id: String) = _vehicles.value.firstOrNull { it.id == id }?.boxType != null

    /** The box type of the selected profile, remembered when it was chosen (the cars built in before: known). */
    private fun selectedBox(): String? = prefs.getString(KEY_SELECTED_BOX, null) ?: CanVehicles.FORMER_BUILT_IN_BOX[_selected.value]

    /** The selected profile as stored (all its cars); not here: the generic profile of its box type. */
    fun profile(): CanVehicle = _vehicles.value.firstOrNull { it.id == _selected.value } ?: CanVehicles.genericFor(selectedBox())

    /** The selected profile for the car chosen among its variants (the car type the box needs). */
    fun current(): CanVehicle = profile().let { it.withVariant(prefs.getString(KEY_VARIANT + it.id, null)) }

    private val _variant = MutableStateFlow(prefs.getString(KEY_VARIANT + profile().id, null))

    /** The car chosen in the selected profile ([CanVehicle.variants]); null: the profile's default. */
    val variant: StateFlow<String?> = _variant.asStateFlow()

    fun selectVariant(name: String?) {
        val id = profile().id
        prefs.edit().apply { if (name == null) remove(KEY_VARIANT + id) else putString(KEY_VARIANT + id, name) }.apply()
        _variant.value = name
        applySelected()
    }

    fun select(id: String) {
        val box = _vehicles.value.firstOrNull { it.id == id }?.box
        prefs.edit().putString(KEY_SELECTED, id).apply { if (box != null) putString(KEY_SELECTED_BOX, box) }.apply()
        _selected.value = id
        _variant.value = prefs.getString(KEY_VARIANT + id, null)
        applySelected()
    }

    /** Validates and stores a profile file; returns it, or throws [CanVehicleException]. Same id = replaced. */
    fun import(uri: Uri): CanVehicle {
        val text =
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw CanVehicleException("Cannot read the file")
        if (text.length > MAX_SIZE) throw CanVehicleException("File too big")
        // "extends": a built-in profile, one of an installed pack, or one imported before (stored with its parent merged in).
        val v =
            CanVehicles.fromJson(text) { id ->
                CanVehicles.builtInText(id)
                    ?: _packs.value.values.flatMap { it.profiles }.firstOrNull { it.id == id }?.let { CanVehicles.toJson(it) }
                    ?: File(dir, "$id.json").takeIf { it.isFile }?.readText()
            }
        if (isBuiltIn(v.id)) throw CanVehicleException("\"${v.id}\" is a built-in profile: change the id")
        File(dir, "${v.id}.json").writeText(CanVehicles.toJson(v))
        _vehicles.value = load()
        if (v.id == _selected.value) applySelected()
        return v
    }

    fun export(
        id: String,
        uri: Uri,
    ) {
        val v = _vehicles.value.firstOrNull { it.id == id } ?: throw CanVehicleException("Unknown profile $id")
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(CanVehicles.toJson(v).toByteArray()) }
            ?: throw CanVehicleException("Cannot write the file")
    }

    fun delete(id: String) {
        if (isBuiltIn(id)) return
        val box = _vehicles.value.firstOrNull { it.id == id }?.box
        File(dir, "$id.json").delete()
        _vehicles.value = load()
        if (_selected.value == id) select(CanVehicles.genericFor(box).id)
    }

    /**
     * Installs a pack ([CanPacks.read]: all its profiles readable by this engine, their examples passing, the SHA-256 of
     * the index when given); replaces the previous revision. The `boxes` pack's box types are known at once. Returns
     * it, or throws.
     */
    @Synchronized
    fun installPack(
        bytes: ByteArray,
        sha256: String?,
    ): CanPack {
        val p = CanPacks.read(bytes, sha256)
        val tmp = File(packDir, "${p.id}.json.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(File(packDir, "${p.id}.json"))) {
            tmp.delete()
            throw CanVehicleException("cannot store pack ${p.id}")
        }
        _packs.value = loadPacks()
        _vehicles.value = load()
        applySelected()
        android.util.Log.i("LibreHU-CAN", "car pack ${p.id} revision ${p.revision} installed (${p.profiles.size} profiles, SHA-256 ${p.sha256})")
        return p
    }

    /** Removes an installed pack: its profiles go (a selected one falls back to its box type's generic profile). */
    @Synchronized
    fun removePack(id: String) {
        File(packDir, "$id.json").delete()
        _packs.value = loadPacks()
        _vehicles.value = load()
        applySelected()
    }

    /**
     * Packs this engine can read, the `boxes` pack first (its box types registered: the makes' profiles may name them);
     * one it cannot read (a service downgraded…) is skipped.
     */
    private fun loadPacks(): Map<String, CanPack> {
        val files = (packDir.listFiles { f -> f.extension == "json" } ?: emptyArray()).sortedBy { if (it.nameWithoutExtension == CarPackIndex.BOXES) 0 else 1 }
        val out = LinkedHashMap<String, CanPack>()
        var boxes = false
        for (f in files) {
            try {
                val p = CanPacks.read(f.readBytes())
                out[p.id] = p
                if (p.id == CarPackIndex.BOXES) {
                    CanBoxFamilies.register(p.profiles)
                    boxes = true
                }
            } catch (e: CanVehicleException) {
                android.util.Log.w("LibreHU-CAN", "car pack ${f.name} skipped: ${e.message}")
            }
        }
        if (!boxes) CanBoxFamilies.register(emptyList())
        return out
    }

    private fun load(): List<CanVehicle> {
        val imported =
            dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
                try {
                    CanVehicles.fromJson(f.readText())
                } catch (_: CanVehicleException) {
                    null
                }
            }
        // The cars (packs, then imported), then the generic profile of each box type (the boxes pack's replace the built-in ones).
        val fromPacks = _packs.value.values.flatMap { it.profiles }.associateBy { it.id }
        val generic = CanVehicles.BUILT_IN.map { fromPacks[it.id] ?: it } + fromPacks.values.filter { p -> p.boxType != null && CanVehicles.BUILT_IN.none { it.id == p.id } }
        val cars = fromPacks.values.filter { it.boxType == null }.sortedBy { it.name.lowercase() }
        return cars + imported.filter { i -> fromPacks[i.id] == null && generic.none { it.id == i.id } }.sortedBy { it.name.lowercase() } + generic
    }

    /**
     * Downloads the pack of the selected profile when it is missing ([missing]); with [retry] (service start, network
     * maybe not up yet), tries again after 30 s, 2 min and 10 min.
     */
    fun fetchMissing(retry: Boolean = false) {
        if (fetching) return
        fetching = true
        Thread({
            try {
                val delays = if (retry) listOf(0L, 30_000L, 120_000L, 600_000L) else listOf(0L)
                for (d in delays) {
                    if (d > 0) Thread.sleep(d)
                    val id = _missing.value ?: break
                    _fetch.value = Fetch.Running
                    val why =
                        try {
                            val index = CarPackIndex.fetch()
                            val p = CarPackIndex.packFor(index, id) ?: throw IllegalStateException("no published pack has \"$id\"")
                            CarPackIndex.install(this, p, index)
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                    if (why == null) {
                        _fetch.value = null
                        android.util.Log.i("LibreHU-CAN", "pack of the selected profile $id installed")
                        break
                    }
                    _fetch.value = Fetch.Failed(why)
                    android.util.Log.w("LibreHU-CAN", "pack of the selected profile $id not installed: $why")
                }
            } finally {
                fetching = false
            }
        }, "can-pack-fetch").start()
    }

    companion object {
        private const val KEY_SELECTED = "selected_vehicle"
        private const val KEY_SELECTED_BOX = "selected_box"
        private const val KEY_VARIANT = "variant."
        private const val MAX_SIZE = 256 * 1024

        @Volatile
        private var instance: CanVehicleStore? = null

        fun get(context: Context): CanVehicleStore =
            instance ?: synchronized(this) { instance ?: CanVehicleStore(context).also { instance = it } }
    }
}
