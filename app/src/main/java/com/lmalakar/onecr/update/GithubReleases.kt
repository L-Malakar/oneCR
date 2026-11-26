package com.lmalakar.onecr.update

/**
 * One GitHub release, reduced to what the updater needs (v0.3.3).
 * Everything here comes from `GET /repos/L-Malakar/oneCR/releases/latest`.
 */
data class UpdateInfo(
    /** Release tag with any leading "v" stripped — "0.3.4", never "v0.3.4". */
    val versionName: String,
    /** The release body (markdown as written on GitHub). */
    val releaseNotes: String,
    /** Direct download URL of the release APK asset. */
    val apkUrl: String,
    /** The release page URL (used by the "open on GitHub" affordances). */
    val releaseUrl: String,
    /** ISO-8601 timestamp of publication, shown as-is in the details. */
    val publishedAt: String,
)

/**
 * Parses the `releases/latest` document and compares versions.
 *
 * Kept free of Android imports so it is unit-testable on the JVM; the only
 * dependency is [MiniJson]. A body that is not a release (rate-limit message,
 * HTML error page, 404 JSON) yields null instead of an exception — the caller
 * treats that as "check failed", never as "no update".
 */
object GithubReleases {

    const val LATEST_URL = "https://api.github.com/repos/L-Malakar/oneCR/releases/latest"

    /** Header GitHub asks for on the REST API. */
    const val ACCEPT_HEADER = "application/vnd.github+json"

    /**
     * Extracts [UpdateInfo] from a `releases/latest` JSON body.
     * Returns null when any required field is missing or no `.apk` asset
     * exists — both mean "this release cannot be installed", not "no update".
     */
    fun parseLatest(json: String): UpdateInfo? = runCatching {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val tag = root["tag_name"] as? String ?: return null
        val htmlUrl = root["html_url"] as? String ?: return null
        val assets = root["assets"] as? List<*> ?: return null
        val apkUrl = assets.firstNotNullOfOrNull { asset ->
            val m = asset as? Map<*, *> ?: return@firstNotNullOfOrNull null
            val name = m["name"] as? String ?: return@firstNotNullOfOrNull null
            if (name.endsWith(".apk", ignoreCase = true)) {
                m["browser_download_url"] as? String
            } else {
                null
            }
        } ?: return null
        UpdateInfo(
            versionName = tag.trim().removePrefix("v").removePrefix("V"),
            releaseNotes = (root["body"] as? String).orEmpty().trim(),
            apkUrl = apkUrl,
            releaseUrl = htmlUrl,
            publishedAt = (root["published_at"] as? String).orEmpty(),
        )
    }.getOrNull()

    /**
     * True when [candidate] sorts after [current]. Segments compare
     * numerically when both are numeric ("0.3.10" > "0.3.4"), lexically
     * otherwise; a missing trailing segment counts as 0 ("0.3" < "0.3.1").
     * Prerelease suffixes ("4-beta") lose to the plain release ("4").
     */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = segments(current)
        val b = segments(candidate)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val sa = a.getOrElse(i) { Segment(0, "") }
            val sb = b.getOrElse(i) { Segment(0, "") }
            val numCmp = sa.number.compareTo(sb.number)
            if (numCmp != 0) return numCmp < 0
            val sfxCmp = sa.suffix.compareTo(sb.suffix, ignoreCase = true)
            if (sfxCmp != 0) {
                // A suffix means prerelease: "4" beats "4-beta" whichever
                // side it is on; between suffixes alphabetical decides.
                return when {
                    sa.suffix.isEmpty() -> false // candidate carries a suffix → prerelease
                    sb.suffix.isEmpty() -> true  // current carries a suffix, candidate is the release
                    else -> sfxCmp < 0
                }
            }
        }
        return false
    }

    private fun segments(version: String): List<Segment> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_')
            .filter { it.isNotEmpty() }
            .map { raw ->
                Segment(
                    number = raw.takeWhile { it.isDigit() }.toIntOrNull() ?: 0,
                    suffix = raw.dropWhile { it.isDigit() },
                )
            }

    private data class Segment(val number: Int, val suffix: String)
}
