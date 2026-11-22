package com.lmalakar.onecr.privilege.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.model.CaptureSource
import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku-backed [PrivilegeProvider] — the privilege bridge that ships in v0.2.0.
 *
 * Flow (see docs/ARCHITECTURE.md §3):
 *  1. Shizuku must be installed and running (`Shizuku.pingBinder()`).
 *  2. The user grants oneCR the Shizuku permission once (`requestPermission`).
 *  3. oneCR binds [ShellService] as a Shizuku *user service* — that class then
 *     runs inside the Shizuku server process as the shell user (UID 2000).
 *  4. `health()` pings the bound binder; binder-death and permission events are
 *     pushed to [onHealthChanged] so the health monitor can update the UI instantly.
 *
 * The audio-capture AIDL methods (probe/start/stop) are intentionally NOT here yet —
 * they join [ShellService] in M3. v0.2.0 proves the bridge with `exec("id")`.
 */
class ShizukuPrivilegeProvider(
    private val appContext: Context,
    private val dispatchers: DispatchersProvider,
) : PrivilegeProvider {

    override val mode: PrivilegeMode = PrivilegeMode.SHIZUKU

    override var onHealthChanged: (() -> Unit)? = null

    /** Bound handle to the shell service; null while not bound. Guarded by [binderLock]. */
    @Volatile
    private var shellService: IShellService? = null
    private val binderLock = Any()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, ShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("onecr_shell")
        .version(USER_SERVICE_VERSION)
        .debuggable(false)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(binderLock) {
                shellService =
                    if (service.pingBinder()) IShellService.Stub.asInterface(service) else null
            }
            onHealthChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(binderLock) { shellService = null }
            onHealthChanged?.invoke()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        // Shizuku came (back) — try to restore the user service binding.
        onHealthChanged?.invoke()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(binderLock) { shellService = null }
        onHealthChanged?.invoke()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            // Grant state changed (granted or denied) — re-evaluate immediately.
            onHealthChanged?.invoke()
        }

    init {
        runCatching {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }
    }

    // ------------------------------------------------------------------
    // PrivilegeProvider
    // ------------------------------------------------------------------

    override suspend fun connect(): PrivilegeState {
        if (!isShizukuInstalled()) return PrivilegeState.NOT_CONFIGURED
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return PrivilegeState.PAIRING_LOST
        }
        if (runCatching { serverApiVersion() }.getOrDefault(0) < MIN_SERVER_API) {
            return PrivilegeState.UNSUPPORTED
        }
        if (!hasPermission()) return PrivilegeState.NEEDS_PERMISSION

        boundServiceOrNull()?.let { return PrivilegeState.OK }

        // Bind (async) and wait briefly for the connection callback.
        runCatching { Shizuku.bindUserService(userServiceArgs, userServiceConnection) }
        repeat(BIND_WAIT_ROUNDS) {
            if (boundServiceOrNull() != null) return PrivilegeState.OK
            delay(BIND_WAIT_STEP_MS)
        }
        return if (boundServiceOrNull() != null) PrivilegeState.OK else PrivilegeState.SERVICE_DEAD
    }

    override suspend fun health(): PrivilegeState {
        boundServiceOrNull()?.let { return PrivilegeState.OK }
        return connect()
    }

    override suspend fun runShellCommand(command: String): ShellCommandResult {
        val service = boundServiceOrNull() ?: run {
            if (connect() != PrivilegeState.OK) {
                return ShellCommandResult(
                    command = command,
                    combinedOutput = "bridge not connected",
                    exitCode = null,
                    ok = false,
                )
            }
            boundServiceOrNull()
        } ?: return ShellCommandResult(
            command = command,
            combinedOutput = "bridge not connected",
            exitCode = null,
            ok = false,
        )

        return withContext(dispatchers.io) {
            runCatching {
                val raw = service.exec(command)
                parseExecResult(command, raw)
            }.getOrElse {
                ShellCommandResult(command, "binder call failed: ${it.message}", null, ok = false)
            }
        }
    }

    override suspend fun probeCapture(): CaptureCapability {
        // M3: delegates to the shell capture probe() over the same binder.
        val probe = runShellCommand("id")
        return CaptureCapability(
            workingSources = emptyList(),
            sampleRateHz = 16_000,
            notes = if (probe.ok) {
                "Bridge verified (shell reachable); audio source probing arrives in M3."
            } else {
                "Bridge not reachable: ${probe.combinedOutput}"
            },
        )
    }

    override fun disconnect() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        synchronized(binderLock) { shellService = null }
    }

    // ------------------------------------------------------------------
    // Shizuku-specific helpers used by the UI (via :app wiring)
    // ------------------------------------------------------------------

    /** True when the Shizuku app is installed on this device. */
    fun isShizukuInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the Shizuku page on Play Store (or the project site as fallback). */
    fun shizukuInstallIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
            .takeIf { it.resolveActivity(appContext.packageManager) != null }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))

    /** Launches the Shizuku app so the user can start it (after reboot etc.). */
    fun shizukuLaunchIntent(): Intent? =
        appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    /**
     * Starts the one-time Shizuku permission flow. Must be called while an Activity
     * is in the foreground (Shizuku shows its own permission screen on top).
     * The result arrives via [permissionResultListener] → [onHealthChanged].
     */
    fun requestPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (hasPermission()) {
            onHealthChanged?.invoke()
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun serverApiVersion(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(0)

    private fun boundServiceOrNull(): IShellService? {
        val service = synchronized(binderLock) { shellService } ?: return null
        val alive = runCatching { service.asBinder().pingBinder() }.getOrDefault(false)
        return if (alive) service else null
    }

    private fun parseExecResult(command: String, raw: String): ShellCommandResult {
        val markerIndex = raw.lastIndexOf(ShellService.EXIT_MARKER)
        if (markerIndex < 0) {
            return ShellCommandResult(command, raw.trimEnd(), null, ok = false)
        }
        val output = raw.substring(0, markerIndex).trimEnd()
        val tail = raw.substring(markerIndex + ShellService.EXIT_MARKER.length)
        val exitCode = tail.takeWhile { it.isDigit() || it == '-' }.toIntOrNull()
        val ok = exitCode == 0
        return ShellCommandResult(
            command = command,
            combinedOutput = output.ifBlank { if (ok) "(no output)" else output },
            exitCode = exitCode,
            ok = ok,
        )
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST_CODE = 1001

        /** User-service dex version — bump when the AIDL changes (Shizuku re-loads it). */
        private const val USER_SERVICE_VERSION = 2

        /** bindUserService needs Shizuku server API 10+. */
        private const val MIN_SERVER_API = 10

        private const val BIND_WAIT_ROUNDS = 25
        private const val BIND_WAIT_STEP_MS = 200L
    }
}
