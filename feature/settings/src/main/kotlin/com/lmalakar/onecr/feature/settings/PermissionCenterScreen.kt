package com.lmalakar.onecr.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.privilege.api.PrivilegeHealth
import com.lmalakar.onecr.privilege.api.PrivilegeState

private data class PermissionRow(
    val title: String,
    val rationale: String,
    val granted: Boolean,
    val milestoneNote: String? = null,
)

/**
 * The M2 permission center: every permission oneCR will ever ask for, with its
 * live status and a request action. Permissions that arrive later (M3+) are listed
 * as "coming with Mx" so users always know the full story.
 */
@Composable
fun PermissionCenterScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh++ }

    val rows = permissionRows(context, refresh)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
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

        rows.forEach { row ->
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
                            imageVector = if (row.granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (row.granted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        row.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.milestoneNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Button(onClick = {
            runtimeLauncher.launch(
                buildList {
                    add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.READ_CALL_LOG)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.toTypedArray(),
            )
        }) { Text("Grant phone, call log and notifications") }

        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            refresh++
        }) { Text("Notification access (VoIP detection)") }

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

        Spacer(modifier = Modifier.size(8.dp))
        Text(
            "Coming with M3+: RECORD_AUDIO (capture), MediaProjection consent (VoIP far end), " +
                "READ_CONTACTS (optional contact names, M4). See PRIVACY.md for the full table.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun permissionRows(context: Context, refresh: Int): List<PermissionRow> = listOf(
    PermissionRow(
        title = "Phone state",
        rationale = "Know when cellular calls start and end so capture can follow them.",
        granted = context.isGranted(Manifest.permission.READ_PHONE_STATE),
    ),
    PermissionRow(
        title = "Call log",
        rationale = "Resolve the number and contact name of calls after they end. Read locally, never uploaded.",
        granted = context.isGranted(Manifest.permission.READ_CALL_LOG),
    ),
    PermissionRow(
        title = "Notifications",
        rationale = "Keep the detection service's low-priority notification visible (Android 13+).",
        granted = Build.VERSION.SDK_INT < 33 ||
            context.isGranted(Manifest.permission.POST_NOTIFICATIONS),
    ),
    PermissionRow(
        title = "Notification access",
        rationale = "Detect ongoing WhatsApp/VoIP calls — reads only the category flag of allowlisted apps.",
        granted = notificationListenerEnabled(context),
    ),
    PermissionRow(
        title = "Battery optimization exemption",
        rationale = "Stop OEM battery savers from pausing detection mid-call.",
        granted = batteryIgnored(context),
    ),
    PermissionRow(
        title = "Microphone",
        rationale = "Near-end capture for VoIP recording and the mic fallback path.",
        granted = false,
        milestoneNote = "Requested with capture (M3)",
    ),
)

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun notificationListenerEnabled(context: Context): Boolean =
    runCatching {
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.any { it.startsWith(context.packageName) }
    }.getOrDefault(false) == true

private fun batteryIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
