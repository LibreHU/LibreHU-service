package org.librehu.service.touch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.librehu.core.touch.Gt9xxMatrix
import org.librehu.core.touch.InputEventParser
import org.librehu.core.touch.Pointercal
import org.librehu.core.touch.TouchSample
import org.librehu.service.root.RootShell
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The Goodix touch panel of the UJC201 (`mtk-tpd` input device): calibration matrix of the driver, raw events
 * (front panel "buttons" printed outside the LCD are only seen there: Android drops touches outside the screen).
 * Paths from ivi-services `Platform_AutoChips_8257_Base` / `TouchEventUtil` and ivi-settings `TouchCheckActivity`.
 */
class TouchPanel private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("touch", Context.MODE_PRIVATE)

    private val listeners = CopyOnWriteArraySet<(TouchSample) -> Unit>()

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var process: Process? = null

    private val _last = MutableStateFlow<TouchSample?>(null)

    /** Last raw sample (while something listens). */
    val last: StateFlow<TouchSample?> = _last.asStateFlow()

    private val _readerError = MutableStateFlow("")
    val readerError: StateFlow<String> = _readerError.asStateFlow()

    // --- Calibration ---------------------------------------------------------------------------------------------

    fun hasDriverNode(): Boolean = File(PROPS).exists()

    fun readMatrix(): Gt9xxMatrix? = Gt9xxMatrix.parse(RootShell.read(PROPS))

    /** Panel id used by pointercal.xml: first 19 characters of `/proc/gt9xx_config`. */
    fun panelId(): String? = RootShell.read(CONFIG)?.take(19)?.takeIf { it.isNotBlank() }

    /** Same text as ivi-services (`TouchEventUtil.initTouchParameter`): the 7 values, each followed by a space. */
    fun writeMatrix(m: Gt9xxMatrix): Boolean = RootShell.write(PROPS, m.values().joinToString("") { "$it " })

    /**
     * Jancar's calibration of this panel, as ivi-services applies it at boot: `pointercal-<long>x<short>.xml` first
     * when the screen is not 1024x600, then `pointercal.xml`; the node of the panel id (or the only node).
     */
    fun factoryMatrix(): Gt9xxMatrix? {
        val id = panelId()
        for (path in pointercalFiles()) {
            val nodes = RootShell.read(path)?.let(Pointercal::parse) ?: continue
            val m =
                nodes[id]
                    ?: id?.let { wanted -> nodes.entries.firstOrNull { it.key.trim() == wanted.trim() }?.value }
                    ?: nodes.values.singleOrNull()
            if (m != null) return m
        }
        return null
    }

    /** pointercal files ivi-services reads, in its order. */
    fun pointercalFiles(): List<String> {
        val dm = app.resources.displayMetrics
        val wm = app.getSystemService(android.view.WindowManager::class.java)
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        runCatching { wm.defaultDisplay.getRealSize(size) }
        val w = maxOf(size.x, size.y).takeIf { it > 0 } ?: maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(size.x, size.y).takeIf { it > 0 } ?: minOf(dm.widthPixels, dm.heightPixels)
        return listOfNotNull(if (w != 1024 || h != 600) "/jancar/config/pointercal-${w}x$h.xml" else null, POINTERCAL)
    }

    /** Jancar's calibration back in the driver (and LibreHU's own forgotten). */
    fun restoreFactory(): Boolean {
        val m = factoryMatrix() ?: return false
        if (!writeMatrix(m)) return false
        prefs.edit().remove(KEY_MATRIX).apply()
        return true
    }

    /**
     * Applies and remembers [m]; also stores it in Jancar's pointercal.xml when [forJancar]. [previous] (the matrix in
     * force before the calibration) is kept once as the backup.
     */
    fun saveCalibration(
        m: Gt9xxMatrix,
        forJancar: Boolean,
        previous: Gt9xxMatrix? = readMatrix(),
    ): Boolean {
        if (!writeMatrix(m)) return false
        val e = prefs.edit().putString(KEY_MATRIX, m.toString())
        if (previous != null && prefs.getString(KEY_BACKUP, null) == null) e.putString(KEY_BACKUP, previous.toString())
        e.apply()
        if (forJancar) {
            val id = panelId()
            // The file ivi-services reads first for this screen.
            val target = pointercalFiles().firstOrNull { RootShell.read(it) != null } ?: POINTERCAL
            if (id != null) RootShell.write(target, Pointercal.upsert(RootShell.read(target), id, m))
        }
        return true
    }

    fun savedMatrix(): Gt9xxMatrix? = Gt9xxMatrix.parse(prefs.getString(KEY_MATRIX, null))

    fun backupMatrix(): Gt9xxMatrix? = Gt9xxMatrix.parse(prefs.getString(KEY_BACKUP, null))

    /** Matrix in force before the first LibreHU calibration. */
    fun restoreBackup(): Boolean {
        val b = backupMatrix() ?: return false
        if (!writeMatrix(b)) return false
        prefs.edit().remove(KEY_MATRIX).apply()
        return true
    }

    /**
     * At service start: the driver forgets the matrix at each boot and only ivi-services wrote it (from
     * pointercal.xml); without it the touches land in the wrong place, as in TWRP. LibreHU's calibration when there is
     * one, else Jancar's.
     */
    fun applySaved() {
        val m = savedMatrix() ?: factoryMatrix()
        if (m == null) {
            Log.w(TAG, "No touch calibration to apply (no LibreHU calibration, no pointercal entry for ${panelId()})")
            return
        }
        if (readMatrix() != m && !writeMatrix(m)) Log.w(TAG, "Cannot apply the touch calibration")
    }

    // --- Raw events ----------------------------------------------------------------------------------------------

    fun addListener(l: (TouchSample) -> Unit) {
        listeners += l
        ensureReader()
    }

    fun removeListener(l: (TouchSample) -> Unit) {
        listeners -= l
        if (listeners.isEmpty()) stopReader()
    }

    private fun devicePath(): String? {
        val devices = RootShell.read("/proc/bus/input/devices") ?: return null
        return InputEventParser.findHandler(devices, DEVICE_NAME)?.let { "/dev/input/$it" }
    }

    @Synchronized
    private fun ensureReader() {
        if (reader != null) return
        reader =
            Thread({
                try {
                    val path = devicePath() ?: throw IllegalStateException("touch panel \"$DEVICE_NAME\" not found")
                    val input: InputStream =
                        if (File(path).canRead()) {
                            FileInputStream(path)
                        } else {
                            val s = RootShell.stream(path) ?: throw IllegalStateException("root needed to read $path")
                            process = s.first
                            s.second
                        }
                    _readerError.value = ""
                    val parser =
                        InputEventParser { s ->
                            _last.value = s
                            for (l in listeners) l(s)
                        }
                    val buf = ByteArray(24 * 32)
                    input.use {
                        while (!Thread.currentThread().isInterrupted) {
                            val n = it.read(buf)
                            if (n < 0) break
                            parser.feed(buf, n)
                        }
                    }
                } catch (e: Exception) {
                    if (reader != null) _readerError.value = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "Touch reader: ${e.message}")
                } finally {
                    synchronized(this) { reader = null }
                }
            }, "touch-raw").apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    private fun stopReader() {
        val t = reader
        reader = null
        process?.destroy()
        process = null
        t?.interrupt()
    }

    companion object {
        private const val TAG = "LibreHU-Touch"
        const val DEVICE_NAME = "mtk-tpd"
        const val PROPS = "/sys/devices/platform/touch/gt9xx_props"
        const val CONFIG = "/proc/gt9xx_config"
        const val POINTERCAL = "/jancar/config/pointercal.xml"
        private const val KEY_MATRIX = "matrix"
        private const val KEY_BACKUP = "backup"

        @Volatile
        private var instance: TouchPanel? = null

        fun get(context: Context): TouchPanel = instance ?: synchronized(this) { instance ?: TouchPanel(context).also { instance = it } }
    }
}
