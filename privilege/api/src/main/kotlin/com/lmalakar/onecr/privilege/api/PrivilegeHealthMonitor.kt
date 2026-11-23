package com.lmalakar.onecr.privilege.api

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.lmalakar.onecr.core.common.DispatchersProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Persists the last observed boot count so reboot detection survives process death. */
interface BootCountStore {
    fun lastKnownBootCount(): Int
    fun rememberBootCount(count: Int)
}

/** A [BootCountStore] that never persists anything (used in tests). */
class NoOpBootCountStore : BootCountStore {
    private var last = -1
    override fun lastKnownBootCount(): Int = last
    override fun rememberBootCount(count: Int) {
        last = count
    }
}

/**
 * Single source of truth for bridge health in the UI.
 *
 * Combines three signals:
 *  - a periodic ping (default 30 s) of [PrivilegeProvider.health];
 *  - provider-pushed changes ([PrivilegeProvider.onHealthChanged] — binder death etc.);
 *  - reboot detection via Settings.Global.BOOT_COUNT (privileges always die on reboot).
 *
 * When the boot counter changes, the detail line gains a "device rebooted" note
 * until the bridge reports OK again — the "repair banner" from the roadmap.
 */
class PrivilegeHealthMonitor(
    private val provider: PrivilegeProvider,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val dispatchers: DispatchersProvider,
    private val bootCountStore: BootCountStore,
    private val pingIntervalMs: Long = 30_000,
) {

    private val _health = MutableStateFlow(
        PrivilegeHealth(
            state = PrivilegeState.NOT_CONFIGURED,
            mode = provider.mode,
            detail = "Checking bridge…",
            action = PrivilegeAction.NONE,
        ),
    )
    val health: StateFlow<PrivilegeHealth> = _health.asStateFlow()

    private var pingJob: Job? = null
    private var rebootedSinceLastOk = false

    fun start() {
        if (pingJob?.isActive == true) return
        provider.onHealthChanged = { refreshAsync() }
        pingJob = scope.launch {
            while (isActive) {
                refreshOnce()
                delay(pingIntervalMs)
            }
        }
    }

    fun stop() {
        pingJob?.cancel()
        pingJob = null
        provider.onHealthChanged = null
    }

    /** Trigger an immediate refresh (e.g. after the user returns from Shizuku). */
    fun refreshAsync() {
        scope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        val state = withContext(dispatchers.io) {
            runCatching { provider.health() }.getOrElse { PrivilegeState.PAIRING_LOST }
        }
        publish(state)
    }

    private fun publish(state: PrivilegeState) {
        if (checkBootCount() && state != PrivilegeState.OK) {
            rebootedSinceLastOk = true
        }
        if (state == PrivilegeState.OK) {
            rebootedSinceLastOk = false
        }

        val (detail, action) = describe(state, provider.mode)
        val fullDetail = buildString {
            append(detail)
            if (rebootedSinceLastOk && state != PrivilegeState.OK) {
                append(" Device rebooted since the last healthy check — reconnect once.")
            }
        }

        _health.value = PrivilegeHealth(
            state = state,
            mode = provider.mode,
            detail = fullDetail,
            action = action,
        )
    }

    /** Reads BOOT_COUNT; returns true when it changed since the last observed value. */
    private fun checkBootCount(): Boolean {
        val current = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(-1)
        if (current < 0) return false
        val last = bootCountStore.lastKnownBootCount()
        bootCountStore.rememberBootCount(current)
        return last >= 0 && last != current
    }

    private fun describe(state: PrivilegeState, mode: PrivilegeMode): Pair<String, PrivilegeAction> =
        when (state) {
            PrivilegeState.OK ->
                "Shell bridge connected (UID 2000). Privileged capture will use it from M3." to PrivilegeAction.NONE
            PrivilegeState.NOT_CONFIGURED -> when (mode) {
                PrivilegeMode.EMBEDDED_ADB ->
                    "No device paired yet — open Wireless debugging and pair with the 6-digit code." to PrivilegeAction.REPAIR
                else ->
                    "Shizuku is not installed. Install it to enable the privilege bridge." to PrivilegeAction.INSTALL_SHIZUKU
            }
            PrivilegeState.NEEDS_PERMISSION ->
                "Grant oneCR permission inside Shizuku to use the shell bridge." to PrivilegeAction.GRANT_PERMISSION
            PrivilegeState.PAIRING_LOST -> when (mode) {
                PrivilegeMode.EMBEDDED_ADB ->
                    "Cannot reach adbd — keep Wireless debugging on and the phone on the same Wi-Fi." to PrivilegeAction.REPAIR
                else ->
                    "Shizuku is installed but not running — open Shizuku and start it." to PrivilegeAction.START_SHIZUKU
            }
            PrivilegeState.SERVICE_DEAD ->
                "The privileged service died (reboot?). Reconnect from Settings." to PrivilegeAction.REPAIR
            PrivilegeState.DEV_OPTIONS_OFF ->
                "Developer options (or Wireless debugging) are off — embedded-ADB mode cannot run." to PrivilegeAction.REPAIR
            PrivilegeState.UNSUPPORTED ->
                "This device/OS combination does not support the bridge." to PrivilegeAction.REPAIR
        }

    companion object {
        /** True when the OS is new enough for the privileged capture path (Android 11+). */
        fun platformSupported(): Boolean = Build.VERSION.SDK_INT >= 30
    }
}
