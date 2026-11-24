package com.lmalakar.onecr

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import com.lmalakar.onecr.core.common.DefaultDispatchersProvider
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.common.Logger
import com.lmalakar.onecr.core.common.SystemTimeProvider
import com.lmalakar.onecr.core.model.AppInfo
import com.lmalakar.onecr.core.model.ThemeMode
import com.lmalakar.onecr.data.files.AppSettingsStore
import com.lmalakar.onecr.data.files.EventLogStore
import com.lmalakar.onecr.notify.NotificationCenter
import com.lmalakar.onecr.notify.PermissionReader
import com.lmalakar.onecr.privilege.api.BootCountStore
import com.lmalakar.onecr.privilege.api.PrivilegeHealthMonitor
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.privilege.embeddedadb.EmbeddedAdbPrivilegeProvider
import com.lmalakar.onecr.privilege.shizuku.ShizukuPrivilegeProvider
import com.lmalakar.onecr.service.detection.CallerIdResolver
import com.lmalakar.onecr.service.detection.DetectionEngine
import com.lmalakar.onecr.service.detection.DetectionServiceHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Logcat-backed Logger (kept out of core:common so that module stays pure JVM). */
class AndroidLogger : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun w(tag: String, message: String) { Log.w(tag, message) }
    override fun e(tag: String, message: String, error: Throwable?) {
        Log.e(tag, message, error)
    }
}

/** SharedPreferences-backed BOOT_COUNT memory for the health monitor. */
private class PrefsBootCountStore(context: Context) : BootCountStore {
    private val prefs = context.getSharedPreferences("privilege_health", Context.MODE_PRIVATE)
    override fun lastKnownBootCount(): Int = prefs.getInt("boot_count", -1)
    override fun rememberBootCount(count: Int) {
        prefs.edit().putInt("boot_count", count).apply()
    }
}

/**
 * Manual service-locator container. Grows with the milestones (recorder +
 * repositories in M3/M4, engines in M5); swap for Hilt when the graph gets heavy.
 */
class AppContainer(appContext: Context) {

    private val appContext = appContext

    val appInfo: AppInfo = AppInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toString(),
        buildType = BuildConfig.BUILD_TYPE,
    )

    val dispatchers: DispatchersProvider = DefaultDispatchersProvider()
    val logger: Logger = AndroidLogger()
    val settings = AppSettingsStore(appContext)

    /** App-lifetime scope for monitors and long-lived collectors. */
    val appScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val eventLog = EventLogStore(File(appContext.filesDir, "logs"))

    // ---- Privilege bridges ----
    // v0.2.0: Shizuku. v0.3.0 (M2.5): embedded ADB joins; the stored mode picks
    // which one is live. Switching modes rebuilds the health monitor and the
    // notification center; both are Compose-observable so every screen that
    // reads them recomposes onto the new bridge immediately.
    val shizukuProvider = ShizukuPrivilegeProvider(appContext, dispatchers)
    val embeddedAdbProvider = EmbeddedAdbPrivilegeProvider(appContext, dispatchers)

    private fun providerFor(mode: String?): PrivilegeProvider =
        if (mode == PrivilegeMode.EMBEDDED_ADB.name) embeddedAdbProvider else shizukuProvider

    private fun buildMonitor(provider: PrivilegeProvider) = PrivilegeHealthMonitor(
        provider = provider,
        appContext = appContext,
        scope = appScope,
        dispatchers = dispatchers,
        bootCountStore = PrefsBootCountStore(appContext),
        // Low-end optimization: on Android Go / low-RAM devices the health ping
        // runs every 2 min instead of 30 s. Binder-death callbacks still fire
        // instantly, so repair UX is unchanged — this only trims idle wakeups.
        pingIntervalMs = if (isLowRamDevice(appContext)) 120_000L else 30_000L,
    )

    private val _privilegeProvider = mutableStateOf(providerFor(settings.privilegeMode))
    var privilegeProvider: PrivilegeProvider
        get() = _privilegeProvider.value
        private set(value) { _privilegeProvider.value = value }

    private val _privilegeMonitor = mutableStateOf(buildMonitor(privilegeProvider))
    var privilegeMonitor: PrivilegeHealthMonitor
        get() = _privilegeMonitor.value
        private set(value) { _privilegeMonitor.value = value }

    // ---- Detection (M2) ----
    val callerIdResolver = CallerIdResolver(appContext)
    val detection = DetectionEngine(
        appContext = appContext,
        dispatchers = dispatchers,
        time = SystemTimeProvider(),
        logger = logger,
        callerId = callerIdResolver,
        eventLog = eventLog,
    )

    // ---- Appearance (v0.2.1) ----
    /** Compose-observable mirror of [AppSettingsStore.themeMode]; SYSTEM by default. */
    val themeMode = mutableStateOf(settings.themeMode)

    /** Persist a theme choice, recompose the UI and re-sync the platform night mode. */
    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        themeMode.value = mode
        // v0.2.4: the window background, system bars and platform dialogs follow
        // the app setting too, not just the Compose palette.
        applyPlatformNightMode(mode)
    }

    // ---- Motion (v0.2.6) ----
    /**
     * Compose-observable mirror of [AppSettingsStore.animationsEnabled]; ON by
     * default. Provided app-wide as [LocalMotionEnabled] from MainActivity, so
     * every transition, crossfade and pulse can honor the user's choice.
     */
    val animationsEnabled = mutableStateOf(settings.animationsEnabled)

    /** Persist the animations toggle; recomposes every transition app-wide. */
    fun setAnimationsEnabled(enabled: Boolean) {
        settings.animationsEnabled = enabled
        animationsEnabled.value = enabled
    }

    // ---- Notification center (v0.2.2) ----

    /** Live permission snapshot; republished on resume and after every grant action. */
    val permissions = MutableStateFlow(PermissionReader.read(appContext))

    /** Bumped from MainActivity.onResume — screens can key recomposition off it. */
    val resumeTick = mutableIntStateOf(0)

    private fun buildNotificationCenter(monitor: PrivilegeHealthMonitor) = NotificationCenter(
        settings = settings,
        health = monitor.health,
        events = detection.recentEvents,
        permissions = permissions,
        running = detection.running,
        showBridge = { settings.privilegeMode != null },
        scope = appScope,
    )

    /** The v0.2.2 notification center: needs + errors + activity in one feed.
     *  Rebuilt when the privilege mode switches so it observes the new
     *  monitor's health flow; the swap is Compose-observable. */
    private val _notificationCenter = mutableStateOf(buildNotificationCenter(privilegeMonitor))
    var notificationCenter: NotificationCenter
        get() = _notificationCenter.value
        private set(value) { _notificationCenter.value = value }

    /** Re-read the OS permission state (call after grants and on resume). */
    fun refreshPermissions() {
        permissions.value = PermissionReader.read(appContext)
    }

    // ---- Privilege mode switching (v0.3.0) ----

    /**
     * Selects the active privilege bridge and restarts health monitoring.
     * Persists the choice so the next launch comes up in the same mode, and
     * rebuilds the notification center on the new monitor's health flow.
     */
    fun setPrivilegeMode(modeName: String) {
        val next = providerFor(modeName)
        settings.privilegeMode = modeName
        if (next === privilegeProvider) return
        privilegeMonitor.stop()
        privilegeProvider = next
        privilegeMonitor = buildMonitor(next)
        privilegeMonitor.start()
        notificationCenter = buildNotificationCenter(privilegeMonitor)
        notificationCenter.refresh()
    }

    init {
        DetectionServiceHost.engineProvider = { detection }
        privilegeMonitor.start()
    }

    private fun isLowRamDevice(context: Context): Boolean =
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        }.getOrDefault(false)
}
