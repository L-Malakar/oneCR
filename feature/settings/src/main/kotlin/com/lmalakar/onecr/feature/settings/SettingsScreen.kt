package com.lmalakar.onecr.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.designsystem.component.SectionHeader
import com.lmalakar.onecr.core.model.AppInfo
import com.lmalakar.onecr.core.model.PermissionSnapshot
import com.lmalakar.onecr.core.model.ThemeMode
import com.lmalakar.onecr.data.files.EventLogEntry
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

/**
 * v0.2.2 settings: the same sections as v0.2.1 (theme, bridge, permissions,
 * event log, about) grouped under section headers, with a live permission
 * summary on the Permission center link.
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
    onGrantShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    onClearEventLog: () -> Unit,
    onRerunOnboarding: () -> Unit,
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

    when (page) {
        SettingsPage.ROOT -> RootSettings(
            appInfo = appInfo,
            logoRes = logoRes,
            privilegeHealth = privilegeHealth,
            recentEvents = recentEvents,
            permissions = permissions,
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            onGrantShizukuPermission = onGrantShizukuPermission,
            onOpenShizuku = onOpenShizuku,
            onInstallShizuku = onInstallShizuku,
            onVerifyBridge = onVerifyBridge,
            onRerunOnboarding = onRerunOnboarding,
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
    onGrantShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onInstallShizuku: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    onRerunOnboarding: () -> Unit,
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
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "\"System default\" follows your phone's dark-mode setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeChange(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == themeMode,
                            onClick = { onThemeChange(mode) },
                        )
                        Icon(
                            imageVector = themeIcon(mode),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(18.dp),
                            tint = if (mode == themeMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }
        }

        SectionHeader("Privilege bridge")
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
                        "Shizuku bridge — ${health.mode.name.lowercase().replaceFirstChar { it.uppercase() }}",
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
