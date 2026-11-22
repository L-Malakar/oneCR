package com.lmalakar.onecr.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.privilege.api.PrivilegeHealth
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.privilege.api.ShellCommandResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val STEP_COUNT = 6

/** Per-OEM survival steps (mirrors docs/COMPATIBILITY.md). */
private data class OemGuidance(val vendors: Set<String>, val steps: List<String>)

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
 * The v0.2.0 setup wizard: welcome → device check → privilege bridge (Shizuku) →
 * OEM survival steps → permissions → done.
 *
 * The bridge step is honestly optional: capture needs it (M3), detection does not.
 */
@Composable
fun OnboardingScreen(
    privilegeHealth: StateFlow<PrivilegeHealth>,
    isShizukuInstalled: () -> Boolean,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
    onComplete: (autostartDetection: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var autostart by rememberSaveable { mutableStateOf(true) }
    val health by privilegeHealth.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Set up oneCR", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Step ${step + 1} of $STEP_COUNT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearProgressIndicator(
            progress = { (step + 1f) / STEP_COUNT },
            modifier = Modifier.fillMaxWidth(),
        )

        when (step) {
            0 -> WelcomeStep()
            1 -> DeviceStep()
            2 -> BridgeStep(
                health = health,
                isShizukuInstalled = isShizukuInstalled,
                onInstallShizuku = onInstallShizuku,
                onOpenShizuku = onOpenShizuku,
                onRequestShizukuPermission = onRequestShizukuPermission,
                onVerifyBridge = onVerifyBridge,
            )
            3 -> OemStep()
            4 -> PermissionsStep()
            5 -> DoneStep(autostart = autostart, onAutostartChange = { autostart = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (step > 0) {
                OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                    Text("Back")
                }
            }
            Button(
                onClick = {
                    if (step == STEP_COUNT - 1) onComplete(autostart) else step++
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (step == STEP_COUNT - 1) "Finish" else "Next")
                Spacer(modifier = Modifier.size(6.dp))
                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
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
        icon = Icons.Filled.Phone,
        title = "oneCR — one call recorder",
        subtitle = "Local-first, privacy-focused call recording for Android.",
    ) {
        Bullet("Recording, transcription and summaries happen 100% on this phone — no cloud, no account, no telemetry.")
        Bullet("This release sets up the privilege bridge and detects calls. Recording itself arrives in v0.3.x.")
        Bullet("Detection is local. Numbers shown in the app are read from your call log, which never leaves the device.")
    }
}

@Composable
private fun DeviceStep() {
    val context = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    val fullSupport = sdk >= 30
    StepScaffold(
        icon = Icons.Filled.Settings,
        title = "Device check",
        subtitle = "Android ${Build.VERSION.RELEASE} (API $sdk)",
    ) {
        // Install floor is Android 8 (API 26): detection + the Shizuku bridge run
        // everywhere. Only the embedded-ADB mode (v0.3.0) wants Android 11+.
        if (fullSupport) {
            Bullet(good = true, text = "Android 11+ — detection and the Shizuku bridge are supported, and the embedded-ADB bridge (v0.3.0) will work too.")
        } else {
            Bullet(
                good = true,
                text = "Android 8–10 — call detection and the Shizuku privilege bridge are fully supported on this phone.",
            )
            Bullet(
                text = "The embedded-ADB mode arriving in v0.3.0 needs Android 11+. Shizuku mode covers everything this release does.",
            )
        }
        Bullet("Shizuku mode (this release) needs the free Shizuku app; embedded ADB mode arrives in v0.3.0.")
    }
}

@Composable
private fun BridgeStep(
    health: PrivilegeHealth,
    isShizukuInstalled: () -> Boolean,
    onInstallShizuku: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onVerifyBridge: suspend () -> ShellCommandResult,
) {
    val installed = isShizukuInstalled()
    val verifyScope = rememberCoroutineScope()
    var verifyResult by remember { mutableStateOf<ShellCommandResult?>(null) }

    StepScaffold(
        icon = Icons.Filled.Lock,
        title = "Privilege bridge (Shizuku)",
        subtitle = when {
            !installed -> "Shizuku is not installed"
            health.state == PrivilegeState.OK -> "Bridge connected"
            health.state == PrivilegeState.NEEDS_PERMISSION -> "Waiting for permission"
            health.state == PrivilegeState.PAIRING_LOST -> "Shizuku is not running"
            else -> "Set up the shell bridge"
        },
    ) {
        Text(
            "To record the far end of cellular calls, oneCR needs adb-shell privileges. " +
                "The cleanest route today is Shizuku: a small, open-source, audited app that " +
                "lends shell rights to apps you choose. oneCR's privileged surface is a tiny " +
                "shell service you can audit in the source.",
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

        Text(
            "You can skip this now — call detection works without it. The bridge only becomes " +
                "required when capture ships (v0.3.x). You can finish this later in Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OemStep() {
    val context = LocalContext.current
    val vendor = remember { Build.MANUFACTURER.lowercase() }
    val guidance = OEM_GUIDANCE.firstOrNull { entry -> entry.vendors.any { vendor.contains(it) } }

    StepScaffold(
        icon = Icons.Filled.Settings,
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

@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val runtimePermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    val phoneOk = context.isGranted(Manifest.permission.READ_PHONE_STATE, refresh)
    val callLogOk = context.isGranted(Manifest.permission.READ_CALL_LOG, refresh)
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        context.isGranted(Manifest.permission.POST_NOTIFICATIONS, refresh)
    val listenerOk = notificationListenerEnabled(context, refresh)
    val batteryOk = batteryIgnored(context, refresh)

    StepScaffold(
        icon = Icons.Filled.Phone,
        title = "Permissions",
        subtitle = "Everything is optional — features degrade, nothing breaks.",
    ) {
        Bullet(good = phoneOk, text = "Phone state (READ_PHONE_STATE) — cellular call detection.")
        Bullet(good = callLogOk, text = "Call log (READ_CALL_LOG) — resolve numbers and contact names after calls.")
        Bullet(good = notifOk, text = "Notifications (POST_NOTIFICATIONS) — keep the detection service visible.")
        Bullet(good = listenerOk, text = "Notification access — VoIP call detection (WhatsApp etc.).")
        Bullet(good = batteryOk, text = "Battery optimization exemption — survive OEM battery savers.")

        Button(onClick = { launcher.launch(runtimePermissions.toTypedArray()) }) {
            Text("Grant phone, call log and notification permissions")
        }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            refresh++
        }) { Text("Notification access settings") }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            refresh++
        }) { Text("Exempt from battery optimization") }

        Text(
            "Notification access reads only the category flag of allowlisted VoIP apps — never notification text. See PRIVACY.md.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneStep(autostart: Boolean, onAutostartChange: (Boolean) -> Unit) {
    StepScaffold(
        icon = Icons.Filled.CheckCircle,
        title = "Ready",
        subtitle = "oneCR v0.2.0 — detection is on the menu, capture is next.",
    ) {
        Bullet("Dashboard shows live call detection and the bridge status.")
        Bullet("Settings → Permission center re-runs any step you skipped.")
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
            imageVector = if (good == true) Icons.Filled.CheckCircle else Icons.Filled.ArrowForward,
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

private fun Context.isGranted(permission: String, refreshKey: Int): Boolean =
    ContextCompat.checkSelfPermission(this, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun notificationListenerEnabled(context: Context, refreshKey: Int): Boolean =
    runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { it.startsWith(context.packageName) }
    }.getOrDefault(false) == true

private fun batteryIgnored(context: Context, refreshKey: Int): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
