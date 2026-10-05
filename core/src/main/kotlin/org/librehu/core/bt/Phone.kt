package org.librehu.core.bt

/** Call states of the Bluetooth hands-free client (`BluetoothHeadsetClientCall.CALL_STATE_*`, Android 9). */
enum class CallState(
    val hfp: Int,
) {
    ACTIVE(0),
    HELD(1),
    DIALING(2),
    ALERTING(3),
    INCOMING(4),
    WAITING(5),
    HELD_BY_RESPONSE_AND_HOLD(6),
    TERMINATED(7),
    ;

    companion object {
        fun ofHfp(value: Int): CallState = entries.firstOrNull { it.hfp == value } ?: TERMINATED
    }
}

/** One call of the phone, as reported by the hands-free profile. */
data class Call(
    val id: Int,
    val state: CallState,
    val number: String,
    val multiParty: Boolean = false,
    val outgoing: Boolean = false,
)

/** What the head unit is doing on the phone side. */
enum class PhonePhase { IDLE, RINGING, OUTGOING, IN_CALL }

/** What "answer" means for the current calls (`BluetoothHeadsetClient.acceptCall` flags). */
enum class AnswerMode(
    val hfpFlag: Int,
) {
    /** Plain incoming call. */
    NONE(0),

    /** Call waiting during a call: put the current one on hold (AT+CHLD=2). */
    HOLD_ACTIVE(1),
}

/** Call list rules shared by the service and its clients (same priorities as Jancar's btservice). */
object Calls {
    fun live(calls: List<Call>): List<Call> = calls.filter { it.state != CallState.TERMINATED }

    fun phase(calls: List<Call>): PhonePhase {
        val live = live(calls)
        return when {
            live.isEmpty() -> PhonePhase.IDLE
            live.any { it.state in IN_CALL } -> PhonePhase.IN_CALL
            live.any { it.state == CallState.DIALING || it.state == CallState.ALERTING } -> PhonePhase.OUTGOING
            live.any { it.state == CallState.INCOMING || it.state == CallState.WAITING } -> PhonePhase.RINGING
            else -> PhonePhase.IDLE
        }
    }

    /** The call to show first: the one being talked to, then the one being placed, then the one ringing. */
    fun primary(calls: List<Call>): Call? = live(calls).minByOrNull { PRIORITY.indexOf(it.state) }

    /** A second call arriving during a call. */
    fun waiting(calls: List<Call>): Call? = live(calls).firstOrNull { it.state == CallState.WAITING }

    /** Local ringtone: only for a plain incoming call (a waiting call beeps on the phone side). */
    fun shouldRing(calls: List<Call>): Boolean = phase(calls) == PhonePhase.RINGING && live(calls).any { it.state == CallState.INCOMING }

    /** How to answer, or null when nothing rings. */
    fun answerMode(calls: List<Call>): AnswerMode? {
        val live = live(calls)
        return when {
            live.any { it.state == CallState.WAITING } && live.any { it.state in IN_CALL } -> AnswerMode.HOLD_ACTIVE
            live.any { it.state == CallState.INCOMING || it.state == CallState.WAITING } -> AnswerMode.NONE
            else -> null
        }
    }

    /** The call that "hang up" ends: the one being talked to or placed (a ringing call is rejected instead). */
    fun hangupTarget(calls: List<Call>): Call? =
        live(calls).firstOrNull { it.state == CallState.ACTIVE }
            ?: live(calls).firstOrNull { it.state == CallState.DIALING || it.state == CallState.ALERTING }
            ?: live(calls).firstOrNull { it.state == CallState.HELD || it.state == CallState.HELD_BY_RESPONSE_AND_HOLD }

    fun isRinging(calls: List<Call>): Boolean = live(calls).any { it.state == CallState.INCOMING || it.state == CallState.WAITING }

    private val IN_CALL = setOf(CallState.ACTIVE, CallState.HELD, CallState.HELD_BY_RESPONSE_AND_HOLD)

    private val PRIORITY =
        listOf(
            CallState.ACTIVE,
            CallState.DIALING,
            CallState.ALERTING,
            CallState.INCOMING,
            CallState.HELD_BY_RESPONSE_AND_HOLD,
            CallState.HELD,
            CallState.WAITING,
            CallState.TERMINATED,
        )
}

/** Phone number helpers (caller name lookup, redial, DTMF). */
object PhoneNumbers {
    /** Digits only, with a leading `+` kept. */
    fun normalize(number: String): String {
        val trimmed = number.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /**
     * Same subscriber: compares the last [SIGNIFICANT] digits, so that `+33 6 12 34 56 78`, `0033612345678` and
     * `06 12 34 56 78` match. Short numbers (services) must match exactly.
     */
    fun same(
        a: String,
        b: String,
    ): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length < SIGNIFICANT || db.length < SIGNIFICANT) return da == db
        return da.takeLast(SIGNIFICANT) == db.takeLast(SIGNIFICANT)
    }

    /** Characters a dial string may contain (`BluetoothHeadsetClient.dial`). */
    fun isDialable(number: String): Boolean = number.isNotBlank() && number.all { it.isDigit() || it in "+*#,;pwPW " }

    /** DTMF keys accepted by `AT+VTS`. */
    fun isDtmf(c: Char): Boolean = c in "0123456789*#ABCD"

    private const val SIGNIFICANT = 9
}
