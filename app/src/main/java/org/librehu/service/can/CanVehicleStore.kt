package org.librehu.service.can

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.can.CanVehicle
import org.librehu.core.can.CanVehicleException
import org.librehu.core.can.CanVehicles
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
        CanMonitor.vehicle = current()
    }

    fun isBuiltIn(id: String) = CanVehicles.BUILT_IN.any { it.id == id }

    fun current(): CanVehicle = _vehicles.value.firstOrNull { it.id == _selected.value } ?: CanVehicles.RENAULT_CLIO3_HIWORLD

    fun select(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
        _selected.value = id
        CanMonitor.vehicle = current()
    }

    /** Validates and stores a profile file; returns it, or throws [CanVehicleException]. Same id = replaced. */
    fun import(uri: Uri): CanVehicle {
        val text =
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw CanVehicleException("Cannot read the file")
        if (text.length > MAX_SIZE) throw CanVehicleException("File too big")
        val v = CanVehicles.fromJson(text)
        if (isBuiltIn(v.id)) throw CanVehicleException("\"${v.id}\" is a built-in profile: change the id")
        File(dir, "${v.id}.json").writeText(CanVehicles.toJson(v))
        _vehicles.value = load()
        if (v.id == _selected.value) CanMonitor.vehicle = current()
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
        private const val MAX_SIZE = 256 * 1024

        @Volatile
        private var instance: CanVehicleStore? = null

        fun get(context: Context): CanVehicleStore =
            instance ?: synchronized(this) { instance ?: CanVehicleStore(context).also { instance = it } }
    }
}
