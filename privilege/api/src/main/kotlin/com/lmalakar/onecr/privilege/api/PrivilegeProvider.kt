package com.lmalakar.onecr.privilege.api

import com.lmalakar.onecr.core.model.CaptureSource

/**
 * Lifecycle of the privilege bridge (embedded ADB or Shizuku).
 * Surfaced to the UI as a StateFlow by PrivilegeHealthMonitor (M2).
 */
enum class PrivilegeState {
    /** User has not completed onboarding yet. */
    NOT_CONFIGURED,

    /** Bridge connected, capture service can run as shell (UID 2000). */
    OK,

    /** Wireless debugging / Shizuku connection lost (toggled off, network change). */
    PAIRING_LOST,

    /** Developer options were disabled — embedded-ADB mode cannot run. */
    DEV_OPTIONS_OFF,

    /** The privileged service died (typically after reboot) and needs a restart. */
    SERVICE_DEAD,

    /** Device/OS combination does not support this bridge. */
    UNSUPPORTED,
}

/** Which privilege route the app uses to obtain shell (UID 2000) capabilities. */
enum class PrivilegeMode {
    /** CallVault-style: on-device wireless-debugging pairing, no extra app. */
    EMBEDDED_ADB,

    /** Borrow privileges from the Shizuku app. */
    SHIZUKU,
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

/**
 * Contract implemented by :privilege:shizuku and :privilege:embedded-adb.
 * Everything behind this interface may run code with adb-shell (UID 2000) privileges;
 * the privileged surface itself is only IShellCaptureService (see ARCHITECTURE.md).
 */
interface PrivilegeProvider {
    val mode: PrivilegeMode

    /** Connect (and pair if needed). Returns the resulting state. */
    suspend fun connect(): PrivilegeState

    /** Cheap liveness check — called periodically and on binder death events. */
    suspend fun health(): PrivilegeState

    /** Probe which privileged audio sources work on this device (run once, cache result). */
    suspend fun probeCapture(): CaptureCapability

    /** Release the bridge. */
    fun disconnect()
}
