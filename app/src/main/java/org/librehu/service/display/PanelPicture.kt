package org.librehu.service.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.atc.AtcHelper
import org.librehu.service.atc.AtcTools

/**
 * Picture of the panel through MediaTek's picture quality engine, what Jancar's `com.jancar.tftdisplay` sets on the
 * AC8257 (its `Platform8257TFTDisplay` calls the ROM's libPQjni.so; MediaTek's MiraVision uses the same engine):
 * brightness, contrast, hue, saturation, sharpness, and hue / saturation of the skin, grass and sky tones, each an index
 * of 0..range - 1 given by the engine. Root: through [AtcTools.pq].
 *
 * The indexes chosen here are kept and set again at start-up and after a real standby (Jancar's app kept them in
 * /jancar/config/displayparams.json for its own services). The ones read the first time are kept as the original ones.
 * Jancar's app also sends the panel's AVDD and VCOM to the MCU (`0F 02` / `0F 03`): on this firmware those are the two
 * PWM outputs that [org.librehu.service.car.FrontLed] drives as the white button backlight, so they are not set here.
 */
object PanelPicture {
    private const val PREF = "panel_picture"
    private const val PREF_ORIGINAL = "panel_picture_original"
    private const val RESTORE_DELAY_MS = 15_000L
    private const val MIRAVISION = "com.mediatek.miravision.ui"

    data class Setting(
        val index: Int,
        val range: Int,
    )

    sealed class State {
        data object NoRoot : State()

        data class Error(
            val message: String,
        ) : State()

        data class Ready(
            val settings: Map<String, Setting>,
        ) : State()
    }

    /** Current indexes and ranges (slow: root helper, off the main thread); the first ones are kept as the original. */
    @Synchronized
    fun read(context: Context): State {
        val state = parse(AtcTools.pq(context))
        val prefs = LibreHuService.prefs(context)
        if (state is State.Ready && prefs.getString(PREF_ORIGINAL, null) == null && saved(context).isEmpty()) {
            prefs.edit().putString(PREF_ORIGINAL, toJson(state.settings.mapValues { it.value.index })).apply()
        }
        return state
    }

    /** Sets [key] to [index], kept for the next start-ups. */
    @Synchronized
    fun set(
        context: Context,
        key: String,
        index: Int,
    ): State {
        val state = parse(AtcTools.pq(context, mapOf(key to index)))
        if (state is State.Ready) save(context, PREF, saved(context) + (key to index))
        return state
    }

    /** Back to the indexes read the first time; nothing is set again at start-up any more. */
    @Synchronized
    fun restoreOriginal(context: Context): State {
        val original = load(context, PREF_ORIGINAL)
        save(context, PREF, emptyMap())
        return parse(AtcTools.pq(context, original))
    }

    /** At start-up and after a real standby: the indexes chosen here, if any, [RESTORE_DELAY_MS] later. */
    fun restoreLater(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        if (saved(app).isEmpty()) return
        Thread({
            Thread.sleep(RESTORE_DELAY_MS)
            val values = saved(app)
            if (values.isNotEmpty()) {
                val r = synchronized(this) { AtcTools.pq(app, values) }
                log(
                    when {
                        r == null -> "Panel picture: no root"
                        r["error"] != null -> "Panel picture: ${r["error"]}"
                        else -> "Panel picture set again: $values"
                    },
                )
            }
        }, "panel-picture").start()
    }

    fun miraVisionInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(MIRAVISION, 0) }.isSuccess

    /** MediaTek's own picture settings (the same engine), when the ROM has them. */
    fun openMiraVision(context: Context): Boolean {
        context.packageManager.getLaunchIntentForPackage(MIRAVISION)?.let { intent ->
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return AtcTools.launch(context, ComponentName(MIRAVISION, "$MIRAVISION.MiraVisionActivity"))
    }

    private fun saved(context: Context): Map<String, Int> = load(context, PREF)

    private fun parse(r: Map<String, String>?): State {
        if (r == null) return State.NoRoot
        r["error"]?.let { return State.Error(it) }
        val settings =
            AtcHelper.PQ_KEYS
                .mapNotNull { key ->
                    val parts = r[key]?.split('/') ?: return@mapNotNull null
                    val index = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val range = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    key to Setting(index, range)
                }.toMap()
        return if (settings.isEmpty()) State.Error("no answer") else State.Ready(settings)
    }

    private fun load(
        context: Context,
        pref: String,
    ): Map<String, Int> =
        runCatching {
            val o = JSONObject(LibreHuService.prefs(context).getString(pref, null) ?: return emptyMap())
            o.keys().asSequence().associateWith { o.getInt(it) }
        }.getOrDefault(emptyMap())

    private fun save(
        context: Context,
        pref: String,
        values: Map<String, Int>,
    ) {
        LibreHuService
            .prefs(context)
            .edit()
            .putString(pref, toJson(values))
            .apply()
    }

    private fun toJson(values: Map<String, Int>): String = JSONObject().apply { values.forEach { (k, v) -> put(k, v) } }.toString()
}
