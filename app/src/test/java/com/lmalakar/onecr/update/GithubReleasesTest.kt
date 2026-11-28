package com.lmalakar.onecr.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val latestJson = """
    {
      "url": "https://api.github.com/repos/L-Malakar/oneCR/releases/123",
      "html_url": "https://github.com/L-Malakar/oneCR/releases/tag/0.3.4",
      "tag_name": "v0.3.4",
      "name": "oneCR v0.3.4",
      "body": "### Added\r\n- In-app updater\r\n\r\n### Fixed\r\n- About page",
      "draft": false,
      "prerelease": false,
      "published_at": "2026-11-26T09:30:00Z",
      "assets": [
        {
          "name": "SHA256SUMS.txt",
          "browser_download_url": "https://github.com/L-Malakar/oneCR/releases/download/0.3.4/SHA256SUMS.txt"
        },
        {
          "name": "oneCR-v0.3.4.apk",
          "browser_download_url": "https://github.com/L-Malakar/oneCR/releases/download/0.3.4/oneCR-v0.3.4.apk"
        }
      ]
    }
""".trimIndent()

class GithubReleasesTest {

    @Test
    fun `parses the latest release`() {
        val info = GithubReleases.parseLatest(latestJson)
        assertEquals("0.3.4", info!!.versionName)
        assertEquals("https://github.com/L-Malakar/oneCR/releases/download/0.3.4/oneCR-v0.3.4.apk", info.apkUrl)
        assertEquals("https://github.com/L-Malakar/oneCR/releases/tag/0.3.4", info.releaseUrl)
        assertEquals("2026-11-26T09:30:00Z", info.publishedAt)
        assertTrue(info.releaseNotes.contains("In-app updater"))
        assertTrue(info.releaseNotes.contains("\r\n")) // notes stay verbatim
    }

    @Test
    fun `release without apk asset is not installable`() {
        val noApk = latestJson.replace("oneCR-v0.3.4.apk", "oneCR-v0.3.4.zip")
        assertNull(GithubReleases.parseLatest(noApk))
    }

    @Test
    fun `error payloads yield null instead of an update`() {
        assertNull(GithubReleases.parseLatest("""{"message":"Not Found"}"""))
        assertNull(GithubReleases.parseLatest("<html><body>rate limited</body></html>"))
        assertNull(GithubReleases.parseLatest(""))
    }

    @Test
    fun `version comparison is numeric per segment`() {
        assertTrue(GithubReleases.isNewer("0.3.3", "0.3.4"))
        assertTrue(GithubReleases.isNewer("0.3.9", "0.3.10"))
        assertTrue(GithubReleases.isNewer("0.9", "0.10"))
        assertTrue(GithubReleases.isNewer("1.2.3", "2.0.0"))
        assertTrue(GithubReleases.isNewer("0.3", "0.3.1"))
        assertTrue(GithubReleases.isNewer("v0.3.3", "v0.3.4"))
        assertFalse(GithubReleases.isNewer("0.3.4", "0.3.4"))
        assertFalse(GithubReleases.isNewer("0.3.5", "0.3.4"))
        assertFalse(GithubReleases.isNewer("0.4.0", "0.3.99"))
        assertFalse(GithubReleases.isNewer("1.0.0", "1.0.0-beta"))
        assertTrue(GithubReleases.isNewer("1.0.0-beta", "1.0.0"))
    }
}

class UpdateCheckerTest {

    @Test
    fun `newer release becomes Available`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { latestJson })
        val result = checker.check("0.3.3")
        assertTrue(result is UpdateCheck.Available)
        assertEquals("0.3.4", (result as UpdateCheck.Available).info.versionName)
    }

    @Test
    fun `same version is UpToDate`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { latestJson })
        assertTrue(checker.check("0.3.4") is UpdateCheck.UpToDate)
    }

    @Test
    fun `older current version than latest is still UpToDate`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { latestJson })
        assertTrue(checker.check("0.9.9") is UpdateCheck.UpToDate)
    }

    @Test
    fun `fetch failure is Failed, never an update`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { null })
        assertTrue(checker.check("0.3.3") is UpdateCheck.Failed)
    }

    @Test
    fun `garbage body is Failed`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { "502 Bad Gateway" })
        assertTrue(checker.check("0.3.3") is UpdateCheck.Failed)
    }

    // ---- v0.3.7: the web fallback (the "Couldn't reach GitHub" fix) ----

    /** The release page as GitHub serves it: the tag is in every anchor. */
    private val releasePageHtml = """
        <!doctype html><html><head>
        <link rel="canonical" href="https://github.com/L-Malakar/oneCR/releases/tag/v0.3.7">
        <meta property="og:url" content="https://github.com/L-Malakar/oneCR/releases/tag/v0.3.7">
        </head><body><a href="/L-Malakar/oneCR/releases/tag/v0.3.7">oneCR v0.3.7</a></body></html>
    """.trimIndent()

    /** The expanded-assets fragment: the APK asset href (site-relative). */
    private val assetsHtml = """
        <div id="assets">
        <a href="/L-Malakar/oneCR/releases/download/v0.3.7/SHA256SUMS-v0.3.7.txt">SHA256SUMS</a>
        <a href="/L-Malakar/oneCR/releases/download/v0.3.7/oneCR-v0.3.7.apk">oneCR-v0.3.7.apk</a>
        </div>
    """.trimIndent()

    @Test
    fun `parses the tag out of the release page`() {
        assertEquals("v0.3.7", GithubReleases.parseTagFromHtml(releasePageHtml))
        assertNull(GithubReleases.parseTagFromHtml("<html>no tags here</html>"))
        assertNull(GithubReleases.parseTagFromHtml(""))
    }

    @Test
    fun `parses the apk href out of the assets fragment`() {
        assertEquals(
            "https://github.com/L-Malakar/oneCR/releases/download/v0.3.7/oneCR-v0.3.7.apk",
            GithubReleases.parseApkUrlFromAssetsHtml(assetsHtml),
        )
        assertNull(GithubReleases.parseApkUrlFromAssetsHtml("<div>no assets</div>"))
    }

    @Test
    fun `api failure falls back to the releases web page`() = kotlinx.coroutines.runBlocking {
        // Carrier-grade NAT reality: api.github.com is rate-limited (null),
        // github.com pages are not. The web path must still find the update.
        val checker = UpdateChecker(fetch = { url ->
            when {
                url == GithubReleases.LATEST_URL -> null // API rate-limited
                url == GithubReleases.WEB_LATEST_URL -> releasePageHtml
                url.contains("expanded_assets") -> assetsHtml
                else -> null
            }
        })
        val result = checker.check("0.3.6")
        assertTrue(result is UpdateCheck.Available)
        val info = (result as UpdateCheck.Available).info
        assertEquals("0.3.7", info.versionName)
        assertEquals(
            "https://github.com/L-Malakar/oneCR/releases/download/v0.3.7/oneCR-v0.3.7.apk",
            info.apkUrl,
        )
        assertEquals("https://github.com/L-Malakar/oneCR/releases/tag/v0.3.7", info.releaseUrl)
    }

    @Test
    fun `web fallback works without the assets fragment via the canonical name`() =
        kotlinx.coroutines.runBlocking {
            val checker = UpdateChecker(fetch = { url ->
                when {
                    url == GithubReleases.LATEST_URL -> "502 Bad Gateway"
                    url == GithubReleases.WEB_LATEST_URL -> releasePageHtml
                    else -> null // expanded_assets failed
                }
            })
            val result = checker.check("0.3.6")
            assertTrue(result is UpdateCheck.Available)
            val info = (result as UpdateCheck.Available).info
            // The canonical asset name is derived from the tag — this project
            // has always published oneCR-v<version>.apk.
            assertEquals(
                "https://github.com/L-Malakar/oneCR/releases/download/v0.3.7/oneCR-v0.3.7.apk",
                info.apkUrl,
            )
        }

    @Test
    fun `web fallback respects an up-to-date device`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { url ->
            when {
                url == GithubReleases.LATEST_URL -> null
                url == GithubReleases.WEB_LATEST_URL -> releasePageHtml
                else -> assetsHtml
            }
        })
        assertTrue(checker.check("0.3.7") is UpdateCheck.UpToDate)
    }

    @Test
    fun `everything unreachable is still Failed, never an update`() = kotlinx.coroutines.runBlocking {
        val checker = UpdateChecker(fetch = { null })
        assertTrue(checker.check("0.3.6") is UpdateCheck.Failed)
    }
}

class MiniJsonTest {

    @Test
    fun `parses scalars and containers`() {
        assertEquals("hi", MiniJson.parse("\"hi\""))
        assertEquals(1.5, MiniJson.parse("1.5"))
        assertEquals(true, MiniJson.parse("true"))
        assertEquals(null, MiniJson.parse("null"))
        assertEquals(listOf(1.0, 2.0, 3.0), MiniJson.parse("[1, 2, 3]"))
        @Suppress("UNCHECKED_CAST")
        val obj = MiniJson.parse("""{"a": 1, "b": [true, null], "c": {"d": "e"}}""") as Map<String, Any?>
        assertEquals(1.0, obj["a"])
        assertEquals(listOf(true, null), obj["b"])
        assertEquals(mapOf("d" to "e"), obj["c"])
    }

    @Test
    fun `resolves every string escape`() {
        assertEquals(
            "a\"b\\c/d\be\u000Cf\ng\rh\tiě",
            MiniJson.parse(""""a\"b\\c\/d\be\ff\ng\rh\ti\u011B""""),
        )
    }

    @Test
    fun `rejects malformed documents`() {
        listOf(
            "{\"a\":}",
            "[1, 2",
            "\"unterminated",
            "{\"a\" 1}",
            "tru",
            "{}extra",
        ).forEach { text ->
            assertTrue("expected failure for: $text", runCatching { MiniJson.parse(text) }.isFailure)
        }
    }
}
