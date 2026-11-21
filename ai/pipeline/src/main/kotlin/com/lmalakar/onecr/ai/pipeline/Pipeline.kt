package com.lmalakar.onecr.ai.pipeline

/**
 * Groundwork for M5: post-call processing state machine. Each stage is persisted in
 * Room by the implementation so a crash resumes rather than restarts.
 */
enum class PipelineStage {
    FINALIZE,
    TRANSCRIBE,
    SUMMARIZE,
    READY,
}

interface PostCallPipeline {
    /**
     * Runs the full post-call pipeline for a recording on a worker dispatcher:
     * finalize audio → transcribe (whisper) → summarize (llama, if enabled) → READY.
     */
    suspend fun process(recordingId: String): PipelineStage
}
