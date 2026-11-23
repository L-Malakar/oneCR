package com.lmalakar.onecr.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.model.AppInfo
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.privilege.api.PrivilegeAction
import com.lmalakar.onecr.privilege.api.PrivilegeHealth
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class SettingsPage { ROOT, PERMISSIONS, EVENT_LOG }

/**
 * v0.2.0 settings: live privilege bridge card + permission center + event log,
 * with future sections (models, storage) still labeled by milestone.
 */
@Composable
fun SettingsScreen(
    appInfo: AppInfo,
    privilegeHealth: StateFlow<PrivilegeHealth>,
    recentEvents: StateFlow<List<EventLogEntry>>,
    selectedPrivilegeMode: String,
    onSelectPrivilegeMode: (String) -> Unit,
    onGrantShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    onClearEventLog: () -> Unit,
    onRerunOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    when (page) {
        SettingsPage.ROOT -> RootSettings(
            appInfo = appInfo,
            privilegeHealth = privilegeHealth,
            recentEvents = recentEvents,
            selectedPrivilegeMode = selectedPrivilegeMode,
            onSelectPrivilegeMode = onSelectPrivilegeMode,
            onGrantShizukuPermission = onGrantShizukuPermission,
            onOpenShizuku = onOpenShizuku,
            onInstallShizuku = onInstallShizuku,
            onVerifyBridge = onVerifyBridge,
            onRerunOnboarding = onRerunOnboarding,
            onOpenPermissions = { page = SettingsPage.PERMISSIONS },
            onOpenEventLog = { page = SettingsPage.EVENT_LOG },
            modifier = modifier,
        )

        SettingsPage.PERMISSIONS -> PermissionCenterScreen(
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

// ---------------------------------------------------------------------
// Root
// ---------------------------------------------------------------------

@Composable
private fun RootSettings(
    appInfo: AppInfo,
    privilegeHealth: StateFlow<PrivilegeHealth>,
    recentEvents: StateFlow<List<EventLogEntry>>,
    selectedPrivilegeMode: String,
    onSelectPrivilegeMode: (String) -> Unit,
    onGrantShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    onRerunOnboarding: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenEventLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val health by privilegeHealth.collectAsState()
    val events by recentEvents.collectAsState()
    val verifyScope = rememberCoroutineScope()
    var verifyResult by remember { mutableStateOf<ShellCommandResult?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // ---------------- Privilege mode picker (v0.3.0) ----------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Privilege mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "How oneCR obtains shell (UID 2000) access for the capture path.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModeRow(
                    title = "Embedded ADB",
                    detail = "No extra app — pairs with Wireless debugging (Android 11+)",
                    selected = selectedPrivilegeMode == "EMBEDDED_ADB",
                    onClick = { onSelectPrivilegeMode("EMBEDDED_ADB") },
                )
                ModeRow(
                    title = "Shizuku",
                    detail = "Uses the separate Shizuku app",
                    selected = selectedPrivilegeMode == "SHIZUKU",
                    onClick = { onSelectPrivilegeMode("SHIZUKU") },
                )
            }
        }

        // ---------------- Privilege bridge (live) ----------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (health.state == PrivilegeState.OK) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.Warning
                        },
                        contentDescription = null,
                        tint = if (health.state == PrivilegeState.OK) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Privilege bridge — ${health.mode.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
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

        // ---------------- Permission center ----------------
        SettingsLink(
            icon = Icons.Filled.Notifications,
            title = "Permission center",
            detail = "Phone, call log, notifications, notification access, battery",
            onClick = onOpenPermissions,
        )

        // ---------------- Event log ----------------
        SettingsLink(
            icon = Icons.Filled.List,
            title = "Event log",
            detail = "${events.size} recent detection events (local only)",
            onClick = onOpenEventLog,
        )

        // ---------------- Future sections ----------------
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

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("About", style = MaterialTheme.typography.titleSmall)
                Text(
                    "oneCR v${appInfo.versionName} (build ${appInfo.versionCode}, ${appInfo.buildType})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Project home: github.com/L-Malakar/oneCR",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/L-Malakar/oneCR")),
                            )
                        }
                    },
                )
                Text(
                    text = "Privacy: nothing leaves this device. See PRIVACY.md in the repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "License: MIT. Recording laws vary by region — use lawfully and inform call participants.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRerunOnboarding) { Text("Re-run the setup wizard") }
            }
        }
    }
}

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
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowForward,
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
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
