package com.lmalakar.onecr.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of one update check. */
sealed interface UpdateCheck {
    data object UpToDate : UpdateCheck

    /** A newer release exists on GitHub. */
    data class Available(val info: UpdateInfo) : UpdateCheck

    /** The check itself failed (offline, rate limit, bad body). */
    data class Failed(val reason: String) : UpdateCheck
}

/**
 * Talks to GitHub's `releases/latest` endpoint (v0.3.3).
 *
 * The HTTP fetch is injected as a suspend `(String) -> String?` so unit tests
 * can serve canned responses without a network stack; the production default
 * is [httpGet] — a plain [HttpURLConnection] with short timeouts. This is a
 * background nicety, never worth blocking the UI or the battery for.
 */
class UpdateChecker(
    private val fetch: suspend (url: String) -> String? = ::httpGet,
) {

    suspend fun check(currentVersion: String): UpdateCheck = withContext(Dispatchers.IO) {
        val body = runCatching { fetch(GithubReleases.LATEST_URL) }.getOrNull()
            ?: return@withContext UpdateCheck.Failed("network")
        val info = GithubReleases.parseLatest(body)
            ?: return@withContext UpdateCheck.Failed("bad release payload")
        if (GithubReleases.isNewer(currentVersion, info.versionName)) {
            UpdateCheck.Available(info)
        } else {
            UpdateCheck.UpToDate
        }
    }
}

private const val TIMEOUT_MS = 10_000

/** Plain JVM HTTP GET; returns null for any non-2xx or IO error. */
internal suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", GithubReleases.ACCEPT_HEADER)
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
