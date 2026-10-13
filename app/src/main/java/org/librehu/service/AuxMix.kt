package org.librehu.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuxMixSettings(
    /** Android's audio (navigation, notifications…) heard while AUX is selected. */
    val enabled: Boolean = true,
    /** Its level relative to the volume (dB). */
    val offsetDb: Int = 0,
)

/** Android over AUX through the mixing input of the BD37534, as ivi-services does; applied by [LibreHuService]. */
object AuxMix {
    private val _settings = MutableStateFlow(AuxMixSettings())
    val settings: StateFlow<AuxMixSettings> = _settings.asStateFlow()
    private var loaded = false

    @Volatile
    var onChange: ((AuxMixSettings) -> Unit)? = null

    fun get(context: Context): StateFlow<AuxMixSettings> {
        load(context)
        return settings
    }

    fun update(
        context: Context,
        transform: (AuxMixSettings) -> AuxMixSettings,
    ) {
        load(context)
        val s = transform(_settings.value)
        _settings.value = s
        LibreHuService
            .prefs(context)
            .edit()
            .putBoolean(KEY_ON, s.enabled)
            .putInt(KEY_OFFSET, s.offsetDb)
            .apply()
        onChange?.invoke(s)
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = LibreHuService.prefs(context)
        val d = AuxMixSettings()
        _settings.value = AuxMixSettings(p.getBoolean(KEY_ON, d.enabled), p.getInt(KEY_OFFSET, d.offsetDb))
    }

    private const val KEY_ON = "aux_mix"
    private const val KEY_OFFSET = "aux_mix_offset"
}
