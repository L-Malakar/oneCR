package com.lmalakar.onecr.core.model

/**
 * Describes a downloadable / sideloadable AI model.
 * [sha256] stays null until the model manifest is pinned (M5) — never guess checksums.
 */
data class ModelDescriptor(
    val type: ModelType,
    val id: String,
    val fileName: String,
    val approxSizeBytes: Long,
    val sha256: String?,
)

enum class ModelType {
    WHISPER,
    LLM,
}

/** Immutable metadata of a single recording. */
data class RecordingMeta(
    val id: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val direction: CallDirection,
    val phone: String?,
    val contactName: String?,
)
