package com.lmalakar.onecr.service.detection

import com.lmalakar.onecr.core.model.CallDirection

/**
 * Cellular call line states mirrored from TelephonyManager (CALL_STATE_*).
 * Our own enum keeps the state machine pure-JVM and unit-testable.
 */
enum class CallState {
    IDLE,
    RINGING,
    OFFHOOK,
}

/**
 * A detected call lifecycle event, emitted by the detection layer.
 * The engine enriches events with CallLog lookups before they reach the UI/log.
 */
sealed interface CallEvent {
    data class IncomingCallStarted(val callerNumber: String?) : CallEvent

    data class OutgoingCallStarted(val dialedNumber: String?) : CallEvent

    /** Rang out / rejected: RINGING → IDLE without ever reaching OFFHOOK. */
    data class CallMissed(val callerNumber: String?) : CallEvent

    data class CallEnded(
        val durationMillis: Long,
        val direction: CallDirection,
        val phone: String?,
        val contactName: String?,
    ) : CallEvent

    /** VoIP call detected for an allowlisted package (WhatsApp etc.). */
    data class VoipCallDetected(val packageName: String) : CallEvent

    data class VoipCallEnded(val packageName: String, val durationMillis: Long) : CallEvent
}

/** Destination for detection events; :svc-recorder implements this from M3. */
interface CallEventSink {
    fun emit(event: CallEvent)
}
