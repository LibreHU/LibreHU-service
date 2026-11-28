package org.librehu.service

import android.content.Context
import org.librehu.core.audio.SubConfig
import org.librehu.core.audio.ToneSetup
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
            // 1 dB gains since API 10; before, steps 0..20 of 2 dB (10 = flat).
            bassDb = prefs.getInt("bass_db", (prefs.getInt("bass", 10) - 10) * 2),
            middleDb = prefs.getInt("middle_db", (prefs.getInt("middle", 10) - 10) * 2),
            trebleDb = prefs.getInt("treble_db", (prefs.getInt("treble", 10) - 10) * 2),
            tone =
                ToneSetup(
                    bassFreq = prefs.getInt("bass_freq", d.tone.bassFreq),
                    bassQ = prefs.getInt("bass_q", d.tone.bassQ),
                    middleFreq = prefs.getInt("middle_freq", d.tone.middleFreq),
                    middleQ = prefs.getInt("middle_q", d.tone.middleQ),
                    trebleFreq = prefs.getInt("treble_freq", d.tone.trebleFreq),
                    trebleQ = prefs.getInt("treble_q", d.tone.trebleQ),
                ),
            loudnessFreq = prefs.getInt("loudness_freq", d.loudnessFreq),
            loudnessHicut = prefs.getInt("loudness_hicut", d.loudnessHicut),
            sub =
                SubConfig(
                    cutoffHz = prefs.getInt("sub_cutoff", d.sub.cutoffHz),
                    phase180 = prefs.getBoolean("sub_phase180", d.sub.phase180),
                    output = prefs.getInt("sub_output", d.sub.output),
                ),
            trims = List(4) { prefs.getInt("trim_$it", 0) },
            maxVolume = prefs.getInt("max_volume", d.maxVolume),
            toneByViper = prefs.getBoolean("tone_by_viper", d.toneByViper),
            balance = prefs.getInt("balance", d.balance),
            fade = prefs.getInt("fade", d.fade),
            loudness = prefs.getInt("loudness", d.loudness),
            subwoofer = prefs.getBoolean("subwoofer", d.subwoofer),
            subLevel = prefs.getInt("sub_level", d.subLevel),
            externalAmp = prefs.getBoolean("external_amp", d.externalAmp),
            androidInputGain = prefs.getInt("android_input_gain", d.androidInputGain),
            bu = org.librehu.core.audio.Bu32107Settings.decode(prefs.getString("bu32107", null)),
        )
    }

    override fun save(settings: HeadUnitSettings) {
        prefs
            .edit()
            .putInt("volume", settings.volume)
            .putBoolean("muted", settings.muted)
            .putInt("bass_db", settings.bassDb)
            .putInt("middle_db", settings.middleDb)
            .putInt("treble_db", settings.trebleDb)
            .putInt("bass_freq", settings.tone.bassFreq)
            .putInt("bass_q", settings.tone.bassQ)
            .putInt("middle_freq", settings.tone.middleFreq)
            .putInt("middle_q", settings.tone.middleQ)
            .putInt("treble_freq", settings.tone.trebleFreq)
            .putInt("treble_q", settings.tone.trebleQ)
            .putInt("loudness_freq", settings.loudnessFreq)
            .putInt("loudness_hicut", settings.loudnessHicut)
            .putInt("sub_cutoff", settings.sub.cutoffHz)
            .putBoolean("sub_phase180", settings.sub.phase180)
            .putInt("sub_output", settings.sub.output)
            .putInt("trim_0", settings.trims.getOrElse(0) { 0 })
            .putInt("trim_1", settings.trims.getOrElse(1) { 0 })
            .putInt("trim_2", settings.trims.getOrElse(2) { 0 })
            .putInt("trim_3", settings.trims.getOrElse(3) { 0 })
            .putInt("max_volume", settings.maxVolume)
            .putBoolean("tone_by_viper", settings.toneByViper)
            .putInt("balance", settings.balance)
            .putInt("fade", settings.fade)
            .putInt("loudness", settings.loudness)
            .putBoolean("subwoofer", settings.subwoofer)
            .putInt("sub_level", settings.subLevel)
            .putBoolean("external_amp", settings.externalAmp)
            .putInt("android_input_gain", settings.androidInputGain)
            .putString("bu32107", settings.bu.encode())
            .apply()
    }
}
