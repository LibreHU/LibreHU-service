package org.librehu.service.bridge

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.service.R
import org.librehu.service.root.RootShell

/**
 * One setting shown by the phone app. The phone draws the pages it is sent ([BridgePages]) without knowing them:
 * new settings need no phone update. Types: `section`, `info` (read only), `switch` (boolean), `slider` (int,
 * [min]..[max] by [step], [unit], `display` = the value written as the head unit shows it), `choice` ([options]),
 * `text` (a string, [max] characters at most), `button` (an action, [confirm] asks first).
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
    val display: ((Int) -> String)? = null,
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
                if (type == "text") put("max", max)
                if (unit.isNotEmpty()) put("unit", unit)
                if (options.isNotEmpty()) put("options", JSONArray(options.map { JSONObject().put("v", it.first).put("l", it.second) }))
                if (confirm) put("confirm", true)
                val v = runCatching { get() }.getOrNull()
                v?.let { put("value", it) }
                if (display != null && v is Int) put("display", display.invoke(v))
            }
}

/** A page; its items are built when asked (reading them may need root or another app), each time afresh. */
class BridgePage(
    val id: String,
    val title: String,
    val icon: String,
    private val build: PageBuilder.() -> Unit,
) {
    val items: List<BridgeItem> get() = PageBuilder().apply(build).items
}

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

    fun text(
        id: String,
        label: String,
        hint: String = "",
        max: Int = 64,
        get: () -> String,
        set: (String) -> Unit,
    ) {
        items += BridgeItem(id, "text", label, hint, max = max, get = get, set = { v -> v?.toString()?.take(max)?.let(set) })
    }

    /** A choice among values of any type, sent by their text (`toString()`: enum name, number…). */
    fun <T> pick(
        id: String,
        label: String,
        options: List<Pair<T, String>>,
        hint: String = "",
        get: () -> T,
        set: (T) -> Unit,
    ) {
        items +=
            BridgeItem(
                id,
                "choice",
                label,
                hint,
                options = options.map { it.first.toString() to it.second },
                get = { get().toString() },
                set = { v -> options.firstOrNull { it.first.toString() == v?.toString() }?.let { set(it.first) } },
            )
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
        hint: String = "",
        display: ((Int) -> String)? = null,
        get: () -> Int,
        set: (Int) -> Unit,
    ) {
        items +=
            BridgeItem(id, "slider", label, hint, min = range.first, max = range.last, step = step, unit = unit, display = display, get = get, set = { v ->
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
        hint: String = "",
        run: () -> Unit,
    ) {
        items += BridgeItem(id, "button", label, hint, confirm = confirm, set = { run() })
    }
}

fun page(
    id: String,
    title: String,
    icon: String,
    build: PageBuilder.() -> Unit,
) = BridgePage(id, title, icon, build)

/**
 * The settings LibreHU-service shows to the phone app, and those of the other LibreHU apps: a LibreHU app adds its
 * pages with a content provider `<package>.bridge` (signature permission `org.librehu.permission.BRIDGE`) answering
 * `call("pages")` (Bundle "json" = array of pages with their items, same format) and `call("set", "<page>/<item>")`
 * (extra "value" as a string) and `call("action", "<page>/<item>")`.
 */
object BridgePages {
    /** LibreHU apps asked for their pages (those installed). */
    val PROVIDERS =
        listOf(
            "org.librehu.launcher",
            "org.librehu.fm",
            "org.librehu.dialer",
            "org.librehu.widgets",
            "org.librehu.btnremap",
            "org.librehu.easysource",
            "org.librehu.disc",
            "org.librehu.ota",
            "org.librehu.helper",
            "com.llsl.viper4android",
            "org.librehu.carplay",
        )

    fun builtIn(context: Context): List<BridgePage> {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
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
                choice("screen_timeout", r(R.string.bp_screen_timeout), TIMEOUTS.map { (ms, label) -> ms.toString() to label(app) }, get = {
                    Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 60_000).toString()
                }) { v -> putSystem(app, Settings.System.SCREEN_OFF_TIMEOUT, v.toInt()) }
                switch("stay_on", r(R.string.bp_stay_on), r(R.string.bp_stay_on_hint), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
                }) { on -> put(app, "global", Settings.Global.STAY_ON_WHILE_PLUGGED_IN, if (on) "7" else "0") }
                choice("font_scale", r(R.string.bp_font_scale), FONT_SCALES.map { it to "${(it.toFloat() * 100).toInt()} %" }, get = {
                    FONT_SCALES.minBy { kotlin.math.abs(it.toFloat() - Settings.System.getFloat(app.contentResolver, Settings.System.FONT_SCALE, 1f)) }
                }) { v -> put(app, "system", Settings.System.FONT_SCALE, v) }
                choice("animations", r(R.string.bp_animations), ANIMATIONS.map { it to if (it == "0") r(R.string.bp_off) else "× $it" }, get = {
                    ANIMATIONS.minBy { kotlin.math.abs(it.toFloat() - Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)) }
                }) { v ->
                    for (k in listOf(Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE)) put(app, "global", k, v)
                }
                section(r(R.string.bp_time))
                switch("auto_time", r(R.string.bp_auto_time), r(R.string.bp_auto_time_hint), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME, 1) != 0
                }) { on -> put(app, "global", Settings.Global.AUTO_TIME, if (on) "1" else "0") }
                switch("auto_zone", r(R.string.bp_auto_zone), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1) != 0
                }) { on -> put(app, "global", Settings.Global.AUTO_TIME_ZONE, if (on) "1" else "0") }
                switch("clock_24", r(R.string.bp_clock_24), get = {
                    Settings.System.getString(app.contentResolver, Settings.System.TIME_12_24) != "12"
                }) { on -> put(app, "system", Settings.System.TIME_12_24, if (on) "24" else "12") }
                section(r(R.string.bp_connections))
                switch("wifi", r(R.string.bp_wifi), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.WIFI_ON, 0) != 0
                }) { on -> RootShell.run("svc wifi ${if (on) "enable" else "disable"}") }
                switch("bluetooth", r(R.string.bp_bluetooth), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.BLUETOOTH_ON, 0) != 0
                }) { on -> RootShell.run("svc bluetooth ${if (on) "enable" else "disable"}") }
                @Suppress("DEPRECATION")
                switch("gps", r(R.string.bp_location), get = {
                    "gps" in Settings.Secure.getString(app.contentResolver, Settings.Secure.LOCATION_PROVIDERS_ALLOWED).orEmpty()
                }) { on -> RootShell.run("settings put secure location_providers_allowed ${if (on) "+" else "-"}gps") }
                switch("airplane", r(R.string.bp_airplane), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
                }) { on ->
                    // Android 9: the setting plus the broadcast the system sends itself (both need root).
                    RootShell.run("settings put global airplane_mode_on ${if (on) 1 else 0} && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on")
                }
                section(r(R.string.bp_developer))
                switch("adb", r(R.string.bp_adb), r(R.string.bp_adb_hint), get = {
                    Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
                }) { on -> put(app, "global", Settings.Global.ADB_ENABLED, if (on) "1" else "0") }
                section(r(R.string.bp_power))
                button("reboot", r(R.string.bp_reboot), confirm = true) { BridgeTools.reboot("normal") }
                button("reboot_recovery", r(R.string.bp_reboot_recovery), confirm = true) { BridgeTools.reboot("recovery") }
                button("reboot_bootloader", r(R.string.bp_reboot_bootloader), confirm = true) { BridgeTools.reboot("bootloader") }
            },
        ) + BridgeSettings.pages(app)
    }

    /** Screen timeouts offered (ms) and their names. */
    private val TIMEOUTS: List<Pair<Int, (Context) -> String>> =
        listOf(
            30_000 to { c -> c.getString(R.string.bp_seconds, 30) },
            60_000 to { c -> c.getString(R.string.bp_minutes, 1) },
            300_000 to { c -> c.getString(R.string.bp_minutes, 5) },
            600_000 to { c -> c.getString(R.string.bp_minutes, 10) },
            1_800_000 to { c -> c.getString(R.string.bp_minutes, 30) },
            Int.MAX_VALUE to { c -> c.getString(R.string.bp_never) },
        )

    // Both followed at once on Android 9: ActivityManagerService's FontScaleSettingObserver, WindowManagerService's
    // SettingsObserver for the animation scales.
    private val FONT_SCALES = listOf("0.85", "1.0", "1.15", "1.3")
    private val ANIMATIONS = listOf("0", "0.5", "1.0", "1.5", "2.0")

    /** Any key of `system`, `secure` or `global`: directly when allowed, else as root. */
    private fun put(
        context: Context,
        ns: String,
        key: String,
        value: String,
    ) {
        BridgeTools.settingsPut(context, ns, key, value)
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
