package org.librehu.core.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTest {
    private fun call(
        id: Int,
        state: CallState,
        number: String = "0612345678",
    ) = Call(id, state, number)

    @Test
    fun hfpStatesMapToEnum() {
        assertEquals(CallState.ACTIVE, CallState.ofHfp(0))
        assertEquals(CallState.INCOMING, CallState.ofHfp(4))
        assertEquals(CallState.TERMINATED, CallState.ofHfp(7))
        assertEquals(CallState.TERMINATED, CallState.ofHfp(42))
    }

    @Test
    fun phases() {
        assertEquals(PhonePhase.IDLE, Calls.phase(emptyList()))
        assertEquals(PhonePhase.IDLE, Calls.phase(listOf(call(1, CallState.TERMINATED))))
        assertEquals(PhonePhase.RINGING, Calls.phase(listOf(call(1, CallState.INCOMING))))
        assertEquals(PhonePhase.OUTGOING, Calls.phase(listOf(call(1, CallState.ALERTING))))
        assertEquals(PhonePhase.IN_CALL, Calls.phase(listOf(call(1, CallState.ACTIVE))))
        // Call waiting during a call: still in call.
        assertEquals(PhonePhase.IN_CALL, Calls.phase(listOf(call(1, CallState.ACTIVE), call(2, CallState.WAITING))))
    }

    @Test
    fun primaryAndWaiting() {
        val calls = listOf(call(2, CallState.WAITING, "0700000000"), call(1, CallState.ACTIVE))
        assertEquals(1, Calls.primary(calls)?.id)
        assertEquals(2, Calls.waiting(calls)?.id)
        assertNull(Calls.primary(listOf(call(1, CallState.TERMINATED))))
    }

    @Test
    fun ringOnlyForPlainIncomingCall() {
        assertTrue(Calls.shouldRing(listOf(call(1, CallState.INCOMING))))
        assertFalse(Calls.shouldRing(listOf(call(1, CallState.ACTIVE), call(2, CallState.WAITING))))
        assertFalse(Calls.shouldRing(listOf(call(1, CallState.DIALING))))
    }

    @Test
    fun answerModes() {
        assertNull(Calls.answerMode(listOf(call(1, CallState.ACTIVE))))
        assertEquals(AnswerMode.NONE, Calls.answerMode(listOf(call(1, CallState.INCOMING))))
        assertEquals(AnswerMode.HOLD_ACTIVE, Calls.answerMode(listOf(call(1, CallState.ACTIVE), call(2, CallState.WAITING))))
    }

    @Test
    fun hangupTargetsTheTalkingCall() {
        assertEquals(1, Calls.hangupTarget(listOf(call(2, CallState.WAITING), call(1, CallState.ACTIVE)))?.id)
        assertEquals(3, Calls.hangupTarget(listOf(call(3, CallState.ALERTING)))?.id)
        assertNull(Calls.hangupTarget(listOf(call(4, CallState.INCOMING))))
    }

    @Test
    fun numbersMatchAcrossFormats() {
        assertTrue(PhoneNumbers.same("+33 6 12 34 56 78", "06 12 34 56 78"))
        assertTrue(PhoneNumbers.same("0033612345678", "+33612345678"))
        assertFalse(PhoneNumbers.same("0612345678", "0612345679"))
        assertTrue(PhoneNumbers.same("3631", "3631"))
        assertFalse(PhoneNumbers.same("3631", "13631"))
        assertFalse(PhoneNumbers.same("", "0612345678"))
        assertEquals("+33612345678", PhoneNumbers.normalize(" +33 6-12.34 56 78"))
    }

    @Test
    fun dialAndDtmf() {
        assertTrue(PhoneNumbers.isDialable("+33 6 12 34 56 78"))
        assertTrue(PhoneNumbers.isDialable("*#06#"))
        assertFalse(PhoneNumbers.isDialable("06 12 ab"))
        assertFalse(PhoneNumbers.isDialable("  "))
        assertTrue(PhoneNumbers.isDtmf('#'))
        assertFalse(PhoneNumbers.isDtmf('x'))
    }
}
