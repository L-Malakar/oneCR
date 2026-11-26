package com.lmalakar.onecr.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the release APK (with SHA-256 verification against the release's
 * SHA256SUMS.txt when present) into app-specific storage and builds the
 * install intent. v0.3.3.
 *
 * No storage permission is needed: the APK lands in
 * `getExternalFilesDir("updates")` and is handed to the system installer
 * through a [FileProvider] content URI.
 */
class ApkDownloader {

    /** Listener for chunked progress (called on the download thread). */
    fun interface ProgressListener {
        fun onProgress(receivedBytes: Long, totalBytes: Long)
    }

    /**
     * Streams [apkUrl] to a file under "updates/" and returns it. Verifies the
     * SHA-256 against [checksumsUrl] when provided — a mismatch throws
     * [ChecksumMismatchException] and the partial file is deleted.
     */
    fun download(
        apkUrl: String,
        apkSizeBytes: Long,
        checksumsUrl: String?,
        targetDir: File,
        fileName: String,
        progress: ProgressListener,
    ): File {
        targetDir.mkdirs()
        val target = File(targetDir, fileName)
        val digest = MessageDigest.getInstance("SHA-256")

        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "oneCR-update-download")
        }
        try {
            val total = connection.contentLong().takeIf { it > 0 } ?: apkSizeBytes
            var received = 0L
            connection.inputStream.use { input ->
                File(target.absolutePath + PART_SUFFIX).outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        received += n
                        progress.onProgress(received, total)
                    }
                }
            }
            if (received <= 0) throw IOException("downloaded 0 bytes from $apkUrl")

            // Integrity: compare against the release's published sums when the
            // release ships SHA256SUMS.txt (the CI release job always does).
            if (checksumsUrl != null) {
                val expected = fetchExpectedSha256(checksumsUrl, target.name)
                if (expected != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expected, ignoreCase = true)) {
                        File(target.absolutePath + PART_SUFFIX).delete()
                        throw ChecksumMismatchException(expected, actual)
                    }
                }
            }

            val part = File(target.absolutePath + PART_SUFFIX)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** Expected hex digest for [fileName] from a sha256sum-style listing, or null. */
    private fun fetchExpectedSha256(checksumsUrl: String, fileName: String): String? {
        val connection = (URL(checksumsUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "oneCR-update-download")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val text = connection.inputStream.use { it.readBytes() }.decodeToString()
                text.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.endsWith(fileName) }
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
            }
        } catch (_: Throwable) {
            null // checksum list unreachable → proceed without verification
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.contentLong(): Long =
        try { contentLengthLong } catch (_: Throwable) { contentLength.toLong() }

    // ------------------------------------------------------------------
    // Permission + install plumbing
    // ------------------------------------------------------------------

    companion object {

        private const val PART_SUFFIX = ".part"

        /** Android 8+ per-app "Install unknown apps" grant for this app. */
        fun canRequestInstalls(context: Context): Boolean =
            context.packageManager.canRequestPackageInstalls()

        /** The system screen the user lands on to allow installs from oneCR. */
        fun unknownAppsSettingsIntent(context: Context): Intent =
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Intent that opens the system package installer for the downloaded
         * APK. The file is app-private, so the installer gets read access via
         * the [FileProvider] grant only.
         */
        fun installIntent(context: Context, apkFile: File): Intent {
            val authority = "${context.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return intent
        }

        /** Stable on-disk name for a release APK, e.g. `oneCR-v0.3.3.apk`. */
        fun fileNameFor(versionName: String): String = "oneCR-v$versionName.apk"

        /** Deletes leftovers of an aborted/failed download. */
        fun cleanup(context: Context, versionName: String) {
            val dir = updatesDir(context)
            File(dir, fileNameFor(versionName)).delete()
            File(dir, fileNameFor(versionName) + PART_SUFFIX).delete()
        }

        /** App-specific update storage (external when mounted, internal fallback). */
        fun updatesDir(context: Context): File =
            context.getExternalFilesDir(null)?.let { File(it, "updates") }
                ?: File(context.filesDir, "updates")

        /**
         * An already-verified APK for [versionName], if a previous download
         * completed (lets the update page re-offer Install without re-downloading).
         */
        fun existingVerifiedApk(context: Context, versionName: String): File? {
            val f = File(updatesDir(context), fileNameFor(versionName))
            return f.takeIf { it.exists() && it.length() > HEADER_SAFETY_MIN_BYTES }
        }

        private const val HEADER_SAFETY_MIN_BYTES = 100_000L
    }

    /** SHA-256 of the downloaded APK did not match the release's published sum. */
    class ChecksumMismatchException(val expected: String, val actual: String) :
        IOException("APK checksum mismatch — expected $expected, got $actual")
}
