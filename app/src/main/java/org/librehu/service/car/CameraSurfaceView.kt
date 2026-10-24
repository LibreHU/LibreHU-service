package org.librehu.service.car

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera picture in a SurfaceView instead of a TextureView. A TextureView draws each camera frame through the window's
 * own rendering: the whole rear view window (guide lines included) is composed again 25 to 30 times a second on the
 * UI's render thread. Here the frame goes from the camera to a [SurfaceTexture] drawn on this view's own GL thread
 * into its own layer, composed by SurfaceFlinger; the window above (lines, buttons) is only drawn when it changes
 * ([developer.android.com, TextureView](https://developer.android.com/reference/android/view/TextureView),
 * [source.android.com, SurfaceView and GLSurfaceView](https://source.android.com/docs/core/graphics/arch-sv-glsv)).
 *
 * Same use as a TextureView for the rear view: [surfaceTextureListener], [surfaceTexture], [setTransform] with the
 * same matrix (picture stretched over the view, then moved in view pixels: rotation, mirror, zoom, shift), drawn
 * here with GLES 2.
 */
class CameraSurfaceView(
    context: Context,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    var surfaceTextureListener: TextureView.SurfaceTextureListener? = null

    /** The texture the camera draws into, between the listener's available and destroyed. */
    var surfaceTexture: SurfaceTexture? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var gl: Handler? = null

    // GL thread.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var texture: SurfaceTexture? = null
    private var viewW = 0
    private var viewH = 0
    private var hasFrame = false
    private val texMatrix = FloatArray(16)
    private val positions: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val coords: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            // Top left, top right, bottom left, bottom right (GL texture coordinates start bottom left).
            put(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            position(0)
        }

    /** The view's corners moved by the matrix, in view pixels; written on the UI thread, read on the GL one. */
    private val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var transform: Matrix? = null

    init {
        holder.addCallback(this)
    }

    /** Same meaning as [TextureView.setTransform]. */
    fun setTransform(m: Matrix?) {
        transform = m?.let { Matrix(it) }
        updateCorners()
        gl?.post { draw() }
    }

    private fun updateCorners() {
        val w = width.toFloat()
        val h = height.toFloat()
        val pts = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
        transform?.mapPoints(pts)
        synchronized(corners) {
            for (i in 0 until 4) {
                corners[i * 2] = if (w > 0) pts[i * 2] / w else 0f
                corners[i * 2 + 1] = if (h > 0) pts[i * 2 + 1] / h else 0f
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val t = HandlerThread("camera-gl").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        gl = h
        h.post {
            val st = runCatching { setUp(holder) }.onFailure { Log.w(TAG, "GL: ${it.message}") }.getOrNull() ?: return@post
            st.setOnFrameAvailableListener({
                hasFrame = true
                draw()
                main.post { surfaceTexture?.let { s -> surfaceTextureListener?.onSurfaceTextureUpdated(s) } }
            }, h)
            main.post {
                if (gl !== h) return@post
                surfaceTexture = st
                updateCorners()
                surfaceTextureListener?.onSurfaceTextureAvailable(st, width, height)
            }
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        updateCorners()
        gl?.post {
            viewW = width
            viewH = height
            draw()
        }
        surfaceTexture?.let { surfaceTextureListener?.onSurfaceTextureSizeChanged(it, width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val st = surfaceTexture
        surfaceTexture = null
        val release = st == null || surfaceTextureListener?.onSurfaceTextureDestroyed(st) != false
        val h = gl ?: return
        gl = null
        // The EGL surface must be gone before this returns: the window surface is destroyed right after.
        val done = CountDownLatch(1)
        h.post {
            tearDown(if (release) st else null)
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread?.quitSafely()
        thread = null
    }

    private fun setUp(holder: SurfaceHolder): SurfaceTexture {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attrs =
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], holder.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent" }
        program = link(VERTEX, FRAGMENT)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        hasFrame = false
        return SurfaceTexture(textureId).also { texture = it }
    }

    /** Black, then the last camera frame where the matrix puts it (latest frame first when one arrived). */
    private fun draw() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || viewW <= 0 || viewH <= 0) return
        val st = texture ?: return
        if (hasFrame) {
            runCatching { st.updateTexImage() }
            st.getTransformMatrix(texMatrix)
        }
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (hasFrame) {
            synchronized(corners) {
                positions.position(0)
                for (i in 0 until 4) {
                    positions.put(corners[i * 2] * 2 - 1)
                    positions.put(1 - corners[i * 2 + 1] * 2)
                }
                positions.position(0)
            }
            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aTex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aTex)
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uImage"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun tearDown(st: SurfaceTexture?) {
        st?.release()
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        textureId = 0
        texture = null
        hasFrame = false
    }

    private fun link(
        vertex: String,
        fragment: String,
    ): Int {
        fun shader(
            type: Int,
            src: String,
        ): Int =
            GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
            }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it)
        }
    }

    private companion object {
        const val TAG = "LibreHU-CameraView"

        val VERTEX =
            """
            attribute vec2 aPos;
            attribute vec2 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTex = (uTex * vec4(aTex, 0.0, 1.0)).xy;
            }
            """.trimIndent()

        val FRAGMENT =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uImage;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(uImage, vTex);
            }
            """.trimIndent()
    }
}
