package org.librehu.service

import android.content.Context
import org.librehu.core.unit.HeadUnitSettings
import org.librehu.core.unit.SettingsStore

/** Head unit settings in a private SharedPreferences file (device protected storage: readable before unlock). */
class PrefsSettingsStore(
    context: Context,
) : SettingsStore {
    private val prefs =
        context.createDeviceProtectedStorageContext().getSharedPreferences("headunit", Context.MODE_PRIVATE)

    override fun load(): HeadUnitSettings {
        val d = HeadUnitSettings()
        return HeadUnitSettings(
            volume = prefs.getInt("volume", d.volume),
            muted = prefs.getBoolean("muted", d.muted),
            bass = prefs.getInt("bass", d.bass),
            middle = prefs.getInt("middle", d.middle),
            treble = prefs.getInt("treble", d.treble),
            balance = prefs.getInt("balance", d.balance),
            fade = prefs.getInt("fade", d.fade),
            loudness = prefs.getInt("loudness", d.loudness),
            subwoofer = prefs.getBoolean("subwoofer", d.subwoofer),
            subLevel = prefs.getInt("sub_level", d.subLevel),
            externalAmp = prefs.getBoolean("external_amp", d.externalAmp),
        )
    }

    override fun save(settings: HeadUnitSettings) {
        prefs
            .edit()
            .putInt("volume", settings.volume)
            .putBoolean("muted", settings.muted)
            .putInt("bass", settings.bass)
            .putInt("middle", settings.middle)
            .putInt("treble", settings.treble)
            .putInt("balance", settings.balance)
            .putInt("fade", settings.fade)
            .putInt("loudness", settings.loudness)
            .putBoolean("subwoofer", settings.subwoofer)
            .putInt("sub_level", settings.subLevel)
            .putBoolean("external_amp", settings.externalAmp)
            .apply()
    }
}
