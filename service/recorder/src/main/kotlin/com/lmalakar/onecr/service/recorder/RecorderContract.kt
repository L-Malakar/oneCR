package com.lmalakar.onecr.service.recorder

/**
 * Groundwork for M3. Stable string contracts for the recording foreground service so
 * detection, UI and tests can reference them without coupling to the class itself.
 */
object RecorderContract {
    const val ACTION_START_RECORDING = "com.lmalakar.onecr.action.START_RECORDING"
    const val ACTION_STOP_RECORDING = "com.lmalakar.onecr.action.STOP_RECORDING"
    const val EXTRA_RECORDING_ID = "com.lmalakar.onecr.extra.RECORDING_ID"

    const val NOTIFICATION_CHANNEL_RECORDING = "recording"
    const val NOTIFICATION_ID_RECORDING = 41
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
