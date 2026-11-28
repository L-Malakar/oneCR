package com.lmalakar.onecr.privilege.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.lmalakar.onecr.core.common.DispatchersProvider
import com.lmalakar.onecr.core.model.CaptureSource
import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.CaptureProtocol
import com.lmalakar.onecr.privilege.api.CaptureStream
import com.lmalakar.onecr.privilege.api.CaptureStreamSpec
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
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

    override suspend fun probeCapture(): CaptureCapability = withContext(dispatchers.io) {
        val probe = runShellCommand(CaptureProtocol.probeCommand(apkPath()))
        if (!probe.ok) {
            return@withContext CaptureCapability(
                workingSources = emptyList(),
                sampleRateHz = 16_000,
                notes = "Probe failed: ${probe.combinedOutput.take(160)}",
            )
        }
        val result = CaptureProtocol.parseProbeOutput(probe.combinedOutput)
        val chain = CaptureProtocol.workingSources(result)
        CaptureCapability(
            workingSources = chain,
            sampleRateHz = CaptureProtocol.workingSampleRate(result),
            notes = buildString {
                append("uid=${result.uid} (shell)")
                if (chain.isEmpty()) {
                    append("; no working source on this device")
                } else {
                    append("; chain: ")
                    append(chain.joinToString("→") { it.name })
                }
            },
        )
    }

    /**
     * M3: asks the privileged service to spawn the capture process and pipes
     * its PCM back over binder FDs. The stop control travels on a second pipe
     * the app writes "stop\n" into.
     */
    override suspend fun openCaptureStream(spec: CaptureStreamSpec): CaptureStream =
        withContext(dispatchers.io) {
            if (connect() != PrivilegeState.OK) {
                throw IOException("Shizuku bridge not connected (install, start and grant permission)")
            }
            val service = boundServiceOrNull()
                ?: throw IOException("Shizuku shell service not bound")

            // [0] = read end (the service side feeds the capture process's stdin).
            val control = ParcelFileDescriptor.createPipe()
            val controlWrite = ParcelFileDescriptor.AutoCloseOutputStream(control[1])

            val pcmPfd = runCatching {
                service.openCaptureStream(
                    CaptureProtocol.startCommand(
                        apkPath = apkPath(),
                        source = spec.source,
                        sampleRateHz = spec.sampleRateHz,
                        maxDurationMs = spec.maxDurationMs,
                    ),
                    control[0],
                )
            }.getOrElse {
                runCatching { control[0].close() }
                controlWrite.close()
                throw IOException("capture spawn failed: ${it.message}", it)
            }

            ShizukuCaptureStream(pcmPfd, controlWrite)
        }

    /** Path of oneCR's own APK — the CLASSPATH the capture dex is spawned from. */
    private fun apkPath(): String = appContext.applicationInfo.sourceDir ?: appContext.packageCodePath

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

        /** User-service dex version — bump when the AIDL changes (Shizuku re-loads it).
         *  v3 = M3 capture AIDL (openCaptureStream). */
        private const val USER_SERVICE_VERSION = 3

        /** bindUserService needs Shizuku server API 10+. */
        private const val MIN_SERVER_API = 10

        private const val BIND_WAIT_ROUNDS = 25
        private const val BIND_WAIT_STEP_MS = 200L
    }
}

/**
 * CaptureStream over two binder pipes: PCM from the service, stop control to
 * the service. awaitExit is EOF-based (the service pipe carries no exit code).
 */
private class ShizukuCaptureStream(
    pcmPfd: ParcelFileDescriptor,
    private val controlWrite: OutputStream,
) : CaptureStream {

    private val pcmInput = ParcelFileDescriptor.AutoCloseInputStream(pcmPfd)
    private val stopped = AtomicReference<Boolean>(false)

    override val pcm: InputStream get() = pcmInput
    override val grantedSampleRateHz: Int = 0 // announced inline by the dex process

    override fun stop() {
        if (stopped.compareAndSet(false, true)) {
            runCatching {
                controlWrite.write((CaptureProtocol.STOP_LINE + "\n").toByteArray())
                controlWrite.flush()
            }
            runCatching { controlWrite.close() }
        }
    }

    override fun awaitExit(timeoutMs: Long): Int? {
        // EOF on the pipe is the exit signal; a precise exit code is not
        // transported over the binder pipe.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopped.get()) return 0
            Thread.sleep(50)
        }
        return null
    }

    override fun close() {
        stop()
        runCatching { pcmInput.close() }
    }
}
