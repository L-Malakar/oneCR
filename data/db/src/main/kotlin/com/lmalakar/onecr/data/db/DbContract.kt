package com.lmalakar.onecr.data.db

/**
 * Groundwork for M4: stable table/column name contract for the Room schema defined in
 * docs/ARCHITECTURE.md. Room entities in M4 will reference these constants so raw SQL
 * (FTS queries, migrations) and Kotlin stay in sync.
 */
object DbContract {
    const val DATABASE_NAME = "onecr.db"
    const val DATABASE_VERSION = 1

    object Recordings {
        const val TABLE = "recordings"
        const val ID = "id"
        const val STARTED_AT = "startedAt"
        const val ENDED_AT = "endedAt"
        const val DURATION_MS = "durationMs"
        const val DIRECTION = "direction"
        const val PHONE = "phone"
        const val CONTACT_NAME = "contactName"
        const val AUDIO_PATH = "audioPath"
        const val FORMAT = "format"
        const val SIZE_BYTES = "sizeBytes"
        const val SAMPLE_RATE = "sampleRate"
        const val CAPTURE_SOURCE = "captureSource"
        const val STATUS = "status"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
    }

    object Transcripts {
        const val TABLE = "transcripts"
        const val ID = "id"
        const val RECORDING_ID = "recordingId"
        const val MODEL_ID = "modelId"
        const val LANGUAGE = "language"
        const val CREATED_AT = "createdAt"
    }

    object TranscriptSegments {
        const val TABLE = "transcript_segments"
        const val ID = "id"
        const val TRANSCRIPT_ID = "transcriptId"
        const val IDX = "idx"
        const val START_MS = "startMs"
        const val END_MS = "endMs"
        const val TEXT = "text"
        const val SPEAKER = "speaker"
    }

    object Summaries {
        const val TABLE = "summaries"
        const val ID = "id"
        const val RECORDING_ID = "recordingId"
        const val MODEL_ID = "modelId"
        const val STYLE = "style"
        const val TEXT = "text"
        const val CREATED_AT = "createdAt"
    }

    object Tags {
        const val TABLE = "tags"
        const val ID = "id"
        const val NAME = "name"
    }

    object RecordingTags {
        const val TABLE = "recording_tags"
        const val RECORDING_ID = "recordingId"
        const val TAG_ID = "tagId"
    }

    object TranscriptFts {
        const val TABLE = "transcript_fts"
        const val RECORDING_ID = "recordingId"
        const val TEXT = "text"
    }
}
