package com.lmalakar.onecr.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.lmalakar.onecr.core.model.PermissionSnapshot
import kotlinx.coroutines.flow.StateFlow

private data class GuidedTask(
    val id: String,
    val title: String,
    val rationale: String,
    val icon: ImageVector,
    val granted: Boolean,
    val actionLabel: String,
)

private enum class Mode { LIST, GUIDED }

/**
 * The permission center. v0.2.2 adds the **guided setup**: tap "Grant one by
 * one" and each tap of the Continue button fires exactly one system dialog —
 * phone → call log → notifications → notification access → battery — with the
 * rationale in front of every ask. Nothing is requested silently or bundled.
 */
@Composable
fun PermissionCenterScreen(
    permissions: StateFlow<PermissionSnapshot>,
    onRefreshPermissions: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot by permissions.collectAsState()
    var mode by rememberSaveable { mutableStateOf(Mode.LIST) }
    var skipCount by remember { mutableIntStateOf(0) }

    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onRefreshPermissions() }

    fun tasksFor(s: PermissionSnapshot): List<Pair<GuidedTask, () -> Unit>> = buildList {
        add(
            GuidedTask(
                id = "phone", title = "Phone state",
                rationale = "Know when cellular calls start and end so capture can follow them.",
                icon = Icons.Filled.Phone,
                granted = s.phoneGranted,
                actionLabel = "Allow phone access",
            ) to { singleLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
        )
        add(
            GuidedTask(
                id = "call_log", title = "Call log",
                rationale = "Resolve the number and contact name of calls after they end. Read locally, never uploaded.",
                icon = Icons.Filled.Phone,
                granted = s.callLogGranted,
                actionLabel = "Allow call log access",
            ) to { singleLauncher.launch(Manifest.permission.READ_CALL_LOG) },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                GuidedTask(
                    id = "notifications", title = "Notifications",
                    rationale = "Keep the detection service's low-priority notification visible (Android 13+).",
                    icon = Icons.Filled.Notifications,
                    granted = s.notificationsGranted,
                    actionLabel = "Allow notifications",
                ) to { singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        add(
            GuidedTask(
                id = "listener", title = "Notification access",
                rationale = "Detect ongoing WhatsApp/VoIP calls — reads only the category flag of allowlisted apps.",
                icon = Icons.Filled.Notifications,
                granted = s.notificationListenerEnabled,
                actionLabel = "Open notification access settings",
            ) to {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            },
        )
        add(
            GuidedTask(
                id = "battery", title = "Battery optimization exemption",
                rationale = "Stop OEM battery savers from pausing detection mid-call.",
                icon = Icons.Filled.Settings,
                granted = s.batteryExempt,
                actionLabel = "Exempt from battery optimization",
            ) to {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
        )
    }

    val tasks = tasksFor(snapshot)
    val pending = tasks.filter { !it.first.granted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Permission center", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Everything is optional — features degrade, nothing breaks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (mode) {
            Mode.LIST -> {
                rows(tasks)

                if (pending.isNotEmpty()) {
                    Button(
                        onClick = {
                            skipCount = 0
                            mode = Mode.GUIDED
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant one by one (guided setup)")
                    }
                    Text(
                        "The guided setup asks for one permission per tap — each system " +
                            "dialog appears only when you press Continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Mode.GUIDED -> guided(
                tasks = tasks,
                pending = pending,
                skipCount = skipCount,
                onSkip = { skipCount++ },
                onExit = { mode = Mode.LIST },
                onAction = { taskAction ->
                    taskAction()
                    onRefreshPermissions()
                },
            )
        }

        Spacer(modifier = Modifier.size(8.dp))
        Text(
            "Coming with M3+: RECORD_AUDIO (capture), MediaProjection consent (VoIP far end), " +
                "READ_CONTACTS (optional contact names, M4). See PRIVACY.md for the full table.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------
// Guided flow — one tap, one ask
// ---------------------------------------------------------------------

@Composable
private fun guided(
    tasks: List<Pair<GuidedTask, () -> Unit>>,
    pending: List<Pair<GuidedTask, () -> Unit>>,
    skipCount: Int,
    onSkip: () -> Unit,
    onExit: () -> Unit,
    onAction: (() -> Unit) -> Unit,
) {
    if (pending.isEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "All set — every permission is granted.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    "Detection can see cellular and VoIP calls and survives battery savers. " +
                        "New asks (microphone for capture) will appear here in M3.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                OutlinedButton(onClick = onExit) { Text("Back to overview") }
            }
        }
        return
    }

    val index = skipCount.coerceAtMost(pending.lastIndex)
    val (task, action) = pending[index]

    Text(
        "Guided setup — step ${index + 1} of ${pending.size}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    LinearProgressIndicator(
        progress = { (index + 1f) / pending.size },
        modifier = Modifier.fillMaxWidth(),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(task.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                task.rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Pressing “${task.actionLabel}” opens exactly one Android dialog (or one settings page). " +
                    "What you answer is up to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(onClick = { onAction(action) }, modifier = Modifier.fillMaxWidth()) {
                Text(task.actionLabel)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onSkip, enabled = index < pending.lastIndex, modifier = Modifier.weight(1f)) {
            Text("Skip this one")
        }
        OutlinedButton(onClick = onExit, modifier = Modifier.weight(1f)) {
            Text("Exit guided setup")
        }
    }

    Text(
        "Remaining after this one: " + pending.drop(index + 1).joinToString { it.first.title }.ifEmpty { "none" },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------------
// Status list
// ---------------------------------------------------------------------

@Composable
private fun rows(tasks: List<Pair<GuidedTask, () -> Unit>>) {
    tasks.forEach { (task, action) ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (task.granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (task.granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    task.rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!task.granted) {
                    OutlinedButton(onClick = { action() }) { Text(task.actionLabel) }
                }
            }
        }
    }
}
