package com.lmalakar.onecr.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.AppContainer
import com.lmalakar.onecr.core.model.ThemeMode
import com.lmalakar.onecr.feature.library.LibraryScreen
import com.lmalakar.onecr.feature.onboarding.OnboardingScreen
import com.lmalakar.onecr.feature.settings.SettingsScreen
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.ui.dashboard.DashboardScreen

/** Model imports kept off the hot path; icons come from material-icons-core. */
private enum class TopLevelTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    LIBRARY("Library", Icons.Filled.PlayArrow),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun OneCRApp(container: AppContainer) {
    val context = LocalContext.current
    var showOnboarding by rememberSaveable {
        mutableStateOf(!container.settings.onboardingCompleted)
    }

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
            onComplete = { autostart ->
                container.settings.privilegeMode = container.privilegeProvider.mode.name
                container.settings.onboardingCompleted = true
                container.settings.detectionAutostart = autostart
                showOnboarding = false
                if (autostart) startDetectionService()
            },
        )
        return
    }

    var selectedTabName by rememberSaveable { mutableStateOf(TopLevelTab.DASHBOARD.name) }
    val currentTab = TopLevelTab.valueOf(selectedTabName)

    Scaffold(
        bottomBar = {
            NavigationBar {
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
                DashboardScreen(container = container, modifier = Modifier.padding(innerPadding))

            TopLevelTab.LIBRARY ->
                LibraryScreen(modifier = Modifier.padding(innerPadding))

            TopLevelTab.SETTINGS ->
                SettingsScreen(
                    appInfo = container.appInfo,
                    privilegeHealth = container.privilegeMonitor.health,
                    recentEvents = container.detection.recentEvents,
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
}
