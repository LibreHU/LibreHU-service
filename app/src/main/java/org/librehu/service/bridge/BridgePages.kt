package org.librehu.service.bridge

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.core.obd.BatteryLimits
import org.librehu.service.car.EngineProfile
import org.librehu.service.car.EngineSound
import org.librehu.service.car.FrontLed
import org.librehu.service.obd.BatteryWatch
import org.librehu.service.obd.ObdManager
import org.librehu.service.overlay.SoundOsd
import org.librehu.service.R
import org.librehu.service.root.RootShell

/**
 * One setting shown by the phone app. The phone draws the pages it is sent ([BridgePages]) without knowing them:
 * new settings need no phone update. Types: `section`, `info` (read only), `switch` (boolean), `slider` (int,
 * [min]..[max] by [step], [unit]), `choice` ([options]), `button` (an action, [confirm] asks first).
 */
class BridgeItem(
    val id: String,
    val type: String,
    val label: String,
    val hint: String = "",
    val min: Int = 0,
    val max: Int = 100,
    val step: Int = 1,
    val unit: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val confirm: Boolean = false,
    val get: () -> Any? = { null },
    val set: (Any?) -> Unit = {},
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", type)
            .put("label", label)
            .apply {
                if (hint.isNotEmpty()) put("hint", hint)
                if (type == "slider") put("min", min).put("max", max).put("step", step)
                if (unit.isNotEmpty()) put("unit", unit)
                if (options.isNotEmpty()) put("options", JSONArray(options.map { JSONObject().put("v", it.first).put("l", it.second) }))
                if (confirm) put("confirm", true)
                runCatching { get() }.getOrNull()?.let { put("value", it) }
            }
}

class BridgePage(
    val id: String,
    val title: String,
    val icon: String,
    val items: List<BridgeItem>,
)

/** Builds a page's items. */
class PageBuilder {
    val items = ArrayList<BridgeItem>()

    fun section(label: String) {
        items += BridgeItem("s${items.size}", "section", label)
    }

    fun info(
        id: String,
        label: String,
        value: () -> String,
    ) {
        items += BridgeItem(id, "info", label, get = value)
    }

    fun switch(
        id: String,
        label: String,
        hint: String = "",
        get: () -> Boolean,
        set: (Boolean) -> Unit,
    ) {
        items += BridgeItem(id, "switch", label, hint, get = get, set = { v -> set(v == true || v == "true") })
    }

    fun slider(
        id: String,
        label: String,
        range: IntRange,
        step: Int = 1,
        unit: String = "",
        get: () -> Int,
        set: (Int) -> Unit,
    ) {
        items +=
            BridgeItem(id, "slider", label, min = range.first, max = range.last, step = step, unit = unit, get = get, set = { v ->
                val n = (v as? Number)?.toInt() ?: v.toString().toIntOrNull() ?: return@BridgeItem
                set(n.coerceIn(range))
            })
    }

    fun choice(
        id: String,
        label: String,
        options: List<Pair<String, String>>,
        get: () -> String,
        set: (String) -> Unit,
    ) {
        items += BridgeItem(id, "choice", label, options = options, get = get, set = { v -> v?.toString()?.takeIf { s -> options.any { it.first == s } }?.let(set) })
    }

    fun button(
        id: String,
        label: String,
        confirm: Boolean = false,
        run: () -> Unit,
    ) {
        items += BridgeItem(id, "button", label, confirm = confirm, set = { run() })
    }
}

fun page(
    id: String,
    title: String,
    icon: String,
    build: PageBuilder.() -> Unit,
) = BridgePage(id, title, icon, PageBuilder().apply(build).items)

/**
 * The settings LibreHU-service shows to the phone app, and those of the other LibreHU apps: a LibreHU app adds its
 * pages with a content provider `<package>.bridge` (signature permission `org.librehu.permission.BRIDGE`) answering
 * `call("pages")` (Bundle "json" = array of pages with their items, same format) and `call("set", "<page>/<item>")`
 * (extra "value" as a string) and `call("action", "<page>/<item>")`.
 */
object BridgePages {
    /** LibreHU apps asked for their pages (those installed). */
    val PROVIDERS = listOf("org.librehu.launcher", "org.librehu.btnremap", "org.librehu.ota", "org.librehu.disc", "org.librehu.fm")

    fun builtIn(context: Context): List<BridgePage> {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val osd = SoundOsd.get(app)
        val engine = EngineSound.get(app)
        val led = FrontLed.get(app)
        val obd = ObdManager.get(app)
        val battery = BatteryWatch.get(app)
        val r: (Int) -> String = app::getString
        return listOf(
            page("android", r(R.string.bp_unit), "settings") {
                section(r(R.string.bp_sound))
                slider("media_volume", r(R.string.bp_media_volume), 0..audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), get = {
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                }) { v -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI) }
                section(r(R.string.bp_screen))
                slider("brightness", r(R.string.bp_brightness), 1..255, get = {
                    Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                }) { v -> putSystem(app, Settings.System.SCREEN_BRIGHTNESS, v) }
                switch("auto_brightness", r(R.string.bp_auto_brightness), get = {
                    Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
                }) { on -> putSystem(app, Settings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0) }
                section(r(R.string.bp_connections))
                switch("wifi", r(R.string.bp_wifi), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.WIFI_ON, 0) != 0
                }) { on -> RootShell.run("svc wifi ${if (on) "enable" else "disable"}") }
                switch("bluetooth", r(R.string.bp_bluetooth), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.BLUETOOTH_ON, 0) != 0
                }) { on -> RootShell.run("svc bluetooth ${if (on) "enable" else "disable"}") }
                section(r(R.string.bp_power))
                button("reboot", r(R.string.bp_reboot), confirm = true) { RootShell.run("svc power reboot || reboot") }
            },
            page("sound", r(R.string.bp_sound_librehu), "volume") {
                switch("osd", r(R.string.bp_osd), r(R.string.bp_osd_hint), get = { osd.settings.value.enabled }) { on ->
                    osd.update { it.copy(enabled = on) }
                }
                section(r(R.string.bp_engine_title))
                switch("engine", r(R.string.bp_engine), get = { engine.settings.value.enabled }) { on -> engine.update { it.copy(enabled = on) } }
                choice("engine_profile", r(R.string.bp_engine_profile), EngineProfile.entries.map { it.name to it.name }, get = { engine.settings.value.profile.name }) { v ->
                    engine.update { it.copy(profile = EngineProfile.valueOf(v)) }
                }
                slider("engine_volume", r(R.string.bp_volume), 0..100, 5, "%", get = { engine.settings.value.volume }) { v -> engine.update { it.copy(volume = v) } }
                choice("engine_turbo", r(R.string.bp_turbo), listOf("0" to r(R.string.bp_turbo_off), "1" to r(R.string.bp_turbo_light), "2" to r(R.string.bp_turbo_strong)), get = { engine.settings.value.turbo.toString() }) { v ->
                    engine.update { it.copy(turbo = v.toInt()) }
                }
                switch("engine_pops", r(R.string.bp_pops), get = { engine.settings.value.pops }) { on -> engine.update { it.copy(pops = on) } }
                button("engine_test", r(R.string.bp_test)) { engine.test() }
            },
            page("lights", r(R.string.bp_lights), "light") {
                switch("backlight", r(R.string.bp_backlight), get = { led.settings.value.backlight }) { on ->
                    led.update { it.copy(backlight = on) }
                }
                slider("day1", r(R.string.bp_day1), 5..100, 5, "%", get = { led.settings.value.dayPwm1 }) { v -> led.update { it.copy(dayPwm1 = v) } }
                slider("night1", r(R.string.bp_night1), 5..100, 5, "%", get = { led.settings.value.nightPwm1 }) { v -> led.update { it.copy(nightPwm1 = v) } }
                slider("day2", r(R.string.bp_day2), 5..100, 5, "%", get = { led.settings.value.dayPwm2 }) { v -> led.update { it.copy(dayPwm2 = v) } }
                slider("night2", r(R.string.bp_night2), 5..100, 5, "%", get = { led.settings.value.nightPwm2 }) { v -> led.update { it.copy(nightPwm2 = v) } }
            },
            page("obd", r(R.string.bp_obd), "car") {
                switch("enabled", r(R.string.bp_obd_enable), get = { obd.settings.value.enabled }) { on -> obd.update { it.copy(enabled = on) } }
                info("battery", r(R.string.bp_battery)) {
                    battery.reading.value?.let { r -> "%.1f V".format(r.voltage) + (r.percent?.let { " · $it %" } ?: " · ${r.status}") } ?: "—"
                }
                section(r(R.string.bp_battery_alarms))
                fun limits(t: (BatteryLimits) -> BatteryLimits) = battery.update { it.copy(limits = t(it.limits)) }
                switch("alarm_low", r(R.string.bp_low), get = { battery.settings.value.limits.alarmLow }) { on -> limits { it.copy(alarmLow = on) } }
                slider("low", r(R.string.bp_low_threshold), 115..125, get = { (battery.settings.value.limits.low * 10).toInt() }) { v ->
                    limits { it.copy(low = v / 10.0) }
                }
                switch("alarm_charge", r(R.string.bp_charge), get = { battery.settings.value.limits.alarmCharge }) { on -> limits { it.copy(alarmCharge = on) } }
                switch("alarm_over", r(R.string.bp_over), get = { battery.settings.value.limits.alarmOver }) { on -> limits { it.copy(alarmOver = on) } }
                switch("sound", r(R.string.bp_alarm_sound), get = { battery.settings.value.sound }) { on -> battery.update { it.copy(sound = on) } }
            },
        )
    }

    private fun putSystem(
        context: Context,
        key: String,
        value: Int,
    ) {
        val ok = runCatching { Settings.System.putInt(context.contentResolver, key, value) }.getOrDefault(false)
        if (!ok) RootShell.run("settings put system $key $value")
    }

    /** Pages of the other LibreHU apps (their `.bridge` providers), with the package that answers. */
    fun external(context: Context): List<Pair<String, JSONObject>> =
        PROVIDERS.flatMap { pkg ->
            val json = call(context, pkg, "pages", null, null)?.getString("json") ?: return@flatMap emptyList()
            runCatching {
                val a = JSONArray(json)
                (0 until a.length()).map { pkg to a.getJSONObject(it) }
            }.getOrDefault(emptyList())
        }

    fun call(
        context: Context,
        pkg: String,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? =
        runCatching {
            val authority = "$pkg.bridge"
            if (context.packageManager.resolveContentProvider(authority, 0) == null) return null
            context.contentResolver.call(android.net.Uri.parse("content://$authority"), method, arg, extras)
        }.getOrNull()
}
