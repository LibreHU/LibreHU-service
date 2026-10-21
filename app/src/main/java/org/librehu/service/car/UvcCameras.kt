package org.librehu.service.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import org.librehu.service.hw.NativeUvc
import org.librehu.service.root.RootShell
import java.io.File

/** A USB camera: [id] = `uvc:<USB port>` (stable while it stays on the same port), its V4L2 node and name. */
data class UvcDevice(
    val id: String,
    val node: String,
    val name: String,
    val port: String,
)

/**
 * USB (UVC) cameras as V4L2 devices (`/sys/class/video4linux`, the uvcvideo driver of the kernel): listed for the
 * settings, opened by [UvcStream]. The node is made readable by root when Android's permissions or SELinux keep it
 * for the camera service (chmod, then an allow rule for this app's own SELinux domain with the root solution's tool).
 */
object UvcCameras {
    private const val TAG = "LibreHU-UVC"
    const val PREFIX = "uvc:"

    fun list(): List<UvcDevice> =
        File("/sys/class/video4linux")
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith("video") }
            .sortedBy { it.name.removePrefix("video").toIntOrNull() ?: 99 }
            .mapNotNull { dir ->
                val device = runCatching { File(dir, "device").canonicalFile }.getOrNull() ?: return@mapNotNull null
                if (!device.path.contains("/usb")) return@mapNotNull null
                // uvcvideo can also make metadata nodes (index 1): only the capture one.
                val index = runCatching { File(dir, "index").readText().trim().toInt() }.getOrDefault(0)
                if (index != 0) return@mapNotNull null
                val name = runCatching { File(dir, "name").readText().trim() }.getOrDefault(dir.name)
                UvcDevice(PREFIX + device.name, "/dev/${dir.name}", name, device.name)
            }

    fun resolve(id: String): UvcDevice? = list().firstOrNull { it.id == id } ?: list().takeIf { id == PREFIX }?.firstOrNull()

    /** Lets this app open [node]: nothing when it already can, else root (chmod, SELinux rule). */
    fun grantAccess(node: String) {
        val f = File(node)
        if (f.canRead() && f.canWrite()) return
        if (!RootShell.isAvailable()) return
        val domain =
            runCatching { File("/proc/self/attr/current").readText().trim().trimEnd('\u0000').split(':').getOrNull(2) }
                .getOrNull() ?: "priv_app"
        val rule = "allow $domain video_device chr_file { open read write ioctl map getattr }"
        RootShell.run(
            "chmod 666 $node; " +
                "(command -v magiskpolicy >/dev/null && magiskpolicy --live '$rule') || " +
                "(command -v supolicy >/dev/null && supolicy --live '$rule') || " +
                "(command -v ksud >/dev/null && ksud sepolicy patch '$rule') || true",
        )
        Log.i(TAG, "access to $node for $domain")
    }
}

/**
 * Pictures of a USB camera drawn into [texture] (the rear view's TextureView): MJPEG decoded by BitmapFactory (the
 * bitmap reused), YUYV converted natively. [onSize] gets the picture size once known, [onFrame] each frame, [onError]
 * a failure (the thread then ends).
 */
class UvcStream(
    private val device: UvcDevice,
    private val texture: SurfaceTexture,
    private val onSize: (Int, Int) -> Unit,
    private val onFrame: () -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var running = true
    private val thread = Thread(::loop, "uvc-${device.port}")

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.interrupt()
        runCatching { thread.join(1500) }
    }

    private fun loop() {
        UvcCameras.grantAccess(device.node)
        val fd = NativeUvc.open(device.node, 1280, 720, 30)
        if (fd < 0) {
            onError("${device.node}: errno ${-fd}")
            return
        }
        var surface: Surface? = null
        try {
            val (fourcc, w, h) = NativeUvc.format(fd).let { Triple(it[0], it[1], it[2]) }
            texture.setDefaultBufferSize(w, h)
            surface = Surface(texture)
            onSize(w, h)
            val jpeg = ByteArray(w * h * 2 + 65536)
            var bitmap: Bitmap? = if (fourcc == NativeUvc.YUYV) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
            val options = BitmapFactory.Options().apply { inMutable = true }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            var errors = 0
            while (running) {
                val n = NativeUvc.read(fd, jpeg, bitmap, 1000)
                if (n == -11) continue // EAGAIN: no frame within the second
                if (n < 0) {
                    if (++errors > 20) throw IllegalStateException("read: errno ${-n}")
                    continue
                }
                errors = 0
                if (fourcc == NativeUvc.MJPEG) {
                    options.inBitmap = bitmap
                    bitmap =
                        runCatching { BitmapFactory.decodeByteArray(jpeg, 0, n, options) }.getOrNull()
                            ?: runCatching { BitmapFactory.decodeByteArray(jpeg, 0, n) }.getOrNull()
                            ?: continue
                }
                val b = bitmap ?: continue
                val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: continue
                try {
                    canvas.drawBitmap(b, null, Rect(0, 0, canvas.width, canvas.height), paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                onFrame()
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            NativeUvc.close(fd)
            surface?.release()
        }
    }
}
