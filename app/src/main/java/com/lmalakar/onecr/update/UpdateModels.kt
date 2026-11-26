package com.lmalakar.onecr.update

import java.io.File

/** GitHub release metadata the updater needs (v0.3.3). */
data class UpdateInfo(
    /** Latest released version, e.g. "0.3.3" (tag prefix stripped). */
    val versionName: String,

    /** Release title, e.g. "oneCR v0.3.3 — update checker". */
    val releaseTitle: String,

    /** Release notes body, cleaned of markdown noise for display. */
    val releaseNotes: String,

    /** ISO-8601 publish timestamp from the API (raw). */
    val publishedAt: String?,

    /** Direct browser download URL of the release APK asset. */
    val apkUrl: String,

    /** APK size in bytes (shown on the update page). */
    val apkSizeBytes: Long,

    /** SHA256SUMS.txt asset URL when the release ships one (integrity check). */
    val checksumsUrl: String?,

    /** HTML page of the release (opened by "View on GitHub"). */
    val releaseUrl: String,
)

/** Overall updater state the update page and About section observe. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** A newer version exists — carries everything the update page renders. */
    data class Available(val info: UpdateInfo) : UpdateState

    /** Checked and the installed version is the latest. */
    data class UpToDate(val currentVersion: String) : UpdateState

    /** Check failed (offline, rate limit, API hiccup) — silent unless forced. */
    data class CheckFailed(val message: String) : UpdateState
}

/** Download/apply progress for the "Update" button flow. */
sealed interface DownloadState {
    data object NotStarted : DownloadState

    /** Android's per-app "Install unknown apps" grant is missing — user must allow it first. */
    data object NeedsPermission : DownloadState

    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : DownloadState {
        val percent: Int
            get() = if (totalBytes > 0) ((receivedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    /** Downloaded — comparing against the release's SHA256SUMS.txt. */
    data object Verifying : DownloadState

    /** Verified and ready; the installer is launched (or re-launched from the page). */
    data class Ready(val apkFile: File) : DownloadState

    /** Download or verification failed — the page offers Retry. */
    data class Failed(val message: String) : DownloadState
}

/**
 * Release-version comparison for the update checker ("0.3.3" vs "v0.3.3" vs
 * the installed versionName). Numeric component-wise, tolerant of a leading
 * `v` and of pre-release suffixes ("0.4.0-beta" compares as 0.4.0 — oneCR
 * publishes stable tags only, so this is a convenience, not a spec).
 */
object UpdateVersions {

    /** "v0.3.3-beta" → [0, 3, 3]; "" / garbage → empty list. */
    fun parse(version: String): List<Int> {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (core.isEmpty()) return emptyList()
        return core.split('.').mapNotNull { segment ->
            val digits = segment.takeWhile { it.isDigit() }
            digits.takeIf { it.isNotEmpty() }?.toIntOrNull()
        }
    }

    /** True when [latest] is strictly newer than [current]. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        if (a.isEmpty() || b.isEmpty()) return false
        val width = maxOf(a.size, b.size)
        for (i in 0 until width) {
            val l = a.getOrElse(i) { 0 }
            val r = b.getOrElse(i) { 0 }
            if (l != r) return l > r
        }
        return false
    }
}
