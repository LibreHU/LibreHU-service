package org.librehu.core.touch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Finger state after one input report (`SYN_REPORT`), in driver coordinates. */
data class TouchSample(
    val down: Boolean,
    val x: Int,
    val y: Int,
    val timeMs: Long,
)

/**
 * Decoder of the binary `struct input_event` stream of `/dev/input/eventN` (Linux input subsystem): 24-byte records
 * on 64-bit kernels (AC8257), 16-byte on 32-bit ones. Follows the first finger (multi-touch slot 0, or the single-touch
 * axes) and emits one [TouchSample] per report.
 */
class InputEventParser(
    private val recordSize: Int = 24,
    private val onSample: (TouchSample) -> Unit,
) {
    private val buf = ByteArray(recordSize)
    private var pos = 0
    private var slot = 0
    private var x = 0
    private var y = 0
    private var down = false
    private var changed = false

    fun feed(
        bytes: ByteArray,
        length: Int = bytes.size,
    ) {
        for (i in 0 until length) {
            buf[pos++] = bytes[i]
            if (pos == recordSize) {
                pos = 0
                record()
            }
        }
    }

    private fun u16(o: Int) = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)

    private fun s32(o: Int) =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or ((buf[o + 2].toInt() and 0xFF) shl 16) or
            (buf[o + 3].toInt() shl 24)

    private fun record() {
        val base = recordSize - 8
        val type = u16(base)
        val code = u16(base + 2)
        val value = s32(base + 4)
        val sec = if (recordSize == 24) s32(0).toLong() else s32(0).toLong()
        val usec = if (recordSize == 24) s32(8).toLong() else s32(4).toLong()
        when (type) {
            EV_ABS -> {
                when (code) {
                    ABS_MT_SLOT -> {
                        slot = value
                    }

                    ABS_MT_TRACKING_ID -> {
                        if (slot == 0) {
                            down = value >= 0
                            changed = true
                        }
                    }

                    ABS_MT_POSITION_X -> {
                        if (slot == 0) setX(value)
                    }

                    ABS_MT_POSITION_Y -> {
                        if (slot == 0) setY(value)
                    }

                    ABS_X -> {
                        setX(value)
                    }

                    ABS_Y -> {
                        setY(value)
                    }
                }
            }

            EV_KEY -> {
                if (code == BTN_TOUCH) {
                    down = value != 0
                    changed = true
                }
            }

            EV_SYN -> {
                if (code == SYN_REPORT && changed) {
                    changed = false
                    onSample(TouchSample(down, x, y, sec * 1000 + usec / 1000))
                }
            }
        }
    }

    private fun setX(v: Int) {
        x = v
        changed = true
    }

    private fun setY(v: Int) {
        y = v
        changed = true
    }

    companion object {
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14A
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_MT_SLOT = 0x2F
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39

        /** `eventN` handler of the input device named [name] in `/proc/bus/input/devices`. */
        fun findHandler(
            procBusInputDevices: String,
            name: String,
        ): String? {
            for (block in procBusInputDevices.split(Regex("\\n\\s*\\n"))) {
                if (!block.contains("Name=\"$name\"")) continue
                return Regex("""\bevent\d+\b""").find(block.lines().firstOrNull { it.startsWith("H:") }.orEmpty())?.value
            }
            return null
        }
    }
}

/** What a touch zone does. [arg]: package name for [LAUNCH_APP], Android key code for [KEYCODE]. */
enum class TouchAction {
    NONE,
    HOME,
    BACK,
    RECENTS,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    PLAY_PAUSE,
    NEXT,
    PREVIOUS,
    SCREEN_OFF,
    LAUNCH_APP,
    KEYCODE,
}

data class ZoneAction(
    val action: TouchAction = TouchAction.NONE,
    val arg: String = "",
)

/**
 * A "button" printed on the front panel that is in fact part of the touch panel, outside the LCD: a disc of [radius]
 * around ([x], [y]) in driver coordinates (as ivi-services' `/jancar/config/touch_key.xml`). [repeat]: the click
 * action repeats while held (volume), otherwise holding gives [longPress].
 */
data class TouchZone(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val radius: Int = 40,
    val click: ZoneAction = ZoneAction(),
    val longPress: ZoneAction = ZoneAction(),
    val repeat: Boolean = false,
) {
    fun contains(
        px: Int,
        py: Int,
    ): Boolean {
        val dx = (px - x).toLong()
        val dy = (py - y).toLong()
        return dx * dx + dy * dy <= radius.toLong() * radius
    }
}

/** Press detection on the zones: click on release, long press or repeat while held. Feed samples and ticks. */
class TouchZoneEngine(
    @Volatile var zones: List<TouchZone>,
    private val longMs: Long = 600,
    private val repeatMs: Long = 150,
    private val onAction: (TouchZone, ZoneAction) -> Unit,
) {
    private var active: TouchZone? = null
    private var downAt = 0L
    private var lastRepeat = 0L
    private var consumed = false

    fun onSample(s: TouchSample) {
        if (s.down) {
            if (active == null) {
                active = zones.firstOrNull { it.contains(s.x, s.y) } ?: return
                downAt = s.timeMs
                lastRepeat = s.timeMs
                consumed = false
            }
            onTick(s.timeMs)
        } else {
            val z = active ?: return
            if (!consumed) onAction(z, z.click)
            active = null
        }
    }

    /** Call regularly while a finger is down (no input report arrives while it does not move). */
    fun onTick(nowMs: Long) {
        val z = active ?: return
        val held = nowMs - downAt
        if (held < longMs) return
        if (z.repeat) {
            if (nowMs - lastRepeat >= repeatMs || !consumed) {
                lastRepeat = nowMs
                consumed = true
                onAction(z, z.click)
            }
        } else if (!consumed && z.longPress.action != TouchAction.NONE) {
            consumed = true
            onAction(z, z.longPress)
        }
    }

    val pressed: Boolean get() = active != null

    companion object {
        fun toJson(zones: List<TouchZone>): String =
            Json.encodeToString(
                JsonArray.serializer(),
                buildJsonArray {
                    for (z in zones) {
                        add(
                            buildJsonObject {
                                put("id", z.id)
                                put("name", z.name)
                                put("x", z.x)
                                put("y", z.y)
                                put("radius", z.radius)
                                put("click", z.click.action.name)
                                put("clickArg", z.click.arg)
                                put("long", z.longPress.action.name)
                                put("longArg", z.longPress.arg)
                                put("repeat", z.repeat)
                            },
                        )
                    }
                },
            )

        fun fromJson(text: String?): List<TouchZone> =
            try {
                Json.parseToJsonElement(text ?: "[]").jsonArray.map { e ->
                    val o = e.jsonObject
                    TouchZone(
                        id = o.i("id") ?: 0,
                        name = o.s("name").orEmpty(),
                        x = o.i("x") ?: 0,
                        y = o.i("y") ?: 0,
                        radius = o.i("radius") ?: 40,
                        click = ZoneAction(action(o.s("click")), o.s("clickArg").orEmpty()),
                        longPress = ZoneAction(action(o.s("long")), o.s("longArg").orEmpty()),
                        repeat = (o["repeat"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }

        private fun action(s: String?) = TouchAction.entries.firstOrNull { it.name == s } ?: TouchAction.NONE

        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    }
}
