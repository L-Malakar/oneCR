package com.lmalakar.onecr.service.detection

import com.lmalakar.onecr.core.common.TimeProvider
import com.lmalakar.onecr.core.model.CallDirection

/**
 * Pure cellular call state machine: IDLE ⇄ RINGING → OFFHOOK → IDLE.
 *
 * Fed with [CallState] transitions (mapped from TelephonyCallback / PhoneStateListener
 * by [CellularCallDetector]); emits [CallEvent]s. No Android imports — fully unit-tested.
 *
 * Transition table:
 *   IDLE    → RINGING : remember incoming number              → (no event; UI shows ringing)
 *   RINGING → OFFHOOK : incoming call answered                → IncomingCallStarted
 *   RINGING → IDLE    : missed / rejected                     → CallMissed
 *   IDLE    → OFFHOOK : outgoing call placed                  → OutgoingCallStarted
 *   OFFHOOK → IDLE    : call over                             → CallEnded(duration, direction)
 *
 * Number updates while RINGING/OFFHOOK are accepted (some OEMs deliver the number
 * a beat after the state change).
 */
class CallStateMachine(private val time: TimeProvider) {

    private var label: CallState = CallState.IDLE
    private var offHookSinceMillis = 0L
    private var ringingNumber: String? = null
    private var incomingCall: Boolean = false

    /** Last number seen for the current call attempt (any state). */
    var lastNumber: String? = null
        private set

    /** True while the machine believes a call is being established or active. */
    val callInProgress: Boolean
        get() = label != CallState.IDLE

    /**
     * Feed one telephony state update; returns the events produced by this transition.
     * @param number incoming/dialed number as reported by the listener (often null).
     */
    fun onCallStateChanged(newState: CallState, number: String?): List<CallEvent> {
        if (number != null && number.isNotBlank()) lastNumber = number

        if (newState == label) {
            // Same-state update: only the number may have changed — nothing to emit.
            return emptyList()
        }

        val events = mutableListOf<CallEvent>()
        val previous = label
        label = newState

        when {
            previous == CallState.IDLE && newState == CallState.RINGING -> {
                incomingCall = true
                ringingNumber = number ?: lastNumber
            }

            previous == CallState.RINGING && newState == CallState.OFFHOOK -> {
                events += CallEvent.IncomingCallStarted(ringingNumber ?: lastNumber)
                offHookSinceMillis = time.nowMillis()
            }

            previous == CallState.RINGING && newState == CallState.IDLE -> {
                events += CallEvent.CallMissed(ringingNumber ?: lastNumber)
                reset()
            }

            previous == CallState.IDLE && newState == CallState.OFFHOOK -> {
                incomingCall = false
                events += CallEvent.OutgoingCallStarted(lastNumber)
                offHookSinceMillis = time.nowMillis()
            }

            previous == CallState.OFFHOOK && newState == CallState.IDLE -> {
                val duration = (time.nowMillis() - offHookSinceMillis).coerceAtLeast(0)
                val direction = if (incomingCall) CallDirection.INCOMING else CallDirection.OUTGOING
                events += CallEvent.CallEnded(
                    durationMillis = duration,
                    direction = direction,
                    phone = lastNumber,
                    contactName = null,
                )
                reset()
            }

            // Irregular jumps (e.g. OFFHOOK → RINGING on some OEM call-waiting paths,
            // or RINGING → IDLE handled above): resync without inventing events.
            else -> {
                if (newState == CallState.OFFHOOK) offHookSinceMillis = time.nowMillis()
                if (newState == CallState.RINGING) ringingNumber = lastNumber
            }
        }
        return events
    }

    /** Hard reset (detection stopped / call state lost). Emits nothing. */
    fun reset() {
        label = CallState.IDLE
        offHookSinceMillis = 0L
        ringingNumber = null
        lastNumber = null
        incomingCall = false
    }
}
