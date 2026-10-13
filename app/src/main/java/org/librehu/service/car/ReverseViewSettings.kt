package org.librehu.service.car

import org.json.JSONObject

/** How the rear view is shown when the reverse gear is engaged. */
enum class ReverseMode {
    /** LibreHU's own view: the rear camera (Camera2) in a window over everything, with guide lines. */
    BUILT_IN,

    /** Another app is opened (Jancar's DVR, Baony AVM 360…). */
    APP,
}

/** Sound when the reverse gear is engaged. */
enum class EngageSound { NONE, BEEP, DOUBLE, CHIME, CUSTOM }

/** Music while reversing (audio focus asked by LibreHU). */
enum class MediaDuring { KEEP, DUCK, PAUSE }

/**
 * Guide lines: a rectangle on the ground behind the car (its width, [lengthCm] long) drawn as a trapezoid on the
 * image, positions in % of the view. Its 4 corners give the perspective, also used by the trajectory.
 */
data class GuideLines(
    val show: Boolean = true,
    /** Centre of the lines at the bumper (% of the width). */
    val centerX: Int = 50,
    /** Bumper line, 0 m (% of the height). */
    val bottomY: Int = 92,
    /** Far end, [lengthCm] (% of the height). */
    val topY: Int = 48,
    /** Width at the bumper (% of the width). */
    val bottomWidth: Int = 74,
    /** Width at the far end (% of the width). */
    val topWidth: Int = 30,
    val lengthCm: Int = 300,
    /** Distance marks (cm): red up to the first, orange up to the second, green beyond. */
    val mark1Cm: Int = 50,
    val mark2Cm: Int = 100,
    val mark3Cm: Int = 200,
    /** Line thickness (dp). */
    val thickness: Int = 5,
    val opacity: Int = 90,
    /** Trajectory following the steering wheel (steering angle from the CAN box). */
    val dynamic: Boolean = true,
    val invertSteering: Boolean = false,
    val wheelbaseCm: Int = 258,
    /** Steering wheel turns per wheel turn (×1). */
    val steeringRatio: Int = 16,
    val carWidthCm: Int = 172,
) {
    companion object
}

data class ReverseViewSettings(
    /** Camera2 id; empty = automatic (the rear camera of Jancar's DVR, then the first back camera). */
    val cameraId: String = "",
    val mirror: Boolean = false,
    val flip: Boolean = false,
    /** 0, 90, 180, 270. */
    val rotation: Int = 0,
    /** Fill the screen (cropping the image), else the whole image with black bars. */
    val fill: Boolean = true,
    /** Zoom (%). */
    val zoom: Int = 100,
    /** Image shift (% of the view). */
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val lines: GuideLines = GuideLines(),
    val sound: EngageSound = EngageSound.BEEP,
    /** content:// of a sound file, for [EngageSound.CUSTOM]. */
    val soundUri: String = "",
    val soundVolume: Int = 80,
    val media: MediaDuring = MediaDuring.DUCK,
    /** Movement behind the car (camera, image differences). */
    val motion: Boolean = false,
    /** 1 (only big movements) .. 100. */
    val motionSensitivity: Int = 50,
    /** Top of the watched zone (% of the height); the zone goes down to the bottom. */
    val motionTop: Int = 40,
    val motionBeep: Boolean = true,
    /** Parking sensors relayed by the CAN box (Hiworld 0x41), with beeps getting faster. */
    val radar: Boolean = true,
    val radarInvert: Boolean = false,
    val radarBeep: Boolean = true,
    /** Experimental: something getting close, guessed from the bottom of the image (not a distance measure). */
    val approach: Boolean = false,
    /** Stops Jancar's DVR (root) so that the camera is free; it restarts with its app. */
    val stopDvr: Boolean = false,
    /** The view stays this long after the gear is released (ms). */
    val closeDelayMs: Int = 0,
    /** Steering angle and sensor levels shown over the image. */
    val showInfo: Boolean = true,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("camera", cameraId)
            .put("mirror", mirror)
            .put("flip", flip)
            .put("rotation", rotation)
            .put("fill", fill)
            .put("zoom", zoom)
            .put("offsetX", offsetX)
            .put("offsetY", offsetY)
            .put("lines", lines.toJson())
            .put("sound", sound.name)
            .put("soundUri", soundUri)
            .put("soundVolume", soundVolume)
            .put("media", media.name)
            .put("motion", motion)
            .put("motionSensitivity", motionSensitivity)
            .put("motionTop", motionTop)
            .put("motionBeep", motionBeep)
            .put("radar", radar)
            .put("radarInvert", radarInvert)
            .put("radarBeep", radarBeep)
            .put("approach", approach)
            .put("stopDvr", stopDvr)
            .put("closeDelay", closeDelayMs)
            .put("showInfo", showInfo)

    companion object {
        fun fromJson(o: JSONObject): ReverseViewSettings {
            val d = ReverseViewSettings()
            return ReverseViewSettings(
                cameraId = o.optString("camera", d.cameraId),
                mirror = o.optBoolean("mirror", d.mirror),
                flip = o.optBoolean("flip", d.flip),
                rotation = o.optInt("rotation", d.rotation),
                fill = o.optBoolean("fill", d.fill),
                zoom = o.optInt("zoom", d.zoom),
                offsetX = o.optInt("offsetX", d.offsetX),
                offsetY = o.optInt("offsetY", d.offsetY),
                lines = o.optJSONObject("lines")?.let { GuideLines.fromJson(it) } ?: d.lines,
                sound = EngageSound.entries.firstOrNull { it.name == o.optString("sound") } ?: d.sound,
                soundUri = o.optString("soundUri", d.soundUri),
                soundVolume = o.optInt("soundVolume", d.soundVolume),
                media = MediaDuring.entries.firstOrNull { it.name == o.optString("media") } ?: d.media,
                motion = o.optBoolean("motion", d.motion),
                motionSensitivity = o.optInt("motionSensitivity", d.motionSensitivity),
                motionTop = o.optInt("motionTop", d.motionTop),
                motionBeep = o.optBoolean("motionBeep", d.motionBeep),
                radar = o.optBoolean("radar", d.radar),
                radarInvert = o.optBoolean("radarInvert", d.radarInvert),
                radarBeep = o.optBoolean("radarBeep", d.radarBeep),
                approach = o.optBoolean("approach", d.approach),
                stopDvr = o.optBoolean("stopDvr", d.stopDvr),
                closeDelayMs = o.optInt("closeDelay", d.closeDelayMs),
                showInfo = o.optBoolean("showInfo", d.showInfo),
            )
        }
    }
}

fun GuideLines.toJson(): JSONObject =
    JSONObject()
        .put("show", show)
        .put("centerX", centerX)
        .put("bottomY", bottomY)
        .put("topY", topY)
        .put("bottomWidth", bottomWidth)
        .put("topWidth", topWidth)
        .put("length", lengthCm)
        .put("mark1", mark1Cm)
        .put("mark2", mark2Cm)
        .put("mark3", mark3Cm)
        .put("thickness", thickness)
        .put("opacity", opacity)
        .put("dynamic", dynamic)
        .put("invertSteering", invertSteering)
        .put("wheelbase", wheelbaseCm)
        .put("ratio", steeringRatio)
        .put("carWidth", carWidthCm)

fun GuideLines.Companion.fromJson(o: JSONObject): GuideLines {
    val d = GuideLines()
    return GuideLines(
        show = o.optBoolean("show", d.show),
        centerX = o.optInt("centerX", d.centerX),
        bottomY = o.optInt("bottomY", d.bottomY),
        topY = o.optInt("topY", d.topY),
        bottomWidth = o.optInt("bottomWidth", d.bottomWidth),
        topWidth = o.optInt("topWidth", d.topWidth),
        lengthCm = o.optInt("length", d.lengthCm),
        mark1Cm = o.optInt("mark1", d.mark1Cm),
        mark2Cm = o.optInt("mark2", d.mark2Cm),
        mark3Cm = o.optInt("mark3", d.mark3Cm),
        thickness = o.optInt("thickness", d.thickness),
        opacity = o.optInt("opacity", d.opacity),
        dynamic = o.optBoolean("dynamic", d.dynamic),
        invertSteering = o.optBoolean("invertSteering", d.invertSteering),
        wheelbaseCm = o.optInt("wheelbase", d.wheelbaseCm),
        steeringRatio = o.optInt("ratio", d.steeringRatio),
        carWidthCm = o.optInt("carWidth", d.carWidthCm),
    )
}
