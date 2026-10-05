package org.librehu.service.mcu

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.mcu.McuProfile
import org.librehu.core.mcu.McuProfiles
import org.librehu.core.mcu.ProfileException
import java.io.File

/**
 * MCU protocol profiles: the built-in ones plus those imported from JSON files (stored in the app's device-protected
 * storage, so that they are readable at boot before the user unlocks). The selected profile is used by the service
 * at the next start of the MCU link.
 */
class ProfileStore private constructor(
    context: Context,
) {
    private val storage = context.applicationContext.createDeviceProtectedStorageContext()
    private val app = context.applicationContext
    private val dir = File(storage.filesDir, "mcu-profiles").apply { mkdirs() }
    private val prefs = storage.getSharedPreferences("mcu", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<McuProfile>> = _profiles.asStateFlow()

    private val _selected = MutableStateFlow(prefs.getString(KEY_SELECTED, null) ?: McuProfiles.JANCAR_JAC_V1.id)
    val selected: StateFlow<String> = _selected.asStateFlow()

    fun isBuiltIn(id: String) = McuProfiles.BUILT_IN.any { it.id == id }

    /** Selected profile, falling back to Jancar when its file disappeared. */
    fun current(): McuProfile = _profiles.value.firstOrNull { it.id == _selected.value } ?: McuProfiles.JANCAR_JAC_V1

    fun select(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
        _selected.value = id
    }

    /** Validates and stores a profile file; returns it, or throws [ProfileException]. Same id = replaced. */
    fun import(uri: Uri): McuProfile {
        val text =
            app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw ProfileException("Cannot read the file")
        if (text.length > MAX_SIZE) throw ProfileException("File too big")
        val p = McuProfiles.fromJson(text)
        if (isBuiltIn(p.id)) throw ProfileException("\"${p.id}\" is a built-in profile: change the id")
        File(dir, "${p.id}.json").writeText(McuProfiles.toJson(p))
        _profiles.value = load()
        return p
    }

    fun export(
        id: String,
        uri: Uri,
    ) {
        val p = _profiles.value.firstOrNull { it.id == id } ?: throw ProfileException("Unknown profile $id")
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(McuProfiles.toJson(p).toByteArray()) }
            ?: throw ProfileException("Cannot write the file")
    }

    fun delete(id: String) {
        if (isBuiltIn(id)) return
        File(dir, "$id.json").delete()
        if (_selected.value == id) select(McuProfiles.JANCAR_JAC_V1.id)
        _profiles.value = load()
    }

    private fun load(): List<McuProfile> {
        val imported =
            dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
                try {
                    McuProfiles.fromJson(f.readText())
                } catch (_: ProfileException) {
                    null
                }
            }
        return McuProfiles.BUILT_IN + imported.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val KEY_SELECTED = "selected_profile"
        private const val MAX_SIZE = 256 * 1024

        @Volatile
        private var instance: ProfileStore? = null

        fun get(context: Context): ProfileStore =
            instance ?: synchronized(this) { instance ?: ProfileStore(context).also { instance = it } }
    }
}
