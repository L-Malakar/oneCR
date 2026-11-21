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
import com.lmalakar.onecr.core.model.AppInfo
import com.lmalakar.onecr.feature.library.LibraryScreen
import com.lmalakar.onecr.feature.settings.SettingsScreen
import com.lmalakar.onecr.ui.dashboard.DashboardScreen

/** Model imports kept off the hot path; icons come from material-icons-core. */
private enum class TopLevelTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    LIBRARY("Library", Icons.Filled.PlayArrow),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun OneCRApp(appInfo: AppInfo) {
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
                DashboardScreen(appInfo = appInfo, modifier = Modifier.padding(innerPadding))
            TopLevelTab.LIBRARY ->
                LibraryScreen(modifier = Modifier.padding(innerPadding))
            TopLevelTab.SETTINGS ->
                SettingsScreen(appInfo = appInfo, modifier = Modifier.padding(innerPadding))
        }
    }
}
