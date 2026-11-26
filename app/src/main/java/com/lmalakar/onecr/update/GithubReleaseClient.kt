package com.lmalakar.onecr.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Minimal GitHub Releases API client for the update checker (v0.3.3).
 *
 * Talks to `releases/latest` with plain HttpURLConnection + the platform
 * org.json parser — zero new dependencies for a privacy-first app. oneCR only
 * ever *reads* this one endpoint: no accounts, no telemetry, no crash
 * reporting; the request carries nothing but a generic User-Agent.
 */
class GithubReleaseClient(
    private val repoOwner: String = "L-Malakar",
    private val repoName: String = "oneCR",
) {

    /** Thrown when the API answers but the payload cannot be used. */
    class ReleaseParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Fetches the latest published (non-draft, non-prerelease) release.
     *
     * @return parsed [UpdateInfo], or null when the repo has no releases yet
     *   (HTTP 404) — that is a normal state for a fresh install, not an error.
     * @throws Exception on network problems / unparseable payloads (caller decides).
     */
    fun fetchLatest(): UpdateInfo? {
        val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "oneCR-update-check")
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code != HttpURLConnection.HTTP_OK) {
                throw ReleaseParseException("GitHub API returned HTTP $code")
            }
            val body = connection.inputStream.use { it.readBytes() }.decodeToString()
            return parse(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    /** Pure JSON → [UpdateInfo] mapping (kept visible for tests/debugging). */
    fun parse(payload: JSONObject): UpdateInfo {
        val tagName = payload.optString("tag_name")
        if (tagName.isBlank()) throw ReleaseParseException("release payload has no tag_name")

        val apkAsset = payload.optJSONArray("assets")
            ?.let { assets ->
                (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .filter { asset ->
                        val name = asset.optString("name")
                        name.endsWith(".apk", ignoreCase = true) &&
                            name.startsWith("oneCR", ignoreCase = true)
                    }
                    .maxByOrNull { it.optString("name").length } // prefer oneCR-vX.Y.Z over generic names
            }
        val apkUrl = apkAsset?.optString("browser_download_url").orEmpty()
        if (apkUrl.isBlank()) throw ReleaseParseException("release $tagName has no oneCR APK asset")

        val checksumsUrl = payload.optJSONArray("assets")
            ?.let { assets ->
                (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == "SHA256SUMS.txt" }
                    ?.optString("browser_download_url")
                    ?.takeIf { it.isNotBlank() }
            }

        return UpdateInfo(
            versionName = tagName.trim().removePrefix("v").removePrefix("V"),
            releaseTitle = payload.optString("name").ifBlank { "oneCR v$tagName" },
            releaseNotes = payload.optString("body").orEmpty(),
            publishedAt = payload.optString("published_at").takeIf { it.isNotBlank() },
            apkUrl = apkUrl,
            apkSizeBytes = apkAsset?.optLong("size") ?: 0L,
            checksumsUrl = checksumsUrl,
            releaseUrl = payload.optString("html_url").ifBlank {
                "https://github.com/$repoOwner/$repoName/releases"
            },
        )
    }
}
