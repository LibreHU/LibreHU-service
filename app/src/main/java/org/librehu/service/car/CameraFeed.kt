package org.librehu.service.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max

/**
 * One more camera of the rear view (side camera, second rear camera, USB camera…) in its own [CameraSurfaceView]
 * (above the main camera, for the picture in picture): Camera2, or a USB camera ([UvcStream]) for a `uvc:` id.
 * Picture filling its box, mirrored when asked; its name in a corner.
 * The head unit's camera service must accept several cameras open at once (else this one shows its error).
 */
class CameraFeed(
    context: Context,
    private val camera: ExtraCamera,
    label: String,
) {
    private val app = context.applicationContext
    private val cameras = app.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    val view = FrameLayout(app)
    private val texture = CameraSurfaceView(app).apply { setZOrderMediaOverlay(true) }
    private val caption = TextView(app)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var uvc: UvcStream? = null
    private var bufferW = 0
    private var bufferH = 0

    @Volatile
    private var closed = false

    init {
        view.setBackgroundColor(Color.BLACK)
        view.addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        caption.text = label
        caption.setTextColor(Color.WHITE)
        caption.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        caption.textSize = 14f
        caption.setPadding(12, 8, 12, 8)
        view.addView(
            caption,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        texture.surfaceTextureListener =
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
                ) = transform()

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    close()
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> transform() }
    }

    private fun error(text: String) {
        Log.w(TAG, "${camera.id}: $text")
        main.post { caption.text = caption.text.toString().substringBefore(" — ") + " — " + text }
    }

    @SuppressLint("MissingPermission") // the rear view checks it before showing
    private fun open(st: SurfaceTexture) {
        if (camera.id.startsWith(UvcCameras.PREFIX)) {
            val d = UvcCameras.resolve(camera.id) ?: return error("USB ?")
            uvc =
                UvcStream(d, st, { w, h -> sized(w, h) }, {}, { error(it) }).also { it.start() }
            return
        }
        val t = HandlerThread("rear-extra-${camera.id}").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        try {
            cameras.openCamera(
                camera.id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (closed) {
                            d.close()
                            return
                        }
                        device = d
                        start(d, st, h)
                    }

                    override fun onDisconnected(d: CameraDevice) = d.close()

                    override fun onError(
                        d: CameraDevice,
                        e: Int,
                    ) {
                        d.close()
                        error("error $e")
                    }
                },
                h,
            )
        } catch (e: Exception) {
            error(e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun start(
        d: CameraDevice,
        st: SurfaceTexture,
        h: Handler,
    ) {
        val sizes =
            runCatching { cameras.getCameraCharacteristics(d.id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
                .getOrNull()
                ?.getOutputSizes(SurfaceTexture::class.java)
        // A small box: up to 720p is enough.
        val size =
            sizes?.filter { it.width <= 1280 }?.maxByOrNull { it.width * it.height }
                ?: sizes?.minByOrNull { it.width * it.height }
        if (size != null) {
            st.setDefaultBufferSize(size.width, size.height)
            sized(size.width, size.height)
        }
        val surface = Surface(st)
        try {
            d.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (closed) {
                            s.close()
                            return
                        }
                        session = s
                        runCatching {
                            val r = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            r.addTarget(surface)
                            s.setRepeatingRequest(r.build(), null, h)
                        }.onFailure { error(it.message ?: "request") }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) = error("session")
                },
                h,
            )
        } catch (e: Exception) {
            error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sized(
        w: Int,
        h: Int,
    ) {
        main.post {
            bufferW = w
            bufferH = h
            transform()
        }
    }

    /** Fills the box, keeping the proportions; mirrored when asked. */
    private fun transform() {
        val w = texture.width.toFloat()
        val h = texture.height.toFloat()
        if (w <= 0 || h <= 0 || bufferW <= 0 || bufferH <= 0) return
        val scale = max(w / bufferW, h / bufferH)
        val m = Matrix()
        m.setScale(bufferW * scale / w, bufferH * scale / h, w / 2, h / 2)
        if (camera.mirror) m.postScale(-1f, 1f, w / 2, h / 2)
        texture.setTransform(m)
    }

    fun close() {
        if (closed) return
        closed = true
        uvc?.stop()
        uvc = null
        val h = handler ?: return
        h.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            session = null
            device = null
            thread?.quitSafely()
        }
        handler = null
    }

    private companion object {
        const val TAG = "LibreHU-RearExtra"
    }
}
