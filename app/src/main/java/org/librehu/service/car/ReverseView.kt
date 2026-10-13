package org.librehu.service.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.librehu.service.LibreHuService
import org.librehu.service.R
import org.librehu.service.can.CanMonitor
import org.librehu.service.root.RootShell
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A camera of the head unit, for the settings. */
data class RearCameraInfo(
    val id: String,
    val label: String,
)

/**
 * LibreHU's own rear view: the rear camera (Camera2) full screen in a window over every app ("display over other
 * apps"), with its image settings (mirror, rotation, zoom, shift), the guide lines, the parking sensors of the CAN box,
 * the movement detection and the sounds. Shown by [ReverseCamera] in the [ReverseMode.BUILT_IN] mode, or as a preview
 * from the settings. Does not need ivi-services nor Jancar's DVR app.
 */
class ReverseView private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = LibreHuService.prefs(app)
    private val wm = app.getSystemService(WindowManager::class.java)
    private val cameras = app.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ReverseViewSettings> = _settings.asStateFlow()

    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()

    private val _status = MutableStateFlow("")

    /** Camera in use and its picture size, or why there is no picture. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val sounds = ReverseSounds(app)
    private var root: FrameLayout? = null
    private var texture: TextureView? = null
    private var guide: ReverseGuideView? = null
    private var scope: CoroutineScope? = null
    private var openedId: String? = null
    private var bufferW = 0
    private var bufferH = 0

    // Camera thread.
    private var camThread: HandlerThread? = null
    private var cam: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var generation = 0

    // Movement analysis (camera thread, then main).
    private var previous: FloatArray? = null
    private var lastAnalysis = 0L
    private val lows = ArrayDeque<Pair<Long, Float>>()
    private var lastMotion = 0L
    private var radarLevel = 0
    private var steeringAngle: Double? = null
    private var frames = 0
    private var fps = 0
    private val fpsTick =
        object : Runnable {
            override fun run() {
                fps = frames
                frames = 0
                updateInfo()
                main.postDelayed(this, 1000)
            }
        }
    private var approachLevel = 0

    fun update(transform: (ReverseViewSettings) -> ReverseViewSettings) {
        val old = _settings.value
        val s = transform(old)
        _settings.value = s
        prefs.edit().putString(PREF, s.toJson().toString()).apply()
        main.post {
            val g = guide ?: return@post
            g.settings = s
            sounds.setVolume(s.soundVolume)
            applyTransform()
            if (s.cameraId != old.cameraId || (s.motion || s.approach) != (old.motion || old.approach)) reopen()
        }
        if (s.widgetLive != old.widgetLive) ReverseWidgetLive.get(app).evaluate()
    }

    fun canShow() = Settings.canDrawOverlays(app)

    fun hasCameraPermission() = app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Shows the rear view (any thread); [preview] = from the settings: no music change. */
    fun show(preview: Boolean = false) {
        main.post { attach(preview) }
    }

    fun hide() {
        main.post { detach() }
    }

    /** Opens the view full screen without the reverse gear (widget), without sound nor music change; or closes it. */
    fun toggle() {
        main.post { if (root != null) detach() else attach(preview = false, manual = true) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(
        preview: Boolean,
        manual: Boolean = false,
    ) {
        val s = _settings.value
        guide?.let {
            // The gear engaged during a preview: the real view.
            if (!preview && it.preview) {
                it.preview = false
                root?.let { frame -> runCatching { wm.updateViewLayout(frame, layout(false)) } }
                sounds.engage(s)
                sounds.duckMedia(s.media)
                it.invalidate()
            }
            return
        }
        if (!canShow()) {
            _status.value = app.getString(R.string.rv_no_overlay)
            return
        }
        val frame = FrameLayout(app)
        frame.setBackgroundColor(android.graphics.Color.BLACK)
        val tv = TextureView(app)
        val g = ReverseGuideView(app)
        g.settings = s
        g.preview = preview
        g.onClose = { detach() }
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(g, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tv.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    st: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = open(st)

                override fun onSurfaceTextureSizeChanged(
                    st: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = applyTransform()

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                    frames++
                }
            }
        tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        val lp = layout(preview)
        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            Log.w(TAG, "window: ${e.message}")
            _status.value = app.getString(R.string.rv_no_overlay)
            return
        }
        root = frame
        texture = tv
        guide = g
        _showing.value = true
        ReverseWidgetLive.get(app).evaluate()
        ReverseWidget.refresh(app)
        frames = 0
        main.postDelayed(fpsTick, 1000)
        if (!manual) sounds.engage(s)
        if (!preview && !manual) sounds.duckMedia(s.media)
        val sc = MainScope()
        scope = sc
        sc.launch { CanMonitor.values.collect { onCan(it) } }
    }

    /**
     * Full screen; the preview takes the top right quarter (same proportions as the screen, so the lines set there are
     * the same full screen) and lets the settings be used beside it.
     */
    private fun layout(preview: Boolean): WindowManager.LayoutParams {
        val dm = app.resources.displayMetrics
        return WindowManager
            .LayoutParams(
                if (preview) dm.widthPixels / 2 else WindowManager.LayoutParams.MATCH_PARENT,
                if (preview) dm.heightPixels / 2 else WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (preview) 0 else WindowManager.LayoutParams.FLAG_FULLSCREEN) or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                title = "LibreHU rear view"
            }
    }

    private fun detach() {
        val frame = root ?: return
        closeCamera()
        scope?.cancel()
        scope = null
        sounds.stop()
        runCatching { wm.removeView(frame) }
        root = null
        texture = null
        guide = null
        lows.clear()
        main.removeCallbacks(fpsTick)
        fps = 0
        radarLevel = 0
        approachLevel = 0
        _showing.value = false
        ReverseWidget.refresh(app)
        ReverseWidgetLive.get(app).evaluate()
    }

    // --- Camera ------------------------------------------------------------------------------------------------

    private fun reopen() {
        val st = texture?.surfaceTexture ?: return
        closeCamera()
        open(st)
    }

    private fun open(st: SurfaceTexture) {
        val g = guide ?: return
        if (!hasCameraPermission()) {
            g.message = app.getString(R.string.rv_no_permission)
            _status.value = g.message.orEmpty()
            return
        }
        g.message = app.getString(R.string.rv_opening)
        val s = _settings.value
        val gen = ++generation
        val thread = HandlerThread("reverse-camera").also { it.start() }
        val handler = Handler(thread.looper)
        camThread = thread
        cam = handler
        handler.post {
            // Jancar's DVR records with the rear camera: stopped first when asked (root), the camera being exclusive.
            if (s.stopDvr && RootShell.isAvailable()) RootShell.run("am force-stop ${ReverseCamera.HD2CAM}")
            // The widget's live picture gives the camera back first.
            ReverseWidgetLive.get(app).release()
            tryOpen(cameraCandidates(s), 0, st, gen, null)
        }
    }

    /** The chosen camera, else the rear one of Jancar's config ("10"), the other usual ids, then the back cameras. */
    /**
     * Camera format last read or set on the Rear view page ([org.librehu.service.atc.CameraFormat] index: 0 CVBS,
     * 1..4 AHD), -1 when unknown. Chooses Jancar's camera in automatic mode.
     */
    var cameraFormat: Int
        get() = prefs.getInt(PREF_FORMAT, -1)
        set(value) = prefs.edit().putInt(PREF_FORMAT, value).apply()

    internal fun cameraCandidates(s: ReverseViewSettings): List<String> {
        if (s.cameraId.isNotEmpty()) return listOf(s.cameraId)
        val listed = runCatching { cameras.cameraIdList.toList() }.getOrDefault(emptyList())
        val back =
            listed.filter {
                runCatching { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.getOrNull() !=
                    CameraCharacteristics.LENS_FACING_FRONT
            }
        // Jancar's rule (BackCarCommon): CVBS rear camera "3"; AHD "2", "10" in its dual-recording DVR mode, "8" with
        // the 9963 chip.
        val auto = if (cameraFormat == 0) listOf(CVBS_ID) + AHD_IDS else AHD_IDS + CVBS_ID
        return (auto + back + listed).distinct()
    }

    @SuppressLint("MissingPermission") // checked in open()
    private fun tryOpen(
        ids: List<String>,
        index: Int,
        st: SurfaceTexture,
        gen: Int,
        lastError: String?,
    ) {
        if (gen != generation) return
        if (index >= ids.size) {
            message(app.getString(R.string.rv_no_camera, lastError ?: "-"))
            return
        }
        val id = ids[index]
        val callback =
            object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) {
                        d.close()
                        return
                    }
                    device = d
                    startSession(d, st, gen)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device === d) {
                        device = null
                        message(app.getString(R.string.rv_camera_lost))
                    }
                }

                override fun onError(
                    d: CameraDevice,
                    error: Int,
                ) {
                    d.close()
                    if (gen != generation) return
                    if (device === d) {
                        device = null
                        message(app.getString(R.string.rv_camera_error, error))
                    } else if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                        error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                    ) {
                        // Busy (the DVR records with it): not replaced by another camera.
                        message(app.getString(R.string.rv_camera_busy, id))
                    } else {
                        tryOpen(ids, index + 1, st, gen, "$id: $error")
                    }
                }
            }
        try {
            cameras.openCamera(id, callback, cam)
        } catch (e: Exception) {
            tryOpen(ids, index + 1, st, gen, "$id: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List): the only one before Android 9's SessionConfiguration is common.
    private fun startSession(
        d: CameraDevice,
        st: SurfaceTexture,
        gen: Int,
        analyse: Boolean = _settings.value.motion || _settings.value.approach,
    ) {
        val map =
            runCatching { cameras.getCameraCharacteristics(d.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
                .getOrNull()
        val size = pickPreview(map?.getOutputSizes(SurfaceTexture::class.java))
        st.setDefaultBufferSize(size.width, size.height)
        val small = if (analyse) pickAnalysis(map?.getOutputSizes(ImageFormat.YUV_420_888)) else null
        val r =
            small?.let {
                ImageReader.newInstance(it.width, it.height, ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ ir -> analyse(ir) }, cam)
                }
            }
        reader = r
        val surface = Surface(st)
        main.post {
            openedId = d.id
            bufferW = size.width
            bufferH = size.height
            previous = null
            applyTransform()
            guide?.message = null
            _status.value = app.getString(R.string.rv_status, d.id, size.width, size.height)
        }
        try {
            d.createCaptureSession(
                listOfNotNull(surface, r?.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) {
                            s.close()
                            return
                        }
                        session = s
                        try {
                            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(surface)
                            r?.let { request.addTarget(it.surface) }
                            s.setRepeatingRequest(request.build(), null, cam)
                        } catch (e: Exception) {
                            message(app.getString(R.string.rv_camera_error_text, e.message ?: e.javaClass.simpleName))
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen != generation) return
                        if (r != null) {
                            // Some AHD cameras take a single stream: the preview alone, no movement detection.
                            r.close()
                            reader = null
                            startSession(d, st, gen, analyse = false)
                        } else {
                            message(app.getString(R.string.rv_camera_error_text, "session"))
                        }
                    }
                },
                cam,
            )
        } catch (e: Exception) {
            message(app.getString(R.string.rv_camera_error_text, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun closeCamera() {
        generation++
        val handler = cam ?: return
        val thread = camThread
        cam = null
        camThread = null
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            session = null
            device = null
            reader = null
            thread?.quitSafely()
        }
        openedId = null
    }

    private fun message(text: String) {
        Log.w(TAG, text)
        main.post {
            guide?.message = text
            _status.value = text
        }
    }

    // --- Picture placement ---------------------------------------------------------------------------------------

    /**
     * TextureView stretches the camera picture over the view: the matrix brings it back to its proportions (filling
     * the screen or whole), then rotation, mirror, flip, zoom and shift.
     */
    private fun applyTransform() {
        val tv = texture ?: return
        val g = guide ?: return
        val s = _settings.value
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (w <= 0 || h <= 0) return
        val m = Matrix()
        if (bufferW > 0 && bufferH > 0) {
            val rot = ((s.rotation % 360 + 360) % 360 / 90) * 90
            val turned = rot % 180 != 0
            val iw = (if (turned) bufferH else bufferW).toFloat()
            val ih = (if (turned) bufferW else bufferH).toFloat()
            val base = if (s.fill) max(w / iw, h / ih) else min(w / iw, h / ih)
            val scale = base * s.zoom.coerceIn(50, 400) / 100f
            val cx = w / 2
            val cy = h / 2
            m.setScale(bufferW * scale / w, bufferH * scale / h, cx, cy)
            if (rot != 0) m.postRotate(rot.toFloat(), cx, cy)
            if (s.mirror) m.postScale(-1f, 1f, cx, cy)
            if (s.flip) m.postScale(1f, -1f, cx, cy)
            m.postTranslate(s.offsetX / 100f * w, s.offsetY / 100f * h)
        }
        tv.setTransform(m)
        g.imageMatrix.set(m)
        g.zone = g.zoneMask(w, h, s.motionTop)
        g.invalidate()
    }

    // --- Movement ------------------------------------------------------------------------------------------------

    /** Camera thread: mean brightness of each cell compared with the previous picture. */
    private fun analyse(ir: ImageReader) {
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        image.use {
            val now = SystemClock.uptimeMillis()
            if (now - lastAnalysis < ANALYSIS_MS) return
            lastAnalysis = now
            val plane = it.planes[0]
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            val iw = it.width
            val ih = it.height
            val w = ReverseGuideView.MOTION_W
            val h = ReverseGuideView.MOTION_H
            val cells = FloatArray(w * h)
            for (cy in 0 until h) {
                for (cx in 0 until w) {
                    var sum = 0
                    for (sy in 0 until 4) {
                        val y = ((cy + (sy + 0.5f) / 4) * ih / h).toInt().coerceIn(0, ih - 1)
                        for (sx in 0 until 4) {
                            val x = ((cx + (sx + 0.5f) / 4) * iw / w).toInt().coerceIn(0, iw - 1)
                            sum += buf.get(y * rs + x * ps).toInt() and 0xFF
                        }
                    }
                    cells[cy * w + cx] = sum / 16f
                }
            }
            val prev = previous
            previous = cells
            if (prev == null) return
            val diff = FloatArray(cells.size) { i -> cells[i] - prev[i] }
            // The same change everywhere is the exposure: taken out (median).
            val median = diff.sortedArray()[diff.size / 2]
            val sensitivity = _settings.value.motionSensitivity.coerceIn(1, 100)
            val threshold = 3f + (100 - sensitivity) * 0.27f
            val moving = BooleanArray(cells.size) { i -> abs(diff[i] - median) > threshold }
            // Most of the picture changing: the car itself moves (or the light): nothing to report.
            if (moving.count { m -> m } > cells.size / 2) moving.fill(false)
            main.post { onMotion(moving) }
        }
    }

    private fun onMotion(moving: BooleanArray) {
        val g = guide ?: return
        val s = _settings.value
        val zone = g.zone ?: return
        val now = SystemClock.uptimeMillis()
        val watched = moving.indices.count { moving[it] && zone[it] }
        val alert = s.motion && watched >= 2
        g.motion = if (s.motion || s.approach) moving else null
        g.alert = alert
        if (alert) {
            lastMotion = now
            if (s.motionBeep) sounds.alert()
        }
        if (s.approach) {
            // Experimental: the lowest moving point going down the screen = something getting closer to the bumper.
            val low = g.lowest(moving, zone, g.width.toFloat(), g.height.toFloat())
            if (low >= 0) lows.addLast(now to low)
            while (lows.isNotEmpty() && now - lows.first().first > APPROACH_WINDOW_MS) lows.removeFirst()
            val first = lows.firstOrNull()?.second
            val last = lows.lastOrNull()?.second
            approachLevel =
                if (lows.size >= 3 && first != null && last != null && last - first > 0.06f && last > 0.55f) {
                    when {
                        last > 0.9f -> 3
                        last > 0.75f -> 2
                        else -> 1
                    }
                } else {
                    0
                }
        } else {
            approachLevel = 0
        }
        g.invalidate()
        beeps()
    }

    // --- CAN box -------------------------------------------------------------------------------------------------

    private fun onCan(values: Map<String, org.librehu.core.can.CanValue>) {
        val g = guide ?: return
        val s = _settings.value
        val angle = values[KEY_STEERING]?.number
        g.steering = angle
        val raw = RADAR_KEYS.map { values[it]?.number }
        val levels =
            if (raw.all { it == null }) {
                null
            } else {
                IntArray(4) { i ->
                    val v = (raw[i] ?: 0.0).toInt().coerceIn(0, 4)
                    if (s.radarInvert) (4 - v) else v
                }
            }
        g.radar = levels
        radarLevel = if (s.radar && s.radarBeep && levels != null) levels.max() else 0
        steeringAngle = angle
        updateInfo()
        beeps()
    }

    /** Steering angle, camera, picture size and frames per second actually received. */
    private fun updateInfo() {
        val g = guide ?: return
        g.info =
            listOfNotNull(
                steeringAngle?.let { app.getString(R.string.rv_info_steering, String.format(Locale.ROOT, "%.0f", it)) },
                openedId?.let { app.getString(R.string.rv_info_camera, it, bufferW, bufferH) },
                if (openedId != null) app.getString(R.string.rv_info_fps, fps) else null,
            ).joinToString("  ·  ")
        g.invalidate()
    }

    private fun beeps() = sounds.setLevel(max(radarLevel, approachLevel))

    // --- Settings ------------------------------------------------------------------------------------------------

    /** Cameras of the head unit: Android's list and the hidden ids that answer (Jancar's rear AHD camera is "10"). */
    fun cameraList(): List<RearCameraInfo> {
        val listed = runCatching { cameras.cameraIdList.toList() }.getOrDefault(emptyList())
        return (listed + (0..15).map { it.toString() })
            .distinct()
            .mapNotNull { id ->
                val c = runCatching { cameras.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
                val facing =
                    when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> app.getString(R.string.rv_facing_front)
                        CameraCharacteristics.LENS_FACING_BACK -> app.getString(R.string.rv_facing_back)
                        else -> app.getString(R.string.rv_facing_external)
                    }
                val best =
                    c
                        .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(SurfaceTexture::class.java)
                        ?.maxByOrNull { it.width * it.height }
                val hidden = if (id in listed) "" else " · " + app.getString(R.string.rv_hidden)
                val role = ROLES[id]?.let { app.getString(it) + " · " }.orEmpty()
                RearCameraInfo(id, "$role$facing${best?.let { " · ${it.width}×${it.height}" }.orEmpty()}$hidden")
            }
    }

    /** Preview size of the first camera that answers (the one the view would open), without opening it. */
    internal fun previewSize(s: ReverseViewSettings): Size? {
        for (id in cameraCandidates(s)) {
            val c = runCatching { cameras.getCameraCharacteristics(id) }.getOrNull() ?: continue
            return pickPreview(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java))
        }
        return null
    }

    private fun pickPreview(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(1280, 720)
        val fitting = sizes.filter { it.width <= 1920 && it.height <= 1080 }.ifEmpty { sizes.toList() }
        return fitting.minByOrNull { abs(it.width * it.height - 1280 * 720) }!!
    }

    private fun pickAnalysis(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes.filter { it.width >= 240 }.minByOrNull { it.width * it.height } ?: sizes.minByOrNull { it.width * it.height }
    }

    private fun load(): ReverseViewSettings =
        prefs.getString(PREF, null)?.let { runCatching { ReverseViewSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: ReverseViewSettings()

    companion object {
        private const val TAG = "LibreHU-Reverse"
        private const val PREF = "reverse_view"
        private const val ANALYSIS_MS = 120L
        private const val APPROACH_WINDOW_MS = 1200L
        private const val KEY_STEERING = "11.steering_angle"
        private val RADAR_KEYS = listOf("41.rear_left", "41.rear_center_left", "41.rear_center_right", "41.rear_right")

        private const val PREF_FORMAT = "reverse_camera_format"

        /** Jancar's camera ids (BackCarCommon of its apps, `[CameraIndex]` of ivi-config.ini). */
        private const val CVBS_ID = "3"
        private val AHD_IDS = listOf("2", "10", "8")
        private val ROLES =
            mapOf(
                "2" to R.string.rv_role_ahd,
                "10" to R.string.rv_role_ahd_dvr,
                "8" to R.string.rv_role_ahd_9963,
                "3" to R.string.rv_role_cvbs,
                "9" to R.string.rv_role_front_9963,
                "11" to R.string.rv_role_front,
                "6" to R.string.rv_role_left,
                "7" to R.string.rv_role_right,
                "0" to R.string.rv_role_web,
            )

        @Volatile
        private var instance: ReverseView? = null

        fun get(context: Context): ReverseView =
            instance ?: synchronized(this) {
                instance ?: ReverseView(context.applicationContext).also { instance = it }
            }
    }
}
