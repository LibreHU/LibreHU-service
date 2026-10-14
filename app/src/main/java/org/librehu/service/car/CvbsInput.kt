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

/**
 * The two CVBS inputs (CVBS IN 1 / 2 RCAs, video only) without Jancar's apps (the VIDEO OUT is [VideoOut]).
 *
 * Inputs: one CVBS decoder (camera "3") behind an analog switch; Jancar's AV app (`com.jancar.avin`) sets the switch
 * through ivi-services (`setAndroidCameraOpenPrepared` → `selectInput`), opens camera "3", and gives it back to the
 * rear view on reverse when the rear camera is CVBS too. [select] writes the same two GPIOs.
 */
object CvbsInput {
    private const val TAG = "LibreHU-CVBS"
    private const val PREF = "cvbs_input"
    const val CAMERA_ID = "3"

    private val _settings = MutableStateFlow(CvbsSettings())
    val settings: StateFlow<CvbsSettings> = _settings
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

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = LibreHuService.prefs(context)
        p.getString(PREF, null)?.let { j ->
            runCatching { CvbsSettings.fromJson(JSONObject(j)) }.getOrNull()?.let { _settings.value = it }
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
}
