package com.lmalakar.onecr

import android.app.Application
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
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.service.detection.CallerIdResolver
import com.lmalakar.onecr.service.detection.DetectionEngine
import com.lmalakar.onecr.service.detection.DetectionServiceHost
import com.lmalakar.onecr.privilege.shizuku.ShizukuPrivilegeProvider
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

    // ---- Privilege bridge (v0.2.0: Shizuku; embedded ADB joins in v0.3.0) ----
    val shizukuProvider = ShizukuPrivilegeProvider(appContext, dispatchers)
    val privilegeProvider: PrivilegeProvider = shizukuProvider
    val privilegeMonitor = PrivilegeHealthMonitor(
        provider = privilegeProvider,
        appContext = appContext,
        scope = appScope,
        dispatchers = dispatchers,
        bootCountStore = PrefsBootCountStore(appContext),
        // Low-end optimization: on Android Go / low-RAM devices the health ping
        // runs every 2 min instead of 30 s. Binder-death callbacks still fire
        // instantly, so repair UX is unchanged — this only trims idle wakeups.
        pingIntervalMs = if (isLowRamDevice(appContext)) 120_000L else 30_000L,
    )

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

    // ---- Notification center (v0.2.2) ----

    /** Live permission snapshot; republished on resume and after every grant action. */
    val permissions = MutableStateFlow(PermissionReader.read(appContext))

    /** Bumped from MainActivity.onResume — screens can key recomposition off it. */
    val resumeTick = mutableIntStateOf(0)

    /** The v0.2.2 notification center: needs + errors + activity in one feed. */
    val notificationCenter = NotificationCenter(
        settings = settings,
        health = privilegeMonitor.health,
        events = detection.recentEvents,
        permissions = permissions,
        running = detection.running,
        showBridge = { settings.privilegeMode != null },
        scope = appScope,
    )

    /** Re-read the OS permission state (call after grants and on resume). */
    fun refreshPermissions() {
        permissions.value = PermissionReader.read(appContext)
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
