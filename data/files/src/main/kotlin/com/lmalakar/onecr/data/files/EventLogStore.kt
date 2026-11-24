package com.lmalakar.onecr.data.files

import java.io.File

/**
 * One entry of the in-app debug Event Log (Settings → Event log).
 * Kept deliberately small: timestamp + kind + human message.
 */
data class EventLogEntry(
    val timestampMillis: Long,
    val kind: String,
    val message: String,
) {
    companion object {
        const val KIND_CALL = "call"
        const val KIND_VOIP = "voip"
        const val KIND_PRIVILEGE = "privilege"
        const val KIND_SERVICE = "service"
        const val KIND_ERROR = "error"
        const val KIND_DEBUG = "debug"
    }
}

/**
 * Append-only JSONL event log under filesDir/logs/events.jsonl (see StorageLayout).
 *
 * Pure JVM (java.io only) so it is unit-testable and usable from any layer.
 * The on-disk line format is: {"ts":<millis>,"kind":"<kind>","msg":"<message>"}
 * Malformed lines are skipped on load — the log must never crash the app.
 *
 * Retention: when the file exceeds [maxLines] lines, it is rewritten keeping only
 * the newest [keepLines]. The in-memory API is a bounded tail, not a database —
 * the real recording history lives in Room (M4).
 */
class EventLogStore(
    private val logDir: File,
    private val maxLines: Int = 2_000,
    private val keepLines: Int = 1_000,
) {

    private val logFile: File
        get() = File(logDir.apply { mkdirs() }, StorageLayout.EVENT_LOG_FILE)

    /** Appends one entry (best effort — IO errors are swallowed, never thrown). */
    fun append(entry: EventLogEntry) {
        runCatching {
            logFile.appendText(lineOf(entry) + "\n")
            trimIfNeeded()
        }
    }

    /** Loads the newest [limit] entries, oldest first. */
    fun loadRecent(limit: Int = 100): List<EventLogEntry> {
        val lines = runCatching { logFile.readLines() }.getOrDefault(emptyList())
        return lines.mapNotNull(::parseLine).takeLast(limit)
    }

    /** Wipes the log file. */
    fun clear() {
        runCatching { logFile.delete() }
    }

    // ------------------------------------------------------------------

    private fun lineOf(entry: EventLogEntry): String =
        "{\"ts\":${entry.timestampMillis},\"kind\":\"${escape(entry.kind)}\"," +
            "\"msg\":\"${escape(entry.message)}\"}"

    private fun parseLine(line: String): EventLogEntry? {
        if (!line.startsWith("{") || !line.endsWith("}")) return null
        val ts = longField(line, "\"ts\":") ?: return null
        val kind = stringField(line, "\"kind\":\"") ?: return null
        val msg = stringField(line, "\"msg\":\"") ?: return null
        return EventLogEntry(ts, kind, msg)
    }

    private fun longField(line: String, key: String): Long? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val rest = line.substring(i + key.length)
        val digits = rest.takeWhile { it.isDigit() || it == '-' }
        return digits.toLongOrNull()
    }

    private fun stringField(line: String, key: String): String? {
        val i = line.indexOf(key)
        if (i < 0) return null
        val rest = line.substring(i + key.length)
        // Raw value = chars up to the first UNESCAPED quote; escapes stay intact
        // here and are resolved by [unescape] so round-tripping is exact.
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
        return unescape(raw.toString())
    }

    private fun trimIfNeeded() {
        val lines = runCatching { logFile.readLines() }.getOrNull() ?: return
        if (lines.size <= maxLines) return
        runCatching { logFile.writeText(lines.takeLast(keepLines).joinToString("\n", postfix = "\n")) }
    }

    companion object {
        /** Minimal JSON string escaping (enough for a debug log; no external deps). */
        internal fun escape(s: String): String = buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append(' ') else append(c)
                }
            }
        }

        internal fun unescape(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        'n' -> { append('\n'); i += 2 }
                        'r' -> { append('\r'); i += 2 }
                        't' -> { append('\t'); i += 2 }
                        '\\' -> { append('\\'); i += 2 }
                        '"' -> { append('"'); i += 2 }
                        else -> { append(c); append(next); i += 2 }
                    }
                } else {
                    append(c); i++
                }
            }
        }
    }
}
