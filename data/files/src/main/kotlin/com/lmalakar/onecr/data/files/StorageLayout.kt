package com.lmalakar.onecr.data.files

/**
 * Groundwork for M3/M4: stable on-disk layout under the app-private files dir.
 *
 * filesDir/
 * ├── recordings/<recordingId>/
 * │   ├── raw/{downlink,uplink,mic,playback}.wav   (per-stream, kept until finalize)
 * │   ├── final/audio.m4a (or audio.wav)           (mixed archive)
 * │   └── journal.json                             (crash-recovery metadata)
 * ├── models/
 * │   ├── whisper/<ggml model>.bin
 * │   └── llm/<gguf model>.gguf
 * ├── logs/events.jsonl                             (detection + privilege event log, M2)
 * └── export/                                      (staging for bulk SAF export)
 */
object StorageLayout {
    const val RECORDINGS_ROOT = "recordings"
    const val MODELS_ROOT = "models"
    const val EXPORT_ROOT = "export"

    const val LOGS_ROOT = "logs"
    const val EVENT_LOG_FILE = "events.jsonl"

    const val RAW_SUBDIR = "raw"
    const val FINAL_SUBDIR = "final"
    const val JOURNAL_FILE = "journal.json"

    const val CALL_STREAM = "call.wav"
    const val DOWNLINK_STREAM = "downlink.wav"
    const val UPLINK_STREAM = "uplink.wav"
    const val MIC_STREAM = "mic.wav"
    const val PLAYBACK_STREAM = "playback.wav"
    const val FINAL_WAV = "audio.wav"
    const val FINAL_AAC = "audio.m4a"

    fun recordingDir(recordingId: String): String = "$RECORDINGS_ROOT/$recordingId"
    fun recordingRawDir(recordingId: String): String = "${recordingDir(recordingId)}/$RAW_SUBDIR"
    fun recordingFinalDir(recordingId: String): String = "${recordingDir(recordingId)}/$FINAL_SUBDIR"
    fun whisperModelDir(): String = "$MODELS_ROOT/whisper"
    fun llmModelDir(): String = "$MODELS_ROOT/llm"
}
