package com.lmalakar.onecr.privilege.api

import com.lmalakar.onecr.core.model.CaptureSource

/**
 * Lifecycle of the privilege bridge (embedded ADB or Shizuku).
 * Published to the UI as [PrivilegeHealth] by [PrivilegeHealthMonitor].
 */
enum class PrivilegeState {
    /** User has not completed onboarding yet (or the bridge app is not installed). */
    NOT_CONFIGURED,

    /** Bridge connected, the shell service runs as shell (UID 2000). */
    OK,

    /** Bridge app is installed but not running / connection lost (toggled off, killed, network change). */
    PAIRING_LOST,

    /** Developer options were disabled — embedded-ADB mode cannot run. */
    DEV_OPTIONS_OFF,

    /** The privileged service died (typically after reboot) and needs a restart. */
    SERVICE_DEAD,

    /** The bridge app runs but oneCR has not been granted permission yet. */
    NEEDS_PERMISSION,

    /** Device/OS combination does not support this bridge. */
    UNSUPPORTED,
}

/** Which privilege route the app uses to obtain shell (UID 2000) capabilities. */
enum class PrivilegeMode {
    /** CallVault-style: on-device wireless-debugging pairing, no extra app (lands in v0.3.0). */
    EMBEDDED_ADB,

    /** Borrow privileges from the Shizuku app. */
    SHIZUKU,
}

/** What the user should do next to restore the bridge — drives the repair UI. */
enum class PrivilegeAction {
    /** Nothing to do, the bridge is healthy. */
    NONE,

    /** Install the Shizuku app. */
    INSTALL_SHIZUKU,

    /** Open Shizuku and start it (it was installed but is not running). */
    START_SHIZUKU,

    /** Grant oneCR permission inside Shizuku. */
    GRANT_PERMISSION,

    /** Re-run setup / re-pair (generic repair). */
    REPAIR,
}

/** Result of probing which privileged audio sources actually work on this device. */
data class CaptureCapability(
    /** Sources confirmed to return valid audio on this device, in fallback order. */
    val workingSources: List<CaptureSource>,

    /** Sample rate reported by the working VOICE_* source(s). */
    val sampleRateHz: Int,

    /** Human-readable notes for the diagnostics screen (OEM quirks, error codes). */
    val notes: String = "",
)

/** Result of a command executed through the shell bridge (runs as UID 2000). */
data class ShellCommandResult(
    val command: String,
    val combinedOutput: String,
    val exitCode: Int?,
    val ok: Boolean,
)

/**
 * Full health snapshot for the UI: state + human detail + next action.
 * Produced exclusively by [PrivilegeHealthMonitor]; screens never poll providers directly.
 */
data class PrivilegeHealth(
    val state: PrivilegeState,
    val mode: PrivilegeMode,
    val detail: String,
    val action: PrivilegeAction,
) {
    val isOk: Boolean get() = state == PrivilegeState.OK
}

/**
 * Contract implemented by :privilege:shizuku and :privilege:embedded-adb.
 * Everything behind this interface may run code with adb-shell (UID 2000) privileges;
 * the privileged surface itself is only the tiny shell service (see ARCHITECTURE.md).
 */
interface PrivilegeProvider {
    val mode: PrivilegeMode

    /** Connect (bind the shell service; request nothing on its own). Returns the resulting state. */
    suspend fun connect(): PrivilegeState

    /** Cheap liveness check — called periodically and on binder death events. */
    suspend fun health(): PrivilegeState

    /**
     * Run a short command through the shell bridge (UID 2000). Used by the onboarding
     * "verify bridge" step (e.g. `id`) — NOT a general escape hatch; keep commands minimal.
     */
    suspend fun runShellCommand(command: String): ShellCommandResult

    /** Probe which privileged audio sources work on this device (run once, cache result; M3). */
    suspend fun probeCapture(): CaptureCapability

    /**
     * Open a live PCM capture stream from the privileged capture process (M3).
     * The stream is backed by a dedicated channel (a second TLS session for the
     * embedded-ADB bridge, a binder pipe for Shizuku) so command traffic on the
     * main bridge — health pings, shell checks — keeps flowing while audio
     * streams. Implementations must return promptly with a stream whose
     * [CaptureStream.pcm] blocks until audio (or EOF) arrives.
     */
    suspend fun openCaptureStream(spec: CaptureStreamSpec): CaptureStream

    /**
     * Listener invoked whenever the provider notices a state change on its own
     * (binder death, permission revoked, Shizuku stopped). The monitor re-pings on it.
     */
    var onHealthChanged: (() -> Unit)?

    /** Release the bridge. */
    fun disconnect()
}
