package com.lmalakar.onecr.privilege.embeddedadb

import android.content.Context
import android.os.Build
import android.provider.Settings
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
import java.io.File
import kotlinx.coroutines.withContext

/**
 * CallVault-style self-contained privilege provider (roadmap M2.5, v0.3.0):
 * oneCR embeds a minimal ADB client that pairs with the phone's own Wireless
 * Debugging (Android 11+ pairing protocol: TLS 1.3 + SPAKE2 with the 6-digit
 * code, port from Developer Options), then talks to the local adbd over TLS and
 * runs shell commands as UID 2000 — no extra app required.
 *
 * Constraints surfaced to the user during onboarding:
 *  - pairing needs Wireless debugging on + the 6-digit code dialog open;
 *  - after a reboot the adbd port changes — reconnect discovers it again (Wi-Fi
 *    must be on once after reboot);
 *  - Developer options must stay enabled;
 *  - OPPO/OnePlus/Realme need "disable permission monitoring".
 *
 * The capture-dex spawn (app_process) joins in M3; this provider already runs
 * shell commands, which is exactly the privileged surface capture will use.
 */
class EmbeddedAdbPrivilegeProvider(
    appContext: Context,
    private val dispatchers: DispatchersProvider,
) : PrivilegeProvider {

    override val mode: PrivilegeMode = PrivilegeMode.EMBEDDED_ADB

    override var onHealthChanged: (() -> Unit)? = null

    private val appContext: Context = appContext.applicationContext
    private val store = PairingStore(appContext)
    private val finder = AdbDeviceFinder(appContext)
    private val filesDir: File = appContext.filesDir

    @Volatile
    private var client: AdbClient? = null

    /** Endpoint the live client connected to — reused for the PCM stream session. */
    @Volatile
    private var lastEndpoint: Pair<String, Int>? = null

    // ------------------------------------------------------------------
    // Public surface used by the UI (onboarding pairing step, settings)
    // ------------------------------------------------------------------

    /** Discovered pairing endpoints (the phone must show the pairing dialog). */
    suspend fun discoverPairingServices(): List<PairingEndpoint> =
        withContext(dispatchers.io) {
            ensureIdentity()
            finder.discover(AdbDeviceFinder.Kind.PAIRING)
                .map { PairingEndpoint(name = it.name, host = it.host, port = it.port) }
        }

    /**
     * Pairs with the device at [host]:[port] using the 6-digit [pairingCode].
     * On success persists the pairing and notifies the health monitor.
     * Returns a human-readable error, or null on success.
     */
    suspend fun pairWithDevice(pairingCode: String, host: String, port: Int): String? =
        withContext(dispatchers.io) {
            ensureIdentity()
            if (pairingCode.length != 6 || pairingCode.any { !it.isDigit() }) {
                return@withContext "The pairing code is exactly 6 digits."
            }
            try {
                val result = AdbPairingClient(identity!!, host, port, pairingCode).run()
                store.paired = true
                store.deviceGuid = result.deviceGuid
                onHealthChanged?.invoke()
                null
            } catch (e: Exception) {
                onHealthChanged?.invoke()
                "${e.message ?: "pairing failed"} — wrong code, dialog closed, or wrong port."
            }
        }

    /** Data for the onboarding pairing UI. */
    data class PairingEndpoint(val name: String, val host: String, val port: Int)

    /** True once the user completed pairing (an authorized key is stored on the phone). */
    val isPaired: Boolean get() = store.paired

    /** Human-readable paired device summary, for the settings card. */
    fun pairedDeviceSummary(): String? {
        if (!store.paired) return null
        val guid = store.deviceGuid
        return guid?.let { "Paired device: $it" }
    }

    /** Clears the pairing (does not remove the key on the phone). */
    fun forgetPairing() {
        store.clearPairing()
        runCatching { client?.close() }
        client = null
        onHealthChanged?.invoke()
    }

    // ------------------------------------------------------------------
    // PrivilegeProvider
    // ------------------------------------------------------------------

    override suspend fun connect(): PrivilegeState = withContext(dispatchers.io) {
        if (Build.VERSION.SDK_INT < 30) return@withContext PrivilegeState.UNSUPPORTED
        if (!devOptionsEnabled()) return@withContext PrivilegeState.DEV_OPTIONS_OFF
        ensureIdentity()
        if (!store.paired) return@withContext PrivilegeState.NOT_CONFIGURED

        val active = client
        if (active != null && active.isAlive()) return@withContext PrivilegeState.OK

        // Preferred: NSD discovery of _adb-tls-connect; fallback: last known port.
        val endpoints = runCatching {
            finder.discover(AdbDeviceFinder.Kind.CONNECT, timeoutMs = 3500)
        }.getOrDefault(emptyList())
        val candidates = buildList {
            endpoints.forEach { add(it.host to it.port) }
            if (store.lastConnectPort > 0) add("127.0.0.1" to store.lastConnectPort)
        }
        for ((host, port) in candidates) {
            val attempt = AdbClient(identity!!, host, port)
            val ok = runCatching {
                attempt.connect()
                true
            }.getOrElse {
                runCatching { attempt.close() }
                false
            }
            if (ok) {
                client = attempt
                lastEndpoint = host to port
                store.lastConnectPort = port
                return@withContext PrivilegeState.OK
            }
        }
        PrivilegeState.PAIRING_LOST
    }

    override suspend fun health(): PrivilegeState {
        val active = client
        if (active != null && active.isAlive()) return PrivilegeState.OK
        return connect()
    }

    override suspend fun runShellCommand(command: String): ShellCommandResult =
        withContext(dispatchers.io) {
            val active = client
            if (active == null || !active.isAlive()) {
                if (connect() != PrivilegeState.OK) {
                    return@withContext ShellCommandResult(
                        command = command,
                        combinedOutput = "embedded ADB bridge not connected (pair and enable Wireless debugging)",
                        exitCode = null,
                        ok = false,
                    )
                }
            }
            val use = client ?: return@withContext ShellCommandResult(
                command, "bridge not connected", null, false,
            )
            runCatching { use.runShell(command) }
                .map { ShellCommandResult(command, it.combinedOutput, it.exitCode, it.exitCode == 0) }
                .getOrElse {
                    runCatching { use.close() }
                    client = null
                    ShellCommandResult(command, "shell failed: ${it.message}", null, false)
                }
        }

    override fun disconnect() {
        runCatching { client?.close() }
        client = null
        lastEndpoint = null
    }

    // ------------------------------------------------------------------
    // Capture (M3)
    // ------------------------------------------------------------------

    /** Path of oneCR's own APK — the CLASSPATH the capture dex is spawned from. */
    private fun apkPath(): String = appContext.applicationInfo.sourceDir ?: appContext.packageCodePath

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
        val rate = CaptureProtocol.workingSampleRate(result)
        val uid = result.uid
        CaptureCapability(
            workingSources = chain,
            sampleRateHz = rate,
            notes = buildString {
                append("uid=$uid (shell)")
                if (chain.isEmpty()) {
                    append("; no working source — device may route nothing into the")
                    append(" telephony streams (see docs/COMPATIBILITY.md)")
                } else {
                    append("; chain: ")
                    append(chain.joinToString("→") { it.name })
                }
            },
        )
    }

    override suspend fun openCaptureStream(spec: CaptureStreamSpec): CaptureStream =
        withContext(dispatchers.io) {
            if (connect() != PrivilegeState.OK) {
                throw java.io.IOException(
                    "embedded ADB bridge not connected (pair and enable Wireless debugging)",
                )
            }
            val endpoint = lastEndpoint
                ?: throw java.io.IOException("bridge endpoint unknown — reconnect first")
            // A dedicated TLS session carries the PCM; the main command client
            // (health pings, shell checks) stays untouched.
            AdbStreamClient(
                identity = identity ?: throw java.io.IOException("bridge identity missing"),
                host = endpoint.first,
                port = endpoint.second,
                command = CaptureProtocol.startCommand(
                    apkPath = apkPath(),
                    source = spec.source,
                    sampleRateHz = spec.sampleRateHz,
                    maxDurationMs = spec.maxDurationMs,
                ),
            )
        }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private var identity: AdbIdentity? = null

    @Synchronized
    private fun ensureIdentity(): AdbIdentity {
        identity?.let { return it }
        val loaded = AdbIdentity.loadOrCreate(filesDir)
        identity = loaded
        TlsSupport.installProvider()
        return loaded
    }

    /** Developer options AND the wireless-debugging switch must both be on. */
    private fun devOptionsEnabled(): Boolean = runCatching {
        val adb = Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED)
        val wifi = if (Build.VERSION.SDK_INT >= 30) {
            Settings.Global.getInt(appContext.contentResolver, "adb_wifi_enabled")
        } else {
            1
        }
        adb == 1 && wifi == 1
    }.getOrDefault(false)
}
