package com.lmalakar.onecr.data.files

import java.io.File

/**
 * One recording session as tracked by the [RecordingJournal] (M3 groundwork,
 * v0.3.4). The journal is the crash-recovery metadata for a take: who called,
 * which capture source fed it, when it started/ended and where the audio is.
 *
 * A session moves through exactly three states:
 *  - [STATE_STARTED]   — journal written when capture begins
 *  - [STATE_FINALIZED] — audio mixed and the final file verified on disk
 *  - [STATE_ORPHANED]  — the sweep decided this take can never be finalized
 *                        (crashed mid-call, final file vanished, …)
 */
data class RecordingSession(
    val id: String,
    val direction: String,
    val phone: String? = null,
    val source: String,
    val sampleRate: Int,
    val channels: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val finalPath: String? = null,
    val finalBytes: Long? = null,
    val state: String = RecordingJournal.STATE_STARTED,
    val orphanReason: String? = null,
)

/** Result of one [RecordingJournal.sweepOrphans] pass. */
data class SweepReport(
    val scanned: Int,
    val orphaned: List<Pair<RecordingSession, String>>,
) {
    val orphanedCount: Int get() = orphaned.size
}

/**
 * Per-recording journal (filesDir/recordings/<id>/journal.json — see
 * [StorageLayout]), the M3 counterpart of the event log: one JSON object per
 * take, written atomically (temp file + rename) so a crash mid-write can never
 * leave a half journal behind.
 *
 * Pure java.io, hand-rolled one-line JSON (same style as [EventLogStore]),
 * unit-testable on the JVM.
 */
class RecordingJournal(
    private val recordingsRoot: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private fun dirOf(id: String): File = File(recordingsRoot, id)

    private fun journalFile(id: String): File = File(dirOf(id), StorageLayout.JOURNAL_FILE)

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Records the start of a take; overwrites any previous journal for [id]. */
    fun start(session: RecordingSession) {
        require(session.state == STATE_STARTED) { "start() expects a fresh session" }
        writeJournal(session)
    }

    /**
     * Marks the take complete. The final file must exist and match [finalBytes]
     * — a finalize against a file that vanished (storage unmounted, cleaner)
     * is refused so the state stays honest.
     */
    fun finalize(
        id: String,
        endedAt: Long = clock(),
        finalPath: String,
        finalBytes: Long,
    ): RecordingSession? {
        val session = load(id) ?: return null
        check(session.state == STATE_STARTED) { "session $id already ${session.state}" }
        val target = File(recordingsRoot, finalPath)
        check(target.isFile && target.length() == finalBytes) {
            "final file missing or size mismatch: $finalPath"
        }
        val updated = session.copy(
            endedAt = endedAt,
            finalPath = finalPath,
            finalBytes = finalBytes,
            state = STATE_FINALIZED,
        )
        writeJournal(updated)
        return updated
    }

    /** Loads one session's journal; null when the take (or journal) is unknown. */
    fun load(id: String): RecordingSession? = parse(journalFile(id))

    /** Every session with a journal on disk, oldest first. */
    fun list(): List<RecordingSession> {
        val dirs = recordingsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.mapNotNull { parse(File(it, StorageLayout.JOURNAL_FILE)) }
            .sortedBy { it.startedAt }
    }

    // ------------------------------------------------------------------
    // Crash recovery
    // ------------------------------------------------------------------

    /**
     * Marks sessions that can never complete as [STATE_ORPHANED]:
     *
     *  - `stale-started` — started long ago, never finalized, no final audio
     *    (the app died mid-call; the raw streams may exist but cannot be
     *    trusted without the mixer that was supposed to run)
     *  - `journal-missing` — a recording directory with audio files but no
     *    journal (manual copy-in, or a journal write that crashed)
     *  - `final-missing` — finalized in the journal but the final file is gone
     *
     * The sweep never deletes anything — deletion is a user decision (M4's
     * storage manager). It only rewrites the journals it orphans.
     */
    fun sweepOrphans(staleAfterMs: Long = DEFAULT_STALE_MS): SweepReport {
        val now = clock()
        val orphaned = mutableListOf<Pair<RecordingSession, String>>()
        val dirs = recordingsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()

        for (dir in dirs) {
            val session = parse(File(dir, StorageLayout.JOURNAL_FILE))
            if (session == null) {
                if (hasAudio(dir)) {
                    orphaned += RecordingSession(
                        id = dir.name,
                        direction = DIRECTION_UNKNOWN,
                        source = SOURCE_UNKNOWN,
                        sampleRate = 0,
                        channels = 0,
                        startedAt = dir.lastModified(),
                        state = STATE_ORPHANED,
                        orphanReason = REASON_JOURNAL_MISSING,
                    ) to REASON_JOURNAL_MISSING
                }
                continue
            }
            when {
                session.state == STATE_STARTED -> {
                    val hasFinal = session.finalPath != null &&
                        File(recordingsRoot, session.finalPath).isFile
                    if (hasFinal) continue // finalize crashed after mixing; safe to keep
                    if (now - session.startedAt > staleAfterMs) {
                        markOrphaned(session, REASON_STALE_STARTED)
                        orphaned += session to REASON_STALE_STARTED
                    }
                }

                session.state == STATE_FINALIZED -> {
                    val finalOk = session.finalPath != null &&
                        File(recordingsRoot, session.finalPath).let { it.isFile && it.length() > 0 }
                    if (!finalOk) {
                        markOrphaned(session, REASON_FINAL_MISSING)
                        orphaned += session to REASON_FINAL_MISSING
                    }
                }
                // already orphaned — leave it be
            }
        }
        return SweepReport(scanned = dirs.size, orphaned = orphaned)
    }

    private fun markOrphaned(session: RecordingSession, reason: String) {
        writeJournal(
            session.copy(state = STATE_ORPHANED, orphanReason = reason),
        )
    }

    /**
     * True when the recording directory holds audio anywhere the StorageLayout
     * puts it: the dir itself and its `raw/` / `final/` subdirectories.
     */
    private fun hasAudio(recordingDir: File): Boolean {
        val here = recordingDir.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
        if (!here.isNullOrEmpty()) return true
        val subdirs = recordingDir.listFiles { f -> f.isDirectory } ?: return false
        return subdirs.any { sub ->
            val wavs = sub.listFiles { f -> f.isFile && f.extension.equals("wav", true) }
            !wavs.isNullOrEmpty()
        }
    }

    // ------------------------------------------------------------------
    // Serialization — one-line JSON, fixed field order (EventLogStore style)
    // ------------------------------------------------------------------

    private fun writeJournal(session: RecordingSession) {
        runCatching {
            val dir = dirOf(session.id).apply { mkdirs() }
            val tmp = File(dir, StorageLayout.JOURNAL_FILE + ".tmp")
            tmp.writeText(serialize(session))
            if (!tmp.renameTo(journalFile(session.id))) {
                // Fall back to a plain write when rename fails (e.g. across
                // mount points on some OEMs) — same content, less atomicity.
                journalFile(session.id).writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun serialize(s: RecordingSession): String = buildString {
        append("{\"id\":\"").append(EventLogStore.escape(s.id)).append('"')
        append(",\"dir\":\"").append(EventLogStore.escape(s.direction)).append('"')
        phone(s.phone)
        append(",\"src\":\"").append(EventLogStore.escape(s.source)).append('"')
        append(",\"rate\":").append(s.sampleRate)
        append(",\"ch\":").append(s.channels)
        append(",\"t0\":").append(s.startedAt)
        optLong("t1", s.endedAt)
        optString("path", s.finalPath)
        optLong("bytes", s.finalBytes)
        append(",\"state\":\"").append(EventLogStore.escape(s.state)).append('"')
        optString("why", s.orphanReason)
        append('}')
    }

    private fun StringBuilder.phone(p: String?) {
        if (p == null) return
        append(",\"phone\":\"").append(EventLogStore.escape(p)).append('"')
    }

    private fun StringBuilder.optString(key: String, value: String?) {
        if (value == null) return
        append(",\"").append(key).append("\":\"").append(EventLogStore.escape(value)).append('"')
    }

    private fun StringBuilder.optLong(key: String, value: Long?) {
        if (value == null) return
        append(",\"").append(key).append("\":").append(value)
    }

    private fun parse(file: File): RecordingSession? {
        val line = runCatching { file.readText() }.getOrNull() ?: return null
        val id = stringField(line, "\"id\":\"") ?: return null
        val direction = stringField(line, "\"dir\":\"") ?: return null
        return RecordingSession(
            id = id,
            direction = direction,
            phone = stringField(line, "\"phone\":\""),
            source = stringField(line, "\"src\":\"") ?: SOURCE_UNKNOWN,
            sampleRate = longField(line, "\"rate\":")?.toInt() ?: 0,
            channels = longField(line, "\"ch\":")?.toInt() ?: 0,
            startedAt = longField(line, "\"t0\":") ?: 0L,
            endedAt = longField(line, "\"t1\":"),
            finalPath = stringField(line, "\"path\":\""),
            finalBytes = longField(line, "\"bytes\":"),
            state = stringField(line, "\"state\":\"") ?: STATE_STARTED,
            orphanReason = stringField(line, "\"why\":\""),
        )
    }

    /** Extracts the raw (still escaped) value, then resolves escapes — so an
     *  escaped quote inside a value can never end the field early. */
    private fun stringField(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val rest = line.substring(i + key.length)
        val raw = StringBuilder()
        var idx = 0
        while (idx < rest.length) {
            val c = rest[idx]
            if (c == '\\' && idx + 1 < rest.length) {
                raw.append(c).append(rest[idx + 1])
                idx += 2
                continue
            }
            if (c == '"') break
            raw.append(c)
            idx++
        }
        return if (idx >= rest.length && raw.isEmpty()) null else EventLogStore.unescape(raw.toString())
    }

    private fun longField(line: String, key: String): Long? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val rest = line.substring(i + key.length)
        return rest.takeWhile { it.isDigit() || it == '-' }.toLongOrNull()
    }

    companion object {
        const val STATE_STARTED = "started"
        const val STATE_FINALIZED = "finalized"
        const val STATE_ORPHANED = "orphaned"

        const val DIRECTION_UNKNOWN = "unknown"
        const val SOURCE_UNKNOWN = "unknown"

        const val REASON_STALE_STARTED = "stale-started"
        const val REASON_JOURNAL_MISSING = "journal-missing"
        const val REASON_FINAL_MISSING = "final-missing"

        /** 24 h — a started session older than this can never still be live. */
        const val DEFAULT_STALE_MS = 24L * 60 * 60 * 1000
    }
}
