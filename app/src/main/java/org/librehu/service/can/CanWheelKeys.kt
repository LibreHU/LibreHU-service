package org.librehu.service.can

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.librehu.core.can.CanboxFrame
import org.librehu.core.can.HiworldWheelKeys
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

    private val detector = HiworldWheelKeys { code -> main.post { onPress(code) } }

    /** Only with a profile that has the steering wheel key in `0x11` (Hiworld LNP002 and alike). */
    fun onFrame(f: CanboxFrame) {
        val m = CanMonitor.vehicle.messages[HiworldWheelKeys.CMD] ?: return
        if (m.signals.none { it.name == "wheel_key" }) return
        detector.onFrame(f)
    }

    private fun onPress(code: Int) {
        _lastKey.value = code
        _seen.value = _seen.value + code
        if (!_enabled.value) return
        val action = _mapping.value[code] ?: return
        if (action.action != TouchAction.NONE) TouchKeys.get(app).run(null, action)
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

    fun resetDefaults() {
        _mapping.value = DEFAULTS
        prefs.edit().remove(KEY_MAP).apply()
    }

    private fun load(): Map<Int, ZoneAction> {
        val text = prefs.getString(KEY_MAP, null) ?: return DEFAULTS
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
            DEFAULTS
        }
    }

    private fun save(m: Map<Int, ZoneAction>) {
        val o = JSONObject()
        m.forEach { (code, a) -> o.put(code.toString(), JSONObject().put("a", a.action.name).put("arg", a.arg)) }
        prefs.edit().putString(KEY_MAP, o.toString()).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAP = "map"

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
