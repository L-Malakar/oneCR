package com.lmalakar.onecr.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.AppContainer
import com.lmalakar.onecr.R
import com.lmalakar.onecr.core.designsystem.motion.LocalMotionEnabled
import com.lmalakar.onecr.core.designsystem.motion.MOTION_ENTER_MS
import com.lmalakar.onecr.core.designsystem.motion.MOTION_EXIT_MS
import com.lmalakar.onecr.core.designsystem.theme.AmberAccent
import com.lmalakar.onecr.core.designsystem.theme.BadgeInk
import com.lmalakar.onecr.feature.library.LibraryScreen
import com.lmalakar.onecr.feature.onboarding.FoundPairingService
import com.lmalakar.onecr.feature.onboarding.OnboardingScreen
import com.lmalakar.onecr.feature.settings.AboutScreen
import com.lmalakar.onecr.feature.settings.SettingsScreen
import com.lmalakar.onecr.privilege.api.PrivilegeAction
import com.lmalakar.onecr.privilege.api.PrivilegeMode
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.ui.dashboard.DashboardScreen
import com.lmalakar.onecr.ui.notifications.NotificationCenterScreen
import com.lmalakar.onecr.ui.update.UpdateScreen
import com.lmalakar.onecr.feature.settings.UpdateCheckUi
import com.lmalakar.onecr.update.ManualCheck

/** Model imports kept off the hot path; icons come from material-icons-core. */
private enum class TopLevelTab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Filled.Home),
    LIBRARY("Library", Icons.Filled.PlayArrow),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Second back press within this window confirms the exit (v0.2.3). */
private const val EXIT_CONFIRM_WINDOW_MS = 2_200L

/** Walk up the context wrappers to the hosting Activity (for a controlled exit). */
private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

/**
 * v0.2.3 shell: branded top app bar (logo + tab + version) with a notification
 * bell on the top-right; the bell opens the notification center — one place for
 * everything the app needs, its errors and its activity log.
 *
 * v0.2.6: the logo + name block in the top bar is a home button — tapping it
 * always returns to the Dashboard, from wherever the user is. Tabs and the
 * notification center animate (slide + fade) when the Animations setting is
 * on (default), and swap instantly when it is off.
 *
 * v0.3.1 (restoring the v0.2.6 shell on top of the v0.3.0 bridge): the wizard
 * carries a privilege-mode route (embedded ADB / Shizuku) and shows the Skip
 * button ONLY when it is re-run from Settings — first launches walk through
 * setup once, as requested.
 *
 * Back navigation (v0.2.3): system back now walks the user back through the
 * screens they visited instead of killing the app. Sub-screens (notification
 * center, settings pages, onboarding steps) intercept back first via their own
 * BackHandlers; the shell then pops the visited-tab history, and only when no
 * history is left does it ask for a second press within a short window before
 * finishing the activity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneCRApp(container: AppContainer) {
    val context = LocalContext.current
    var showOnboarding by rememberSaveable {
        mutableStateOf(!container.settings.onboardingCompleted)
    }
    // True only when the wizard was opened again from Settings (or the
    // notification center's repair action). First launch never offers Skip —
    // the user must walk through setup once; re-runs do.
    var onboardingIsRerun by rememberSaveable { mutableStateOf(false) }
    // Privilege route chosen inside the wizard (embedded ADB on Android 11+,
    // Shizuku otherwise as the initial suggestion).
    var wizardMode by rememberSaveable {
        mutableStateOf(
            container.settings.privilegeMode
                ?: if (android.os.Build.VERSION.SDK_INT >= 30) {
                    PrivilegeMode.EMBEDDED_ADB.name
                } else {
                    PrivilegeMode.SHIZUKU.name
                },
        )
    }
    var showNotificationCenter by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val notificationModel by container.notificationCenter.model.collectAsState()

    // Update state is collected once at the shell level: the update page reads
    // it at the bottom of the composition, and the About page's manual
    // "Check for updates" row reads it here (v0.3.5).
    val updateInfo by container.updateCoordinator.available.collectAsState()
    val updateManualCheck by container.updateCoordinator.manualCheck.collectAsState()

    // Visited-tab history (most recent last, never contains the current tab).
    var tabHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedTabName by rememberSaveable { mutableStateOf(TopLevelTab.DASHBOARD.name) }
    // valueOf is guarded so an older persisted name can never crash a new build.
    val currentTab = runCatching { TopLevelTab.valueOf(selectedTabName) }
        .getOrDefault(TopLevelTab.DASHBOARD)
    var lastBackAt by remember { mutableLongStateOf(0L) }

    fun openTab(tab: TopLevelTab) {
        if (tab.name == selectedTabName) return
        tabHistory = (tabHistory + selectedTabName).distinct()
        selectedTabName = tab.name
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

    // Shell-level back handling — sub-screens register their own BackHandler
    // later in the composition, so they intercept first when composed.
    BackHandler {
        when {
            showAbout -> showAbout = false

            showNotificationCenter -> showNotificationCenter = false

            tabHistory.isNotEmpty() -> {
                selectedTabName = tabHistory.last()
                tabHistory = tabHistory.dropLast(1)
            }

            currentTab != TopLevelTab.DASHBOARD -> selectedTabName = TopLevelTab.DASHBOARD.name

            else -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < EXIT_CONFIRM_WINDOW_MS) {
                    context.findHostActivity()?.finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
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
            onDiscoverPairingServices = {
                container.embeddedAdbProvider.discoverPairingServices().map {
                    FoundPairingService(name = it.name, host = it.host, port = it.port)
                }
            },
            onPairEmbedded = { code, host, port ->
                container.embeddedAdbProvider.pairWithDevice(code, host, port)
            },
            selectedPrivilegeMode = wizardMode,
            onSelectPrivilegeMode = { modeName ->
                wizardMode = modeName
                // Switch the live monitor too, so the health readouts inside
                // the wizard already describe the route being configured.
                container.setPrivilegeMode(modeName)
            },
            onComplete = { selectedMode, autostart ->
                container.setPrivilegeMode(selectedMode)
                container.settings.onboardingCompleted = true
                container.settings.detectionAutostart = autostart
                container.refreshPermissions()
                showOnboarding = false
                if (autostart) startDetectionService()
            },
            // v0.3.1: the Skip escape hatch exists only on a re-run of the
            // wizard — leave everything as it was configured before.
            onSkip = {
                container.settings.onboardingCompleted = true
                showOnboarding = false
            },
            allowSkip = onboardingIsRerun,
        )
    } else {

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
                    // v0.2.6: logo + name is a home button — tap to go to the
                    // Dashboard from any tab. A clipped ripple keeps the
                    // touch target honest without changing the bar's look.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { openTab(TopLevelTab.DASHBOARD) },
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
                    // v0.3.4: the About page — an ⓘ beside the bell, its own
                    // standalone page like the notification center.
                    IconButton(onClick = { showAbout = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "About",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The bell: unread badge counts needs + errors + unseen activity.
                    IconButton(onClick = { showNotificationCenter = true }) {
                        BadgedBox(
                            badge = {
                                if (badgeCount > 0) {
                                    Badge(containerColor = AmberAccent) {
                                        Text(
                                            text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                                            color = BadgeInk,
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
                        onClick = { openTab(tab) },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(text = tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        // v0.2.6: tabs slide + fade in the direction of travel when Animations
        // is on; with the switch off they swap instantly.
        val motion = LocalMotionEnabled.current
        AnimatedContent(
            targetState = currentTab,
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
            label = "tabSwitch",
        ) { tab ->
            when (tab) {
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
                        privilegeHealth = container.privilegeMonitor.health,
                        recentEvents = container.detection.recentEvents,
                        permissions = container.permissions,
                        onRefreshPermissions = container::refreshPermissions,
                        themeMode = container.themeMode.value,
                        onThemeChange = container::setThemeMode,
                        animationsEnabled = container.animationsEnabled.value,
                        onAnimationsChange = container::setAnimationsEnabled,
                        onGrantShizukuPermission = container.shizukuProvider::requestPermission,
                        onOpenShizuku = {
                            val launch = container.shizukuProvider.shizukuLaunchIntent()
                            if (launch != null) runCatching { context.startActivity(launch) }
                        },
                        onInstallShizuku = {
                            runCatching { context.startActivity(container.shizukuProvider.shizukuInstallIntent()) }
                        },
                        onVerifyBridge = { container.privilegeProvider.runShellCommand("id") },
                        selectedPrivilegeMode = container.privilegeProvider.mode.name,
                        onSelectPrivilegeMode = { modeName -> container.setPrivilegeMode(modeName) },
                        onClearEventLog = container.detection::clearLog,
                        onRerunOnboarding = {
                            onboardingIsRerun = true
                            showOnboarding = true
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
            }
        }
    }

    // The notification center slides in from the right edge and slides back
    // out on close (v0.2.6, honors the Animations switch).
    val motionNow = LocalMotionEnabled.current
    AnimatedVisibility(
        visible = showNotificationCenter,
        enter = if (motionNow) {
            slideInHorizontally(tween(MOTION_ENTER_MS)) { it } + fadeIn(tween(MOTION_ENTER_MS))
        } else {
            EnterTransition.None
        },
        exit = if (motionNow) {
            slideOutHorizontally(tween(MOTION_EXIT_MS)) { it } + fadeOut(tween(MOTION_EXIT_MS))
        } else {
            ExitTransition.None
        },
    ) {
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
                        // Repairing is a wizard re-run: Skip stays available.
                        onboardingIsRerun = true
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

    // The About page (v0.3.4) slides in from the right like the notification
    // center — a standalone page behind the ⓘ next to the bell.
    val motionAbout = LocalMotionEnabled.current
    AnimatedVisibility(
        visible = showAbout,
        enter = if (motionAbout) {
            slideInHorizontally(tween(MOTION_ENTER_MS)) { it } + fadeIn(tween(MOTION_ENTER_MS))
        } else {
            EnterTransition.None
        },
        exit = if (motionAbout) {
            slideOutHorizontally(tween(MOTION_EXIT_MS)) { it } + fadeOut(tween(MOTION_EXIT_MS))
        } else {
            ExitTransition.None
        },
    ) {
        AboutScreen(
            appInfo = container.appInfo,
            logoRes = R.drawable.ic_brand_logo,
            onRerunOnboarding = {
                showAbout = false
                onboardingIsRerun = true
                showOnboarding = true
            },
            onBack = { showAbout = false },
            updateAvailableVersion = updateInfo?.versionName,
            updateCheck = when (val mc = updateManualCheck) {
                ManualCheck.Checking -> UpdateCheckUi(checking = true)
                ManualCheck.UpToDate -> UpdateCheckUi(upToDate = true)
                is ManualCheck.Failed -> UpdateCheckUi(failed = true)
                ManualCheck.Idle -> UpdateCheckUi()
            },
            onCheckForUpdates = { container.updateCoordinator.checkNow() },
            onOpenUpdatePage = { container.updateCoordinator.showPageIfAvailable() },
            modifier = Modifier.fillMaxSize(),
        )
    }
    } // not-onboarding branch

    // The update page (v0.3.3) renders above EVERYTHING — onboarding, tabs,
    // the notification center, About. It is composed LAST on purpose:
    // Compose draws later siblings on top, and an update outranks whatever
    // screen is showing — including the wizard.
    val updatePageVisible by container.updateCoordinator.pageVisible.collectAsState()
    val updateDownload by container.updateCoordinator.download.collectAsState()
    val motionUpdate = LocalMotionEnabled.current
    updateInfo?.let { info ->
        AnimatedVisibility(
            visible = updatePageVisible,
            enter = if (motionUpdate) {
                slideInHorizontally(tween(MOTION_ENTER_MS)) { it } + fadeIn(tween(MOTION_ENTER_MS))
            } else {
                EnterTransition.None
            },
            exit = if (motionUpdate) {
                slideOutHorizontally(tween(MOTION_EXIT_MS)) { it } + fadeOut(tween(MOTION_EXIT_MS))
            } else {
                ExitTransition.None
            },
        ) {
            UpdateScreen(
                info = info,
                currentVersion = container.appInfo.versionName,
                downloadState = updateDownload,
                canInstallApks = container.updateCoordinator.canInstallApks(),
                logoRes = R.drawable.ic_brand_logo,
                onUpdate = { container.updateCoordinator.onUpdatePressed() },
                onCancel = { container.updateCoordinator.dismissForSession() },
                onRetry = { container.updateCoordinator.retryDownload() },
                onOpenReleasePage = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(info.releaseUrl),
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
