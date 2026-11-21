package com.lmalakar.onecr.privilege.embeddedadb

import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.privilege.api.PrivilegeProvider
import com.lmalakar.onecr.privilege.api.PrivilegeState

/**
 * CallVault-style self-contained provider: oneCR embeds a minimal ADB client that
 * pairs with the phone's own Wireless Debugging (Android 11+ pairing protocol:
 * TLS handshake with a 6-digit code + port from Developer Options), then connects
 * to local adbd and spawns the shell capture dex via `app_process`.
 *
 * M2/M2.5 implementation plan:
 *  - pairing: port + code entry UI, TLS pairing socket (Android 11+ protocol),
 *    persist the granted adb key;
 *  - connect: open the local adbd socket, push/spawn the capture dex;
 *  - constraints surfaced to the user in onboarding: pairing needs Wi-Fi once after
 *    reboot, Developer options stay enabled, Android 11 keeps the screen unlocked
 *    during calls, OPPO/OnePlus/Realme need "disable permission monitoring".
 *
 * The stub below keeps the module graph compiling in M1.
 */
class EmbeddedAdbPrivilegeProvider : PrivilegeProvider {

    override val mode: PrivilegeMode = PrivilegeMode.EMBEDDED_ADB

    override suspend fun connect(): PrivilegeState {
        // TODO(M2): TLS pair + adb connect + spawn capture dex.
        return PrivilegeState.NOT_CONFIGURED
    }

    override suspend fun health(): PrivilegeState {
        // TODO(M2): socket liveness + child process liveness + BOOT_COUNT change detection.
        return PrivilegeState.NOT_CONFIGURED
    }

    override suspend fun probeCapture(): CaptureCapability {
        // TODO(M2): delegate to the shell capture service probe().
        return CaptureCapability(workingSources = emptyList(), sampleRateHz = 16_000)
    }

    override fun disconnect() {
        // TODO(M2): stop spawned capture process, close adbd socket.
    }
}
