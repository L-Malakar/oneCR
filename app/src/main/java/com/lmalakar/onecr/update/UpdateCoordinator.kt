package com.lmalakar.onecr.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lmalakar.onecr.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * State of the manual "Check for updates" tap on the About page (v0.3.5).
 * The automatic launch check stays silent — this one answers the user.
 */
sealed interface ManualCheck {
    data object Idle : ManualCheck
    data object Checking : ManualCheck
    data object UpToDate : ManualCheck
    data class Failed(val reason: String) : ManualCheck
}

/** Progress of the APK download shown on the update page. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val bytes: Long, val totalBytes: Long?) : DownloadState
    data class Done(val filePath: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * The in-app updater (v0.3.3), one place for the whole flow:
 *
 *  1. on every app open [checkOnLaunch] asks GitHub for `releases/latest`;
 *  2. a newer version raises [pageVisible] (the update page renders above
 *     everything) and posts the update notification;
 *  3. tapping the notification (or its Update action) calls
 *     [openFromNotification] — same page;
 *  4. the Update button asks for the "Install unknown apps" grant first if
 *     needed; the moment the user returns from that system screen with the
 *     grant, the download continues automatically;
 *  5. the APK streams into filesDir/updates with live progress, and a
 *     finished download launches the system package installer by itself.
 *
 * Cancel is per-session by design: the page and notification come back on
 * the next app open, per the requested behavior — an update should not be
 * silently forgotten.
 */
class UpdateCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val currentVersion: String,
    private val fetch: suspend (url: String) -> String? = ::httpGet,
) {

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    /** The newest GitHub release, once a check has found one. */
    val available: StateFlow<UpdateInfo?> = _available.asStateFlow()

    private val _pageVisible = MutableStateFlow(false)
    /** True while the full-screen update page should render above the app. */
    val pageVisible: StateFlow<Boolean> = _pageVisible.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private val _manualCheck = MutableStateFlow<ManualCheck>(ManualCheck.Idle)
    /** Manual check state the About page renders (v0.3.5). */
    val manualCheck: StateFlow<ManualCheck> = _manualCheck.asStateFlow()

    /** True after the user tapped Update but lacked the install grant. */
    private var awaitingInstallGrant = false

    private var lastCheckedVersion: String? = null

    // ------------------------------------------------------------------
    // 1 + 2 — the launch check
    // ------------------------------------------------------------------

    /** Checks GitHub once per [MainActivity] creation; idempotent per version. */
    fun checkOnLaunch() {
        if (lastCheckedVersion == currentVersion && _available.value != null) return
        lastCheckedVersion = currentVersion
        scope.launch {
            when (val result = UpdateChecker(fetch).check(currentVersion)) {
                is UpdateCheck.Available -> onUpdateFound(result.info)
                is UpdateCheck.UpToDate, is UpdateCheck.Failed -> Unit
            }
        }
    }

    private fun onUpdateFound(info: UpdateInfo) {
        _available.value = info
        _pageVisible.value = true
        UpdateNotifier.notify(appContext, info)
    }

    // ------------------------------------------------------------------
    // 3 — notification entry points
    // ------------------------------------------------------------------

    /** Notification / Update-action tap: open the app on the update page. */
    fun openFromNotification() {
        _available.value?.let {
            _pageVisible.value = true
            UpdateNotifier.cancel(appContext)
        }
    }

    /** Cancel button: hide page + notification for this session. */
    fun dismissForSession() {
        _pageVisible.value = false
        UpdateNotifier.cancel(appContext)
    }

    /** Re-open the page from the About screen when a check found an update. */
    fun showPageIfAvailable() {
        if (_available.value != null) _pageVisible.value = true
    }

    // ------------------------------------------------------------------
    // 6 — the manual check (About page button, v0.3.5)
    // ------------------------------------------------------------------

    /**
     * The About page's "Check for updates": a real GitHub check on demand.
     * A newer release raises the full update page (same as the automatic
     * path); "latest" and failures land in [manualCheck] for the row to
     * show, and the row resets itself a few seconds later.
     */
    fun checkNow() {
        if (_manualCheck.value == ManualCheck.Checking) return
        _manualCheck.value = ManualCheck.Checking
        scope.launch {
            when (val result = UpdateChecker(fetch).check(currentVersion)) {
                is UpdateCheck.Available -> {
                    _manualCheck.value = ManualCheck.Idle
                    onUpdateFound(result.info)
                }
                is UpdateCheck.UpToDate -> {
                    _manualCheck.value = ManualCheck.UpToDate
                    // Quietly return the row to its resting state.
                    delay(MANUAL_CHECK_RESULT_MS)
                    if (_manualCheck.value == ManualCheck.UpToDate) {
                        _manualCheck.value = ManualCheck.Idle
                    }
                }
                is UpdateCheck.Failed -> _manualCheck.value = ManualCheck.Failed(result.reason)
            }
        }
    }

    // ------------------------------------------------------------------
    // 4 — the "Install unknown apps" grant flow
    // ------------------------------------------------------------------

    /** Android 8+ per-app grant; always true on this minSdk 26 app's floor. */
    fun canInstallApks(): Boolean = runCatching {
        appContext.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** Opens the system "Allow from this source" screen for oneCR. */
    fun requestInstallPermission() {
        awaitingInstallGrant = true
        runCatching {
            appContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${appContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Called from [MainActivity.onResume]: when the user comes back from the
     * system screen with the grant in hand, the download they asked for
     * starts on its own — no second tap.
     */
    fun onAppResumed() {
        if (awaitingInstallGrant && canInstallApks()) {
            awaitingInstallGrant = false
            if (_download.value is DownloadState.Idle) startDownload()
        }
    }

    // ------------------------------------------------------------------
    // 5 — download + automatic install
    // ------------------------------------------------------------------

    fun downloadedFile(): File? = (_download.value as? DownloadState.Done)?.filePath?.let(::File)

    /**
     * The Update button, end to end: re-open the installer when the APK is
     * already on disk, ask for the "Install unknown apps" grant when missing,
     * and otherwise start the download.
     */
    fun onUpdatePressed() {
        when {
            _download.value is DownloadState.Done -> launchInstaller()
            !canInstallApks() -> requestInstallPermission()
            else -> startDownload()
        }
    }

    fun startDownload() {
        val info = _available.value ?: return
        if (_download.value is DownloadState.Running) return
        scope.launch {
            downloadApk(info)
        }
    }

    private suspend fun downloadApk(info: UpdateInfo) {
        val target = File(updateDir(), "oneCR-v${info.versionName}.apk")
        _download.value = DownloadState.Running(0, null)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                updateDir().mkdirs()
                // A stale half-download from an earlier attempt never survives.
                if (target.exists()) target.delete()
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) {
                        _download.value = DownloadState.Failed("HTTP ${conn.responseCode}")
                        return@runCatching false
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 }
                    conn.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read: Int
                            var seen = 0L
                            var lastTick = 0L
                            while (input.read(buf).also { read = it } >= 0) {
                                if (read == 0) continue
                                out.write(buf, 0, read)
                                seen += read
                                // Throttle progress updates to ~4/s — the UI
                                // does not need every 64 KiB.
                                val now = System.nanoTime()
                                if (now - lastTick > 250_000_000L) {
                                    lastTick = now
                                    _download.value = DownloadState.Running(seen, total)
                                }
                            }
                            out.flush()
                        }
                    }
                    if (target.length() == 0L) {
                        _download.value = DownloadState.Failed("empty download")
                        return@runCatching false
                    }
                    true
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { e ->
                _download.value = DownloadState.Failed(e.message ?: "download failed")
                false
            }
        }
        if (ok) {
            _download.value = DownloadState.Done(target.absolutePath)
            launchInstaller()
        }
    }

    /** Hands the finished APK to the system installer — the "run it" step. */
    fun launchInstaller() {
        val apk = downloadedFile() ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apk,
            )
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _download.value = DownloadState.Failed("cannot open installer")
        }
    }

    fun retryDownload() {
        _download.value = DownloadState.Idle
        startDownload()
    }

    private fun updateDir(): File = File(appContext.filesDir, "updates")

    companion object {
        /** PendingIntent that opens MainActivity on the update page. */
        fun showUpdatePageIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_SHOW_PAGE,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_SHOW_UPDATE_PAGE, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        const val EXTRA_SHOW_UPDATE_PAGE = "com.lmalakar.onecr.extra.SHOW_UPDATE_PAGE"
        private const val REQUEST_SHOW_PAGE = 40_01

        /** How long the "You're on the latest version" line stays visible. */
        private const val MANUAL_CHECK_RESULT_MS = 5_000L
    }
}
