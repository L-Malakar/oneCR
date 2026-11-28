package com.lmalakar.onecr.update

import com.lmalakar.onecr.BuildConfig
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
        // 1) REST API first — the rich payload (release notes, date, asset list).
        runCatching { fetch(GithubReleases.LATEST_URL) }.getOrNull()
            ?.let { body -> GithubReleases.parseLatest(body) }
            ?.let { info -> return@withContext decide(currentVersion, info) }

        // 2) Web fallback (v0.3.7): the unauthenticated api.github.com quota
        //    (60 req/h per IP) is exhausted within minutes on carrier-grade
        //    NAT, which is exactly why both the automatic and the manual check
        //    kept failing with "Couldn't reach GitHub" for every user on a
        //    mobile network. The releases page has no such quota.
        runCatching { GithubReleases.latestViaWeb(fetch) }.getOrNull()
            ?.let { info -> return@withContext decide(currentVersion, info) }

        UpdateCheck.Failed("network")
    }

    private fun decide(currentVersion: String, info: UpdateInfo): UpdateCheck =
        if (GithubReleases.isNewer(currentVersion, info.versionName)) {
            UpdateCheck.Available(info)
        } else {
            UpdateCheck.UpToDate
        }
}

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

/** Plain JVM HTTP GET; returns null for any non-2xx or IO error. */
internal suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", GithubReleases.ACCEPT_HEADER)
            // GitHub rejects requests without a User-Agent, and some carriers
            // strip the platform default — always send an explicit one.
            conn.setRequestProperty("User-Agent", "oneCR/${BuildConfig.VERSION_NAME} (in-app updater)")
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
