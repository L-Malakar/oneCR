package com.lmalakar.onecr.service.recorder

import com.lmalakar.onecr.data.files.StorageLayout
import java.io.File

/**
 * Crash-recovery journal for one recording session (M3).
 *
 * One `k=v` line per field under `recordings/<id>/journal.json` (the name
 * predates the format — kept stable on purpose, see StorageLayout). The file
 * is rewritten atomically (tmp + rename) so a kill mid-write never corrupts
 * the previous state. Statuses:
 *
 *   recording — session open, WAV growing
 *   recovered — process died mid-session; orphan sweep patched the WAV sizes
 *   finalized — clean stop
 *   failed    — capture stream never produced audio / opening failed
 *
 * Pure JVM: unit-testable.
 */
data class RecordingJournal(
    val recordingId: String,
    val status: String = STATUS_RECORDING,
    val startedAtMillis: Long,
    val endedAtMillis: Long = 0,
    val direction: String = "UNKNOWN",
    val phone: String? = null,
    val trigger: String = TRIGGER_MANUAL,
    val captureSource: String = "",
    val sampleRateHz: Int = 0,
    val wavFile: String = "",
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
) {

    fun save(dir: File) {
        dir.mkdirs()
        val tmp = File(dir, TMP_NAME)
        val lines = buildList {
            add("recordingId=$recordingId")
            add("status=$status")
            add("startedAtMillis=$startedAtMillis")
            add("endedAtMillis=$endedAtMillis")
            add("direction=$direction")
            add("phone=$phone")
            add("trigger=$trigger")
            add("captureSource=$captureSource")
            add("sampleRateHz=$sampleRateHz")
            add("wavFile=$wavFile")
            add("sizeBytes=$sizeBytes")
            add("durationMs=$durationMs")
            add("error=$error")
        }
        tmp.writeText(lines.joinToString("\n") + "\n")
        val target = File(dir, StorageLayout.JOURNAL_FILE)
        if (!tmp.renameTo(target)) {
            // Cross-device rename edge: fall back to a plain copy+delete.
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val STATUS_RECORDING = "recording"
        const val STATUS_RECOVERED = "recovered"
        const val STATUS_FINALIZED = "finalized"
        const val STATUS_FAILED = "failed"

        const val TRIGGER_AUTO = "auto"
        const val TRIGGER_MANUAL = "manual"

        private const val TMP_NAME = "journal.tmp"

        /** Loads the journal of one recording dir, or null if absent/garbled. */
        fun load(dir: File): RecordingJournal? {
            val file = File(dir, StorageLayout.JOURNAL_FILE)
            if (!file.exists()) return null
            return runCatching { parse(file.readText()) }.getOrNull()
        }

        fun parse(text: String): RecordingJournal? {
            val map = mutableMapOf<String, String>()
            for (line in text.lineSequence()) {
                val idx = line.indexOf('=')
                if (idx <= 0) continue
                map[line.substring(0, idx)] = line.substring(idx + 1)
            }
            val id = map["recordingId"] ?: return null
            val started = map["startedAtMillis"]?.toLongOrNull() ?: return null
            // "null" and "" both mean a missing optional field (save writes null
            // verbatim; parse must round-trip it back to a real null).
            fun optional(key: String): String? =
                map[key]?.takeIf { it.isNotEmpty() && it != "null" }
            return RecordingJournal(
                recordingId = id,
                status = map["status"] ?: STATUS_RECORDING,
                startedAtMillis = started,
                endedAtMillis = map["endedAtMillis"]?.toLongOrNull() ?: 0,
                direction = map["direction"] ?: "UNKNOWN",
                phone = optional("phone"),
                trigger = map["trigger"] ?: TRIGGER_MANUAL,
                captureSource = map["captureSource"].orEmpty(),
                sampleRateHz = map["sampleRateHz"]?.toIntOrNull() ?: 0,
                wavFile = map["wavFile"].orEmpty(),
                sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0,
                durationMs = map["durationMs"]?.toLongOrNull() ?: 0,
                error = optional("error"),
            )
        }

        /**
         * Orphan sweep (boot / first engine start): every dir whose journal says
         * `recording` belonged to a killed process. The WAV header carries
         * placeholder sizes — patch them from the actual file length and mark
         * the recording `recovered` so the future library screen (M4) can list it.
         *
         * @return number of recovered sessions.
         */
        fun sweepOrphans(recordingsRoot: File, timeNowMillis: Long): Int {
            var recovered = 0
            val root = recordingsRoot.takeIf { it.isDirectory } ?: return 0
            for (dir in root.listFiles().orEmpty()) {
                if (!dir.isDirectory) continue
                val journal = load(dir) ?: continue
                if (journal.status != STATUS_RECORDING) continue

                val wav = File(dir, journal.wavFile.ifBlank { StorageLayout.FINAL_WAV })
                val bytes = (wav.length() - WavWriter.HEADER_BYTES).coerceAtLeast(0)
                if (wav.exists() && bytes > 0) {
                    // The killed process never patched the sizes — fix the
                    // RIFF/data lengths from the real file size so any player
                    // (and the future M4 library screen) can open it.
                    WavWriter.patchHeaderSizes(wav)
                }
                val patch = journal.copy(
                    status = STATUS_RECOVERED,
                    endedAtMillis = if (journal.endedAtMillis > 0) journal.endedAtMillis else timeNowMillis,
                    sizeBytes = bytes,
                    durationMs = if (journal.sampleRateHz > 0) {
                        // mono s16 → bytes per second = rate * 2; ms = bytes * 1000 / bps
                        bytes * 1000 / (journal.sampleRateHz * 2)
                    } else {
                        0
                    },
                )
                patch.save(dir)
                recovered++
            }
            return recovered
        }
    }
}
