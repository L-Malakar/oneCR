package com.lmalakar.onecr.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.core.designsystem.component.SectionHeader
import com.lmalakar.onecr.core.designsystem.motion.LocalMotionEnabled
import com.lmalakar.onecr.core.designsystem.motion.MOTION_ENTER_MS
import com.lmalakar.onecr.core.designsystem.motion.MOTION_EXIT_MS
import com.lmalakar.onecr.core.model.AppInfo
import com.lmalakar.onecr.core.model.PermissionSnapshot
import com.lmalakar.onecr.core.model.ThemeMode
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.privilege.api.CaptureCapability
import com.lmalakar.onecr.privilege.api.PrivilegeAction
import com.lmalakar.onecr.privilege.api.PrivilegeHealth
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class SettingsPage { ROOT, PERMISSIONS, EVENT_LOG }

/** Leading icon for each theme choice (v0.2.4 — icons over words where possible). */
private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> Icons.Filled.Contrast
    ThemeMode.DARK -> Icons.Filled.DarkMode
    ThemeMode.LIGHT -> Icons.Filled.LightMode
}

/** Short labels for the segmented theme picker (the full label is too wide). */
private fun themeSegmentLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

/**
 * v0.2.2 settings: the same sections as v0.2.1 (theme, bridge, permissions,
 * event log, about) grouped under section headers, with a live permission
 * summary on the Permission center link.
 *
 * v0.2.6: the panel grew a modern look — every card leads with a colored icon
 * tile, the theme choice became a three-way segmented control with icons, and
 * a new Animations switch (default ON) gates every transition in the app. A
 * battery-optimization card under "Keep alive" protects the 24×7 watcher, and
 * the Permission center / Event log pages now slide in and out.
 *
 * v0.3.1: the Privilege bridge card carries the v0.3.0 mode picker — embedded
 * ADB (no extra app) or Shizuku — above the live bridge status, so the route
 * can be switched without re-running the wizard.
 *
 * v0.3.3: a Capture section joins the panel — the auto-record switch (M3
 * step 1) and a capture probe card that reports which audio sources actually
 * work on this phone (the fallback chain the recorder uses).
 */
@Composable
fun SettingsScreen(
    appInfo: AppInfo,
    logoRes: Int,
    privilegeHealth: StateFlow<PrivilegeHealth>,
    recentEvents: StateFlow<List<EventLogEntry>>,
    permissions: StateFlow<PermissionSnapshot>,
    onRefreshPermissions: () -> Unit,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    animationsEnabled: Boolean,
    onAnimationsChange: (Boolean) -> Unit,
    onGrantShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    selectedPrivilegeMode: String,
    onSelectPrivilegeMode: (String) -> Unit,
    onClearEventLog: () -> Unit,
    onRerunOnboarding: () -> Unit,
    autoRecordEnabled: Boolean,
    onAutoRecordChange: (Boolean) -> Unit,
    onProbeCapture: suspend () -> CaptureCapability,
    onCheckForUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    // v0.2.5: the settings root keeps its scroll position. The scroll state is
    // owned here — above the page switch — so opening the Permission center or
    // the Event log (which replaces ROOT in composition) and coming back via
    // back/gesture no longer snaps the list to the top; it stays exactly where
    // the user left it. ScrollState.Saver also survives tab switches and process
    // death.
    val rootScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    // System back returns from the sub-pages to the settings root instead of
    // leaving the app (v0.2.3).
    BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }

    // v0.2.6: sub-pages slide in the direction of travel; instant when the
    // Animations switch is off.
    val motion = LocalMotionEnabled.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (motion) {
                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (
                    slideInHorizontally(tween(MOTION_ENTER_MS)) { dir * it / 5 } +
                        fadeIn(tween(MOTION_ENTER_MS))
                    ) togetherWith (
                    slideOutHorizontally(tween(MOTION_EXIT_MS)) { -dir * it / 5 } +
                        fadeOut(tween(MOTION_EXIT_MS))
                    )
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "settingsPage",
    ) { target ->
        when (target) {
            SettingsPage.ROOT -> RootSettings(
                appInfo = appInfo,
                logoRes = logoRes,
                privilegeHealth = privilegeHealth,
                recentEvents = recentEvents,
                permissions = permissions,
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                animationsEnabled = animationsEnabled,
                onAnimationsChange = onAnimationsChange,
                onGrantShizukuPermission = onGrantShizukuPermission,
                onOpenShizuku = onOpenShizuku,
                onInstallShizuku = onInstallShizuku,
                onVerifyBridge = onVerifyBridge,
                selectedPrivilegeMode = selectedPrivilegeMode,
                onSelectPrivilegeMode = onSelectPrivilegeMode,
                onRerunOnboarding = onRerunOnboarding,
                autoRecordEnabled = autoRecordEnabled,
                onAutoRecordChange = onAutoRecordChange,
                onProbeCapture = onProbeCapture,
                onCheckForUpdates = onCheckForUpdates,
                onOpenPermissions = { page = SettingsPage.PERMISSIONS },
                onOpenEventLog = { page = SettingsPage.EVENT_LOG },
                scrollState = rootScrollState,
                modifier = modifier,
            )

            SettingsPage.PERMISSIONS -> PermissionCenterScreen(
                permissions = permissions,
                onRefreshPermissions = onRefreshPermissions,
                onBack = { page = SettingsPage.ROOT },
                modifier = modifier,
            )

            SettingsPage.EVENT_LOG -> EventLogScreen(
                events = recentEvents,
                onClear = onClearEventLog,
                onBack = { page = SettingsPage.ROOT },
                modifier = modifier,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------

@Composable
private fun RootSettings(
    appInfo: AppInfo,
    logoRes: Int,
    privilegeHealth: StateFlow<PrivilegeHealth>,
    recentEvents: StateFlow<List<EventLogEntry>>,
    permissions: StateFlow<PermissionSnapshot>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    animationsEnabled: Boolean,
    onAnimationsChange: (Boolean) -> Unit,
    onGrantShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    selectedPrivilegeMode: String,
    onSelectPrivilegeMode: (String) -> Unit,
    onRerunOnboarding: () -> Unit,
    autoRecordEnabled: Boolean,
    onAutoRecordChange: (Boolean) -> Unit,
    onProbeCapture: suspend () -> CaptureCapability,
    onCheckForUpdates: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenEventLog: () -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val health by privilegeHealth.collectAsState()
    val events by recentEvents.collectAsState()
    val permissionSnapshot by permissions.collectAsState()
    val verifyScope = rememberCoroutineScope()
    var verifyResult by remember { mutableStateOf<ShellCommandResult?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Appearance")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconTile(Icons.Filled.Palette)
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    "\"System\" follows your phone's dark-mode setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = mode == themeMode,
                            onClick = { onThemeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                            icon = {
                                Icon(
                                    imageVector = themeIcon(mode),
                                    contentDescription = null,
                                    modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            },
                            label = { Text(themeSegmentLabel(mode)) },
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                // v0.2.6: the master motion switch — ON by default. Gates every
                // screen transition, crossfade and pulse in the app.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Animation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Animations",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Smooth motion across screens and controls. " +
                                "Turn off for an instant, minimal feel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = animationsEnabled,
                        onCheckedChange = onAnimationsChange,
                    )
                }
            }
        }

        SectionHeader("Privilege bridge")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconTile(
                        if (health.state == PrivilegeState.OK) Icons.Filled.CheckCircle else Icons.Filled.Key,
                        containerColor = if (health.state == PrivilegeState.OK) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        iconColor = if (health.state == PrivilegeState.OK) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                    Text(
                        "Privilege bridge — ${health.mode.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // v0.3.1: the v0.3.0 mode picker, restyled into the modern card.
                // Switching routes persists the choice and restarts the live
                // health monitor on the new bridge.
                ModeRow(
                    title = "Embedded ADB",
                    detail = "No extra app — pairs with Wireless debugging (Android 11+)",
                    selected = selectedPrivilegeMode == "EMBEDDED_ADB",
                    onClick = { onSelectPrivilegeMode("EMBEDDED_ADB") },
                )
                ModeRow(
                    title = "Shizuku",
                    detail = "Uses the separate Shizuku app (Android 8+)",
                    selected = selectedPrivilegeMode == "SHIZUKU",
                    onClick = { onSelectPrivilegeMode("SHIZUKU") },
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Text(
                    health.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                when (health.action) {
                    PrivilegeAction.INSTALL_SHIZUKU ->
                        OutlinedButton(onClick = onInstallShizuku) { Text("Install Shizuku") }

                    PrivilegeAction.START_SHIZUKU ->
                        OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku and start it") }

                    PrivilegeAction.GRANT_PERMISSION ->
                        OutlinedButton(onClick = onGrantShizukuPermission) { Text("Grant permission in Shizuku") }

                    PrivilegeAction.REPAIR ->
                        OutlinedButton(onClick = onRerunOnboarding) { Text("Re-run setup") }

                    PrivilegeAction.NONE -> Unit
                }

                if (health.state == PrivilegeState.OK) {
                    if (verifyResult == null) {
                        OutlinedButton(onClick = {
                            verifyScope.launch { verifyResult = onVerifyBridge() }
                        }) { Text("Verify bridge (runs `id`)") }
                    } else {
                        val result = verifyResult
                        Text(
                            if (result?.ok == true) {
                                "Shell check: ${result.combinedOutput.lineSequence().firstOrNull().orEmpty()}"
                            } else {
                                "Shell check failed: ${result?.combinedOutput.orEmpty()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { verifyResult = null }) { Text("Re-run check") }
                    }
                }
            }
        }

        SectionHeader("Capture (M3)")
        CaptureCards(
            autoRecordEnabled = autoRecordEnabled,
            onAutoRecordChange = onAutoRecordChange,
            onProbeCapture = onProbeCapture,
        )

        SectionHeader("Keep alive")
        BatteryCard()

        SectionHeader("Permissions and diagnostics")
        SettingsLink(
            icon = Icons.Filled.Notifications,
            title = "Permission center",
            detail = if (permissionSnapshot.allGranted) {
                "All 5 grants in place — tap for details"
            } else {
                "${5 - permissionSnapshot.missingCount} of 5 granted — grant the rest one by one"
            },
            onClick = onOpenPermissions,
        )

        SettingsLink(
            icon = Icons.Filled.List,
            title = "Event log",
            detail = "${events.size} recent detection events (local only)",
            onClick = onOpenEventLog,
        )

        SectionHeader("Coming next")
        FutureCard(
            icon = Icons.Filled.Star,
            title = "Models",
            detail = "whisper.cpp + llama.cpp download, sideload, delete",
            milestone = "M5",
        )
        FutureCard(
            icon = Icons.Filled.Clear,
            title = "Storage and export",
            detail = "Retention policy, export location, storage stats",
            milestone = "M4",
        )

        SectionHeader("About")
        AboutSection(
            appInfo = appInfo,
            logoRes = logoRes,
            onRerunOnboarding = onRerunOnboarding,
            onCheckForUpdates = onCheckForUpdates,
        )
    }
}

// ---------------------------------------------------------------------
// Capture (M3 step 1, v0.3.3) — auto-record + capability probe
// ---------------------------------------------------------------------

/**
 * Two cards:
 *  - **Auto-record calls** — when the watcher sees a cellular call go active
 *    and this switch is on, the capture engine starts recording through its
 *    probe-verified fallback chain (VOICE_CALL → mic) and stops when the call
 *    ends. Manual "Record now" on the Dashboard works regardless.
 *  - **Capture probe** — opens the candidate sources once and reports which
 *    ones return real audio (some OEMs silence VOICE_CALL for normal apps).
 *    The probe needs the microphone permission the same way recording does.
 */
@Composable
private fun CaptureCards(
    autoRecordEnabled: Boolean,
    onAutoRecordChange: (Boolean) -> Unit,
    onProbeCapture: suspend () -> CaptureCapability,
) {
    val context = LocalContext.current
    val probeScope = rememberCoroutineScope()
    var probeResult by remember { mutableStateOf<CaptureCapability?>(null) }
    var probeFailed by remember { mutableStateOf<String?>(null) }
    var probeBusy by remember { mutableStateOf(false) }

    fun runProbe() {
        probeBusy = true
        probeFailed = null
        probeScope.launch {
            runCatching { onProbeCapture() }
                .onSuccess { probeResult = it }
                .onFailure { probeFailed = it.message ?: it.javaClass.simpleName }
            probeBusy = false
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) runProbe()
    }

    fun startProbe() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) runProbe() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconTile(Icons.Filled.Mic)
                Text(
                    "Call capture",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-record calls",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Starts recording when a cellular call goes active and stops " +
                            "when it ends. Recordings stay on this device only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoRecordEnabled, onCheckedChange = onAutoRecordChange)
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            Text(
                "Capture probe",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Checks which audio sources this phone actually opens (VOICE_CALL, " +
                    "microphone) — the chain the recorder walks before recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (probeResult != null || probeFailed != null) {
                val result = probeResult
                Text(
                    text = when {
                        probeFailed != null -> "Probe failed: $probeFailed"
                        result != null -> "Working sources: " +
                            (result.workingSources.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }
                                ?: "none usable in-app") +
                            " · ${result.sampleRateHz} Hz"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (probeFailed != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (result?.notes?.isNotBlank() == true) {
                    Text(
                        result.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(onClick = ::startProbe, enabled = !probeBusy) {
                Text(if (probeBusy) "Probing…" else "Run capture probe")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Battery — protects the 24×7 watcher (v0.2.6)
// ---------------------------------------------------------------------

/**
 * The companion card to the "Watching for calls" fix: many OEM ROMs pause
 * background apps unless they are exempt from battery optimization. Exempting
 * oneCR is what keeps the watcher (and its notification) alive when the app
 * itself is closed. The state is re-read on every recomposition, so coming
 * back from the system dialog updates the card instantly.
 */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val exempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    if (exempt) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Icons.Filled.CheckCircle)
                Column {
                    Text(
                        "Battery optimization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "oneCR is exempt — the watcher keeps running with the app closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Battery optimization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "Your ROM can pause the 24×7 watcher when oneCR is closed. " +
                            "Exempting it keeps the \"Watching for calls\" notification alive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = {
                            runCatching {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                    ) {
                        Text("Allow background running")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Reusable pieces
// ---------------------------------------------------------------------

/** Rounded tonal icon container leading modern cards (v0.2.6). */
@Composable
private fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = iconColor,
        )
    }
}

@Composable
private fun SettingsLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FutureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    milestone: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = milestone,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * One privilege route in the Settings mode picker (v0.3.0, restyled v0.3.1).
 * A radio row inside the Privilege bridge card — tapping it switches the live
 * bridge and restarts the health monitor on the new route.
 */
@Composable
private fun ModeRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
