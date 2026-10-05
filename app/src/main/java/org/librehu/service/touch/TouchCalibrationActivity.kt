package org.librehu.service.touch

import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.librehu.core.touch.CalibrationResult
import org.librehu.core.touch.CalibrationSample
import org.librehu.core.touch.Gt9xxMatrix
import org.librehu.core.touch.TouchCalibration
import org.librehu.core.touch.TouchSample
import org.librehu.service.R
import org.librehu.service.ui.Actions
import org.librehu.service.ui.BodyText
import org.librehu.service.ui.CarColors
import org.librehu.service.ui.CarTheme
import org.librehu.service.ui.Card
import org.librehu.service.ui.Hint
import org.librehu.service.ui.Pill
import org.librehu.service.ui.SwitchRow

/**
 * Full screen calibration of the touch panel: five targets (Jancar's layout), the raw driver coordinates of each
 * touch read from the input device, Android's screen coordinates of the same touch, then [TouchCalibration.solve].
 * The new matrix is tried first and reverted after [VERIFY_S] seconds unless confirmed, so a bad result cannot leave
 * the panel unusable.
 */
class TouchCalibrationActivity : ComponentActivity() {
    private enum class Phase { INTRO, TOUCH, RESULT, VERIFY }

    private lateinit var panel: TouchPanel
    private val phase = mutableStateOf(Phase.INTRO)
    private val step = mutableIntStateOf(0)
    private val samples = mutableStateListOf<CalibrationSample>()
    private val result = mutableStateOf<CalibrationResult?>(null)
    private val error = mutableStateOf("")
    private val identityFirst = mutableStateOf(false)
    private val forJancar = mutableStateOf(true)
    private val dots = mutableStateListOf<Offset>()
    private val countdown = mutableIntStateOf(VERIFY_S)
    private var targets: List<Pair<Double, Double>> = emptyList()

    /** Matrix in force when the activity opened (restored on cancel). */
    private var original: Gt9xxMatrix? = null

    /** Matrix in force while touching the targets. */
    private var measuring: Gt9xxMatrix = Gt9xxMatrix.IDENTITY

    /** The driver matrix was changed by this screen / the new one was saved. */
    @Volatile
    private var modified = false

    @Volatile
    private var saved = false

    @Volatile
    private var rawDown: TouchSample? = null

    /** Uptime of the last raw sample kept in [rawDown]. */
    @Volatile
    private var rawDownAt = 0L

    /** Uptime of Android's last ACTION_UP while touching the targets. */
    @Volatile
    private var motionUpAt = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val listener: (TouchSample) -> Unit = { s ->
        if (s.down) {
            rawDown = s
            rawDownAt = android.os.SystemClock.uptimeMillis()
        } else if (phase.value == Phase.TOUCH) {
            // The panel saw a touch: if Android does not deliver it, the current matrix sends it off the screen.
            val releasedAt = android.os.SystemClock.uptimeMillis()
            handler.postDelayed({
                if (phase.value == Phase.TOUCH && motionUpAt < releasedAt - LOST_MARGIN_MS) {
                    error.value = getString(R.string.cal_lost_touch)
                    phase.value = Phase.INTRO
                }
            }, LOST_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = TouchPanel.get(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        TouchKeys.get(this).learning = true
        panel.addListener(listener)
        Thread { original = panel.readMatrix() }.start()
        setContent { CarTheme { Screen() } }
    }

    override fun onDestroy() {
        panel.removeListener(listener)
        TouchKeys.get(this).learning = false
        // Leaving without saving: back to the matrix in force before.
        val o = original
        if (modified && !saved && o != null) Thread { panel.writeMatrix(o) }.start()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val p = phase.value
        if (p == Phase.TOUCH) {
            // Not cleared on ACTION_DOWN: the raw reader usually has the sample before Android dispatches it.
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                motionUpAt = android.os.SystemClock.uptimeMillis()
                onTargetTouched(ev.rawX.toDouble(), ev.rawY.toDouble())
            }
            return true
        }
        if (p == Phase.VERIFY && ev.actionMasked == MotionEvent.ACTION_DOWN) dots += Offset(ev.rawX, ev.rawY)
        return super.dispatchTouchEvent(ev)
    }

    private fun start() {
        val d = window.decorView
        targets = TouchCalibration.targets(d.width, d.height)
        samples.clear()
        step.intValue = 0
        error.value = ""
        Thread {
            // Measure on a matrix known for sure: written now. Jancar's factory one when asked (or when the driver's
            // cannot be read), else the one in force.
            val factory = panel.factoryMatrix()
            val current = panel.readMatrix() ?: original ?: panel.savedMatrix()
            val base =
                if (identityFirst.value || current == null) {
                    factory ?: current ?: Gt9xxMatrix.IDENTITY
                } else {
                    current
                }
            modified = true
            if (!panel.writeMatrix(base)) {
                error.value = getString(R.string.touch_write_failed)
                return@Thread
            }
            measuring = base
            runOnUiThread {
                rawDown = null
                phase.value = Phase.TOUCH
            }
        }.start()
    }

    private fun onTargetTouched(
        x: Double,
        y: Double,
    ) {
        val raw = rawDown?.takeIf { android.os.SystemClock.uptimeMillis() - rawDownAt < RAW_MAX_AGE_MS }
        rawDown = null
        if (raw == null) {
            error.value = getString(R.string.cal_no_raw, panel.readerError.value)
            phase.value = Phase.INTRO
            return
        }
        val (tx, ty) = targets[step.intValue]
        samples += CalibrationSample(raw.x.toDouble(), raw.y.toDouble(), x, y, tx, ty)
        if (step.intValue + 1 < targets.size) {
            step.intValue++
            return
        }
        try {
            result.value = TouchCalibration.solve(measuring, samples)
            error.value = ""
        } catch (e: IllegalArgumentException) {
            result.value = null
            error.value = e.message ?: ""
        }
        phase.value = Phase.RESULT
    }

    private fun tryResult() {
        val r = result.value ?: return
        modified = true
        Thread {
            if (panel.writeMatrix(r.matrix)) {
                runOnUiThread {
                    dots.clear()
                    countdown.intValue = VERIFY_S
                    phase.value = Phase.VERIFY
                }
            } else {
                error.value = getString(R.string.touch_write_failed)
            }
        }.start()
    }

    private fun keep() {
        val r = result.value ?: return
        Thread {
            val ok = panel.saveCalibration(r.matrix, forJancar.value, previous = original)
            runOnUiThread {
                saved = ok
                Toast.makeText(this, if (ok) R.string.cal_saved else R.string.touch_write_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    }

    private fun revert() {
        val back = measuring
        Thread { panel.writeMatrix(back) }.start()
        phase.value = Phase.RESULT
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(CarColors.Background)) {
            when (phase.value) {
                Phase.INTRO -> Intro()
                Phase.TOUCH -> Targets()
                Phase.RESULT -> Result()
                Phase.VERIFY -> Verify()
            }
        }
    }

    @Composable
    private fun Centered(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }

    @Composable
    private fun Intro() =
        Centered {
            Card(stringResource(R.string.cal_title)) {
                Hint(stringResource(R.string.cal_intro))
                SwitchRow(stringResource(R.string.cal_identity_first), identityFirst.value, stringResource(R.string.cal_identity_hint)) {
                    identityFirst.value = it
                }
                SwitchRow(stringResource(R.string.cal_jancar), forJancar.value, stringResource(R.string.cal_jancar_hint)) {
                    forJancar.value =
                        it
                }
                if (error.value.isNotEmpty()) BodyText(error.value)
                Actions {
                    Pill(stringResource(R.string.cal_start), selected = true) { start() }
                    Pill(stringResource(android.R.string.cancel)) { finish() }
                }
            }
        }

    @Composable
    private fun Targets() {
        val i = step.intValue
        val accent = CarColors.Accent
        val dim = CarColors.TextDim
        Canvas(Modifier.fillMaxSize()) {
            targets.forEachIndexed { k, (x, y) ->
                val c = Offset(x.toFloat(), y.toFloat())
                if (k == i) {
                    drawCircle(accent, radius = 36f, center = c, alpha = 0.3f)
                    drawCircle(accent, radius = 10f, center = c)
                    drawLine(accent, c.copy(x = c.x - 60f), c.copy(x = c.x + 60f), strokeWidth = 3f)
                    drawLine(accent, c.copy(y = c.y - 60f), c.copy(y = c.y + 60f), strokeWidth = 3f)
                } else if (k < i) {
                    drawCircle(dim, radius = 8f, center = c)
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(top = 160.dp), contentAlignment = Alignment.TopCenter) {
            BodyText(stringResource(R.string.cal_touch_target, i + 1, targets.size))
        }
    }

    @Composable
    private fun Result() =
        Centered {
            Card(stringResource(R.string.cal_title)) {
                val r = result.value
                if (r != null) {
                    BodyText(stringResource(R.string.cal_result, r.maxErrorPx))
                    BodyText(r.matrix.toString(), mono = true)
                    if (r.maxErrorPx > BAD_ERROR_PX) Hint(stringResource(R.string.cal_result_bad))
                }
                if (error.value.isNotEmpty()) BodyText(error.value)
                Actions {
                    if (r != null) Pill(stringResource(R.string.cal_try), selected = true) { tryResult() }
                    Pill(stringResource(R.string.cal_retry)) { start() }
                    Pill(stringResource(android.R.string.cancel)) { finish() }
                }
            }
        }

    @Composable
    private fun Verify() {
        LaunchedEffect(Unit) {
            while (countdown.intValue > 0) {
                delay(1000)
                countdown.intValue--
            }
            revert()
        }
        val accent = CarColors.Accent
        Canvas(Modifier.fillMaxSize()) { for (d in dots) drawCircle(Color.Red, radius = 12f, center = d) }
        Canvas(
            Modifier.fillMaxSize(),
        ) { for ((x, y) in targets) drawCircle(accent, radius = 6f, center = Offset(x.toFloat(), y.toFloat())) }
        Centered {
            Card(stringResource(R.string.cal_title)) {
                BodyText(stringResource(R.string.cal_verify, countdown.intValue))
                Actions {
                    Pill(stringResource(R.string.cal_keep), selected = true) { keep() }
                    Pill(stringResource(R.string.cal_revert)) { revert() }
                }
            }
        }
    }

    companion object {
        private const val VERIFY_S = 20
        private const val RAW_MAX_AGE_MS = 2000L
        private const val LOST_DELAY_MS = 700L
        private const val LOST_MARGIN_MS = 300L
        private const val BAD_ERROR_PX = 20.0
    }
}
