package com.lmalakar.onecr.core.model

/** Direction of a captured call. */
enum class CallDirection {
    INCOMING,
    OUTGOING,
    VOIP,
    UNKNOWN,
}

/** Audio source actually used by the capture engine (probe-selected, see :privilege:api). */
enum class CaptureSource {
    /** Mixed uplink+downlink from the telephony stack (requires shell privilege). */
    VOICE_CALL,

    /** Separate downlink and uplink streams when the device exposes them (enables speaker labels). */
    VOICE_DOWNLINK_UPLINK,

    /** Plain microphone fallback (may only capture the near end on some devices). */
    MICROPHONE,

    /** VoIP: MediaProjection playback capture (far end) mixed with mic (near end). */
    VOIP_PLAYBACK_MIX,
}

/** High-level state of the recorder, exposed to the UI as a StateFlow. */
enum class RecorderState {
    IDLE,
    RECORDING,
    FINALIZING,
    TRANSCRIBING,
    SUMMARIZING,
    READY,
    FAILED,
}
