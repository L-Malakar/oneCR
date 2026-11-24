package com.lmalakar.onecr.ui.notifications

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.designsystem.component.EmptyState
import com.lmalakar.onecr.core.designsystem.component.SectionHeader
import com.lmalakar.onecr.notify.NotificationItem
import com.lmalakar.onecr.notify.NotificationModel
import com.lmalakar.onecr.notify.NotificationSeverity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The v0.2.2 notification center — one place for everything the app needs
 * (permissions, bridge, detection), every error, and the recent activity log.
 * Opened from the bell icon in the top bar.
 *
 * v0.2.4: the hand-rolled header row became a real Material 3 top app bar.
 * The old row drew its title in the ambient content color, which defaults to
 * plain black outside a Surface — i.e. the title stayed black on the navy
 * background in dark mode. The top app bar paints with the scheme's surface
 * and onSurface colors, so the title, back arrow and status line follow the
 * theme in both modes, and the panel finally has a proper top bar with a
 * divider under it (no bottom bar, as before).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    model: NotificationModel,
    onRefreshPermissions: () -> Unit,
    onStartDetection: () -> Unit,
    onBridgeAction: () -> Unit,
    onMarkAllSeen: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // System back closes the center instead of leaving the app (v0.2.3).
    BackHandler { onBack() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onRefreshPermissions() }

    fun perform(item: NotificationItem) {
        when (item.id) {
            "detection.off" -> onStartDetection()
            "perm.phone" -> permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            "perm.call_log" -> permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            "perm.notifications" ->
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            "perm.listener" -> runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            "perm.battery" -> runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            "bridge.state" -> onBridgeAction()
        }
        onRefreshPermissions()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ---- Top bar (v0.2.4): themed title + back + "Mark read" ------------
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text("Notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            text = when {
                                model.needs.isNotEmpty() && model.errors.isNotEmpty() ->
                                    "${model.needs.size} need attention · ${model.errors.size} error(s)"
                                model.needs.isNotEmpty() -> "${model.needs.size} thing(s) need attention"
                                model.errors.isNotEmpty() -> "${model.errors.size} error(s)"
                                else -> "All clear"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                TextButton(onClick = onMarkAllSeen, enabled = model.badgeCount > 0) {
                    Text("Mark read")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (model.isEmpty) {
            EmptyState(
                icon = Icons.Filled.Notifications,
                title = "Nothing to show",
                description = "When oneCR needs something — a permission, a restart, " +
                    "or when an error happens — it shows up here.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (model.needs.isNotEmpty()) {
                    item(key = "header-needs") { SectionHeader("Needs attention") }
                    model.needs.forEach { item ->
                        item(key = item.id) {
                            ActionCard(item = item, onAction = { perform(item) })
                        }
                    }
                }
                if (model.errors.isNotEmpty()) {
                    item(key = "header-errors") { SectionHeader("Errors") }
                    model.errors.forEach { entry ->
                        item(key = entry.id) {
                            ActionCard(item = entry, onAction = null)
                        }
                    }
                }
                if (model.logs.isNotEmpty()) {
                    item(key = "header-logs") { SectionHeader("Recent activity") }
                    model.logs.forEach { entry ->
                        item(key = entry.id + entry.timestampMillis) {
                            LogRow(entry)
                        }
                    }
                }
                item(key = "footer") {
                    Text(
                        "Everything here is generated on this device. The full event log " +
                            "lives in Settings → Event log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------

@Composable
private fun ActionCard(item: NotificationItem, onAction: (() -> Unit)?) {
    val isError = item.severity == NotificationSeverity.ERROR
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Icon(
                    imageVector = iconFor(item),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(20.dp),
                    tint = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = item.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (item.actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier.padding(top = 2.dp),
                    ) { Text(item.actionLabel) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: NotificationItem) {
    val now = System.currentTimeMillis()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KindChip(entry.kind ?: "log")
        Column {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = relativeTime(entry.timestampMillis, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KindChip(kind: String) {
    val label = when (kind) {
        "call" -> "call"
        "voip" -> "voip"
        "privilege" -> "bridge"
        "service" -> "service"
        "error" -> "error"
        else -> kind
    }
    val isError = kind == "error"
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            label,
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .width(44.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

private fun iconFor(item: NotificationItem): ImageVector = when {
    item.severity == NotificationSeverity.ERROR -> Icons.Filled.Warning
    item.id == "detection.off" -> Icons.Filled.PlayArrow
    item.id == "perm.phone" || item.id == "perm.call_log" -> Icons.Filled.Phone
    item.id == "perm.notifications" || item.id == "perm.listener" -> Icons.Filled.Notifications
    item.id == "perm.battery" -> Icons.Filled.Settings
    item.id == "bridge.state" -> Icons.Filled.Lock
    else -> Icons.Filled.Warning
}

private fun relativeTime(timestampMillis: Long, now: Long): String {
    if (timestampMillis <= 0) return ""
    val delta = now - timestampMillis
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000} min ago"
        delta < 86_400_000 ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))
        else ->
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMillis))
    }
}
