package org.librehu.service.car

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.librehu.core.unit.BoardGpio
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.hw.SocGpio
import org.librehu.service.root.RootShell

/**
 * Position of the analog video switch in front of the CVBS decoder (Android camera "3"), as ivi-services sets it
 * (`Platform_AutoChips_8257_Base.platformSelectInput`, GPIO 167 = SW1, GPIO 165 = SW2).
 */
enum class CvbsPosition(
    val sw1: Boolean,
    val sw2: Boolean,
    val label: Int,
) {
    /** Jancar's AV input (and TV): `AVInManager.open(1)` of its AV app. */
    AV(false, false, R.string.cvbs_pos_av),

    /** Never set by Jancar: maybe a second input on some boards. */
    UNUSED(false, true, R.string.cvbs_pos_unused),

    /** Jancar's front camera (`IVIAVIn.Id.FRONT`). */
    FRONT(true, false, R.string.cvbs_pos_front),

    /** Jancar's CVBS rear camera (`IVIAVIn.Id.REAR`), set before the reverse view. */
    REAR(true, true, R.string.cvbs_pos_rear),
}

data class CvbsSettings(
    /** Switch position of the "CVBS IN 1" RCA (to find with the test buttons of the CVBS IN screen). */
    val in1: CvbsPosition = CvbsPosition.AV,
    val in2: CvbsPosition = CvbsPosition.FRONT,
    /** Camera of the CVBS decoder ("3" in Jancar's `[CameraIndex]`). */
    val cameraId: String = CvbsInput.CAMERA_ID,
    /** Video only with the handbrake on, like Jancar's AV app (`isMediaVideoPermit`). */
    val handbrakeOnly: Boolean = true,
    /** Rear view on the CVBS camera: switch set to [reverse] first, as Jancar's AV app does on reverse. */
    val switchForReverse: Boolean = true,
    val reverse: CvbsPosition = CvbsPosition.REAR,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("in1", in1.name)
            .put("in2", in2.name)
            .put("camera", cameraId)
            .put("handbrake", handbrakeOnly)
            .put("reverse_switch", switchForReverse)
            .put("reverse", reverse.name)

    companion object {
        fun fromJson(o: JSONObject): CvbsSettings {
            val d = CvbsSettings()

            fun pos(
                key: String,
                def: CvbsPosition,
            ) = runCatching { CvbsPosition.valueOf(o.optString(key, def.name)) }.getOrDefault(def)
            return CvbsSettings(
                in1 = pos("in1", d.in1),
                in2 = pos("in2", d.in2),
                cameraId = o.optString("camera", d.cameraId).ifEmpty { d.cameraId },
                handbrakeOnly = o.optBoolean("handbrake", d.handbrakeOnly),
                switchForReverse = o.optBoolean("reverse_switch", d.switchForReverse),
                reverse = pos("reverse", d.reverse),
            )
        }
    }
}

/** Rear video output (VIDEO OUT RCA), as Jancar's "Rear output settings" stores it. */
data class VideoOutSettings(
    /** Applied by the service at start (the properties do not survive a restart); off = left as the system sets it. */
    val apply: Boolean = false,
    /** True = PAL, false = NTSC (`hwc.rear_disp_fmt` P / N). */
    val pal: Boolean = true,
    /** Black borders 0..50 (`hwc.rear_disp_lb_*`): top, bottom, left, right. */
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("apply", apply)
            .put("pal", pal)
            .put("top", top)
            .put("bottom", bottom)
            .put("left", left)
            .put("right", right)

    companion object {
        const val MARGIN_MAX = 50

        fun fromJson(o: JSONObject): VideoOutSettings {
            val d = VideoOutSettings()
            return VideoOutSettings(
                apply = o.optBoolean("apply", d.apply),
                pal = o.optBoolean("pal", d.pal),
                top = o.optInt("top", d.top),
                bottom = o.optInt("bottom", d.bottom),
                left = o.optInt("left", d.left),
                right = o.optInt("right", d.right),
            )
        }
    }
}

/**
 * The two CVBS inputs (CVBS IN 1 / 2 RCAs, video only) and the rear video output (VIDEO OUT) without Jancar's apps.
 *
 * Inputs: one CVBS decoder (camera "3") behind an analog switch; Jancar's AV app (`com.jancar.avin`) sets the switch
 * through ivi-services (`setAndroidCameraOpenPrepared` → `selectInput`), opens camera "3", and gives it back to the
 * rear view on reverse when the rear camera is CVBS too. [select] writes the same two GPIOs.
 *
 * Output: Autochips' "Rear display" (metazone 65550, read at start-up) turns the output on; Jancar's settings then
 * start `com.jancar.usbdisplay/.service.CaptureService` and set the `hwc.rear_disp_*` properties (format, borders).
 */
object CvbsInput {
    private const val TAG = "LibreHU-CVBS"
    private const val PREF = "cvbs_input"
    private const val PREF_OUT = "video_out"
    const val CAMERA_ID = "3"

    /** Jancar's rear output app and service (`IVISystem.PACKAGE_REAR_DISPLAY` / `SERVICE_REAR_DISPLAY`). */
    const val USB_DISPLAY = "com.jancar.usbdisplay"
    private const val CAPTURE_SERVICE = "$USB_DISPLAY/.service.CaptureService"

    /** `JACSettings.Global.KEY_REAR_DISPLAY`, written by Jancar's settings on the AC8257. */
    private const val KEY_REAR_DISPLAY = "key_rear_display"

    private val _settings = MutableStateFlow(CvbsSettings())
    val settings: StateFlow<CvbsSettings> = _settings
    private val _videoOut = MutableStateFlow(VideoOutSettings())
    val videoOut: StateFlow<VideoOutSettings> = _videoOut
    private val _position = MutableStateFlow<CvbsPosition?>(null)

    /** Last position written (null = never written since the service started). */
    val position: StateFlow<CvbsPosition?> = _position
    private var loaded = false

    /** The CVBS IN screen is showing an input (it holds camera "3"). */
    @Volatile
    var playing = false

    fun get(context: Context): StateFlow<CvbsSettings> {
        load(context)
        return settings
    }

    fun videoOut(context: Context): StateFlow<VideoOutSettings> {
        load(context)
        return videoOut
    }

    fun update(
        context: Context,
        transform: (CvbsSettings) -> CvbsSettings,
    ) {
        load(context)
        val s = transform(_settings.value)
        _settings.value = s
        LibreHuService
            .prefs(context)
            .edit()
            .putString(PREF, s.toJson().toString())
            .apply()
    }

    fun updateVideoOut(
        context: Context,
        transform: (VideoOutSettings) -> VideoOutSettings,
    ) {
        load(context)
        val s = transform(_videoOut.value)
        _videoOut.value = s
        LibreHuService
            .prefs(context)
            .edit()
            .putString(PREF_OUT, s.toJson().toString())
            .apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = LibreHuService.prefs(context)
        p.getString(PREF, null)?.let { j ->
            runCatching { CvbsSettings.fromJson(JSONObject(j)) }.getOrNull()?.let { _settings.value = it }
        }
        p.getString(PREF_OUT, null)?.let { j ->
            runCatching { VideoOutSettings.fromJson(JSONObject(j)) }.getOrNull()?.let { _videoOut.value = it }
        }
    }

    /** Sets the video switch (any thread); false when a GPIO write failed. */
    fun select(position: CvbsPosition): Boolean {
        val ok =
            runCatching {
                SocGpio.set(BoardGpio.VIDEO_SW1, position.sw1) and SocGpio.set(BoardGpio.VIDEO_SW2, position.sw2)
            }.getOrDefault(false)
        if (ok) _position.value = position else Log.w(TAG, "select $position: GPIO write failed")
        return ok
    }

    /** Before the rear view opens camera [cameraId]: the rear camera's position when it is the CVBS one. */
    fun beforeReverse(
        context: Context,
        cameraId: String,
    ) {
        val s = get(context).value
        if (s.switchForReverse && cameraId == s.cameraId) select(s.reverse)
    }

    // --- Video output --------------------------------------------------------------------------------------------

    /**
     * What Jancar's "Rear output settings" do (root): the properties, `key_rear_display`, then Jancar's capture service
     * when it is installed (off then on, as its Save button does). Null = done, else what failed.
     */
    fun applyVideoOut(
        context: Context,
        on: Boolean,
    ): String? {
        if (!RootShell.isAvailable()) return context.getString(R.string.cvbs_out_no_root)
        val s = videoOut(context).value
        val props =
            listOf(
                "hwc.rear_disp_fmt" to if (s.pal) "P" else "N",
                "hwc.rear_disp_lb_y_top" to s.top.toString(),
                "hwc.rear_disp_lb_y_bottom" to s.bottom.toString(),
                "hwc.rear_disp_lb_x_left" to s.left.toString(),
                "hwc.rear_disp_lb_x_right" to s.right.toString(),
            )
        val script =
            buildString {
                for ((k, v) in props) append("setprop $k $v; ")
                append("settings put global $KEY_REAR_DISPLAY ${if (on) 1 else 0}")
            }
        if (RootShell.run(script) == null) return "setprop"
        if (hasCaptureService(context)) {
            val start = "am startservice -n $CAPTURE_SERVICE -a change_rear_display --ez change"
            if (on) RootShell.run("$start false; sleep 1; $start true") else RootShell.run("$start false")
        }
        return null
    }

    fun hasCaptureService(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(USB_DISPLAY, 0) }.isSuccess

    /** At service start: the saved output settings, when asked (the `hwc.*` properties are lost on restart). */
    fun applyAtStart(context: Context) {
        val s = videoOut(context).value
        if (!s.apply) return
        Thread({ applyVideoOut(context, true)?.let { Log.w(TAG, "video out: $it") } }, "video-out").start()
    }

    /** `hwc.external_display_*`, set by the composer when the rear output exists / is open. */
    fun composerState(): Pair<String, String> {
        fun prop(name: String) =
            runCatching {
                Class
                    .forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java, String::class.java)
                    .invoke(null, name, "") as String
            }.getOrDefault("")
        return prop("hwc.external_display_enable") to prop("hwc.external_display_open")
    }
}
