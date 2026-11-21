package com.lmalakar.onecr.ai.llm

/**
 * Contract for the llama.cpp JNI wrapper (implemented in M5 on top of
 * third_party/llama.cpp, GGUF models only).
 */
enum class SummaryStyle {
    /** 3-5 line gist of the call. */
    BRIEF,

    /** Bullet list of action items with owners where the transcript makes them clear. */
    ACTION_ITEMS,

    /** Structured detailed summary: participants, topics, decisions, follow-ups. */
    DETAILED,
}

interface SummaryEngine {
    /** Load a GGUF model file. Returns false (and logs) on failure; never throws. */
    fun load(modelPath: String): Boolean

    /** Blocking summarization — call from a dedicated worker thread, never the UI thread. */
    fun summarize(transcript: String, style: SummaryStyle): String

    /** Free the native model + context. Must be called before whisper loads again. */
    fun unload()
}
