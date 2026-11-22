package com.lmalakar.onecr.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.AppContainer
import com.lmalakar.onecr.R
import com.lmalakar.onecr.core.designsystem.theme.AmberAccent
import com.lmalakar.onecr.feature.library.LibraryScreen
import com.lmalakar.onecr.feature.onboarding.OnboardingScreen
import com.lmalakar.onecr.feature.settings.SettingsScreen
import com.lmalakar.onecr.privilege.api.PrivilegeAction
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.ui.dashboard.DashboardScreen
import com.lmalakar.onecr.ui.notifications.NotificationCenterScreen

/** Model imports kept off the hot path; icons come from material-icons-core. */
private enum class TopLevelTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    LIBRARY("Library", Icons.Filled.PlayArrow),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/**
 * v0.2.2 shell: branded top app bar (logo + tab + version) with a notification
 * bell on the top-right; the bell opens the notification center — one place for
 * everything the app needs, its errors and its activity log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneCRApp(container: AppContainer) {
    val context = LocalContext.current
    var showOnboarding by rememberSaveable {
        mutableStateOf(!container.settings.onboardingCompleted)
    }
    var showNotificationCenter by rememberSaveable { mutableStateOf(false) }
    val notificationModel by container.notificationCenter.model.collectAsState()

    fun startDetectionService() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                android.content.Intent(context, CallDetectionService::class.java)
                    .setAction(DetectionContract.ACTION_START),
            )
        }
    }

    if (showOnboarding) {
        OnboardingScreen(
            privilegeHealth = container.privilegeMonitor.health,
            permissions = container.permissions,
            versionName = container.appInfo.versionName,
            isShizukuInstalled = container.shizukuProvider::isShizukuInstalled,
            onInstallShizuku = {
                runCatching { context.startActivity(container.shizukuProvider.shizukuInstallIntent()) }
            },
            onOpenShizuku = {
                val launch = container.shizukuProvider.shizukuLaunchIntent()
                if (launch != null) runCatching { context.startActivity(launch) }
            },
            onRequestShizukuPermission = container.shizukuProvider::requestPermission,
            onVerifyBridge = { container.privilegeProvider.runShellCommand("id") },
            onRefreshPermissions = container::refreshPermissions,
            onComplete = { autostart ->
                container.settings.privilegeMode = container.privilegeProvider.mode.name
                container.settings.onboardingCompleted = true
                container.settings.detectionAutostart = autostart
                container.refreshPermissions()
                showOnboarding = false
                if (autostart) startDetectionService()
            },
        )
        return
    }

    var selectedTabName by rememberSaveable { mutableStateOf(TopLevelTab.DASHBOARD.name) }
    val currentTab = TopLevelTab.valueOf(selectedTabName)
    val badgeCount = notificationModel.badgeCount

    // Opening the center clears the unread badge immediately; the items stay
    // visible until they are actually resolved.
    LaunchedEffect(showNotificationCenter) {
        if (showNotificationCenter) container.notificationCenter.markAllSeen()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_brand_logo),
                            contentDescription = "oneCR logo",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Column {
                            Text(
                                text = "oneCR",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${currentTab.label} · v${container.appInfo.versionName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // The bell: unread badge counts needs + errors + unseen activity.
                    IconButton(onClick = { showNotificationCenter = true }) {
                        BadgedBox(
                            badge = {
                                if (badgeCount > 0) {
                                    Badge(containerColor = AmberAccent) {
                                        Text(
                                            text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                                            color = Color(0xFF14181F),
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Notifications,
                                contentDescription = "Notifications",
                                tint = if (badgeCount > 0) {
                                    AmberAccent
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            // Container pinned to `surface` — the default baseline surfaceContainer
            // is plain grey and clashed with the navy palette (v0.2.2 fix).
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TopLevelTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == currentTab,
                        onClick = { selectedTabName = tab.name },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(text = tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (currentTab) {
            TopLevelTab.DASHBOARD ->
                DashboardScreen(
                    container = container,
                    onOpenNotifications = { showNotificationCenter = true },
                    modifier = Modifier.padding(innerPadding),
                )

            TopLevelTab.LIBRARY ->
                LibraryScreen(modifier = Modifier.padding(innerPadding))

            TopLevelTab.SETTINGS ->
                SettingsScreen(
                    appInfo = container.appInfo,
                    privilegeHealth = container.privilegeMonitor.health,
                    recentEvents = container.detection.recentEvents,
                    permissions = container.permissions,
                    onRefreshPermissions = container::refreshPermissions,
                    themeMode = container.themeMode.value,
                    onThemeChange = container::setThemeMode,
                    onGrantShizukuPermission = container.shizukuProvider::requestPermission,
                    onOpenShizuku = {
                        val launch = container.shizukuProvider.shizukuLaunchIntent()
                        if (launch != null) runCatching { context.startActivity(launch) }
                    },
                    onInstallShizuku = {
                        runCatching { context.startActivity(container.shizukuProvider.shizukuInstallIntent()) }
                    },
                    onVerifyBridge = { container.privilegeProvider.runShellCommand("id") },
                    onClearEventLog = container.detection::clearLog,
                    onRerunOnboarding = { showOnboarding = true },
                    modifier = Modifier.padding(innerPadding),
                )
        }
    }

    if (showNotificationCenter) {
        NotificationCenterScreen(
            model = notificationModel,
            onRefreshPermissions = container::refreshPermissions,
            onStartDetection = { startDetectionService() },
            onBridgeAction = {
                when (container.privilegeMonitor.health.value.action) {
                    PrivilegeAction.INSTALL_SHIZUKU ->
                        runCatching { context.startActivity(container.shizukuProvider.shizukuInstallIntent()) }

                    PrivilegeAction.START_SHIZUKU -> {
                        val launch = container.shizukuProvider.shizukuLaunchIntent()
                        if (launch != null) runCatching { context.startActivity(launch) }
                    }

                    PrivilegeAction.GRANT_PERMISSION -> container.shizukuProvider.requestPermission()

                    PrivilegeAction.REPAIR -> {
                        showNotificationCenter = false
                        showOnboarding = true
                    }

                    PrivilegeAction.NONE -> Unit
                }
            },
            onMarkAllSeen = { container.notificationCenter.markAllSeen() },
            onBack = { showNotificationCenter = false },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
