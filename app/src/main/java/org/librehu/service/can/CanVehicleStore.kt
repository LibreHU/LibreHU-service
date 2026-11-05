package org.librehu.service.can

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.can.CanVehicle
import org.librehu.core.can.CanVehicleException
import org.librehu.core.can.CanVehicles
import org.librehu.core.can.TwrpCanConf
import java.io.File

/**
 * Car profiles for the CAN box decoding: the built-in ones plus those imported from JSON files (device-protected
 * storage, like the MCU profiles). The selected one decodes the messages right away ([CanMonitor.vehicle]).
 */
class CanVehicleStore private constructor(
    context: Context,
) {
    private val storage = context.applicationContext.createDeviceProtectedStorageContext()
    private val app = context.applicationContext
    private val dir = File(storage.filesDir, "can-vehicles").apply { mkdirs() }
    private val prefs = storage.getSharedPreferences("can", Context.MODE_PRIVATE)

    private val _vehicles = MutableStateFlow(load())
    val vehicles: StateFlow<List<CanVehicle>> = _vehicles.asStateFlow()

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null) ?: CanVehicles.DEFAULT_ID)
    val selected: StateFlow<String> = _selected.asStateFlow()

    init {
        applySelected()
    }

    /** The selected profile decodes the box now, and TWRP answers the box and reads its keys the same way. */
    private fun applySelected() {
        val v = current()
        CanMonitor.vehicle = v
        Thread({ writeTwrpConf(v) }, "twrp-can-conf").start()
    }

    /**
     * `/sdcard/TWRP/ujc201_can.conf` for LibreHU's TWRP ([TwrpCanConf]), written when it changes: directly, else as
     * root (the storage permission may not be granted).
     */
    private fun writeTwrpConf(v: CanVehicle) {
        val text = TwrpCanConf.of(v)
        val f = File(android.os.Environment.getExternalStorageDirectory(), "TWRP/ujc201_can.conf")
        if (runCatching { f.readText() }.getOrNull() == text) return
        val ok =
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(text)
            }.isSuccess ||
                (
                    org.librehu.service.root.RootShell.isAvailable() &&
                        org.librehu.service.root.RootShell.run("mkdir -p /data/media/0/TWRP") != null &&
                        org.librehu.service.root.RootShell.write("/data/media/0/TWRP/ujc201_can.conf", text)
                )
        android.util.Log.i("LibreHU-CAN", if (ok) "TWRP CAN dialogue written for ${v.id}" else "TWRP CAN dialogue not written (storage)")
    }

    fun isBuiltIn(id: String) = CanVehicles.BUILT_IN.any { it.id == id }

    /** The selected profile as stored (all its cars). */
    fun profile(): CanVehicle = _vehicles.value.firstOrNull { it.id == _selected.value } ?: CanVehicles.RENAULT_CLIO3_HIWORLD

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
        prefs.edit().putString(KEY_SELECTED, id).apply()
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
        // "extends": a built-in profile or one imported before (stored with its parent already merged in).
        val v = CanVehicles.fromJson(text) { id -> CanVehicles.builtInText(id) ?: File(dir, "$id.json").takeIf { it.isFile }?.readText() }
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
        File(dir, "$id.json").delete()
        _vehicles.value = load()
        if (_selected.value == id) select(CanVehicles.DEFAULT_ID)
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
        return CanVehicles.BUILT_IN + imported.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val KEY_SELECTED = "selected_vehicle"
        private const val KEY_VARIANT = "variant."
        private const val MAX_SIZE = 256 * 1024

        @Volatile
        private var instance: CanVehicleStore? = null

        fun get(context: Context): CanVehicleStore =
            instance ?: synchronized(this) { instance ?: CanVehicleStore(context).also { instance = it } }
    }
}
