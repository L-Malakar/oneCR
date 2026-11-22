package com.lmalakar.onecr.service.detection

import com.lmalakar.onecr.core.common.TimeProvider
import com.lmalakar.onecr.core.model.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic clock for tests. */
private class FakeCallTime(var now: Long = 1_000_000L) : TimeProvider {
    override fun nowMillis(): Long = now
}

class CallStateMachineTest {

    private val time = FakeCallTime()
    private val machine = CallStateMachine(time)

    @Test
    fun `incoming call answered emits started and ended with incoming direction`() {
        time.now += 5_000
        machine.onCallStateChanged(CallState.RINGING, "+911234567890")
        time.now += 10_000
        val started = machine.onCallStateChanged(CallState.OFFHOOK, null)
        time.now += 90_000
        val ended = machine.onCallStateChanged(CallState.IDLE, null)

        assertEquals(1, started.size)
        val start = started.single() as CallEvent.IncomingCallStarted
        assertEquals("+911234567890", start.callerNumber)

        assertEquals(1, ended.size)
        val end = ended.single() as CallEvent.CallEnded
        assertEquals(CallDirection.INCOMING, end.direction)
        assertEquals(90_000L, end.durationMillis)
        assertEquals("+911234567890", end.phone)
    }

    @Test
    fun `outgoing call emits started then ended with outgoing direction`() {
        time.now += 2_000
        val started = machine.onCallStateChanged(CallState.OFFHOOK, null)
        time.now += 45_000
        val ended = machine.onCallStateChanged(CallState.IDLE, null)

        assertEquals(1, started.size)
        assertTrue(started.single() is CallEvent.OutgoingCallStarted)
        assertEquals(1, ended.size)
        val end = ended.single() as CallEvent.CallEnded
        assertEquals(CallDirection.OUTGOING, end.direction)
        assertEquals(45_000L, end.durationMillis)
    }

    @Test
    fun `ringing then idle without offhook is a missed call`() {
        machine.onCallStateChanged(CallState.RINGING, "+911111111111")
        val events = machine.onCallStateChanged(CallState.IDLE, null)

        assertEquals(1, events.size)
        val missed = events.single() as CallEvent.CallMissed
        assertEquals("+911111111111", missed.callerNumber)
    }

    @Test
    fun `same-state updates emit nothing`() {
        machine.onCallStateChanged(CallState.RINGING, "+912222222222")
        assertEquals(0, machine.onCallStateChanged(CallState.RINGING, "+912222222222").size)
        machine.onCallStateChanged(CallState.OFFHOOK, null)
        assertEquals(0, machine.onCallStateChanged(CallState.OFFHOOK, null).size)
    }

    @Test
    fun `number delivered after ringing is used for the incoming event`() {
        machine.onCallStateChanged(CallState.RINGING, null)
        machine.onCallStateChanged(CallState.RINGING, "+913333333333")
        val started = machine.onCallStateChanged(CallState.OFFHOOK, null)

        val start = started.single() as CallEvent.IncomingCallStarted
        assertEquals("+913333333333", start.callerNumber)
    }

    @Test
    fun `callInProgress reflects the current label`() {
        assertEquals(false, machine.callInProgress)
        machine.onCallStateChanged(CallState.RINGING, null)
        assertEquals(true, machine.callInProgress)
        machine.onCallStateChanged(CallState.OFFHOOK, null)
        assertEquals(true, machine.callInProgress)
        machine.onCallStateChanged(CallState.IDLE, null)
        assertEquals(false, machine.callInProgress)
    }

    @Test
    fun `reset clears transient state`() {
        machine.onCallStateChanged(CallState.RINGING, "+914444444444")
        machine.reset()
        assertEquals(null, machine.lastNumber)
        assertEquals(false, machine.callInProgress)
        // After reset the machine must accept a fresh incoming call.
        machine.onCallStateChanged(CallState.RINGING, "+915555555555")
        val started = machine.onCallStateChanged(CallState.OFFHOOK, null)
        assertEquals("+915555555555", (started.single() as CallEvent.IncomingCallStarted).callerNumber)
    }

    @Test
    fun `irregular jump offhook to ringing does not crash or emit bogus events`() {
        machine.onCallStateChanged(CallState.OFFHOOK, null)
        val events = machine.onCallStateChanged(CallState.RINGING, null)
        // Resync path: no invented events.
        assertEquals(0, events.size)
    }
}
