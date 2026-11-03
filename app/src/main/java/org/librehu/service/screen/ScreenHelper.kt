package org.librehu.service.screen

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream

/**
 * Root helper of the screen sharing ([ScreenShare]), run by app_process as root (`CLASSPATH=<LibreHU apk> app_process
 * /system/bin org.librehu.service.screen.ScreenHelper <max size> <bit rate>`): mirrors the head unit's screen into the
 * hardware H.264 encoder and plays the phone's touches and keys. Same way as scrcpy on Android 9
 * (github.com/Genymobile/scrcpy, Apache 2.0): a SurfaceControl display showing the main display's layer stack into
 * the encoder's input surface, and InputManager.injectInputEvent, which root may call.
 *
 * stdout: packets `kind (1 byte) length (4) pts (8, µs) data`: `I` first (text "<video w> <video h> <screen w>
 * <screen h>"), then `C` (SPS and PPS), `K` (key frame), `P` (other frame), H.264 in Annex B (start codes).
 * stdin: lines `d|m|u <id> <x> <y>[ <id> <x> <y>…]` (touch down / move / up, x and y from 0 to 1 of the screen), `c`
 * (touch cancelled), `k <key code>`, `s` (key frame now), `q`; the end of stdin stops it too. Errors on stderr.
 */
object ScreenHelper {
    private const val MIME = "video/avc"
    private const val I_FRAME_S = 10

    /** A frame repeated after 100 ms of a still screen: the phone shows the last change at once. */
    private const val REPEAT_US = 100_000L
    private const val MAX_FPS = 30f

    @Volatile
    private var running = true

    @JvmStatic
    fun main(args: Array<String>) {
        val max = args.getOrNull(0)?.toIntOrNull()?.coerceIn(240, 1920) ?: 1280
        val bitrate = args.getOrNull(1)?.toIntOrNull()?.coerceIn(250_000, 16_000_000) ?: 4_000_000
        val code =
            try {
                share(max, bitrate)
                0
            } catch (e: Throwable) {
                System.err.println("error=${e.javaClass.simpleName}: ${e.message}")
                1
            }
        System.exit(code)
    }

    /** Main display: logical size (rotation included) and layer stack. */
    private class Screen(
        val width: Int,
        val height: Int,
        val layerStack: Int,
    )

    private fun screen(): Screen {
        val dmg =
            Class
                .forName("android.hardware.display.DisplayManagerGlobal")
                .getDeclaredMethod("getInstance")
                .invoke(null)!!
        val info = dmg.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dmg, 0)!!
        val c = info.javaClass
        return Screen(c.getDeclaredField("logicalWidth").getInt(info), c.getDeclaredField("logicalHeight").getInt(info), c.getDeclaredField("layerStack").getInt(info))
    }

    /** Video size: the screen's within [max], multiples of 8 (what the encoders take). */
    private fun fit(
        s: Screen,
        max: Int,
    ): Pair<Int, Int> {
        val scale = minOf(1.0, max.toDouble() / maxOf(s.width, s.height))
        fun r8(v: Double) = maxOf(8, (v.toInt() + 4) and 7.inv())
        return r8(s.width * scale) to r8(s.height * scale)
    }

    private fun share(
        max: Int,
        bitrate: Int,
    ) {
        val s = screen()
        val (w, h) = fit(s, max)
        val out = DataOutputStream(BufferedOutputStream(FileOutputStream(FileDescriptor.out), 1 shl 16))
        val codec = MediaCodec.createEncoderByType(MIME)
        var surface: Surface? = null
        var display: IBinder? = null
        try {
            codec.configure(format(w, h, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            display = mirror(surface, s, w, h)
            codec.start()
            packet(out, 'I', 0, "$w $h ${s.width} ${s.height}".toByteArray())
            Thread({ control(s, codec) }, "screen-control").apply {
                isDaemon = true
                start()
            }
            drain(codec, out)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            display?.let { d -> runCatching { surfaceControl().getMethod("destroyDisplay", IBinder::class.java).invoke(null, d) } }
            surface?.release()
        }
    }

    private fun format(
        w: Int,
        h: Int,
        bitrate: Int,
    ): MediaFormat =
        MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            // Needed to configure; the real rate follows the screen's changes.
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_S)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_US)
            // Real-time priority; one frame out as soon as one is in; at most 30 frames a second (private key that
            // Android 9 already reads, scrcpy issue 488).
            setInteger("priority", 0)
            setInteger("latency", 1)
            setFloat("max-fps-to-encoder", MAX_FPS)
        }

    private fun surfaceControl(): Class<*> = Class.forName("android.view.SurfaceControl")

    /** A display of SurfaceFlinger showing the main display's layer stack, scaled into [surface]. */
    private fun mirror(
        surface: Surface,
        s: Screen,
        w: Int,
        h: Int,
    ): IBinder {
        val sc = surfaceControl()
        // Secure: windows marked FLAG_SECURE are shown too (root may create such a display up to Android 11).
        val display = sc.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType).invoke(null, "librehu-screen", true) as IBinder
        sc.getMethod("openTransaction").invoke(null)
        try {
            sc.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, display, surface)
            sc
                .getMethod("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java)
                .invoke(null, display, 0, Rect(0, 0, s.width, s.height), Rect(0, 0, w, h))
            sc.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType).invoke(null, display, s.layerStack)
        } finally {
            sc.getMethod("closeTransaction").invoke(null)
        }
        return display
    }

    private fun drain(
        codec: MediaCodec,
        out: DataOutputStream,
    ) {
        val info = MediaCodec.BufferInfo()
        var bytes = ByteArray(1 shl 16)
        while (running) {
            val i = codec.dequeueOutputBuffer(info, 100_000)
            if (i < 0) continue
            try {
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                if (info.size > 0) {
                    val b = codec.getOutputBuffer(i) ?: continue
                    if (bytes.size < info.size) bytes = ByteArray(info.size + (info.size shr 2))
                    b.position(info.offset)
                    b.get(bytes, 0, info.size)
                    val kind =
                        when {
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 -> 'C'
                            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 -> 'K'
                            else -> 'P'
                        }
                    packet(out, kind, info.presentationTimeUs, bytes, info.size)
                }
            } finally {
                codec.releaseOutputBuffer(i, false)
            }
        }
    }

    private fun packet(
        out: DataOutputStream,
        kind: Char,
        pts: Long,
        data: ByteArray,
        size: Int = data.size,
    ) {
        out.writeByte(kind.code)
        out.writeInt(size)
        out.writeLong(pts)
        out.write(data, 0, size)
        out.flush()
    }

    // --- Touches and keys --------------------------------------------------------------------------------------

    private val inputManager: Any by lazy {
        android.hardware.input.InputManager::class.java
            .getDeclaredMethod("getInstance")
            .invoke(null)!!
    }

    private val inject by lazy { android.hardware.input.InputManager::class.java.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType) }

    private fun inject(e: InputEvent) {
        // 0: asynchronous (the phone does not wait for the app that receives it).
        runCatching { inject.invoke(inputManager, e, 0) }.onFailure { System.err.println("inject: ${it.cause?.message ?: it.message}") }
    }

    /** Fingers down now, in their order of arrival (= pointer index), with the phone's id and our pointer id. */
    private class Finger(
        val phoneId: Int,
        val id: Int,
        var x: Float,
        var y: Float,
    )

    private val fingers = ArrayList<Finger>()
    private var downTime = 0L

    private fun control(
        s: Screen,
        codec: MediaCodec,
    ) {
        try {
            val reader = System.`in`.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                val f = line.trim().split(' ')
                when (f[0]) {
                    "d", "m", "u" -> touch(f[0], f.drop(1), s)
                    "c" -> cancel()
                    "k" -> f.getOrNull(1)?.toIntOrNull()?.let { key(it) }
                    "s" -> runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                    "q" -> break
                }
            }
        } catch (e: Exception) {
            System.err.println("control: ${e.message}")
        }
        running = false
    }

    private fun touch(
        action: String,
        args: List<String>,
        s: Screen,
    ) {
        var changed: Finger? = null
        for (k in 0 until args.size / 3) {
            val phoneId = args[3 * k].toIntOrNull() ?: return
            val x = (args[3 * k + 1].toFloatOrNull() ?: return).coerceIn(0f, 1f) * (s.width - 1)
            val y = (args[3 * k + 2].toFloatOrNull() ?: return).coerceIn(0f, 1f) * (s.height - 1)
            var f = fingers.firstOrNull { it.phoneId == phoneId }
            if (f == null) {
                // A move or a lift of a finger we never saw down: a down first.
                if (fingers.size >= MAX_FINGERS) return
                val id = (0 until MAX_FINGERS).first { n -> fingers.none { it.id == n } }
                f = Finger(phoneId, id, x, y)
                fingers += f
                send(if (fingers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, fingers.size - 1)
                if (action == "d") continue
            }
            f.x = x
            f.y = y
            changed = f
        }
        val f = changed ?: return
        when (action) {
            "m" -> send(MotionEvent.ACTION_MOVE, 0)
            "u" -> {
                val index = fingers.indexOf(f)
                send(if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, index)
                fingers.remove(f)
            }
            else -> {}
        }
    }

    private fun cancel() {
        if (fingers.isEmpty()) return
        send(MotionEvent.ACTION_CANCEL, 0)
        fingers.clear()
    }

    private fun send(
        action: Int,
        index: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props =
            Array(fingers.size) { n ->
                MotionEvent.PointerProperties().apply {
                    id = fingers[n].id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val up = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        val coords =
            Array(fingers.size) { n ->
                MotionEvent.PointerCoords().apply {
                    x = fingers[n].x
                    y = fingers[n].y
                    pressure = if (up) 0f else 1f
                    size = 1f
                }
            }
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val e = MotionEvent.obtain(downTime, now, a, fingers.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject(e)
        e.recycle()
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, now, a, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
    }

    private const val MAX_FINGERS = 10
}
