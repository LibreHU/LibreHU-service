package org.librehu.service.can

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.librehu.core.can.CanboxFrame
import org.librehu.core.can.HiworldWheelKeys
import org.librehu.core.can.WheelHold
import org.librehu.core.touch.TouchAction
import org.librehu.core.touch.ZoneAction
import org.librehu.service.LibreHuService
import org.librehu.service.touch.TouchKeys

/**
 * Steering wheel keys relayed by the CAN box (Hiworld `0x11`), turned into the same actions as the touch keys.
 * ivi-canbus used to do it; on by default once ivi-services is disabled (two actions per press otherwise).
 */
class CanWheelKeys private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("can_keys", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, !LibreHuService.isIviServicesEnabled(app)))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mapping = MutableStateFlow(load())

    /** Key code → action; codes without an entry do nothing. */
    val mapping: StateFlow<Map<Int, ZoneAction>> = _mapping.asStateFlow()

    private val _lastKey = MutableStateFlow<Int?>(null)

    /** Last key pressed (to find which code a button sends). */
    val lastKey: StateFlow<Int?> = _lastKey.asStateFlow()

    private val _seen = MutableStateFlow<Set<Int>>(emptySet())
    val seen: StateFlow<Set<Int>> = _seen.asStateFlow()

    private val _long = MutableStateFlow(load(KEY_LONG, emptyMap()))

    /** Key code → action of a long press (key held); without one, holding repeats the click action (volume). */
    val longMapping: StateFlow<Map<Int, ZoneAction>> = _long.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)

    /** D1 D2 D3 of the last key frame of the box, in hexadecimal (key test). */
    val lastRaw: StateFlow<String?> = _raw.asStateFlow()

    private val _timeline = MutableStateFlow<String?>(null)

    /** Last press of a key with a long press: its frames (↓ press, ↑ release, ms from the first) and what it gave. */
    val lastTimeline: StateFlow<String?> = _timeline.asStateFlow()

    private val detector =
        HiworldWheelKeys(
            { d1, d2, d3 -> _raw.value = "%02X %02X %02X".format(d1, d2, d3) },
            { code -> main.post { onUp(code) } },
        ) { code -> main.post { onPress(code) } }

    /** Short / long press of the keys that have a long action, on the frames' timing ([WheelHold]). */
    private var hold = WheelHold(longMs().toLong(), GAP_MS)
    private val tick = Runnable { onTick() }

    /** Until when the keys are ignored after the head unit starts or wakes (elapsedRealtime), see [guard]. */
    @Volatile
    private var ignoreUntil = 0L

    /**
     * The car was just started: keys are ignored for [ignoreAfterOnS] seconds. On a Clio 3 the remote sends a press
     * (SRC) by itself when the ignition comes on, which switched the source.
     */
    fun guard() {
        val s = ignoreAfterOnS()
        if (s > 0) ignoreUntil = SystemClock.elapsedRealtime() + s * 1000L
    }

    fun ignoreAfterOnS(): Int = prefs.getInt(KEY_IGNORE_S, DEFAULT_IGNORE_S)

    fun setIgnoreAfterOnS(s: Int) = prefs.edit().putInt(KEY_IGNORE_S, s.coerceIn(0, 30)).apply()

    /** Only with a profile that has the steering wheel key in `0x11` (Hiworld LNP002 and alike). */
    fun onFrame(f: CanboxFrame) {
        val m = CanMonitor.vehicle.messages[HiworldWheelKeys.CMD] ?: return
        if (m.signals.none { it.name == "wheel_key" }) return
        detector.onFrame(f)
    }

    private val _claims = MutableStateFlow(loadClaims())

    /** Buttons handled by another app (package → ids "can:<code>"), see [KeyClaimReceiver]. */
    val claims: StateFlow<Map<String, Set<String>>> = _claims.asStateFlow()

    /** Codes some installed app handles: the service leaves them to it. */
    fun claimed(code: Int): Boolean = _claims.value.any { (pkg, keys) -> "can:$code" in keys && installed(pkg) }

    fun setClaims(
        pkg: String,
        keys: Set<String>,
    ) {
        val m = if (keys.isEmpty()) _claims.value - pkg else _claims.value + (pkg to keys)
        _claims.value = m
        val o = JSONObject()
        m.forEach { (p, k) -> o.put(p, org.json.JSONArray(k.toList())) }
        prefs.edit().putString(KEY_CLAIMS, o.toString()).apply()
    }

    private fun loadClaims(): Map<String, Set<String>> =
        runCatching {
            val o = JSONObject(prefs.getString(KEY_CLAIMS, null) ?: return emptyMap())
            o
                .keys()
                .asSequence()
                .associateWith { p ->
                    val a = o.getJSONArray(p)
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }
        }.getOrDefault(emptyMap())

    private fun installed(pkg: String): Boolean = runCatching { app.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Mapping of [code], or of its low 5 bits (codes mapped before the whole byte was read). */
    private fun click(code: Int): ZoneAction? = _mapping.value[code] ?: _mapping.value[code and 0x1F]?.takeIf { code > 0x1F }

    private fun longOf(code: Int): ZoneAction? =
        (_long.value[code] ?: _long.value[code and 0x1F]?.takeIf { code > 0x1F })?.takeIf { it.action != TouchAction.NONE }

    private fun onPress(code: Int) {
        val now = SystemClock.elapsedRealtime()
        _lastKey.value = code
        _seen.value = _seen.value + code
        if (!_enabled.value || claimed(code)) return
        if (now < ignoreUntil) {
            android.util.Log.i("LibreHU-CAN", "wheel key $code ignored (car just started)")
            return
        }
        if (longOf(code) == null) {
            // No long action: each press frame is a click (a held volume key goes on, as the box repeats it)
            hold.end()?.let(::done)
            run(click(code))
            return
        }
        hold.down(code, now)?.let(::done)
        schedule()
    }

    private fun onUp(code: Int) {
        hold.up(code, SystemClock.elapsedRealtime())
        schedule()
    }

    private fun onTick() {
        hold.tick(SystemClock.elapsedRealtime())?.let(::done)
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        val at = hold.deadline ?: return
        main.postDelayed(tick, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun done(p: WheelHold.Press) {
        _timeline.value = "${HiworldWheelKeys.name(p.code) ?: p.code} : ${p.frames}"
        run(if (p.result == WheelHold.Result.LONG) longOf(p.code) else click(p.code))
    }

    /** Hold time before a long press, in ms (setting). */
    fun longMs(): Int = prefs.getInt(KEY_LONG_MS, DEFAULT_LONG_MS)

    fun setLongMs(ms: Int) {
        prefs.edit().putInt(KEY_LONG_MS, ms.coerceIn(MIN_LONG_MS, MAX_LONG_MS)).apply()
        if (!hold.active) hold = WheelHold(longMs().toLong(), GAP_MS)
    }

    private fun run(action: ZoneAction?) {
        if (action != null && action.action != TouchAction.NONE) TouchKeys.get(app).run(null, action)
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    fun set(
        code: Int,
        action: ZoneAction,
    ) {
        val m = _mapping.value + (code to action)
        _mapping.value = m
        save(m)
    }

    fun setLong(
        code: Int,
        action: ZoneAction,
    ) {
        val m = if (action.action == TouchAction.NONE) _long.value - code else _long.value + (code to action)
        _long.value = m
        save(m, KEY_LONG)
    }

    fun resetDefaults() {
        _mapping.value = DEFAULTS
        _long.value = emptyMap()
        prefs.edit().remove(KEY_MAP).remove(KEY_LONG).apply()
    }

    private fun load(
        key: String = KEY_MAP,
        defaults: Map<Int, ZoneAction> = DEFAULTS,
    ): Map<Int, ZoneAction> {
        val text = prefs.getString(key, null) ?: return defaults
        return try {
            val o = JSONObject(text)
            o
                .keys()
                .asSequence()
                .mapNotNull { k ->
                    val code = k.toIntOrNull() ?: return@mapNotNull null
                    val e = o.getJSONObject(k)
                    val a = runCatching { TouchAction.valueOf(e.getString("a")) }.getOrNull() ?: return@mapNotNull null
                    code to ZoneAction(a, e.optString("arg"))
                }.toMap()
        } catch (_: Exception) {
            defaults
        }
    }

    private fun save(
        m: Map<Int, ZoneAction>,
        key: String = KEY_MAP,
    ) {
        val o = JSONObject()
        m.forEach { (code, a) -> o.put(code.toString(), JSONObject().put("a", a.action.name).put("arg", a.arg)) }
        prefs.edit().putString(key, o.toString()).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAP = "map"
        private const val KEY_CLAIMS = "claims"
        private const val KEY_LONG = "long"
        private const val KEY_IGNORE_S = "ignore_after_on_s"
        private const val DEFAULT_IGNORE_S = 5

        private const val KEY_LONG_MS = "long_ms"

        /**
         * Default hold time before a long press. Above a tap plus [GAP_MS] even when the box sends a tap as a few
         * presses over ~300 ms, so that a tap is never taken for a long press.
         */
        const val DEFAULT_LONG_MS = 900
        const val MIN_LONG_MS = 600
        const val MAX_LONG_MS = 2000

        /** No press frame for this long after a release: the key was let go (a held key pulses every ~380 ms). */
        private const val GAP_MS = 450L

        /**
         * Hiworld's meaning of the codes (HdRenaultProtocolLNP002). Codes 4 and 0x11 also come from a Clio 3 remote
         * but their button is not known yet: nothing until the user picks an action.
         */
        val DEFAULTS: Map<Int, ZoneAction> =
            mapOf(
                1 to ZoneAction(TouchAction.VOLUME_UP),
                2 to ZoneAction(TouchAction.VOLUME_DOWN),
                3 to ZoneAction(TouchAction.MUTE),
                5 to ZoneAction(TouchAction.PREVIOUS),
                6 to ZoneAction(TouchAction.NEXT),
                13 to ZoneAction(TouchAction.PREVIOUS),
                14 to ZoneAction(TouchAction.NEXT),
                18 to ZoneAction(TouchAction.MUTE),
            )

        @Volatile
        private var instance: CanWheelKeys? = null

        fun get(context: Context): CanWheelKeys =
            instance ?: synchronized(this) { instance ?: CanWheelKeys(context).also { instance = it } }
    }
}
