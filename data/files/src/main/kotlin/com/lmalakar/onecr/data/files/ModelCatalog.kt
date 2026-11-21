package com.lmalakar.onecr.data.files

import com.lmalakar.onecr.core.model.ModelDescriptor
import com.lmalakar.onecr.core.model.ModelType

/**
 * Pre-selected model catalog (docs/ARCHITECTURE.md §5).
 *
 * Checksums are null on purpose: SHA-256 values are pinned in a signed manifest when
 * the Model Manager lands (M5) — never hardcode a guessed checksum. Sizes are
 * approximate and used for storage pre-flight checks only.
 */
object ModelCatalog {

    /** Default speech-to-text model: multilingual whisper base, q5_1 quantized (~57 MB). */
    val WHISPER_DEFAULT = ModelDescriptor(
        type = ModelType.WHISPER,
        id = "ggml-base-q5_1",
        fileName = "ggml-base-q5_1.bin",
        approxSizeBytes = 60_000_000L,
        sha256 = null,
    )

    /** English-only variant of the same size, selectable in Model Manager. */
    val WHISPER_DEFAULT_EN = ModelDescriptor(
        type = ModelType.WHISPER,
        id = "ggml-base.en-q5_1",
        fileName = "ggml-base.en-q5_1.bin",
        approxSizeBytes = 60_000_000L,
        sha256 = null,
    )

    /** Default summarization model: Qwen2.5-1.5B-Instruct Q4_K_M GGUF (~1.0 GB). */
    val LLM_DEFAULT = ModelDescriptor(
        type = ModelType.LLM,
        id = "Qwen2.5-1.5B-Instruct-Q4_K_M",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        approxSizeBytes = 1_000_000_000L,
        sha256 = null,
    )

    /** Low-RAM fallback (~400 MB) offered on devices with under 6 GB. */
    val LLM_LOW_RAM = ModelDescriptor(
        type = ModelType.LLM,
        id = "Qwen2.5-0.5B-Instruct-Q4_K_M",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        approxSizeBytes = 400_000_000L,
        sha256 = null,
    )

    val all: List<ModelDescriptor>
        get() = listOf(WHISPER_DEFAULT, WHISPER_DEFAULT_EN, LLM_DEFAULT, LLM_LOW_RAM)
}
