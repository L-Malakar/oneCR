package com.lmalakar.onecr

import android.app.Application
import android.content.Context
import android.util.Log
import com.lmalakar.onecr.core.common.DefaultDispatchersProvider
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.common.Logger
import com.lmalakar.onecr.core.common.SystemTimeProvider
import com.lmalakar.onecr.core.model.AppInfo
import com.lmalakar.onecr.data.files.AppSettingsStore
import com.lmalakar.onecr.data.files.EventLogStore
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

    /** App context for checks that outlive the constructor scope. */
    private val appContext: Context = appContext.applicationContext

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
    // which one is live. Switching modes rebuilds the health monitor.
    val shizukuProvider = ShizukuPrivilegeProvider(appContext, dispatchers)
    val embeddedAdbProvider = EmbeddedAdbPrivilegeProvider(appContext, dispatchers)

    private fun providerFor(mode: String?): PrivilegeProvider =
        if (mode == PrivilegeMode.EMBEDDED_ADB.name) embeddedAdbProvider else shizukuProvider

    var privilegeProvider: PrivilegeProvider = providerFor(settings.privilegeMode)
        private set

    var privilegeMonitor: PrivilegeHealthMonitor = PrivilegeHealthMonitor(
        provider = privilegeProvider,
        appContext = appContext,
        scope = appScope,
        dispatchers = dispatchers,
        bootCountStore = PrefsBootCountStore(appContext),
    )
        private set

    /**
     * Selects the active privilege bridge and restarts health monitoring.
     * Persists the choice so the next launch comes up in the same mode.
     */
    fun setPrivilegeMode(modeName: String) {
        val next = providerFor(modeName)
        settings.privilegeMode = modeName
        if (next === privilegeProvider) return
        privilegeMonitor.stop()
        privilegeProvider = next
        privilegeMonitor = PrivilegeHealthMonitor(
            provider = next,
            appContext = appContext,
            scope = appScope,
            dispatchers = dispatchers,
            bootCountStore = PrefsBootCountStore(appContext),
        )
        privilegeMonitor.start()
    }

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

    init {
        DetectionServiceHost.engineProvider = { detection }
        privilegeMonitor.start()
    }
}
