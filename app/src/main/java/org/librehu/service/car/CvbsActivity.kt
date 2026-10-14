package org.librehu.service.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.librehu.service.MainActivity
import org.librehu.service.R
import org.librehu.service.ServiceState
import org.librehu.service.ui.CarColors
import org.librehu.service.ui.CarTheme
import org.librehu.service.ui.Pill
import kotlin.math.abs

/**
 * CVBS IN 1 / 2 full screen (video only: these RCAs have no sound), like Jancar's AV app: the video switch on the
 * input, then the CVBS decoder's camera. The camera is given back on reverse (the rear view uses it when the rear
 * camera is CVBS) and while the handbrake is off when "handbrake only" is set. [EXTRA_TEST] shows the four switch
 * positions, to find which one each RCA is on.
 */
class CvbsActivity : ComponentActivity() {
    private lateinit var cameras: CameraManager
    private var texture: SurfaceTexture? = null
    private var camThread: HandlerThread? = null
    private var cam: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var generation = 0
    private var resumed = false

    /** What is shown: an input (1, 2) or, in test mode, a raw switch position. */
    private val input = mutableStateOf(1)
    private val testPosition = mutableStateOf<CvbsPosition?>(null)
    private val message = mutableStateOf<String?>(null)
    private val fill = mutableStateOf(true)
    private val test = mutableStateOf(false)
    private var blocked: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameras = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        readIntent(intent)
        setContent { CarTheme { Screen() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceState.vehicle.collect { v ->
                    val s = CvbsInput.get(this@CvbsActivity).value
                    val reason =
                        when {
                            v.reverse -> getString(R.string.cvbs_blocked_reverse)
                            // Without the MCU link the handbrake is unknown: not blocked.
                            s.handbrakeOnly && v.mcuOnline && !v.handbrake -> getString(R.string.cvbs_blocked_handbrake)
                            else -> null
                        }
                    if (reason != blocked) {
                        blocked = reason
                        restart()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
        restart()
    }

    private fun readIntent(intent: Intent?) {
        input.value = if (intent?.getIntExtra(EXTRA_INPUT, 1) == 2) 2 else 1
        test.value = intent?.getBooleanExtra(EXTRA_TEST, false) == true
        testPosition.value = if (test.value) CvbsInput.get(this).value.in1 else null
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        CvbsInput.playing = true
        restart()
    }

    override fun onPause() {
        resumed = false
        closeCamera()
        CvbsInput.playing = false
        // Back to the rear camera's input: the rear view widget shows it again.
        val s = CvbsInput.get(this).value
        if (s.switchForReverse) Thread({ CvbsInput.select(s.reverse) }, "cvbs-switch").start()
        super.onPause()
    }

    override fun onDestroy() {
        closeCamera()
        super.onDestroy()
    }

    private fun position(): CvbsPosition {
        val s = CvbsInput.get(this).value
        return testPosition.value ?: if (input.value == 2) s.in2 else s.in1
    }

    // --- Camera ----------------------------------------------------------------------------------------------------

    private fun restart() {
        closeCamera()
        val st = texture ?: return
        if (!resumed) return
        blocked?.let {
            message.value = it
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            message.value = getString(R.string.rv_no_permission)
            return
        }
        message.value = getString(R.string.rv_opening)
        val gen = ++generation
        val id = CvbsInput.get(this).value.cameraId
        val pos = position()
        val thread = HandlerThread("cvbs-camera").also { it.start() }
        val handler = Handler(thread.looper)
        camThread = thread
        cam = handler
        handler.post {
            if (!CvbsInput.select(pos)) post(gen) { message.value = getString(R.string.cvbs_gpio_failed) }
            open(id, st, gen)
        }
    }

    private fun post(
        gen: Int,
        block: () -> Unit,
    ) = runOnUiThread { if (gen == generation) block() }

    @SuppressLint("MissingPermission") // checked in restart()
    private fun open(
        id: String,
        st: SurfaceTexture,
        gen: Int,
    ) {
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
                    post(gen) { message.value = getString(R.string.rv_camera_lost) }
                }

                override fun onError(
                    d: CameraDevice,
                    error: Int,
                ) {
                    d.close()
                    post(gen) {
                        message.value =
                            if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                                error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                            ) {
                                getString(R.string.rv_camera_busy, id)
                            } else {
                                getString(R.string.rv_camera_error, error)
                            }
                    }
                }
            }
        try {
            cameras.openCamera(id, callback, cam)
        } catch (e: Exception) {
            post(gen) { message.value = getString(R.string.rv_no_camera, "$id: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List): Android 9.
    private fun startSession(
        d: CameraDevice,
        st: SurfaceTexture,
        gen: Int,
    ) {
        val sizes =
            runCatching {
                cameras
                    .getCameraCharacteristics(d.id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
            }.getOrNull()
        val size = pickSize(sizes)
        st.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(st)
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
                        runCatching {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
                            s.setRepeatingRequest(req.build(), null, cam)
                        }
                        post(gen) { message.value = null }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        post(gen) { message.value = getString(R.string.rv_camera_error_text, "session") }
                    }
                },
                cam,
            )
        } catch (e: Exception) {
            post(gen) { message.value = getString(R.string.rv_camera_error_text, e.message ?: e.javaClass.simpleName) }
        }
    }

    /** CVBS: 720×576 (PAL) or 720×480 (NTSC) when listed, else the size nearest to PAL. */
    private fun pickSize(sizes: Array<Size>?): Size {
        if (sizes.isNullOrEmpty()) return Size(720, 576)
        return sizes.minByOrNull { abs(it.width * it.height - 720 * 576) }!!
    }

    private fun closeCamera() {
        generation++
        val handler = cam ?: return
        val thread = camThread
        cam = null
        camThread = null
        val s = session
        val d = device
        session = null
        device = null
        handler.post {
            runCatching { s?.close() }
            runCatching { d?.close() }
            thread?.quitSafely()
        }
    }

    // --- Screen ----------------------------------------------------------------------------------------------------

    @Composable
    private fun Screen() {
        val settings by CvbsInput.get(this).collectAsState()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { c ->
                    TextureView(c).apply {
                        surfaceTextureListener =
                            object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    st: SurfaceTexture,
                                    w: Int,
                                    h: Int,
                                ) {
                                    texture = st
                                    restart()
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: SurfaceTexture,
                                    w: Int,
                                    h: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    closeCamera()
                                    texture = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                    }
                },
                modifier = if (fill.value) Modifier.fillMaxSize() else Modifier.align(Alignment.Center).aspectRatio(4f / 3f),
            )
            message.value?.let {
                Text(
                    it,
                    color = Color.White,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(CarColors.Surface.copy(alpha = 0.85f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (test.value) {
                    for (p in CvbsPosition.entries) {
                        Pill(stringResource(p.label), selected = testPosition.value == p) {
                            testPosition.value = p
                            restart()
                        }
                    }
                } else {
                    for (i in 1..2) {
                        val p = if (i == 1) settings.in1 else settings.in2
                        Pill(stringResource(R.string.cvbs_in_n, i) + " · " + stringResource(p.label), selected = input.value == i) {
                            input.value = i
                            restart()
                        }
                    }
                }
                Pill(stringResource(if (fill.value) R.string.cvbs_fit_43 else R.string.cvbs_fit_fill), icon = Icons.Default.AspectRatio) {
                    fill.value = !fill.value
                }
                Pill(stringResource(R.string.cvbs_settings), icon = Icons.Default.Settings) {
                    startActivity(
                        Intent(this@CvbsActivity, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_TAB, "VIDEO")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    )
                }
                Pill(stringResource(R.string.cvbs_close), icon = Icons.Default.Close) { finish() }
            }
        }
    }

    companion object {
        /** 1 = CVBS IN 1, 2 = CVBS IN 2. */
        const val EXTRA_INPUT = "input"

        /** Shows the four switch positions instead of the two inputs. */
        const val EXTRA_TEST = "test"

        fun open(
            context: Context,
            input: Int,
            test: Boolean = false,
        ) {
            context.startActivity(
                Intent(context, CvbsActivity::class.java)
                    .putExtra(EXTRA_INPUT, input)
                    .putExtra(EXTRA_TEST, test)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
