package org.librehu.service.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.librehu.core.obd.ObdPid
import org.librehu.core.unit.VehicleInput
import org.librehu.service.AuxMix
import org.librehu.service.LibreHuService
import org.librehu.service.Microphone
import org.librehu.service.R
import org.librehu.service.can.CanWheelKeys
import org.librehu.service.car.CameraLayout
import org.librehu.service.car.CarName
import org.librehu.service.car.CvbsInput
import org.librehu.service.car.CvbsPosition
import org.librehu.service.car.DriverSide
import org.librehu.service.car.EngageSound
import org.librehu.service.car.EngineProfile
import org.librehu.service.car.EngineSound
import org.librehu.service.car.FrontLed
import org.librehu.service.car.GuideLines
import org.librehu.service.car.MediaDuring
import org.librehu.service.car.ReverseCamera
import org.librehu.service.car.ReverseExit
import org.librehu.service.car.ReverseMode
import org.librehu.service.car.ReverseView
import org.librehu.service.car.ReverseViewSettings
import org.librehu.service.car.VehicleInfo
import org.librehu.service.car.VideoOut
import org.librehu.service.car.WidgetLive
import org.librehu.service.config.ServiceConfig
import org.librehu.service.display.DarkMode
import org.librehu.service.display.DisplayController
import org.librehu.service.net.UsbNet
import org.librehu.service.obd.BatteryWatch
import org.librehu.service.obd.ObdCarStore
import org.librehu.service.obd.ObdLabels
import org.librehu.service.obd.ObdManager
import org.librehu.service.obd.ObdTransport
import org.librehu.service.overlay.LevelOverlay
import org.librehu.service.overlay.LevelOverlaySettings
import org.librehu.service.overlay.OverlayEdge
import org.librehu.service.overlay.OverlayKind
import org.librehu.service.overlay.OverlayStyle
import org.librehu.service.overlay.SoundChannel
import org.librehu.service.overlay.SoundOsd
import org.librehu.service.overlay.SoundOsdSettings
import org.librehu.service.overlay.StatusOsd
import org.librehu.service.perf.AppCompiler
import org.librehu.service.perf.ZygotePreload
import org.librehu.service.phone.PhoneInfo
import org.librehu.service.power.BootApps
import org.librehu.service.power.EarlyStart
import org.librehu.service.power.Standby
import org.librehu.service.power.StandbyMode
import org.librehu.service.power.StandbySettings
import org.librehu.service.projection.AndroidAuto
import org.librehu.service.projection.CarPlay
import org.librehu.service.projection.HeadunitApp
import org.librehu.service.time.TimeController
import org.librehu.service.touch.TouchKeys
import org.librehu.service.tweaks.Tweaks
import java.util.Locale

/**
 * Every setting of LibreHU-service for the phone app, page by page as the screens of the head unit: the same labels
 * (same string resources, so the same languages) and the same stores (each item reads and changes what the screen
 * reads and changes: no copy to keep in step). Pages are built when asked, so that reading one (root, another app)
 * costs nothing to the others; a page with options that depend on another (a mode, an on / off) is sent again whole
 * after each change.
 *
 * Left on the head unit only: the camera's format and the front panel LED colours (they write the metazone / the
 * LEDs are on hold), the MCU and its profiles, the diagnostics (MCU tools, audio diagnostics, Autochips, traffic),
 * backups, the DDT4ALL files and the touch zones (drawn on the screen).
 */
object BridgeSettings {
    fun pages(context: Context): List<BridgePage> {
        val app = context.applicationContext
        val r: (Int) -> String = app::getString
        return listOf(
            audio(app, r),
            vehicle(app, r),
            reverse(app, r),
            video(app, r),
            obd(app, r),
            bluetooth(app, r),
            projection(app, r),
            display(app, r),
            lights(app, r),
            system(app, r),
            time(app, r),
            tweaks(app, r),
        )
    }

    private fun db(step: Int) = String.format(Locale.ROOT, "%+d dB", (step - 10) * 2)

    private fun centred(v: Int) = (v - 30).let { if (it == 0) "0" else String.format(Locale.ROOT, "%+d", it) }

    private fun pct(v: Int) = "$v %"

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    // --- Audio -------------------------------------------------------------------------------------------------------

    private fun audio(
        app: Context,
        r: (Int) -> String,
    ) = page("audio", r(R.string.tab_audio), "volume") {
        val api = LibreHuService.api
        if (api == null) info("offline", r(R.string.audio_offline)) { "" }
        if (api != null) {
            section(r(R.string.section_audio))
            slider("volume", r(R.string.volume), 0..api.maxVolume, get = { api.volume }) { api.volume = it }
            switch("mute", r(R.string.mute), get = { api.isMuted }) { api.isMuted = it }
            section(r(R.string.audio_source))
            pick("source", r(R.string.audio_source), listOf(0 to r(R.string.audio_source_android), 1 to r(R.string.audio_source_aux)), r(R.string.audio_source_hint), get = {
                api.audioSource
            }) { api.audioSource = it }
        }
        val mix = AuxMix.get(app).value
        switch("aux_mix", r(R.string.aux_mix), r(R.string.aux_mix_hint), get = { mix.enabled }) { on -> AuxMix.update(app) { it.copy(enabled = on) } }
        if (mix.enabled) {
            slider("aux_mix_level", r(R.string.aux_mix_level), -30..7, display = { "${if (it > 0) "+" else ""}$it dB" }, get = { mix.offsetDb }) { v ->
                AuxMix.update(app) { it.copy(offsetDb = v) }
            }
        }
        section(r(R.string.mic_title))
        val mic = Microphone.get(app).value
        pick("mic_gain", r(R.string.mic_gain), listOf(0 to r(R.string.mic_rom)) + Microphone.GAINS.mapIndexed { i, g -> g to "${i + 1}" }, get = { mic.gain }) { g ->
            Microphone.update(app) { it.copy(gain = g) }
        }
        pick("mic_ecnr", r(R.string.mic_ecnr), listOf(-1 to r(R.string.mic_rom), 0 to r(R.string.off), 1 to r(R.string.on)), get = {
            mic.ecnr?.let { if (it) 1 else 0 } ?: -1
        }) { v -> Microphone.update(app) { it.copy(ecnr = if (v < 0) null else v == 1) } }
        if (api != null) {
            section(r(R.string.audio_tone))
            val t = api.tone
            slider("bass", r(R.string.bass), 0..20, display = ::db, get = { t[0] }) { v -> api.setTone(v, api.tone[1], api.tone[2]) }
            slider("middle", r(R.string.middle), 0..20, display = ::db, get = { t[1] }) { v -> api.setTone(api.tone[0], v, api.tone[2]) }
            slider("treble", r(R.string.treble), 0..20, display = ::db, get = { t[2] }) { v -> api.setTone(api.tone[0], api.tone[1], v) }
            slider("loudness", r(R.string.loudness), 0..15, display = { "$it dB" }, get = { api.loudness }) { api.loudness = it }
            section(r(R.string.audio_balance))
            slider("balance", r(R.string.balance), 0..60, display = ::centred, get = { api.balanceFade[0] }) { v -> api.setBalanceFade(v, api.balanceFade[1]) }
            slider("fade", r(R.string.fade), 0..60, display = ::centred, get = { api.balanceFade[1] }) { v -> api.setBalanceFade(api.balanceFade[0], v) }
            section(r(R.string.audio_outputs))
            switch("subwoofer", r(R.string.subwoofer), get = { api.isSubwooferOn }) { on -> api.setSubwoofer(on, api.subwooferLevel) }
            slider("sub_level", r(R.string.sub_level), 0..12, display = { "${it - 5} dB" }, get = { api.subwooferLevel }) { v -> api.setSubwoofer(api.isSubwooferOn, v) }
            switch("external_amp", r(R.string.external_amp), r(R.string.external_amp_hint), get = { api.isExternalAmpEnabled }) { api.isExternalAmpEnabled = it }
        }
        overlay(this, app, r, OverlayKind.VOLUME)
        section(r(R.string.sound_osd))
        val osd = SoundOsd.get(app)
        val o = osd.settings.value
        switch("osd", r(R.string.sound_osd_enable), r(R.string.sound_osd_hint), get = { o.enabled }) { on -> osd.update { it.copy(enabled = on) } }
        if (o.enabled) {
            for (c in SoundChannel.entries) {
                if (c == SoundChannel.UNIT) continue
                switch("osd_${c.name}", r(c.label), get = { c in o.channels }) { on -> osd.update { it.copy(channels = if (on) it.channels + c else it.channels - c) } }
            }
            switch("osd_back", r(R.string.sound_osd_back), get = { o.backToUnit }) { on -> osd.update { it.copy(backToUnit = on) } }
            pick("osd_timeout", r(R.string.sound_osd_timeout), SoundOsdSettings.TIMEOUTS.map { it to seconds(it) }, get = { o.timeoutMs }) { v -> osd.update { it.copy(timeoutMs = v) } }
            button("osd_test", r(R.string.sound_osd_test)) { osd.open() }
        }
    }

    /** Level panel of the volume or of the brightness ([LevelOverlay]). */
    private fun overlay(
        b: PageBuilder,
        app: Context,
        r: (Int) -> String,
        kind: OverlayKind,
    ) = with(b) {
        val volume = kind == OverlayKind.VOLUME
        val p = if (volume) "vol_" else "bright_"
        val overlay = LevelOverlay.get(app, kind)
        val s = overlay.settings.value
        section(r(if (volume) R.string.vol_overlay else R.string.bright_overlay))
        switch("${p}enabled", r(if (volume) R.string.vol_overlay_enable else R.string.bright_overlay_enable), r(if (volume) R.string.vol_overlay_hint else R.string.bright_overlay_hint), get = {
            s.enabled
        }) { on -> overlay.update { it.copy(enabled = on) } }
        if (!s.enabled) return@with
        pick("${p}style", r(R.string.vol_overlay_style), listOf(OverlayStyle.VERTICAL to r(R.string.vol_style_vertical), OverlayStyle.HORIZONTAL to r(R.string.vol_style_horizontal)), get = {
            s.style
        }) { st ->
            overlay.update {
                val edge =
                    when {
                        st == OverlayStyle.VERTICAL && (it.edge == OverlayEdge.TOP || it.edge == OverlayEdge.BOTTOM) -> OverlayEdge.RIGHT
                        st == OverlayStyle.HORIZONTAL && (it.edge == OverlayEdge.LEFT || it.edge == OverlayEdge.RIGHT) -> OverlayEdge.TOP
                        else -> it.edge
                    }
                it.copy(style = st, edge = edge)
            }
        }
        val edges =
            if (s.style == OverlayStyle.VERTICAL) {
                listOf(OverlayEdge.RIGHT to r(R.string.vol_edge_right), OverlayEdge.LEFT to r(R.string.vol_edge_left))
            } else {
                listOf(OverlayEdge.TOP to r(R.string.vol_edge_top), OverlayEdge.BOTTOM to r(R.string.vol_edge_bottom))
            }
        pick("${p}edge", r(R.string.vol_overlay_edge), edges, get = { s.edge }) { e -> overlay.update { it.copy(edge = e) } }
        val centre = r(R.string.overlay_centre)
        slider(
            "${p}offset",
            r(if (s.style == OverlayStyle.VERTICAL) R.string.overlay_offset_vertical else R.string.overlay_offset_horizontal),
            -40..40,
            display = { if (it == 0) centre else String.format(Locale.ROOT, "%+d %%", it) },
            get = { s.offset },
        ) { v -> overlay.update { it.copy(offset = v) } }
        slider("${p}margin", r(R.string.overlay_margin), 0..120, display = { "$it dp" }, get = { s.margin }) { v -> overlay.update { it.copy(margin = v) } }
        pick(
            "${p}size",
            r(R.string.vol_overlay_size),
            listOf(0 to r(R.string.overlay_size_xs), 1 to r(R.string.vol_size_small), 2 to r(R.string.vol_size_normal), 3 to r(R.string.vol_size_large), 4 to r(R.string.overlay_size_xl)),
            get = { s.size },
        ) { z -> overlay.update { it.copy(size = z) } }
        slider("${p}opacity", r(R.string.overlay_opacity), 40..100, display = ::pct, get = { s.opacity }) { v -> overlay.update { it.copy(opacity = v) } }
        pick("${p}timeout", r(R.string.vol_overlay_timeout), LevelOverlaySettings.TIMEOUTS.map { it to seconds(it) }, get = { s.timeoutMs }) { t -> overlay.update { it.copy(timeoutMs = t) } }
        switch("${p}number", r(if (volume) R.string.vol_overlay_number else R.string.bright_overlay_number), get = { s.showNumber }) { on -> overlay.update { it.copy(showNumber = on) } }
        switch("${p}touch", r(R.string.vol_overlay_touch), r(if (volume) R.string.vol_overlay_touch_hint else R.string.bright_overlay_touch_hint), get = { s.touch }) { on ->
            overlay.update { it.copy(touch = on) }
        }
    }

    // --- Vehicle -----------------------------------------------------------------------------------------------------

    private fun vehicle(
        app: Context,
        r: (Int) -> String,
    ) = page("vehicle", r(R.string.tab_vehicle), "car") {
        section(r(R.string.car_name_title))
        text("car_name", r(R.string.car_name_label), r(R.string.car_name_hint), 64, get = { CarName.name(app) }) { name ->
            if (name.isNotBlank()) CarName.set(app, name.trim(), CarName.renameHotspot(app))
        }
        section(r(R.string.vehicle_title))
        val v = VehicleInfo.get(app).value
        text("make", r(R.string.vehicle_make), max = 40, get = { v.make }) { t -> VehicleInfo.update(app) { it.copy(make = t) } }
        text("model", r(R.string.vehicle_model), max = 40, get = { v.model }) { t -> VehicleInfo.update(app) { it.copy(model = t) } }
        text("year", r(R.string.vehicle_year), max = 4, get = { v.year }) { t -> VehicleInfo.update(app) { it.copy(year = t.filter(Char::isDigit).take(4)) } }
        text("color", r(R.string.vehicle_color), max = 40, get = { v.color }) { t -> VehicleInfo.update(app) { it.copy(color = t) } }
        text("plate", r(R.string.vehicle_plate), max = 40, get = { v.plate }) { t -> VehicleInfo.update(app) { it.copy(plate = t) } }
        text("fuel", r(R.string.vehicle_fuel), max = 40, get = { v.fuel }) { t -> VehicleInfo.update(app) { it.copy(fuel = t) } }
        pick("driver_side", r(R.string.vehicle_driver_side), listOf(DriverSide.LEFT to r(R.string.vehicle_driver_left), DriverSide.RIGHT to r(R.string.vehicle_driver_right)), get = {
            v.driverSide
        }) { d -> VehicleInfo.update(app) { it.copy(driverSide = d) } }
        section(r(R.string.vehicle_inputs))
        val inverted = LibreHuService.invertedInputs(app)
        for ((input, label) in listOf(
            VehicleInput.REVERSE to R.string.invert_reverse,
            VehicleInput.HANDBRAKE to R.string.invert_handbrake,
            VehicleInput.HEADLIGHT to R.string.invert_headlight,
            VehicleInput.TURN_LEFT to R.string.invert_turn_left,
            VehicleInput.TURN_RIGHT to R.string.invert_turn_right,
        )) {
            switch("invert_${input.name}", r(label), get = { input in inverted }) { on -> LibreHuService.setInverted(app, input, on) }
        }
        switch("turn_gpio", r(R.string.turn_gpio), r(R.string.turn_gpio_hint), get = {
            LibreHuService.prefs(app).getBoolean(LibreHuService.PREF_TURN_GPIO, true)
        }) { on -> LibreHuService.prefs(app).edit().putBoolean(LibreHuService.PREF_TURN_GPIO, on).apply() }
        section(r(R.string.can_keys))
        val can = CanWheelKeys.get(app)
        switch("can_keys", r(R.string.can_keys_enable), r(R.string.can_keys_hint), get = { can.enabled.value }) { can.setEnabled(it) }
        slider("can_ignore", r(R.string.can_keys_ignore), 0..15, hint = r(R.string.can_keys_ignore_hint), display = { if (it == 0) "—" else "$it s" }, get = {
            can.ignoreAfterOnS()
        }) { can.setIgnoreAfterOnS(it) }
        slider("can_long", r(R.string.can_keys_long_ms), CanWheelKeys.MIN_LONG_MS / 100..CanWheelKeys.MAX_LONG_MS / 100, display = { "${it * 100} ms" }, get = {
            can.longMs() / 100
        }) { can.setLongMs(it * 100) }
        section(r(R.string.tab_touch))
        val touch = TouchKeys.get(app)
        switch("touch_keys", r(R.string.touch_keys_enable), r(R.string.touch_keys_ivi), get = { touch.enabled.value }) { touch.setEnabled(it) }
    }

    // --- Rear view ---------------------------------------------------------------------------------------------------

    private fun reverse(
        app: Context,
        r: (Int) -> String,
    ) = page("reverse", r(R.string.tab_reverse), "camera") {
        val camera = ReverseCamera.get(app)
        val s = camera.settings.value
        switch("enabled", r(R.string.reverse_enable), r(R.string.reverse_enable_hint), get = { s.enabled }) { on -> camera.update { it.copy(enabled = on) } }
        pick("mode", r(R.string.reverse_mode), listOf(ReverseMode.BUILT_IN to r(R.string.reverse_mode_builtin), ReverseMode.APP to r(R.string.reverse_mode_app)), get = {
            s.mode
        }) { m -> camera.update { it.copy(mode = m) } }
        if (s.mode == ReverseMode.APP) {
            pick(
                "exit",
                r(R.string.reverse_exit),
                listOf(ReverseExit.CLOSE to r(R.string.reverse_exit_close), ReverseExit.HOME to r(R.string.reverse_exit_home), ReverseExit.NOTHING to r(R.string.reverse_exit_nothing)),
                get = { s.exit },
            ) { e -> camera.update { it.copy(exit = e) } }
        }
        slider("delay", r(R.string.reverse_delay), 0..30, display = { "${it * 100} ms" }, get = { s.delayMs / 100 }) { d -> camera.update { it.copy(delayMs = d * 100) } }
        button("test", r(if (s.mode == ReverseMode.BUILT_IN) R.string.rv_preview_button else R.string.reverse_test)) { camera.test() }
        if (s.mode != ReverseMode.BUILT_IN) return@page
        val view = ReverseView.get(app)
        val v = view.settings.value
        fun set(t: (ReverseViewSettings) -> ReverseViewSettings) = view.update(t)
        section(r(R.string.rv_image_title))
        val cameras = runCatching { view.cameraList() }.getOrDefault(emptyList())
        pick("camera", r(R.string.rv_camera), listOf("" to r(R.string.rv_camera_auto)) + cameras.map { it.id to "${it.id} · ${it.label}" }, get = { v.cameraId }) { id ->
            set { it.copy(cameraId = id) }
        }
        switch("mirror", r(R.string.rv_mirror), get = { v.mirror }) { on -> set { it.copy(mirror = on) } }
        switch("flip", r(R.string.rv_flip), get = { v.flip }) { on -> set { it.copy(flip = on) } }
        pick("rotation", r(R.string.rv_rotation), listOf(0, 90, 180, 270).map { it to "$it°" }, get = { v.rotation }) { d -> set { it.copy(rotation = d) } }
        switch("fill", r(R.string.rv_fill), r(R.string.rv_fill_hint), get = { v.fill }) { on -> set { it.copy(fill = on) } }
        slider("zoom", r(R.string.rv_zoom), 50..300, display = ::pct, get = { v.zoom }) { z -> set { it.copy(zoom = z) } }
        slider("offset_x", r(R.string.rv_offset_x), -30..30, display = ::pct, get = { v.offsetX }) { x -> set { it.copy(offsetX = x) } }
        slider("offset_y", r(R.string.rv_offset_y), -30..30, display = ::pct, get = { v.offsetY }) { y -> set { it.copy(offsetY = y) } }
        switch("stop_dvr", r(R.string.rv_stop_dvr), r(R.string.rv_stop_dvr_hint), get = { v.stopDvr }) { on -> set { it.copy(stopDvr = on) } }
        slider("close_delay", r(R.string.rv_close_delay), 0..20, display = { String.format(Locale.ROOT, "%.1f s", it * 0.5) }, get = { v.closeDelayMs / 500 }) { d ->
            set { it.copy(closeDelayMs = d * 500) }
        }
        switch("show_info", r(R.string.rv_show_info), get = { v.showInfo }) { on -> set { it.copy(showInfo = on) } }
        if (v.extras.isNotEmpty()) {
            section(r(R.string.rv_extras_title))
            v.extras.forEachIndexed { i, e ->
                val label = cameras.firstOrNull { it.id == e.id }?.label ?: e.id
                switch("extra_$i", "${i + 2}. $label", r(R.string.rv_mirror), get = { e.mirror }) { on ->
                    set { s2 -> s2.copy(extras = s2.extras.mapIndexed { j, x -> if (j == i) x.copy(mirror = on) else x }) }
                }
            }
            pick(
                "layout",
                r(R.string.rv_layout),
                listOf(CameraLayout.SWITCH to r(R.string.rv_layout_switch), CameraLayout.SPLIT to r(R.string.rv_layout_split), CameraLayout.PIP to r(R.string.rv_layout_pip)),
                r(R.string.rv_layout_hint),
                get = { v.layout },
            ) { l -> set { it.copy(layout = l) } }
        }
        section(r(R.string.rv_widget_title))
        pick(
            "widget_live",
            r(R.string.rv_widget_live),
            listOf(WidgetLive.OFF to r(R.string.rv_widget_off), WidgetLive.LAUNCHER to r(R.string.rv_widget_launcher), WidgetLive.ALWAYS to r(R.string.rv_widget_always)),
            r(R.string.rv_widget_hint),
            get = { v.widgetLive },
        ) { m -> set { it.copy(widgetLive = m) } }
        if (v.widgetLive != WidgetLive.OFF) {
            pick("widget_fps", r(R.string.rv_widget_fps), listOf(5, 10, 15, 20).map { it to "$it" }, get = { v.widgetFps }) { f -> set { it.copy(widgetFps = f) } }
        }
        section(r(R.string.rv_lines_title))
        val g = v.lines
        fun lines(t: (GuideLines) -> GuideLines) = set { it.copy(lines = t(it.lines)) }
        switch("lines", r(R.string.rv_lines_show), get = { g.show }) { on -> lines { it.copy(show = on) } }
        if (g.show) {
            slider("lines_center", r(R.string.rv_lines_center), 20..80, display = ::pct, get = { g.centerX }) { x -> lines { it.copy(centerX = x) } }
            slider("lines_bottom", r(R.string.rv_lines_bottom), 50..100, display = ::pct, get = { g.bottomY }) { y -> lines { it.copy(bottomY = y) } }
            slider("lines_bottom_width", r(R.string.rv_lines_bottom_width), 20..120, display = ::pct, get = { g.bottomWidth }) { w -> lines { it.copy(bottomWidth = w) } }
            slider("lines_top", r(R.string.rv_lines_top), 10..90, display = ::pct, get = { g.topY }) { y -> lines { it.copy(topY = y) } }
            slider("lines_top_width", r(R.string.rv_lines_top_width), 4..100, display = ::pct, get = { g.topWidth }) { w -> lines { it.copy(topWidth = w) } }
            slider("lines_length", r(R.string.rv_lines_length), 10..60, display = { "${it * 10} cm" }, get = { g.lengthCm / 10 }) { l -> lines { it.copy(lengthCm = l * 10) } }
            slider("lines_mark1", r(R.string.rv_lines_mark1), 0..30, display = { "${it * 10} cm" }, get = { g.mark1Cm / 10 }) { m -> lines { it.copy(mark1Cm = m * 10) } }
            slider("lines_mark2", r(R.string.rv_lines_mark2), 0..40, display = { "${it * 10} cm" }, get = { g.mark2Cm / 10 }) { m -> lines { it.copy(mark2Cm = m * 10) } }
            slider("lines_mark3", r(R.string.rv_lines_mark3), 0..60, display = { "${it * 10} cm" }, get = { g.mark3Cm / 10 }) { m -> lines { it.copy(mark3Cm = m * 10) } }
            slider("lines_thickness", r(R.string.rv_lines_thickness), 1..14, display = { "$it dp" }, get = { g.thickness }) { t -> lines { it.copy(thickness = t) } }
            slider("lines_opacity", r(R.string.rv_lines_opacity), 10..100, display = ::pct, get = { g.opacity }) { o -> lines { it.copy(opacity = o) } }
            switch("lines_dynamic", r(R.string.rv_lines_dynamic), r(R.string.rv_lines_dynamic_hint), get = { g.dynamic }) { on -> lines { it.copy(dynamic = on) } }
            if (g.dynamic) {
                switch("lines_invert", r(R.string.rv_lines_invert), get = { g.invertSteering }) { on -> lines { it.copy(invertSteering = on) } }
                slider("lines_wheelbase", r(R.string.rv_lines_wheelbase), 180..360, display = { "$it cm" }, get = { g.wheelbaseCm }) { w -> lines { it.copy(wheelbaseCm = w) } }
                slider("lines_width", r(R.string.rv_lines_width), 120..230, display = { "$it cm" }, get = { g.carWidthCm }) { w -> lines { it.copy(carWidthCm = w) } }
                slider("lines_ratio", r(R.string.rv_lines_ratio), 10..24, display = { "$it:1" }, get = { g.steeringRatio }) { x -> lines { it.copy(steeringRatio = x) } }
            }
        }
        section(r(R.string.rv_sound_title))
        val sounds =
            listOf(EngageSound.NONE to r(R.string.rv_sound_none), EngageSound.BEEP to r(R.string.rv_sound_beep), EngageSound.DOUBLE to r(R.string.rv_sound_double), EngageSound.CHIME to r(R.string.rv_sound_chime)) +
                // A sound file is chosen on the head unit.
                if (v.soundUri.isNotEmpty()) listOf(EngageSound.CUSTOM to r(R.string.rv_sound_custom)) else emptyList()
        pick("sound", r(R.string.rv_sound_engage), sounds, get = { v.sound }) { e -> set { it.copy(sound = e) } }
        slider("sound_volume", r(R.string.rv_sound_volume), 0..100, display = ::pct, get = { v.soundVolume }) { x -> set { it.copy(soundVolume = x) } }
        pick(
            "media",
            r(R.string.rv_media),
            listOf(MediaDuring.KEEP to r(R.string.rv_media_keep), MediaDuring.DUCK to r(R.string.rv_media_duck), MediaDuring.PAUSE to r(R.string.rv_media_pause)),
            get = { v.media },
        ) { m -> set { it.copy(media = m) } }
        section(r(R.string.rv_alerts_title))
        switch("radar", r(R.string.rv_radar), r(R.string.rv_radar_hint), get = { v.radar }) { on -> set { it.copy(radar = on) } }
        if (v.radar) {
            switch("radar_beep", r(R.string.rv_radar_beep), get = { v.radarBeep }) { on -> set { it.copy(radarBeep = on) } }
            switch("radar_invert", r(R.string.rv_radar_invert), r(R.string.rv_radar_invert_hint), get = { v.radarInvert }) { on -> set { it.copy(radarInvert = on) } }
        }
        switch("motion", r(R.string.rv_motion), r(R.string.rv_motion_hint), get = { v.motion }) { on -> set { it.copy(motion = on) } }
        if (v.motion) {
            slider("motion_sensitivity", r(R.string.rv_motion_sensitivity), 1..100, get = { v.motionSensitivity }) { x -> set { it.copy(motionSensitivity = x) } }
            slider("motion_top", r(R.string.rv_motion_top), 0..90, display = ::pct, get = { v.motionTop }) { x -> set { it.copy(motionTop = x) } }
            switch("motion_beep", r(R.string.rv_motion_beep), get = { v.motionBeep }) { on -> set { it.copy(motionBeep = on) } }
        }
        switch("approach", r(R.string.rv_approach), r(R.string.rv_approach_hint), get = { v.approach }) { on -> set { it.copy(approach = on) } }
    }

    // --- Video in / out ----------------------------------------------------------------------------------------------

    private fun video(
        app: Context,
        r: (Int) -> String,
    ) = page("video", r(R.string.tab_video), "video") {
        val s = CvbsInput.get(app).value
        val positions = CvbsPosition.entries.map { it to r(it.label) }
        section(r(R.string.cvbs_inputs_title))
        pick("in1", r(R.string.cvbs_in_position).format(1), positions, get = { s.in1 }) { p -> CvbsInput.update(app) { it.copy(in1 = p) } }
        pick("in2", r(R.string.cvbs_in_position).format(2), positions, get = { s.in2 }) { p -> CvbsInput.update(app) { it.copy(in2 = p) } }
        switch("handbrake", r(R.string.cvbs_handbrake), r(R.string.cvbs_handbrake_hint), get = { s.handbrakeOnly }) { on -> CvbsInput.update(app) { it.copy(handbrakeOnly = on) } }
        pick("camera", r(R.string.cvbs_camera), (0..15).map { it.toString() to it.toString() }, get = { s.cameraId }) { id -> CvbsInput.update(app) { it.copy(cameraId = id) } }
        section(r(R.string.cvbs_reverse_title))
        switch("switch_reverse", r(R.string.cvbs_reverse_switch), r(R.string.cvbs_reverse_hint), get = { s.switchForReverse }) { on ->
            CvbsInput.update(app) { it.copy(switchForReverse = on) }
        }
        if (s.switchForReverse) pick("reverse", r(R.string.cvbs_reverse_title), positions, get = { s.reverse }) { p -> CvbsInput.update(app) { it.copy(reverse = p) } }
        section(r(R.string.cvbs_out_title))
        if (VideoOut.installed(app)) {
            switch("video_out", r(R.string.cvbs_out_enable), r(R.string.cvbs_out_enable_hint), get = { VideoOut.enabled(app) }) { on -> VideoOut.setEnabled(app, on) }
        } else {
            info("video_out_missing", r(R.string.cvbs_out_app_missing)) { "" }
        }
    }

    // --- OBD, battery, engine sound ----------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun obd(
        app: Context,
        r: (Int) -> String,
    ) = page("obd", r(R.string.tab_obd), "car") {
        val obd = ObdManager.get(app)
        val s = obd.settings.value
        val st = obd.state.value
        switch("enabled", r(R.string.obd_enable), r(R.string.obd_enable_hint), get = { s.enabled }) { on -> obd.update { it.copy(enabled = on) } }
        info("state", r(R.string.obd_adapter)) { listOf(st.connection.name, st.adapter, st.protocol, st.message).filter { it.isNotEmpty() }.joinToString(" · ") }
        section(r(R.string.obd_adapter))
        pick("transport", r(R.string.obd_adapter), listOf(ObdTransport.BLUETOOTH to r(R.string.obd_bluetooth), ObdTransport.USB to r(R.string.obd_usb)), get = { s.transport }) { t ->
            obd.update { it.copy(transport = t) }
        }
        if (s.transport == ObdTransport.BLUETOOTH) {
            val bonded = runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().map { it.address to (it.name ?: it.address) } }.getOrDefault(emptyList())
            pick("bt_address", r(R.string.obd_bluetooth), bonded, r(R.string.obd_bt_hint), get = { s.bluetoothAddress }) { a -> obd.update { it.copy(bluetoothAddress = a) } }
        } else {
            pick("usb_device", r(R.string.obd_usb), listOf("" to r(R.string.obd_usb_first)) + obd.usbAdapters(), r(R.string.obd_usb_hint), get = { s.usbDevice }) { d ->
                obd.update { it.copy(usbDevice = d) }
            }
            pick("usb_baud", r(R.string.obd_baud), ObdManager.BAUD_RATES.map { it to it.toString() }, get = { s.usbBaud }) { b -> obd.update { it.copy(usbBaud = b) } }
        }
        button("reconnect", r(R.string.obd_reconnect)) { obd.restart() }
        section(r(R.string.obd_values))
        for (pid in ObdPid.entries) {
            if (st.supported.isNotEmpty() && pid.pid !in st.supported) continue
            switch("pid_${pid.name}", ObdLabels.label(app, pid.name), get = { pid in s.pids }) { on -> obd.update { it.copy(pids = if (on) it.pids + pid else it.pids - pid) } }
        }
        section(r(R.string.obd_widget))
        val keys = listOf(ObdManager.BATTERY) + ObdPid.entries.map { it.name }
        s.widget.forEachIndexed { slot, k ->
            pick("widget_$slot", r(R.string.obd_widget_slot).format(slot + 1), keys.map { it to ObdLabels.label(app, it) }, get = { k }) { v ->
                obd.update { it.copy(widget = it.widget.toMutableList().also { w -> w[slot] = v }) }
            }
        }
        section(r(R.string.obd_dtc))
        info("dtcs", r(R.string.obd_dtc)) {
            val codes = st.dtcs
            when {
                codes == null -> r(R.string.obd_dtc_hint)
                codes.isEmpty() -> r(R.string.obd_dtc_none)
                else -> codes.joinToString(", ")
            }
        }
        button("dtc_read", r(R.string.obd_dtc_read)) { obd.readDtcs() }
        button("dtc_clear", r(R.string.obd_dtc_clear), confirm = true, hint = r(R.string.obd_dtc_clear_confirm)) { obd.clearDtcs() }
        val cars = ObdCarStore.get(app)
        pick("car", r(R.string.obd_car_none).let { r(R.string.tab_vehicle) }, listOf("" to r(R.string.obd_car_none)) + cars.cars.value.map { it.id to it.name }, get = { cars.selected.value }) {
            cars.select(it)
        }
        section(r(R.string.bat_title))
        val watch = BatteryWatch.get(app)
        val b = watch.settings.value
        val l = b.limits
        fun volts(v: Int) = String.format(Locale.ROOT, "%.1f V", v / 10.0)
        switch("alarm_low", r(R.string.bat_alarm_low), get = { l.alarmLow }) { on -> watch.update { it.copy(limits = it.limits.copy(alarmLow = on)) } }
        slider("low", r(R.string.bat_low), 115..125, display = ::volts, get = { (l.low * 10).toInt() }) { v -> watch.update { it.copy(limits = it.limits.copy(low = v / 10.0)) } }
        switch("alarm_charge", r(R.string.bat_alarm_charge), get = { l.alarmCharge }) { on -> watch.update { it.copy(limits = it.limits.copy(alarmCharge = on)) } }
        slider("charge_min", r(R.string.bat_charge_min), 120..136, display = ::volts, get = { (l.chargeMin * 10).toInt() }) { v ->
            watch.update { it.copy(limits = it.limits.copy(chargeMin = v / 10.0)) }
        }
        switch("alarm_over", r(R.string.bat_alarm_over), get = { l.alarmOver }) { on -> watch.update { it.copy(limits = it.limits.copy(alarmOver = on)) } }
        switch("bat_sound", r(R.string.bat_sound), get = { b.sound }) { on -> watch.update { it.copy(sound = on) } }
        slider("bat_offset", r(R.string.bat_offset), -5..5, display = { String.format(Locale.ROOT, "%+.1f V", it / 10.0) }, get = { (b.offset * 10).toInt() }) { v ->
            watch.update { it.copy(offset = v / 10.0) }
        }
        section(r(R.string.engine_title))
        val engine = EngineSound.get(app)
        val e = engine.settings.value
        switch("engine", r(R.string.engine_enable), r(R.string.engine_hint), get = { e.enabled }) { on -> engine.update { it.copy(enabled = on) } }
        pick(
            "engine_profile",
            r(R.string.engine_profile),
            listOf(EngineProfile.I4 to r(R.string.engine_i4), EngineProfile.V6 to r(R.string.engine_v6), EngineProfile.V8 to r(R.string.engine_v8), EngineProfile.V12 to r(R.string.engine_v12)),
            get = { e.profile },
        ) { p -> engine.update { it.copy(profile = p) } }
        slider("engine_volume", r(R.string.engine_volume), 0..100, display = ::pct, get = { e.volume }) { v -> engine.update { it.copy(volume = v) } }
        pick("engine_turbo", r(R.string.engine_turbo), listOf(0 to r(R.string.engine_turbo_off), 1 to r(R.string.engine_turbo_light), 2 to r(R.string.engine_turbo_strong)), get = {
            e.turbo
        }) { t -> engine.update { it.copy(turbo = t) } }
        switch("engine_pops", r(R.string.engine_pops), r(R.string.engine_pops_hint), get = { e.pops }) { on -> engine.update { it.copy(pops = on) } }
        slider("engine_redline", r(R.string.engine_redline), 40..90, display = { "${it * 100} rpm" }, get = { e.redline / 100 }) { x -> engine.update { it.copy(redline = x * 100) } }
        button("engine_test", r(R.string.engine_test)) { engine.test() }
    }

    // --- Bluetooth and phone -----------------------------------------------------------------------------------------

    private fun bluetooth(
        app: Context,
        r: (Int) -> String,
    ) = page("bluetooth", r(R.string.tab_bluetooth), "bluetooth") {
        val bt = LibreHuService.btApi
        if (bt != null) {
            val s = bt.status
            switch("bt_enabled", r(R.string.bt_enabled), get = { s.enabled }) { bt.setEnabled(it) }
            switch("bt_auto_connect", r(R.string.bt_auto_connect), get = { s.autoConnect }) { bt.setAutoConnect(it) }
            switch("bt_auto_answer", r(R.string.bt_auto_answer), get = { s.autoAnswer }) { bt.setAutoAnswer(it) }
            text("bt_name", r(R.string.bt_rename), max = 64, get = { s.name }) { if (it.isNotBlank()) bt.setName(it.trim()) }
            button("bt_discoverable", r(R.string.bt_discoverable)) { bt.setDiscoverable(120) }
        }
        section(r(R.string.tab_phone))
        switch("iphone_pair", r(R.string.phone_iphone_pair), r(R.string.phone_iphone_pair_hint), get = { PhoneInfo.autoPair(app) }) { PhoneInfo.setAutoPair(app, it) }
        section(r(R.string.tab_network))
        switch("iphone_net", r(R.string.net_iphone_auto), r(R.string.net_iphone_auto_hint), get = { UsbNet.iphoneAuto(app) }) { UsbNet.setIphoneAuto(app, it) }
    }

    // --- Android Auto and CarPlay ------------------------------------------------------------------------------------

    private fun projection(
        app: Context,
        r: (Int) -> String,
    ) = page("projection", r(R.string.tab_android_auto) + " / " + r(R.string.tab_carplay), "phone") {
        val aa = AndroidAuto.installed(app)
        if (aa != null) {
            section(r(R.string.tab_android_auto))
            val s = AndroidAuto.settings.value
            switch("aa_night", r(R.string.aa_night_headlights), r(R.string.aa_night_headlights_hint), get = { s.nightFromHeadlights }) { on ->
                AndroidAuto.update(app) { it.copy(nightFromHeadlights = on) }
            }
            if (aa == HeadunitApp.LIBREHU) {
                switch("aa_car_data", r(R.string.aa_car_data), r(R.string.aa_car_data_hint), get = { s.carData }) { on -> AndroidAuto.update(app) { it.copy(carData = on) } }
                switch("aa_keys", r(R.string.aa_keys), r(R.string.aa_keys_hint), get = { s.keys }) { on -> AndroidAuto.update(app) { it.copy(keys = on) } }
            }
            switch("aa_raise", r(R.string.aa_raise_after_reverse), r(R.string.aa_raise_after_reverse_hint), get = { s.raiseAfterReverse }) { on ->
                AndroidAuto.update(app) { it.copy(raiseAfterReverse = on) }
            }
            // Open Headunit's own settings (its automation commands), each by its type.
            section(r(R.string.aa_settings_title))
            val values = runBlocking { withTimeoutOrNull(5_000) { AndroidAuto.readSettings(app).getOrNull() } }
            if (values == null) {
                info("aa_settings_none", r(R.string.aa_settings_empty)) { "" }
            } else {
                for (key in values.keys().asSequence().sorted()) {
                    fun write(v: Any) = runBlocking { withTimeoutOrNull(5_000) { AndroidAuto.writeSettings(app, JSONObject().put(key, v)) } }
                    when (val v = values.opt(key)) {
                        is Boolean -> switch("oh_$key", key, get = { v }) { write(it) }
                        is Int, is Long -> text("oh_$key", key, max = 18, get = { v.toString() }) { t -> t.trim().toLongOrNull()?.let { write(it.toInt()) } }
                        is String -> text("oh_$key", key, max = 256, get = { v }) { write(it) }
                        is JSONArray -> info("oh_$key", key) { (0 until v.length()).joinToString(", ") { v.optString(it) } }
                        else -> info("oh_$key", key) { v.toString() }
                    }
                }
            }
        }
        if (CarPlay.installed(app) == CarPlay.PACKAGE) {
            section(r(R.string.tab_carplay))
            val c = CarPlay.settings.value
            switch("cp_night", r(R.string.aa_night_headlights), r(R.string.cp_night_hint), get = { c.nightFromHeadlights }) { on -> CarPlay.update(app) { it.copy(nightFromHeadlights = on) } }
            switch("cp_open", r(R.string.cp_open_on_iphone), r(R.string.cp_open_on_iphone_hint), get = { c.openOnIphone }) { on -> CarPlay.update(app) { it.copy(openOnIphone = on) } }
            switch("cp_low_latency", r(R.string.video_low_latency), r(R.string.video_low_latency_hint), get = { c.videoLowLatency }) { on ->
                CarPlay.update(app) { it.copy(videoLowLatency = on) }
            }
        }
        if (aa == null && CarPlay.installed(app) == null) info("none", r(R.string.aa_settings_empty)) { "" }
    }

    // --- Display -----------------------------------------------------------------------------------------------------

    private fun display(
        app: Context,
        r: (Int) -> String,
    ) = page("display", r(R.string.tab_display), "light") {
        val display = DisplayController.get(app)
        val s = display.settings.value
        pick(
            "dark",
            r(R.string.display_dark),
            listOf(
                DarkMode.UNCHANGED to r(R.string.display_dark_unchanged),
                DarkMode.LIGHT to r(R.string.display_dark_light),
                DarkMode.DARK to r(R.string.display_dark_dark),
                DarkMode.HEADLIGHTS to r(R.string.display_dark_lights),
            ),
            r(R.string.display_dark_hint),
            get = { s.darkMode },
        ) { m -> display.update { it.copy(darkMode = m) } }
        section(r(R.string.display_brightness))
        switch("dim", r(R.string.display_dim), r(R.string.display_dim_hint), get = { s.dimWithHeadlights }) { on -> display.update { it.copy(dimWithHeadlights = on) } }
        slider("day", r(R.string.display_day), 10..255, display = { "${it * 100 / 255} %" }, get = { s.dayBrightness }) { v -> display.update { it.copy(dayBrightness = v) } }
        slider("night", r(R.string.display_night), 10..255, display = { "${it * 100 / 255} %" }, get = { s.nightBrightness }) { v -> display.update { it.copy(nightBrightness = v) } }
        section(r(R.string.osd_title))
        val osd = StatusOsd.get(app)
        val o = osd.settings.value
        switch("osd", r(R.string.osd_enable), r(R.string.osd_hint), get = { o.enabled }) { on -> osd.update { it.copy(enabled = on) } }
        if (o.enabled) {
            switch("osd_headlights", r(R.string.osd_headlights), get = { o.headlights }) { on -> osd.update { it.copy(headlights = on) } }
            switch("osd_reverse", r(R.string.osd_reverse), get = { o.reverse }) { on -> osd.update { it.copy(reverse = on) } }
            switch("osd_handbrake", r(R.string.osd_handbrake), get = { o.handbrake }) { on -> osd.update { it.copy(handbrake = on) } }
            switch("osd_turn", r(R.string.osd_turn), r(R.string.osd_turn_hint), get = { o.turnSignals }) { on -> osd.update { it.copy(turnSignals = on) } }
            pick("osd_place", r(R.string.osd_title), listOf(true to r(R.string.osd_top), false to r(R.string.osd_bottom)), get = { o.top }) { t -> osd.update { it.copy(top = t) } }
            slider("osd_duration", r(R.string.osd_duration), 2..12, display = { String.format(Locale.ROOT, "%.1f s", it * 0.5) }, get = { o.durationMs / 500 }) { v ->
                osd.update { it.copy(durationMs = v * 500) }
            }
            button("osd_test", r(R.string.osd_test)) { osd.test() }
        }
        overlay(this, app, r, OverlayKind.BRIGHTNESS)
    }

    // --- Button backlight (the panel LED colours stay on the head unit) ----------------------------------------------

    private fun lights(
        app: Context,
        r: (Int) -> String,
    ) = page("lights", r(R.string.tab_lights), "light") {
        val led = FrontLed.get(app)
        val s = led.settings.value
        section(r(R.string.led_backlight_title))
        switch("backlight", r(R.string.led_backlight), r(R.string.led_backlight_hint), get = { s.backlight }) { on -> led.update { it.copy(backlight = on) } }
        if (!s.backlight) return@page
        val out1 = if (s.separateOutputs) 1 else null
        slider("day1", if (s.separateOutputs) r(R.string.led_day_n).format(1) else r(R.string.led_day), 5..100, display = ::pct, get = { s.dayPwm1 }) { v ->
            led.update { it.copy(dayPwm1 = v) }
            led.preview(out1, v)
        }
        slider("night1", if (s.separateOutputs) r(R.string.led_night_n).format(1) else r(R.string.led_night), 5..100, display = ::pct, get = { s.nightPwm1 }) { v ->
            led.update { it.copy(nightPwm1 = v) }
            led.preview(out1, v)
        }
        if (s.separateOutputs) {
            slider("day2", r(R.string.led_day_n).format(2), 5..100, display = ::pct, get = { s.dayPwm2 }) { v ->
                led.update { it.copy(dayPwm2 = v) }
                led.preview(2, v)
            }
            slider("night2", r(R.string.led_night_n).format(2), 5..100, display = ::pct, get = { s.nightPwm2 }) { v ->
                led.update { it.copy(nightPwm2 = v) }
                led.preview(2, v)
            }
        }
        switch("separate", r(R.string.led_separate), r(R.string.led_separate_hint), get = { s.separateOutputs }) { on ->
            led.update { it.copy(separateOutputs = on, dayPwm2 = it.dayPwm1, nightPwm2 = it.nightPwm1) }
        }
    }

    // --- System: standby, boot, watchdog -----------------------------------------------------------------------------

    private fun system(
        app: Context,
        r: (Int) -> String,
    ) = page("system", r(R.string.tab_system), "settings") {
        section(r(R.string.standby_title))
        val standby = Standby.get(app)
        val s = standby.settings.value
        slider("acc_delay", r(R.string.standby_acc_delay), 0..StandbySettings.MAX_ACC_OFF_DELAY_SEC, hint = r(R.string.standby_acc_delay_hint), display = {
            if (it == 0) "0" else "$it s"
        }, get = { s.accOffDelaySec }) { v -> standby.update { it.copy(accOffDelaySec = v) } }
        pick(
            "mode",
            r(R.string.standby_title),
            listOf(StandbyMode.OFF to r(R.string.standby_mode_off), StandbyMode.SLEEP to r(R.string.standby_mode_sleep), StandbyMode.SHUTDOWN to r(R.string.standby_mode_shutdown)),
            r(
                when (s.mode) {
                    StandbyMode.OFF -> R.string.standby_mode_off_hint
                    StandbyMode.SLEEP -> R.string.standby_mode_sleep_hint
                    StandbyMode.SHUTDOWN -> R.string.standby_mode_shutdown_hint
                },
            ),
            get = { s.mode },
        ) { m -> standby.update { it.copy(mode = m) } }
        if (s.mode == StandbyMode.SLEEP) {
            pick("max_hours", r(R.string.standby_max), StandbySettings.HOURS_CHOICES.map { h -> h to if (h % 24 == 0) r(R.string.standby_days).format(h / 24) else "$h h" }, get = {
                s.maxHours
            }) { h -> standby.update { it.copy(maxHours = h) } }
            switch("radios", r(R.string.standby_radios), r(R.string.standby_radios_hint), get = { s.radiosOff }) { on -> standby.update { it.copy(radiosOff = on) } }
        }
        if (s.mode != StandbyMode.OFF) switch("resume_media", r(R.string.standby_media), get = { s.resumeMedia }) { on -> standby.update { it.copy(resumeMedia = on) } }
        section(r(R.string.boot_apps))
        for (a in BootApps.list(app)) {
            switch("boot_${a.pkg}", a.label, a.pkg, get = { a.enabled }) { on -> BootApps.set(a, on) }
        }
        switch("early", r(R.string.boot_early).format(EarlyStart.APPS.joinToString { it.second }), r(R.string.boot_early_hint), get = { EarlyStart.enabled(app) }) {
            EarlyStart.setEnabled(app, it)
        }
        section(r(R.string.watchdog_title))
        val w = ServiceConfig.watchdog(app)
        if (ServiceConfig.KEY_DISARM !in w.fromFile) switch("disarm", r(R.string.watchdog_disarm), r(R.string.watchdog_disarm_hint), get = { w.disarm }) { ServiceConfig.setDisarm(app, it) }
        if (ServiceConfig.KEY_TOAST !in w.fromFile) switch("toast", r(R.string.watchdog_toast), get = { w.toast }) { ServiceConfig.setToast(app, it) }
        button("reset_soc", r(R.string.mcu_reset_soc), confirm = true, hint = r(R.string.mcu_reset_soc_confirm)) { LibreHuService.resetSoc(app) }
        button("safe_shutdown", r(R.string.safe_shutdown), confirm = true) {
            app.startForegroundService(Intent(app, LibreHuService::class.java).setAction(LibreHuService.ACTION_SAFE_SHUTDOWN))
        }
        section(r(R.string.tab_mcu))
        switch("force_start", r(R.string.force_start), r(R.string.mcu_force_hint), get = { LibreHuService.prefs(app).getBoolean(LibreHuService.PREF_FORCE, false) }) { on ->
            LibreHuService.prefs(app).edit().putBoolean(LibreHuService.PREF_FORCE, on).apply()
            LibreHuService.start(app, restart = true)
        }
        button("restart_link", r(R.string.restart)) { LibreHuService.start(app, restart = true) }
    }

    // --- Clock and GPS -----------------------------------------------------------------------------------------------

    private fun time(
        app: Context,
        r: (Int) -> String,
    ) = page("gps", r(R.string.tab_gps), "gps") {
        val time = TimeController.get(app)
        val s = time.settings.value
        switch("from_gps", r(R.string.time_from_gps), r(R.string.time_from_gps_hint), get = { s.fromGps }) { on -> time.update { it.copy(fromGps = on) } }
        if (s.fromGps) {
            pick("interval", r(R.string.time_from_gps), TimeController.INTERVALS.map { m -> m to if (m < 60) r(R.string.time_minutes).format(m) else r(R.string.time_hours).format(m / 60) }, get = {
                s.gpsIntervalMin
            }) { m -> time.update { it.copy(gpsIntervalMin = m) } }
            button("sync", r(R.string.time_clock)) { time.syncFromGps() }
        }
        switch("from_mcu", r(R.string.time_from_mcu), r(R.string.time_from_mcu_hint), get = { s.fromMcu }) { on -> time.update { it.copy(fromMcu = on) } }
        switch("to_mcu", r(R.string.time_to_mcu), r(R.string.time_to_mcu_hint), get = { s.toMcu }) { on -> time.update { it.copy(toMcu = on) } }
    }

    // --- Tweaks: compilation, Zygote, kernel -------------------------------------------------------------------------

    private fun tweaks(
        app: Context,
        r: (Int) -> String,
    ) = page("tweaks", r(R.string.tab_tweaks), "speed") {
        section(r(R.string.aot_title))
        val compiler = AppCompiler.get(app)
        switch("aot_auto", r(R.string.aot_auto), r(R.string.aot_hint), get = { compiler.auto.value }) { compiler.setAuto(it) }
        val apps = compiler.apps.value
        if (apps.isNotEmpty()) {
            val native = apps.count { it.filter == "speed" || it.filter == "everything" }
            info("aot_count", r(R.string.aot_title)) { r(R.string.aot_count).format(native, apps.size) }
        }
        button("aot_now", r(R.string.aot_now)) { compiler.compileAll() }
        section(r(R.string.zyg_title))
        val zp = ZygotePreload
        if (zp.modulesAvailable()) {
            val on = (zp.next() ?: zp.current()) == "1"
            switch("zygote", r(R.string.zyg_switch), r(R.string.zyg_switch_hint) + " " + r(R.string.zyg_reboot), get = { on }) { v -> zp.set(if (v) "1" else "0") }
            button("zygote_rom", r(R.string.zyg_rom)) { zp.set(null) }
        } else {
            info("zygote_none", r(R.string.zyg_no_modules)) { "" }
        }
        val tweaks = Tweaks.get(app)
        val st = tweaks.refresh() ?: tweaks.state.value
        section(r(R.string.tw_title))
        switch("tw_boot", r(R.string.tw_boot), r(R.string.tw_boot_hint), get = { tweaks.settings.value.applyAtBoot }) { on -> tweaks.update { it.copy(applyAtBoot = on) } }
        val boost = org.librehu.service.tweaks.TouchBoost.get(app)
        switch("tw_touch_boost", r(R.string.tw_touch_boost), r(R.string.tw_touch_boost_hint), get = { boost.enabled.value }) { on -> boost.setEnabled(on) }
        button("tw_factory", r(R.string.tw_factory), confirm = true) { tweaks.restoreFactory() }
        fun mhz(khz: Int) = if (khz <= 0) "—" else "${khz / 1000} MHz"
        for (p in st.cpu) {
            section(r(R.string.tw_cpu).format(p.cpus.ifEmpty { p.id }))
            if (p.governors.isNotEmpty()) pick("gov_${p.id}", r(R.string.tw_governor), p.governors.map { it to it }, get = { p.governor }) { g -> tweaks.update { it.copy(governor = it.governor + (p.id to g)) } }
            if (p.freqs.isNotEmpty()) {
                pick("min_${p.id}", r(R.string.tw_min), p.freqs.map { it to mhz(it) }, get = { p.minKhz }) { f -> tweaks.update { it.copy(minKhz = it.minKhz + (p.id to f)) } }
                pick("max_${p.id}", r(R.string.tw_max), p.freqs.map { it to mhz(it) }, get = { p.maxKhz }) { f -> tweaks.update { it.copy(maxKhz = it.maxKhz + (p.id to f)) } }
            }
        }
        if (st.gpuOpps.isNotEmpty()) {
            pick("gpu", r(R.string.tw_gpu), listOf(0 to r(R.string.tw_gpu_auto)) + st.gpuOpps.map { it to mhz(it) }, r(R.string.tw_gpu_hint), get = { st.gpuKeptKhz }) { f ->
                tweaks.update { it.copy(gpuKhz = f) }
            }
        }
        if (st.ioDevice.isNotEmpty()) {
            section(r(R.string.tw_io).format(st.ioDevice))
            pick("io_scheduler", r(R.string.tw_io_scheduler), st.ioSchedulers.map { it to it }, get = { st.ioScheduler }) { v -> tweaks.update { it.copy(ioScheduler = v) } }
            pick("read_ahead", r(R.string.tw_read_ahead), listOf(128, 256, 512, 1024, 2048).map { it to "$it kB" }, get = { st.readAheadKb }) { v -> tweaks.update { it.copy(readAheadKb = v) } }
        }
        section(r(R.string.tw_memory_network))
        if (st.swappiness >= 0) pick("swappiness", r(R.string.tw_swappiness), listOf(0, 20, 40, 60, 80, 100).map { it to "$it" }, get = { st.swappiness }) { v -> tweaks.update { it.copy(swappiness = v) } }
        if (st.tcpAvailable.isNotEmpty()) pick("tcp", r(R.string.tw_tcp), st.tcpAvailable.map { it to it }, get = { st.tcp }) { v -> tweaks.update { it.copy(tcp = v) } }
    }
}
