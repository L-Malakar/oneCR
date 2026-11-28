package com.lmalakar.onecr.service.recorder

/**
 * Stable string contracts for the recording foreground service so detection,
 * UI and tests can reference them without coupling to the class itself.
 */
object RecorderContract {
    const val ACTION_START_RECORDING = "com.lmalakar.onecr.action.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.lmalakar.onecr.action.STOP_RECORDING"

    /**
     * v0.3.7: bring the service to the foreground with the mediaProjection
     * (+ microphone) type so the engine can claim the consented projection —
     * Android 14+ requires the typed foreground service first.
     */
    const val ACTION_ARM_VOIP = "com.lmalakar.onecr.action.ARM_VOIP"

    const val EXTRA_RECORDING_ID = "com.lmalakar.onecr.extra.RECORDING_ID"

    const val NOTIFICATION_CHANNEL_RECORDING = "recording"
    const val NOTIFICATION_ID_RECORDING = 41

    /** What started the session (journal + event log). */
    const val TRIGGER_AUTO = "auto"
    const val TRIGGER_MANUAL = "manual"
}

/**
 * Bridge the service uses to reach the app's single [RecordingEngine]
 * (same inversion as DetectionServiceHost — :app injects it at process start).
 */
object RecorderServiceHost {
    var engineProvider: (() -> RecordingEngine)? = null
}

/** Parameters chosen for a recording session (defaults match the whisper pipeline input). */
data class RecordingSessionSpec(
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    /** Keep raw per-stream WAVs until finalize (enables ME/THEM speaker labels later). */
    val keepPerStreamRaw: Boolean = true,
    /** Encode the mixed archive as AAC (M4) or plain WAV (M3 default). */
    val encodeAac: Boolean = false,
)
