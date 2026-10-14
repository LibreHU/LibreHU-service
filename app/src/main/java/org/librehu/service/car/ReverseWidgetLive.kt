package org.librehu.service.car

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.RemoteViews
import org.librehu.service.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Live picture of the rear camera in the home screen widget, two ways:
 * - **Stream** ([attachSurface]): LibreHU Launcher shows its own view in place of the widget and hands its surface
 *   over (RearPreviewService); the camera draws straight into it, at full rate.
 * - **Pictures**, for any launcher: an app widget cannot show a camera stream (its RemoteViews have no surface), so
 *   small frames are read from the camera (ImageReader, YUV), turned into bitmaps and sent to the widget,
 *   [ReverseViewSettings.widgetFps] per second, while the widget can be seen: LibreHU Launcher says when its home
 *   screen shows ([ACTION_HOME_VISIBLE]), or in [WidgetLive.ALWAYS] while the screen is on.
 * Never while the full rear view is open, which takes the camera ([release]).
 */
class ReverseWidgetLive private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val cameras = app.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val view by lazy { ReverseView.get(app) }

    private var homeVisible = false
    private var screenOn = app.getSystemService(PowerManager::class.java).isInteractive
    private var running = false

    /** Surface of LibreHU Launcher's view, and how to tell it the state (main thread). */
    private var target: Surface? = null
    private var client: Messenger? = null
    private var streaming = false

    // Camera thread.
    private var thread: HandlerThread? = null
    private var cam: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @Volatile
    private var closed: CountDownLatch? = null

    @Volatile
    private var generation = 0
    private var lastPush = 0L
    private var shown = false

    /** Last picture sent, kept for the widget's full updates. */
    @Volatile
    var lastFrame: Bitmap? = null
        private set

    private val retry = Runnable { evaluate() }

    init {
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    screenOn = intent.action == Intent.ACTION_SCREEN_ON
                    evaluate()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
    }

    /** LibreHU Launcher's home screen shown or left. */
    fun setHomeVisible(visible: Boolean) {
        main.post {
            homeVisible = visible
            evaluate()
        }
    }

    /** Starts or stops the frames as the conditions say (any thread). */
    fun evaluate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { evaluate() }
            return
        }
        main.removeCallbacks(retry)
        val s = view.settings.value
        val allowed =
            s.widgetLive != WidgetLive.OFF &&
                screenOn &&
                !view.showing.value &&
                app.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val stream = allowed && target != null
        val pictures = allowed && !stream && (homeVisible || s.widgetLive == WidgetLive.ALWAYS) && widgetIds().isNotEmpty()
        if (running && (streaming != stream || !(stream || pictures))) stop()
        if (!running && (stream || pictures)) start(s, stream)
        if (target != null && !stream) tell(if (view.showing.value) STATE_PAUSED else STATE_OFF, "")
    }

    /** LibreHU Launcher's view is on screen: the camera draws into [surface]; [messenger] gets the state. */
    fun attachSurface(
        surface: Surface,
        messenger: Messenger?,
    ) {
        main.post {
            if (running && streaming) stop()
            target = surface
            client = messenger
            runCatching { messenger?.binder?.linkToDeath({ main.post { if (client === messenger) detachSurface() } }, 0) }
            evaluate()
        }
    }

    /** The launcher's view is gone (or its surface): back to pictures if they are wanted. */
    fun detachSurface() {
        main.post {
            if (target == null) return@post
            target = null
            client = null
            if (running && streaming) stop()
            evaluate()
        }
    }

    /** State to the launcher's view ([STATE_LIVE], [STATE_PAUSED]…) with a reason. */
    private fun tell(
        state: Int,
        text: String,
    ) {
        val m = client ?: return
        runCatching {
            m.send(
                Message.obtain(null, RearPreviewService.MSG_STATE, state, 0).apply {
                    data =
                        Bundle().apply { putString("text", text) }
                },
            )
        }
    }

    /** Frees the camera for the full rear view, waiting (camera thread of the caller) until it is closed. */
    fun release(timeoutMs: Long = 1500) {
        val latch = closed
        main.post { if (running) stop() }
        latch?.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun widgetIds(): IntArray =
        AppWidgetManager.getInstance(app)?.getAppWidgetIds(ComponentName(app, ReverseWidget::class.java)) ?: IntArray(0)

    private fun start(
        s: ReverseViewSettings,
        stream: Boolean,
    ) {
        running = true
        streaming = stream
        val gen = ++generation
        val t = HandlerThread("widget-camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        cam = h
        // A camera just closed (switch between pictures and stream) is free only once it says so.
        val previous = closed
        closed = CountDownLatch(1)
        h.post {
            previous?.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            tryOpen(view.cameraCandidates(s), 0, gen)
        }
    }

    private fun stop() {
        running = false
        generation++
        main.removeCallbacks(retry)
        val h = cam ?: return
        val t = thread
        val latch = closed
        cam = null
        thread = null
        h.post {
            runCatching { session?.close() }
            runCatching { reader?.close() }
            val d = device
            session = null
            reader = null
            device = null
            if (d == null || runCatching { d.close() }.isFailure) {
                latch?.countDown()
                t?.quitSafely()
            } else {
                // onClosed ends the thread; this in case it never comes.
                h.postDelayed({
                    latch?.countDown()
                    t?.quitSafely()
                }, CLOSE_TIMEOUT_MS)
            }
        }
    }

    /** Camera lost or busy: tried again later if still wanted. */
    private fun fail(
        gen: Int,
        why: String,
        delayMs: Long,
    ) {
        Log.w(TAG, why)
        main.post {
            if (gen != generation) return@post
            val wasStream = streaming
            stop()
            if (wasStream) tell(STATE_ERROR, why)
            main.postDelayed(retry, delayMs)
        }
    }

    @SuppressLint("MissingPermission") // checked in evaluate()
    private fun tryOpen(
        ids: List<String>,
        index: Int,
        gen: Int,
    ) {
        if (gen != generation) return
        if (index >= ids.size) return fail(gen, "no camera", RETRY_MS)
        val id = ids[index]
        val latch = closed
        val callback =
            object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (gen != generation) {
                        d.close()
                        return
                    }
                    device = d
                    startSession(d, gen)
                }

                override fun onClosed(d: CameraDevice) {
                    // Closed by stop() (not a candidate that failed): the camera is free.
                    if (gen != generation) {
                        latch?.countDown()
                        Looper.myLooper()?.quitSafely()
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    fail(gen, "camera $id taken", RETRY_SHORT_MS)
                }

                override fun onError(
                    d: CameraDevice,
                    error: Int,
                ) {
                    d.close()
                    when {
                        gen != generation -> {}

                        device === d -> {
                            fail(gen, "camera $id error $error", RETRY_MS)
                        }

                        error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                            error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> {
                            fail(gen, "camera $id busy", RETRY_MS)
                        }

                        else -> {
                            tryOpen(ids, index + 1, gen)
                        }
                    }
                }
            }
        // Not while the CVBS IN screen shows an input: it holds the same camera and switch.
        if (!CvbsInput.playing) CvbsInput.beforeReverse(app, id)
        try {
            cameras.openCamera(id, callback, cam)
        } catch (e: Exception) {
            tryOpen(ids, index + 1, gen)
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List), as in ReverseView.
    private fun startSession(
        d: CameraDevice,
        gen: Int,
    ) {
        if (streaming) return startStream(d, gen)
        val sizes =
            runCatching {
                cameras
                    .getCameraCharacteristics(d.id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888)
            }.getOrNull()
        val size = pickSize(sizes) ?: return fail(gen, "camera ${d.id}: no YUV output", RETRY_MS)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ ir -> onImage(ir, gen) }, cam)
        reader = r
        try {
            d.createCaptureSession(
                listOf(r.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) {
                            s.close()
                            return
                        }
                        session = s
                        try {
                            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            request.addTarget(r.surface)
                            s.setRepeatingRequest(request.build(), null, cam)
                        } catch (e: Exception) {
                            fail(gen, "request: ${e.message}", RETRY_MS)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fail(gen, "session", RETRY_MS)
                    }
                },
                cam,
            )
        } catch (e: Exception) {
            fail(gen, "session: ${e.message}", RETRY_MS)
        }
    }

    /** The camera draws into the launcher's surface (its buffer size set by the launcher from [ReverseView.previewSize]). */
    @Suppress("DEPRECATION")
    private fun startStream(
        d: CameraDevice,
        gen: Int,
    ) {
        val surface = target ?: return fail(gen, "no surface", RETRY_SHORT_MS)
        try {
            d.createCaptureSession(
                listOf(surface),
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
                            s.setRepeatingRequest(request.build(), null, cam)
                            main.post { if (gen == generation) tell(STATE_LIVE, d.id) }
                        } catch (e: Exception) {
                            dropSurface(gen, "request: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        dropSurface(gen, "session")
                    }
                },
                cam,
            )
        } catch (e: Exception) {
            dropSurface(gen, "session: ${e.message}")
        }
    }

    /** The launcher's surface does not work (gone, refused by the camera): pictures instead, until it attaches again. */
    private fun dropSurface(
        gen: Int,
        why: String,
    ) {
        Log.w(TAG, "stream: $why")
        main.post {
            if (gen != generation) return@post
            tell(STATE_ERROR, why)
            stop()
            target = null
            client = null
            evaluate()
        }
    }

    private fun onImage(
        ir: ImageReader,
        gen: Int,
    ) {
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (gen != generation) return
            val s = view.settings.value
            val now = SystemClock.elapsedRealtime()
            if (now - lastPush < 1000L / s.widgetFps.coerceIn(1, 30)) return
            lastPush = now
            val bitmap = orient(toBitmap(image), s)
            lastFrame = bitmap
            push(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** The picture to the widget; the first one also hides the camera icon. */
    private fun push(bitmap: Bitmap) {
        val ids = widgetIds()
        if (ids.isEmpty()) {
            main.post { evaluate() }
            return
        }
        val rv = RemoteViews(app.packageName, R.layout.widget_reverse)
        rv.setImageViewBitmap(R.id.reverse_live, bitmap)
        if (!shown) {
            liveLook(rv)
            shown = true
        }
        AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids, rv)
    }

    /** Picture shown, camera icon hidden, the label at the bottom over a dark band. */
    fun liveLook(rv: RemoteViews) {
        rv.setViewVisibility(R.id.reverse_live, View.VISIBLE)
        rv.setViewVisibility(R.id.reverse_icon, View.GONE)
        rv.setInt(R.id.reverse_content, "setGravity", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        rv.setInt(R.id.reverse_label, "setBackgroundColor", 0x99000000.toInt())
        rv.setTextColor(R.id.reverse_label, 0xFFFFFFFF.toInt())
    }

    /** Called by the widget's full updates, which redraw it from its layout. */
    fun onFullUpdate() {
        shown = false
    }

    /** YUV 420 to ARGB (BT.601, full range), every [step]th pixel, so that the widget gets about [TARGET_W] wide. */
    private fun toBitmap(image: Image): Bitmap {
        val w = image.width
        val h = image.height
        val step = (w / TARGET_W).coerceAtLeast(1)
        val ow = w / step
        val oh = h / step
        val yp = image.planes[0]
        val up = image.planes[1]
        val vp = image.planes[2]
        val y = yp.buffer
        val u = up.buffer
        val v = vp.buffer
        val out = IntArray(ow * oh)
        var i = 0
        for (row in 0 until oh) {
            val sy = row * step
            val yRow = sy * yp.rowStride
            val uvRow = (sy / 2) * up.rowStride
            val vRow = (sy / 2) * vp.rowStride
            for (col in 0 until ow) {
                val sx = col * step
                val yy = (y.get(yRow + sx * yp.pixelStride).toInt() and 0xFF)
                val uu = (u.get(uvRow + (sx / 2) * up.pixelStride).toInt() and 0xFF) - 128
                val vv = (v.get(vRow + (sx / 2) * vp.pixelStride).toInt() and 0xFF) - 128
                val r = (yy + ((91881 * vv) shr 16)).coerceIn(0, 255)
                val g = (yy - ((22554 * uu + 46802 * vv) shr 16)).coerceIn(0, 255)
                val b = (yy + ((116130 * uu) shr 16)).coerceIn(0, 255)
                out[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** Mirror, flip and rotation of the rear view settings. */
    private fun orient(
        b: Bitmap,
        s: ReverseViewSettings,
    ): Bitmap {
        if (!s.mirror && !s.flip && s.rotation % 360 == 0) return b
        val m = Matrix()
        m.postScale(if (s.mirror) -1f else 1f, if (s.flip) -1f else 1f)
        m.postRotate(s.rotation.toFloat())
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Smallest YUV size at least [TARGET_W] wide (the frames are made smaller anyway). */
    private fun pickSize(sizes: Array<Size>?): Size? {
        if (sizes.isNullOrEmpty()) return null
        return sizes.filter { it.width >= TARGET_W }.minByOrNull { it.width * it.height } ?: sizes.maxByOrNull { it.width * it.height }
    }

    companion object {
        private const val TAG = "LibreHU-WidgetLive"

        /** States sent to LibreHU Launcher's view (MSG_STATE arg1). */
        const val STATE_OFF = 0
        const val STATE_LIVE = 1
        const val STATE_PAUSED = 2
        const val STATE_ERROR = 3

        /** Sent by LibreHU Launcher to this package, extra [EXTRA_VISIBLE]. */
        const val ACTION_HOME_VISIBLE = "org.librehu.action.HOME_VISIBLE"
        const val EXTRA_VISIBLE = "visible"

        private const val TARGET_W = 400
        private const val RETRY_MS = 15_000L
        private const val RETRY_SHORT_MS = 3_000L
        private const val CLOSE_TIMEOUT_MS = 2_000L

        @Volatile
        private var instance: ReverseWidgetLive? = null

        fun get(context: Context): ReverseWidgetLive =
            instance ?: synchronized(this) {
                instance ?: ReverseWidgetLive(context.applicationContext).also { instance = it }
            }
    }
}
