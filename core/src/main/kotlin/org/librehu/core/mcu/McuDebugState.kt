package org.librehu.core.mcu

/**
 * Everything the MCU said, for the debug screen (MCU-tools-app "State" tab). Built from the decoded events, so it
 * works with any protocol; the frames without an event (mute, antenna, option) are read the Jancar way when [jancar].
 */
data class McuDebugState(
    val received: Int = 0,
    val sent: Int = 0,
    val acks: Int = 0,
    val simulated: Int = 0,
    val acc: Boolean? = null,
    val handbrake: Boolean? = null,
    val headlight: Boolean? = null,
    val backlight: Boolean? = null,
    val reverse: Boolean? = null,
    val mute: Boolean? = null,
    val antenna: Boolean? = null,
    val option: Int? = null,
    val version: String = "",
    val date: String = "",
    val time: String = "",
    val lastKey: String = "",
) {
    fun onSent() = copy(sent = sent + 1)

    /** A frame received from the MCU (or simulated), with its decoded [event]. */
    fun onReceived(
        frame: McuFrame,
        event: McuEvent,
        jancar: Boolean,
        simulated: Boolean = false,
    ): McuDebugState {
        val s = if (simulated) copy(simulated = this.simulated + 1) else copy(received = received + 1)
        return when (event) {
            is McuEvent.Ack -> {
                s.copy(acks = s.acks + 1)
            }

            is McuEvent.Acc -> {
                s.copy(acc = event.on)
            }

            is McuEvent.Handbrake -> {
                s.copy(handbrake = event.on)
            }

            is McuEvent.Headlight -> {
                s.copy(headlight = event.on)
            }

            is McuEvent.Backlight -> {
                s.copy(backlight = event.on)
            }

            is McuEvent.Reverse -> {
                s.copy(reverse = event.on)
            }

            is McuEvent.Version -> {
                s.copy(version = event.text)
            }

            is McuEvent.Date -> {
                s.copy(date = "%04d-%02d-%02d".format(event.year, event.month, event.day))
            }

            is McuEvent.Time -> {
                s.copy(time = "%02d:%02d:%02d".format(event.hour, event.minute, event.second))
            }

            is McuEvent.Key -> {
                val text =
                    if (jancar) {
                        JacDebug.keyText(
                            frame,
                        )
                    } else {
                        "channel ${event.channel}  ${event.values.joinToString(" ") { "%02X".format(it) }}"
                    }
                s.copy(lastKey = (if (event.learning) "[learn] " else "") + text)
            }

            is McuEvent.Other -> {
                if (jancar) s.jancarOther(frame) else s
            }

            else -> {
                s
            }
        }
    }

    private fun jancarOther(f: McuFrame): McuDebugState {
        if (f.data.isEmpty()) return this
        return when (f.cmd) {
            Mcu.CMD_MUTE -> copy(mute = f.u(0) != 0)
            Mcu.CMD_ANTENNA -> copy(antenna = f.u(0) != 0)
            Mcu.CMD_CONFIG -> if (f.u(0) == JacDebug.CFG_OPTION && f.data.size >= 2) copy(option = f.u(1)) else this
            else -> this
        }
    }
}
