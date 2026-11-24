package com.lmalakar.onecr.feature.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.designsystem.motion.LocalMotionEnabled
import com.lmalakar.onecr.core.designsystem.motion.MOTION_ENTER_MS
import com.lmalakar.onecr.core.designsystem.motion.MOTION_EXIT_MS
import com.lmalakar.onecr.core.model.PermissionSnapshot
import com.lmalakar.onecr.privilege.api.PrivilegeHealth
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val STEP_COUNT = 6

/** Per-OEM survival steps (mirrors docs/COMPATIBILITY.md). */
private data class OemGuidance(val vendors: Set<String>, val steps: List<String>)

/** A pairing service advertised by this phone (_adb-tls-pairing._tcp). */
data class FoundPairingService(val name: String, val host: String, val port: Int)

private val OEM_GUIDANCE = listOf(
    OemGuidance(
        setOf("oneplus", "oppo", "realme"),
        listOf(
            "Settings → About device → tap Build number 7×, then Developer options → enable “Disable permission monitoring”",
            "Battery → oneCR → Don't optimize",
            "App battery usage → oneCR → Allow background activity / Auto-launch",
        ),
    ),
    OemGuidance(
        setOf("xiaomi", "redmi", "poco"),
        listOf(
            "Settings → Apps → oneCR → Autostart: on",
            "Battery → oneCR → No restrictions",
            "MIUI optimization tips in docs/COMPATIBILITY.md if detection drops in background",
        ),
    ),
    OemGuidance(
        setOf("samsung"),
        listOf(
            "Battery → oneCR → Unrestricted",
            "Disable “Put unused apps to sleep” for oneCR",
        ),
    ),
    OemGuidance(
        setOf("vivo", "iqoo"),
        listOf(
            "Battery → Background power consumption → oneCR: allow",
            "Settings → Apps → oneCR → Autostart: on",
        ),
    ),
)

/**
 * One ask in the v0.2.2 one-by-one permission wizard: each button fires exactly
 * one system dialog (or opens one settings page) per tap — nothing is requested
 * silently and nothing is bundled together.
 */
private data class PermissionTask(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val granted: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit,
)

private enum class TaskState { DONE, ACTIVE, PENDING }

/** Icon shown in the wizard's fixed header for the current step (v0.2.4). */
private fun stepIcon(step: Int) = when (step) {
    0 -> Icons.Filled.RocketLaunch
    1 -> Icons.Filled.PhoneAndroid
    2 -> Icons.Filled.Key
    3 -> Icons.Filled.BatteryChargingFull
    4 -> Icons.Filled.VerifiedUser
    else -> Icons.Filled.CheckCircle
}

/**
 * The setup wizard: welcome → device check → privilege bridge →
 * OEM survival steps → permissions (one by one) → done.
 *
 * The bridge step offers the two privilege routes (v0.3.0): the embedded ADB
 * bridge (no extra app, Android 11+ wireless-debugging pairing) and Shizuku.
 *
 * v0.2.4 layout: three-region column — a fixed header (title + progress), a
 * scrollable step area and a **fixed bottom bar** that always sits at the bottom
 * edge of the screen. The screen paints the scheme's own background so it
 * follows the dark theme even before the platform window background catches up.
 *
 * v0.2.6: steps slide and fade as you move through the wizard, and the progress
 * bar animates to its new position (all gated by the Animations switch).
 *
 * v0.3.0: the **Skip** button is shown only when the wizard is RE-RUN from
 * Settings. On a first launch the user must walk through the steps once (they
 * can always leave the app and resume later); this prevents accidentally
 * skipping setup on the very first open.
 */
@Composable
fun OnboardingScreen(
    privilegeHealth: StateFlow<PrivilegeHealth>,
    permissions: StateFlow<PermissionSnapshot>,
    versionName: String,
    isShizukuInstalled: () -> Boolean,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    onRefreshPermissions: () -> Unit,
    onDiscoverPairingServices: suspend () -> List<FoundPairingService>,
    onPairEmbedded: suspend (code: String, host: String, port: Int) -> String?,
    selectedPrivilegeMode: String,
    onSelectPrivilegeMode: (String) -> Unit,
    onComplete: (selectedMode: String, autostartDetection: Boolean) -> Unit,
    onSkip: () -> Unit,
    allowSkip: Boolean,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var autostart by rememberSaveable { mutableStateOf(true) }
    val health by privilegeHealth.collectAsState()
    val permissionSnapshot by permissions.collectAsState()

    // v0.2.5 UX: every step starts at the top of its own content. Without this,
    // the scroll offset carried over from the previous (shorter or longer) step,
    // so Next could land the user mid-text.
    val stepScroll = rememberScrollState()
    LaunchedEffect(step) { stepScroll.scrollTo(0) }

    // System back walks the wizard backwards; at the first step the shell's
    // double-back-to-exit confirmation takes over (v0.2.3).
    BackHandler(enabled = step > 0) { step-- }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- Fixed header: icon + title + progress + Skip -----------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = stepIcon(step),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column {
                    Text(
                        "Set up oneCR",
                        style = MaterialTheme.typography.headlineSmall,
                        // Explicit onBackground: readable in dark and light (v0.2.5).
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Step ${step + 1} of $STEP_COUNT",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v0.3.0: the escape hatch exists only when the wizard is
                // re-run from Settings — first launches walk through setup.
                Spacer(modifier = Modifier.weight(1f))
                if (allowSkip) {
                    TextButton(onClick = onSkip) {
                        Text("Skip")
                    }
                }
            }
            // v0.2.6: the progress bar glides to its new position instead of
            // jumping (instant when Animations is off).
            val motionProgress = LocalMotionEnabled.current
            val animatedProgress by animateFloatAsState(
                targetValue = (step + 1f) / STEP_COUNT,
                animationSpec = tween(durationMillis = if (motionProgress) 320 else 0),
                label = "wizardProgress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Scrollable step content ----------------------------------------
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(stepScroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // v0.2.6: steps slide and fade in the direction of travel; instant
            // when the Animations switch is off.
            val motionSteps = LocalMotionEnabled.current
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (motionSteps) {
                        val dir = if (targetState > initialState) 1 else -1
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
                label = "wizardStep",
            ) { target ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (target) {
                        0 -> WelcomeStep()
                        1 -> DeviceStep()
                        2 -> BridgeStep(
                            health = health,
                            isShizukuInstalled = isShizukuInstalled,
                            onInstallShizuku = onInstallShizuku,
                            onOpenShizuku = onOpenShizuku,
                            onRequestShizukuPermission = onRequestShizukuPermission,
                            onVerifyBridge = onVerifyBridge,
                            selectedPrivilegeMode = selectedPrivilegeMode,
                            onSelectPrivilegeMode = onSelectPrivilegeMode,
                            onDiscoverPairingServices = onDiscoverPairingServices,
                            onPairEmbedded = onPairEmbedded,
                        )
                        3 -> OemStep()
                        4 -> PermissionsStep(permissionSnapshot, onRefreshPermissions)
                        5 -> DoneStep(
                            versionName = versionName,
                            autostart = autostart,
                            onAutostartChange = { autostart = it },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- Fixed bottom bar: Back / Next / Finish never move (v0.2.4) ----
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Back")
                    }
                }
                Button(
                    onClick = {
                        if (step == STEP_COUNT - 1) {
                            onComplete(selectedPrivilegeMode, autostart)
                        } else {
                            step++
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    if (step == STEP_COUNT - 1) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Finish")
                    } else {
                        Text("Next")
                        Spacer(modifier = Modifier.size(6.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    StepScaffold(
        icon = Icons.Filled.RocketLaunch,
        title = "oneCR — one call recorder",
        subtitle = "Local-first, privacy-focused call recording for Android.",
    ) {
        Bullet("Recording, transcription and summaries happen 100% on this phone — no cloud, no account, no telemetry.")
        Bullet("This release sets up call detection and the embedded ADB privilege bridge — no extra app needed. Recording itself arrives in v0.3.x.")
        Bullet("Detection is local. Numbers shown in the app are read from your call log, which never leaves the device.")
    }
}

@Composable
private fun DeviceStep() {
    val sdk = Build.VERSION.SDK_INT
    val fullSupport = sdk >= 30
    StepScaffold(
        icon = Icons.Filled.PhoneAndroid,
        title = "Device check",
        subtitle = "Android ${Build.VERSION.RELEASE} (API $sdk)",
    ) {
        // Install floor is Android 8 (API 26): detection + the Shizuku bridge run
        // everywhere. The embedded-ADB mode (v0.3.0) wants Android 11+.
        if (fullSupport) {
            Bullet(good = true, text = "Android 11+ — detection works, and the embedded ADB bridge (no extra app) is supported on this phone.")
        } else {
            Bullet(
                good = true,
                text = "Android 8–10 — call detection and the Shizuku privilege bridge are fully supported on this phone.",
            )
            Bullet(
                text = "The embedded-ADB mode needs Android 11+. Shizuku mode covers everything this release does.",
            )
        }
        Bullet("Pick the privilege route on the next step — embedded ADB (recommended) or Shizuku.")
    }
}

/**
 * The privilege bridge step (v0.3.0): a mode picker between the embedded ADB
 * bridge and Shizuku, with per-mode guidance — the pairing-code UI for embedded
 * ADB, install/start/grant/verify for Shizuku.
 */
@Composable
private fun BridgeStep(
    health: PrivilegeHealth,
    isShizukuInstalled: () -> Boolean,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    selectedPrivilegeMode: String,
    onSelectPrivilegeMode: (String) -> Unit,
    onDiscoverPairingServices: suspend () -> List<FoundPairingService>,
    onPairEmbedded: suspend (code: String, host: String, port: Int) -> String?,
) {
    val isEmbedded = selectedPrivilegeMode == "EMBEDDED_ADB"
    val installed = isShizukuInstalled()

    StepScaffold(
        icon = Icons.Filled.Key,
        title = "Privilege bridge",
        subtitle = when {
            isEmbedded ->
                if (health.state == PrivilegeState.OK) "Embedded ADB — bridge connected"
                else "Embedded ADB — no extra app (Android 11+)"
            !installed -> "Shizuku is not installed"
            health.state == PrivilegeState.OK -> "Shizuku — bridge connected"
            health.state == PrivilegeState.NEEDS_PERMISSION -> "Shizuku — waiting for permission"
            health.state == PrivilegeState.PAIRING_LOST -> "Shizuku is not running"
            else -> "Set up the shell bridge"
        },
    ) {
        Text(
            "To record the far end of cellular calls, oneCR needs adb-shell privileges. " +
                "Pick the route below — you can switch anytime in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- Mode picker (v0.3.0) ----
        ModeOption(
            title = "Embedded ADB (recommended)",
            detail = "Pairs with this phone's Wireless debugging. No extra app; needs Android 11+.",
            selected = isEmbedded,
            enabled = Build.VERSION.SDK_INT >= 30,
            onClick = { onSelectPrivilegeMode("EMBEDDED_ADB") },
        )
        ModeOption(
            title = "Shizuku",
            detail = "Borrows shell rights from the free, open-source Shizuku app. Works from Android 8.",
            selected = !isEmbedded,
            enabled = true,
            onClick = { onSelectPrivilegeMode("SHIZUKU") },
        )

        if (isEmbedded) {
            EmbeddedPairingSection(
                onDiscoverPairingServices = onDiscoverPairingServices,
                onPairEmbedded = onPairEmbedded,
            )
        } else {
            ShizukuSection(
                health = health,
                installed = installed,
                onInstallShizuku = onInstallShizuku,
                onOpenShizuku = onOpenShizuku,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onVerifyBridge = onVerifyBridge,
            )
        }

        Text(
            "You can skip this now — call detection works without it. The bridge only becomes " +
                "required when capture ships (v0.3.x). You can finish this later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One selectable privilege route in the wizard's mode picker (v0.3.0). */
@Composable
private fun ModeOption(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, enabled = enabled, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    detail + if (!enabled) " — unavailable on this Android version" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The embedded-ADB pairing UI: guidance, code + port fields, search and pair (v0.3.0). */
@Composable
private fun EmbeddedPairingSection(
    onDiscoverPairingServices: suspend () -> List<FoundPairingService>,
    onPairEmbedded: suspend (code: String, host: String, port: Int) -> String?,
) {
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var searchNote by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Bullet("Developer options → Wireless debugging → ON")
        Bullet("Tap “Pair device with pairing code” and keep the dialog open")
        Bullet("The phone and oneCR must be on the same Wi-Fi network")
        if (Build.MANUFACTURER.lowercase().let { it.contains("oppo") || it.contains("oneplus") || it.contains("realme") }) {
            Bullet("OPPO/OnePlus/Realme: also enable “Disable permission monitoring”")
        }

        OutlinedTextField(
            value = code,
            onValueChange = { value -> if (value.length <= 6 && value.all { it.isDigit() }) code = value },
            label = { Text("6-digit pairing code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { value -> if (value.length <= 5 && value.all { it.isDigit() }) port = value },
            label = { Text("Pairing port (from the dialog)") },
            singleLine = true,
            supportingText = { Text(searchNote ?: "Optional — “Search” fills it in via mDNS") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        searching = true
                        searchNote = null
                        val found = runCatching { onDiscoverPairingServices() }.getOrDefault(emptyList())
                        searching = false
                        val candidate = found.firstOrNull()
                        if (candidate != null) {
                            port = candidate.port.toString()
                            searchNote = "Found: ${candidate.name} (port ${candidate.port})"
                        } else {
                            searchNote = "No pairing service found — type the port manually."
                        }
                    }
                },
                enabled = !searching,
            ) { Text(if (searching) "Searching…" else "Search") }

            Button(
                onClick = {
                    scope.launch {
                        pairing = true
                        resultMessage = null
                        val host = "127.0.0.1"
                        val portNumber = port.toIntOrNull() ?: 0
                        val error = runCatching { onPairEmbedded(code, host, portNumber) }
                            .getOrElse { "Pairing failed: ${it.message}" }
                        pairing = false
                        resultMessage = error ?: "PAIRED"
                    }
                },
                enabled = !pairing && code.length == 6 && (port.toIntOrNull() ?: 0) > 0,
            ) { Text(if (pairing) "Pairing…" else "Pair") }
        }

        resultMessage?.let { message ->
            Bullet(
                good = message == "PAIRED",
                text = if (message == "PAIRED") {
                    "Paired! The embedded ADB bridge is ready (reconnects automatically after reboots)."
                } else {
                    message
                },
            )
        }
    }
}

/** The Shizuku guidance: install → start → grant → verify (v0.2.0). */
@Composable
private fun ShizukuSection(
    health: PrivilegeHealth,
    installed: Boolean,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
) {
    val verifyScope = rememberCoroutineScope()
    var verifyResult by remember { mutableStateOf<ShellCommandResult?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Shizuku: a small, open-source, audited app that lends shell rights to apps " +
                "you choose. oneCR's privileged surface is a tiny shell service you can " +
                "audit in the source.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!installed) {
            Bullet("Install Shizuku from Play Store (or shizuku.rikka.app)")
            Button(onClick = onInstallShizuku) { Text("Open Shizuku page") }
        } else {
            when (health.state) {
                PrivilegeState.OK -> {
                    Bullet(good = true, text = "Shell bridge is connected.")
                    if (verifyResult == null) {
                        OutlinedButton(onClick = {
                            verifyScope.launch { verifyResult = onVerifyBridge() }
                        }) { Text("Verify shell identity (runs `id`)") }
                    } else {
                        val result = verifyResult
                        if (result != null && result.ok) {
                            Bullet(
                                good = true,
                                text = "Verified: ${result.combinedOutput.lineSequence().firstOrNull() ?: "shell"}",
                            )
                        } else {
                            Bullet(text = "Verify failed: ${result?.combinedOutput ?: "no result"}")
                        }
                    }
                }

                PrivilegeState.NEEDS_PERMISSION -> {
                    Bullet("Shizuku is running. Grant oneCR permission inside Shizuku.")
                    Button(onClick = onRequestShizukuPermission) { Text("Grant permission") }
                }

                PrivilegeState.PAIRING_LOST -> {
                    Bullet("Open Shizuku and start it (Wireless debugging or connect to PC once per reboot).")
                    Button(onClick = onOpenShizuku) { Text("Open Shizuku") }
                }

                else -> {
                    Bullet("Open Shizuku, start it, then grant oneCR permission.")
                    Button(onClick = onOpenShizuku) { Text("Open Shizuku") }
                }
            }
        }
    }
}

@Composable
private fun OemStep() {
    val vendor = remember { Build.MANUFACTURER.lowercase() }
    val guidance = OEM_GUIDANCE.firstOrNull { entry -> entry.vendors.any { vendor.contains(it) } }

    StepScaffold(
        icon = Icons.Filled.BatteryChargingFull,
        title = "Keep oneCR alive on this phone",
        subtitle = "Detected manufacturer: ${Build.MANUFACTURER}",
    ) {
        if (guidance == null) {
            Bullet("Exclude oneCR from battery optimization (Settings → Apps → oneCR → Battery).")
            Bullet("If your ROM has an auto-start/background manager, allow oneCR there.")
        } else {
            guidance.steps.forEach { Bullet(it) }
        }
        Bullet("These steps stop OEM battery killers from silently pausing detection.")
    }
}

// ---------------------------------------------------------------------
// Permissions — the one-by-one wizard (v0.2.2)
// ---------------------------------------------------------------------

@Composable
private fun PermissionsStep(snapshot: PermissionSnapshot, onRefreshPermissions: () -> Unit) {
    val context = LocalContext.current
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onRefreshPermissions() }

    val tasks = buildList {
        add(
            PermissionTask(
                id = "phone",
                title = "Phone state",
                description = "See when cellular calls start and end so oneCR can follow them.",
                icon = Icons.Filled.Phone,
                granted = snapshot.phoneGranted,
                actionLabel = "Allow phone access",
                onAction = { singleLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
            ),
        )
        add(
            PermissionTask(
                id = "call_log",
                title = "Call log",
                description = "Resolve the number and contact name after a call ends — read locally, never uploaded.",
                icon = Icons.Filled.History,
                granted = snapshot.callLogGranted,
                actionLabel = "Allow call log access",
                onAction = { singleLauncher.launch(Manifest.permission.READ_CALL_LOG) },
            ),
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                PermissionTask(
                    id = "notifications",
                    title = "Notifications",
                    description = "Keep the detection service's low-priority notification visible (Android 13+).",
                    icon = Icons.Filled.Notifications,
                    granted = snapshot.notificationsGranted,
                    actionLabel = "Allow notifications",
                    onAction = { singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                ),
            )
        }
        add(
            PermissionTask(
                id = "listener",
                title = "Notification access",
                description = "Detect ongoing WhatsApp/VoIP calls — reads only the category flag of allowlisted apps, never the text.",
                icon = Icons.Filled.NotificationsActive,
                granted = snapshot.notificationListenerEnabled,
                actionLabel = "Open notification access settings",
                onAction = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                },
            ),
        )
        add(
            PermissionTask(
                id = "battery",
                title = "Battery optimization",
                description = "Exempt oneCR so OEM battery savers don't pause detection mid-call.",
                icon = Icons.Filled.BatteryChargingFull,
                granted = snapshot.batteryExempt,
                actionLabel = "Exempt from battery optimization",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
            ),
        )
    }

    val activeIndex = tasks.indexOfFirst { !it.granted }

    StepScaffold(
        icon = Icons.Filled.VerifiedUser,
        title = "Permissions — one at a time",
        subtitle = if (activeIndex == -1) {
            "All set — every permission granted."
        } else {
            "${tasks.count { it.granted }} of ${tasks.size} granted. Tap the highlighted button, answer it, then the next."
        },
    ) {
        tasks.forEachIndexed { index, task ->
            val state = when {
                task.granted -> TaskState.DONE
                index == activeIndex -> TaskState.ACTIVE
                else -> TaskState.PENDING
            }
            TaskCard(task, state)
        }

        Text(
            "Each button asks for exactly one thing — one system dialog per tap, nothing " +
                "requested silently. Skipping is fine: features degrade, nothing breaks, and " +
                "you can grant later in Settings → Permission center. Notification access reads " +
                "only the category flag of allowlisted VoIP apps — never notification text. See PRIVACY.md.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskCard(task: PermissionTask, state: TaskState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (state == TaskState.PENDING) 0.65f else 1f),
        shape = RoundedCornerShape(12.dp),
        color = if (state == TaskState.ACTIVE) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (state == TaskState.ACTIVE) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (state) {
                    TaskState.DONE -> Icons.Filled.CheckCircle
                    else -> task.icon
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = when (state) {
                    TaskState.DONE -> MaterialTheme.colorScheme.primary
                    TaskState.ACTIVE -> MaterialTheme.colorScheme.onPrimaryContainer
                    TaskState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = task.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    TaskState.DONE -> Text(
                        "Granted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TaskState.ACTIVE -> Button(
                        onClick = task.onAction,
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(task.actionLabel) }
                    TaskState.PENDING -> Text(
                        "Up next",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DoneStep(versionName: String, autostart: Boolean, onAutostartChange: (Boolean) -> Unit) {
    StepScaffold(
        icon = Icons.Filled.CheckCircle,
        title = "Ready",
        subtitle = "oneCR v$versionName — call detection plus the embedded ADB bridge.",
    ) {
        Bullet("Dashboard shows live call detection and the bridge status.")
        Bullet("The bell in the top bar collects anything the app needs, errors and activity in one place.")
        Bullet("Settings → Permission center re-runs the one-by-one grants any time.")
        Bullet("Settings → Event log shows every detection event with timestamps.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Start detection when the app opens", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Runs a low-priority foreground service so detection survives backgrounding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autostart, onCheckedChange = onAutostartChange)
        }
    }
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

@Composable
private fun StepScaffold(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        content()
    }
}

@Composable
private fun Bullet(text: String, good: Boolean? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (good == true) Icons.Filled.CheckCircle else Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 3.dp),
            tint = when (good) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
