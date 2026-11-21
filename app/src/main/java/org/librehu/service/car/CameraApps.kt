package org.librehu.service.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.librehu.service.R
import org.librehu.service.root.RootShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How Android's camera apps (Camera, video calls...) see one camera of the head unit. */
enum class CameraRole(
    /** Value of `persist.librehu.camera.facing` ("id:<facing>"), null when not forced. */
    val facing: String?,
    val label: Int,
) {
    /** As the camera HAL says (Jancar: "front" for camera 0, "back" for every other). */
    AUTO(null, R.string.camapps_role_auto),

    /** Not shown to apps (`persist.librehu.camera.hidden`). */
    HIDDEN(null, R.string.camapps_role_hidden),
    BACK("back", R.string.camapps_role_back),
    FRONT("front", R.string.camapps_role_front),

    /** Shown, but neither "back" nor "front": apps that take the first back / front camera skip it. */
    EXTERNAL("external", R.string.camapps_role_external),
}

/** One camera of the camera service: id, facing as apps see it now, largest output size. */
data class CameraAppsInfo(
    val id: String,
    val facing: Int?,
    val size: String,
)

/**
 * Cameras of Android's camera apps, through the cameraserver patch of the LibreHU ROMs (frameworks/av,
 * `librehuCameraHiddenFacing`, LineageOS 16.0 and 17.1 built after 21/11/2026): `persist.librehu.camera.hidden` (ids
 * not shown) and `persist.librehu.camera.facing` ("3:back,0:front"). Jancar's camera HAL declares ids with no camera
 * behind them (2, 10, 11, 12 on the unit of 21/11: open fails with -38) and calls every camera "back" but camera 0,
 * so the Camera app, which opens the first "back" one, showed nothing. Written as root, then cameraserver is
 * restarted (it reads them when it lists the cameras). Without the patch (stock ROM, older build) they do nothing.
 *
 * A hidden camera cannot be opened by anyone, this service included: the cameras of the rear view and of the CVBS
 * inputs are never hidden ([inUse]).
 */
object CameraApps {
    private const val TAG = "LibreHU-CameraApps"
    const val PROP_HIDDEN = "persist.librehu.camera.hidden"
    const val PROP_FACING = "persist.librehu.camera.facing"

    /** Written when nothing is set: setprop does not take an empty value on every Android version. */
    private const val NONE = "none"
    private const val PROBE_TIMEOUT_S = 4L

    /** The ids Jancar's HAL may declare (cameraList of the rear view looks at the same). */
    val IDS: List<String> = (0..15).map { it.toString() }

    /** Roles from the properties now (readable without root). */
    fun read(): Map<String, CameraRole> {
        val hidden =
            prop(PROP_HIDDEN)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        val facing =
            prop(PROP_FACING)
                .split(',')
                .mapNotNull { item ->
                    item.split(':').takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                }.toMap()
        return IDS.associateWith { id ->
            if (id in hidden) {
                CameraRole.HIDDEN
            } else {
                CameraRole.entries.firstOrNull { it.facing != null && it.facing == facing[id] } ?: CameraRole.AUTO
            }
        }
    }

    /** (hidden, facing) values of the two properties for [roles]. */
    fun encode(roles: Map<String, CameraRole>): Pair<String, String> {
        val ids = roles.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        val hidden = ids.filter { roles[it] == CameraRole.HIDDEN }.joinToString(",")
        val facing = ids.mapNotNull { id -> roles[id]?.facing?.let { "$id:$it" } }.joinToString(",")
        return hidden.ifEmpty { NONE } to facing.ifEmpty { NONE }
    }

    /**
     * Writes the properties and restarts cameraserver (root). Null when done, else why not ("root"). The apps that
     * had a camera open lose it: they reopen it (the Camera app does when it comes back to the front).
     */
    fun apply(roles: Map<String, CameraRole>): String? {
        val (hidden, facing) = encode(roles)
        // only digits, commas, colons and the facing words: nothing to quote
        require(Regex("[0-9a-z,:]+").matches(hidden) && Regex("[0-9a-z,:]+").matches(facing))
        Log.i(TAG, "hidden=$hidden facing=$facing: cameraserver restarted")
        val out =
            RootShell.run(
                "setprop $PROP_HIDDEN $hidden && setprop $PROP_FACING $facing && stop cameraserver; start cameraserver; " +
                    "getprop $PROP_HIDDEN",
                timeoutS = 15,
            ) ?: return "root"
        return if (out.trim().endsWith(hidden)) null else out.trim().ifEmpty { "setprop" }
    }

    /** Cameras that the rear view and the CVBS inputs use: never hidden. */
    fun inUse(context: Context): Set<String> {
        val rear = ReverseView.get(context)
        val s = rear.settings.value
        return buildSet {
            add(CvbsInput.get(context).value.cameraId)
            if (s.cameraId.isNotEmpty()) add(s.cameraId) else rear.cameraCandidates(s).firstOrNull()?.let { add(it) }
            s.extras.forEach { add(it.id) }
        }.filterTo(mutableSetOf()) { it.toIntOrNull() != null }
    }

    /** The camera of the rear view (chosen, else the first the view would try). */
    fun rearCamera(context: Context): String? {
        val rear = ReverseView.get(context)
        val s = rear.settings.value
        return s.cameraId.ifEmpty { rear.cameraCandidates(s).firstOrNull().orEmpty() }.takeIf { it.toIntOrNull() != null }
    }

    /** Cameras the camera service shows now, with their facing and largest output. */
    fun list(context: Context): List<CameraAppsInfo> {
        val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        return listed.map { id ->
            val c = runCatching { cm.getCameraCharacteristics(id) }.getOrNull()
            val best =
                c
                    ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.maxByOrNull { it.width * it.height }
            CameraAppsInfo(id, c?.get(CameraCharacteristics.LENS_FACING), best?.let { "${it.width}×${it.height}" }.orEmpty())
        }
    }

    /**
     * Opens each listed camera a moment, [skip] left alone: the ids that refuse to open (no camera behind, -38 from
     * Jancar's HAL). Slow (a few seconds per camera): off the main thread.
     */
    @SuppressLint("MissingPermission") // the service holds CAMERA
    fun findDead(
        context: Context,
        skip: Set<String>,
    ): List<String> {
        val cm = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val thread = HandlerThread("camera-probe").apply { start() }
        val handler = Handler(thread.looper)
        val dead = mutableListOf<String>()
        try {
            for (id in runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())) {
                if (id in skip) continue
                val done = CountDownLatch(1)
                var ok = false
                val callback =
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            ok = true
                            camera.close()
                            done.countDown()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            done.countDown()
                        }

                        override fun onError(
                            camera: CameraDevice,
                            error: Int,
                        ) {
                            camera.close()
                            done.countDown()
                        }
                    }
                val started = runCatching { cm.openCamera(id, callback, handler) }.isSuccess
                if (started) done.await(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
                Log.i(TAG, "probe camera $id: ${if (ok) "opens" else "does not open"}")
                if (!ok) dead += id
            }
        } finally {
            thread.quitSafely()
        }
        return dead
    }

    private fun prop(name: String): String =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String
        }.getOrDefault("").let { if (it == NONE) "" else it }
}
