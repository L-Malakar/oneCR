package com.lmalakar.onecr.privilege.embeddedadb

import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult

/**
 * CallVault-style self-contained provider: oneCR embeds a minimal ADB client that
 * pairs with the phone's own Wireless Debugging (Android 11+ pairing protocol:
 * TLS handshake with a 6-digit code + port from Developer Options), then connects
 * to local adbd and spawns the shell capture dex via `app_process`.
 *
 * Scoped to v0.3.0 ("M2.5" in the roadmap) so v0.2.0 can ship the Shizuku bridge
 * and the full detection stack first. Implementation plan when it lands:
 *  - pairing: port + code entry UI, TLS pairing socket (SPAKE2-based Android 11+
 *    protocol), persist the granted adb key;
 *  - connect: open the local adbd socket, spawn the capture dex via app_process;
 *  - constraints surfaced in onboarding: pairing needs Wi-Fi once after reboot,
 *    Developer options stay enabled, Android 11 keeps the screen unlocked during
 *    calls, OPPO/OnePlus/Realme need "disable permission monitoring".
 *
 * Until then this provider stays inert and is never selected by the app.
 */
class EmbeddedAdbPrivilegeProvider : PrivilegeProvider {

    override val mode: PrivilegeMode = PrivilegeMode.EMBEDDED_ADB

    override var onHealthChanged: (() -> Unit)? = null

    override suspend fun connect(): PrivilegeState {
        // TODO(v0.3.0): TLS pair + adb connect + spawn capture dex.
        return PrivilegeState.NOT_CONFIGURED
    }

    override suspend fun health(): PrivilegeState {
        // TODO(v0.3.0): socket liveness + child process liveness + BOOT_COUNT change detection.
        return PrivilegeState.NOT_CONFIGURED
    }

    override suspend fun runShellCommand(command: String): ShellCommandResult {
        return ShellCommandResult(
            command = command,
            combinedOutput = "embedded-ADB bridge arrives in v0.3.0 — use the Shizuku bridge",
            exitCode = null,
            ok = false,
        )
    }

    override suspend fun probeCapture(): CaptureCapability {
        // TODO(v0.3.0): delegate to the shell capture service probe().
        return CaptureCapability(workingSources = emptyList(), sampleRateHz = 16_000)
    }

    override fun disconnect() {
        // TODO(v0.3.0): stop spawned capture process, close adbd socket.
    }
}
