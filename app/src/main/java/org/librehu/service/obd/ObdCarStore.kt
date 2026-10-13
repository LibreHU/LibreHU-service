package org.librehu.service.obd

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.obd.ObdCar
import org.librehu.core.obd.ObdCarException
import org.librehu.core.obd.ObdCars
import org.librehu.core.obd.ObdPid
import java.io.File

/**
 * Cars for the ELM327 adapter (manufacturer values read from their ECUs): imported from ddt4all ECU files or LibreHU
 * car files, kept in device-protected storage. The selected car and its chosen values are read by [ObdManager].
 * Nothing is built in: the ECU descriptions come from the user's own files.
 */
class ObdCarStore private constructor(
    context: Context,
) {
    private val storage = context.applicationContext.createDeviceProtectedStorageContext()
    private val app = context.applicationContext
    private val dir = File(storage.filesDir, "obd-cars").apply { mkdirs() }
    private val prefs = storage.getSharedPreferences("obd-cars", Context.MODE_PRIVATE)

    private val _cars = MutableStateFlow(load())
    val cars: StateFlow<List<ObdCar>> = _cars.asStateFlow()

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, "") ?: "")
    val selected: StateFlow<String> = _selected.asStateFlow()

    /** Values chosen for the selected car ([ObdCars.key]). */
    private val _chosen = MutableStateFlow(chosenOf(_selected.value))
    val chosen: StateFlow<Set<String>> = _chosen.asStateFlow()

    fun current(): ObdCar? = _cars.value.firstOrNull { it.id == _selected.value }

    fun select(id: String) {
        val oldInit = current()?.elmInit.orEmpty()
        prefs.edit().putString(KEY_SELECTED, id).apply()
        _selected.value = id
        _chosen.value = chosenOf(id)
        val car = current()
        val obd = ObdManager.get(app)
        val pids = car?.pids?.mapNotNull { ObdPid.byName(it) }.orEmpty()
        if (pids.isNotEmpty()) obd.update { it.copy(pids = pids.toSet()) }
        // Its adapter commands are sent when the session starts; its values are read from the next round.
        if (obd.settings.value.enabled && car?.elmInit.orEmpty() != oldInit) obd.restart()
    }

    fun toggle(key: String) {
        val id = _selected.value.ifEmpty { return }
        val s = if (key in _chosen.value) _chosen.value - key else _chosen.value + key
        prefs.edit().putString(KEY_ITEMS + id, s.joinToString("\n")).apply()
        _chosen.value = s
    }

    /**
     * Reads a car file: a new car (a LibreHU car file replaces the one with the same id), or, when [intoSelected],
     * its ECUs added to the selected car (a ddt4all ECU file = one ECU). Returns the car, or throws [ObdCarException].
     */
    fun import(
        uri: Uri,
        intoSelected: Boolean,
    ): ObdCar {
        val name = displayName(uri).substringBeforeLast('.')
        val text =
            app.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_SIZE) throw ObdCarException("File too big")
                bytes.toString(Charsets.UTF_8)
            } ?: throw ObdCarException("Cannot read the file")
        val read = ObdCars.fromJson(text, name)
        val target = current()
        val car =
            if (intoSelected && target != null) {
                read.ecus.fold(target) { c, e -> ObdCars.withEcu(c, e) }
            } else {
                read
            }
        File(dir, "${car.id}.json").writeText(ObdCars.toJson(car))
        _cars.value = load()
        if (_selected.value.isEmpty() || car.id == _selected.value) select(car.id)
        return car
    }

    fun export(
        id: String,
        uri: Uri,
    ) {
        val car = _cars.value.firstOrNull { it.id == id } ?: throw ObdCarException("Unknown car $id")
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ObdCars.toJson(car).toByteArray()) }
            ?: throw ObdCarException("Cannot write the file")
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        prefs.edit().remove(KEY_ITEMS + id).apply()
        _cars.value = load()
        if (_selected.value == id) select("")
    }

    private fun chosenOf(id: String): Set<String> =
        if (id.isEmpty()) {
            emptySet()
        } else {
            prefs
                .getString(KEY_ITEMS + id, null)
                ?.split('\n')
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
        }

    private fun displayName(uri: Uri): String =
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment.orEmpty()

    private fun load(): List<ObdCar> =
        dir
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .mapNotNull { f ->
                try {
                    ObdCars.fromJson(f.readText())
                } catch (_: ObdCarException) {
                    null
                }
            }.sortedBy { it.name.lowercase() }

    companion object {
        private const val KEY_SELECTED = "selected"
        private const val KEY_ITEMS = "items_"

        /** ddt4all ECU files of big ECUs reach a few megabytes. */
        private const val MAX_SIZE = 16 * 1024 * 1024

        @Volatile
        private var instance: ObdCarStore? = null

        fun get(context: Context): ObdCarStore = instance ?: synchronized(this) { instance ?: ObdCarStore(context).also { instance = it } }
    }
}
