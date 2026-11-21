package com.lmalakar.onecr.service.detection

/**
 * Groundwork for M2. Call state machine inputs:
 *  - TelephonyCallback (API 31+) / PhoneStateListener (API 30) for cellular calls,
 *  - NotificationListenerService + AudioManager playback/recording callbacks for VoIP.
 */
enum class CallState {
    IDLE,
    RINGING,
    OFFHOOK,
}

/** A detected call lifecycle event, emitted by the detection layer into :svc-recorder. */
sealed interface CallEvent {
    data class IncomingCallStarted(val callerNumber: String?) : CallEvent
    data class OutgoingCallStarted(val dialedNumber: String?) : CallEvent

    /** VoIP call detected for an allowlisted package (WhatsApp etc.). */
    data class VoipCallDetected(val packageName: String) : CallEvent

    data class CallEnded(val durationMillis: Long) : CallEvent
}

/** Destination for detection events; :svc-recorder implements this in M2/M3. */
interface CallEventSink {
    fun emit(event: CallEvent)
}
