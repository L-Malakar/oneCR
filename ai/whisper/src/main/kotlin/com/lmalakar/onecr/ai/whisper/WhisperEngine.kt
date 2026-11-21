package com.lmalakar.onecr.ai.whisper

/**
 * Contract for the whisper.cpp JNI wrapper (implemented in M5 on top of
 * third_party/whisper.cpp, built via CMake for arm64-v8a).
 *
 * whisper.cpp expects 16 kHz mono float PCM; conversion from the recorder's
 * 16-bit PCM (and any resampling) happens inside the native layer, never in Kotlin.
 */
data class WhisperSegment(
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
)

interface WhisperEngine {
    /** Load a GGML model file. Returns false (and logs) on failure; never throws. */
    fun load(modelPath: String): Boolean

    /** Blocking transcription — call from a dedicated worker thread, never the UI thread. */
    fun transcribe(
        pcm16kMono: FloatArray,
        onProgress: (progressFraction: Float) -> Unit = {},
    ): List<WhisperSegment>

    /** Free the native context. Must be called before any LLM model is loaded. */
    fun unload()
}
