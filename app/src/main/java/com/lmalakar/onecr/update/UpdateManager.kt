package com.lmalakar.onecr.update

import android.content.Context
import android.content.Intent
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.common.Logger
import com.lmalakar.onecr.data.files.AppSettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The v0.3.3 update checker: one place that knows the whole flow —
 *
 *  1. **Check** — on every app open (throttled to one GitHub call per hour)
 *     and on demand from About → "Check for updates". Compares the repo's
 *     latest GitHub release against the installed versionName.
 *  2. **Notify** — when a newer version is found, posts a notification whose
 *     expanded view carries the release notes plus Update / Cancel actions;
 *     tapping anywhere on it opens the app straight onto the update page.
 *  3. **Page** — the full-screen update page (Compose) shows above everything
 *     when a new version is discovered on open, or when the user asks for it.
 *     Cancel snoozes reminders for that version for 24 h; a newer release
 *     overrides any snooze.
 *  4. **Update** — the Update button asks Android's per-app
 *     "Install unknown apps" permission (exactly once), downloads the release
 *     APK from GitHub with live progress, verifies it against the release's
 *     SHA256SUMS.txt, and then launches the system installer automatically.
 *
 * Privacy: the check fetches one public API URL and the download fetches the
 * release asset — nothing else leaves the phone, nothing identifies the user
 * beyond a generic "oneCR-update-check" User-Agent.
 */
class UpdateManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val dispatchers: DispatchersProvider,
    private val settings: AppSettingsStore,
    private val logger: Logger,
    private val currentVersionName: String,
    private val client: GithubReleaseClient = GithubReleaseClient(),
) {

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _pageVisible = MutableStateFlow(false)
    val pageVisible: StateFlow<Boolean> = _pageVisible.asStateFlow()

    /** One download at a time; cancelled by the page's Cancel button. */
    private var downloadJob: Job? = null

    /** Set by the notification's Update action — consumes itself on next resume. */
    @Volatile
    private var pendingStartUpdate = false

    /** The auto-open page fires once per process (explicit opens always work). */
    @Volatile
    private var autoPageShownThisProcess = false

    /** Set while the installer intent is auto-launched on the next resume. */
    @Volatile
    private var pendingInstall: File? = null

    /** True between [onAppResumed] and [onAppPaused] — gates auto-launching. */
    @Volatile
    private var resumed = false

    private val downloader = ApkDownloader()

    // ------------------------------------------------------------------
    // Lifecycle hooks (MainActivity)
    // ------------------------------------------------------------------

    /** Called from onCreate/onNewIntent — notification taps carry the page extras. */
    fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(UpdateIntents.EXTRA_SHOW_UPDATE_PAGE, false)) {
            openPage()
            if (intent.getBooleanExtra(UpdateIntents.EXTRA_START_UPDATE_NOW, false)) {
                pendingStartUpdate = true
            }
            // Consume the extras so a config-change recreation doesn't re-trigger.
            intent.removeExtra(UpdateIntents.EXTRA_SHOW_UPDATE_PAGE)
            intent.removeExtra(UpdateIntents.EXTRA_START_UPDATE_NOW)
        }
    }

    /** Called from onResume — throttled check, auto page, auto download/install. */
    fun onAppResumed() {
        resumed = true

        if (pendingStartUpdate) {
            pendingStartUpdate = false
            openPage()
            requestUpdateFlow()
        }

        // A grant is coming back from the "Install unknown apps" screen —
        // continue the flow the user started before leaving.
        if (_downloadState.value == DownloadState.NeedsPermission) {
            if (ApkDownloader.canRequestInstalls(appContext)) {
                startDownload()
            }
            // Still denied: keep NeedsPermission so the page keeps its prompt.
        }

        // A finished download auto-opens the installer exactly once, while the
        // app is visible (background activity launches are blocked on 10+).
        if (_downloadState.value is DownloadState.Ready) {
            launchInstallerIfPending()
        }

        // Throttled background check (once per hour) unless one is in flight.
        if (_updateState.value is UpdateState.Checking) return
        val now = System.currentTimeMillis()
        if (now - settings.updateLastCheckAtMillis >= CHECK_THROTTLE_MS) {
            checkNow(force = false)
        }

        // Already-known update: open the page above everything (first time in
        // this process). A discovery that arrives AFTER this resume completes
        // auto-opens via [maybeAutoOpenPage] inside checkNow.
        maybeAutoOpenPage()
    }

    /**
     * Opens the update page on first discovery while the app is in the
     * foreground — used both right after resume (known update) and from the
     * async check's success path (fresh discovery).
     */
    private fun maybeAutoOpenPage() {
        val state = _updateState.value
        if (state is UpdateState.Available && resumed && !autoPageShownThisProcess && !isSnoozed(state.info)) {
            autoPageShownThisProcess = true
            openPage()
        }
    }

    /** Called from onPause — stops auto-launching installer intents. */
    fun onAppPaused() {
        resumed = false
    }

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    /**
     * Runs a GitHub check. [force]=true ignores the hourly throttle (About
     * button) and surfaces failures as [UpdateState.CheckFailed]; a background
     * check fails silently back to Idle.
     */
    fun checkNow(force: Boolean) {
        _updateState.value = UpdateState.Checking
        scope.launch(dispatchers.io) {
            val result = runCatching { client.fetchLatest() }
            result.onSuccess { info ->
                settings.updateLastCheckAtMillis = System.currentTimeMillis()
                if (info == null) {
                    _updateState.value = UpdateState.CheckFailed("no published release found")
                    if (force) _updateState.value = UpdateState.UpToDate(currentVersionName)
                    return@onSuccess
                }
                when {
                    UpdateVersions.isNewer(info.versionName, currentVersionName) -> {
                        _updateState.value = UpdateState.Available(info)
                        postNotificationIfDue(info)
                        // Fresh discovery while the user has the app open —
                        // show the update page now, not on the next launch.
                        maybeAutoOpenPage()
                    }

                    force -> _updateState.value = UpdateState.UpToDate(currentVersionName)

                    else -> _updateState.value = UpdateState.Idle
                }
            }.onFailure { t ->
                logger.w(TAG, "update check failed: ${t.message}")
                _updateState.value = if (force) {
                    UpdateState.CheckFailed(t.message ?: t.javaClass.simpleName)
                } else {
                    UpdateState.Idle
                }
            }
        }
    }

    private fun postNotificationIfDue(info: UpdateInfo) {
        if (isSnoozed(info)) return
        if (settings.updateLastNotifiedVersion == info.versionName) return
        runCatching { UpdateNotifier.post(appContext, info) }
        settings.updateLastNotifiedVersion = info.versionName
    }

    // ------------------------------------------------------------------
    // Page visibility
    // ------------------------------------------------------------------

    /** Shows the update page above everything (notification tap, About, auto-open). */
    fun openPage() {
        if (_updateState.value is UpdateState.Available) {
            _pageVisible.value = true
        }
    }

    /** Cancel on the page: close it and snooze reminders for this version. */
    fun onDismissClicked() {
        snoozeNotification()
        closePage()
    }

    fun closePage() {
        _pageVisible.value = false
    }

    /** Snoozes notifications AND the auto-open page for this version for 24 h. */
    fun snoozeNotification() {
        val info = (_updateState.value as? UpdateState.Available)?.info ?: return
        settings.updateSnoozedVersion = info.versionName
        settings.updateSnoozeUntilMillis = System.currentTimeMillis() + SNOOZE_MS
        logger.d(TAG, "update reminders snoozed for v${info.versionName}")
    }

    private fun isSnoozed(info: UpdateInfo): Boolean =
        settings.updateSnoozedVersion == info.versionName &&
            System.currentTimeMillis() < settings.updateSnoozeUntilMillis

    // ------------------------------------------------------------------
    // Update flow (permission → download → verify → install)
    // ------------------------------------------------------------------

    /**
     * The "Update" button: asks for the per-app "Install unknown apps" grant
     * when missing (the page switches to its permission state), otherwise
     * starts the download immediately.
     */
    fun requestUpdateFlow() {
        if (_updateState.value !is UpdateState.Available) return
        if (ApkDownloader.canRequestInstalls(appContext)) {
            startDownload()
        } else {
            _downloadState.value = DownloadState.NeedsPermission
            runCatching { appContext.startActivity(ApkDownloader.unknownAppsSettingsIntent(appContext)) }
                .onFailure { logger.w(TAG, "could not open unknown-apps settings: ${it.message}") }
        }
    }

    /** Opens the system "Install unknown apps" screen for oneCR. */
    fun openPermissionSettings() {
        runCatching { appContext.startActivity(ApkDownloader.unknownAppsSettingsIntent(appContext)) }
    }

    /** Streams the release APK into app storage with live progress. */
    fun startDownload() {
        val info = (_updateState.value as? UpdateState.Available)?.info ?: return
        if (downloadJob?.isActive == true) return
        val fileName = ApkDownloader.fileNameFor(info.versionName)
        val targetDir = ApkDownloader.updatesDir(appContext)

        downloadJob = scope.launch(dispatchers.io) {
            _downloadState.value = DownloadState.Downloading(0, info.apkSizeBytes)
            runCatching {
                downloader.download(
                    apkUrl = info.apkUrl,
                    apkSizeBytes = info.apkSizeBytes,
                    checksumsUrl = info.checksumsUrl,
                    targetDir = targetDir,
                    fileName = fileName,
                    progress = { received, total ->
                        _downloadState.value = DownloadState.Downloading(received, total)
                    },
                )
            }.onSuccess { file ->
                _downloadState.value = DownloadState.Ready(file)
                logger.d(TAG, "update APK ready: ${file.name} (${file.length()} bytes)")
                launchInstallerIfPending()
            }.onFailure { t ->
                _downloadState.value = DownloadState.Failed(t.message ?: t.javaClass.simpleName)
                logger.w(TAG, "update download failed: ${t.message}")
            }
        }
    }

    /** Cancel during download: abort the job and clean the partial file. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val info = (_updateState.value as? UpdateState.Available)?.info
        if (info != null) runCatching { ApkDownloader.cleanup(appContext, info.versionName) }
        _downloadState.value = DownloadState.NotStarted
    }

    /** Re-offers an already-downloaded APK without re-downloading. */
    fun reuseExistingApk() {
        val info = (_updateState.value as? UpdateState.Available)?.info ?: return
        val existing = ApkDownloader.existingVerifiedApk(appContext, info.versionName)
        if (existing != null) {
            _downloadState.value = DownloadState.Ready(existing)
        }
    }

    /** Marks [file] ready and lets onResume launch the installer (foreground). */
    fun queueInstall(file: File) {
        pendingInstall = file
        _downloadState.value = DownloadState.Ready(file)
    }

    /**
     * Launches the system installer for a finished download. Must be called
     * while an activity is resumed (Android 10+ blocks background starts);
     * returns the intent for the caller to start when null was not consumed.
     */
    fun consumeInstallIntent(): Intent? {
        val file = pendingInstall ?: return null
        pendingInstall = null
        return runCatching { ApkDownloader.installIntent(appContext, file) }.getOrNull()
    }

    /** Manual "Install" from the page (also the fallback after a cancelled installer). */
    fun installNow(): Intent? {
        val file = (_downloadState.value as? DownloadState.Ready)?.apkFile
            ?: (_updateState.value as? UpdateState.Available)?.info?.let { info ->
                ApkDownloader.existingVerifiedApk(appContext, info.versionName)
            }
        return file?.let { runCatching { ApkDownloader.installIntent(appContext, it) }.getOrNull() }
    }

    private fun launchInstallerIfPending() {
        if (!resumed) return
        val intent = consumeInstallIntent() ?: return
        runCatching { appContext.startActivity(intent) }
            .onFailure { logger.w(TAG, "could not launch installer: ${it.message}") }
    }

    companion object {
        private const val TAG = "UpdateManager"

        /** One GitHub check per hour at most (app-open throttling). */
        const val CHECK_THROTTLE_MS = 60 * 60 * 1_000L

        /** "Cancel" snoozes reminders and the auto-open page for a day. */
        const val SNOOZE_MS = 24 * 60 * 60 * 1_000L
    }
}
