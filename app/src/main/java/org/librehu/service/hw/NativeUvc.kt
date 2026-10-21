package org.librehu.service.hw

import android.graphics.Bitmap

/** JNI bindings of `librehu_uvc.c` (V4L2 capture of USB cameras). Negative results are `-errno`. */
object NativeUvc {
    init {
        System.loadLibrary("librehu_hw")
    }

    /** V4L2 four-character codes of the formats used. */
    const val MJPEG = 0x47504A4D
    const val YUYV = 0x56595559

    @JvmStatic external fun open(
        path: String,
        width: Int,
        height: Int,
        fps: Int,
    ): Int

    /** [fourcc, width, height]. */
    @JvmStatic external fun format(fd: Int): IntArray

    /** MJPEG: the JPEG's length in [jpeg]; YUYV: 0, the picture in [bitmap] (ARGB_8888, the stream's size). */
    @JvmStatic external fun read(
        fd: Int,
        jpeg: ByteArray,
        bitmap: Bitmap?,
        timeoutMs: Int,
    ): Int

    @JvmStatic external fun close(fd: Int)
}
