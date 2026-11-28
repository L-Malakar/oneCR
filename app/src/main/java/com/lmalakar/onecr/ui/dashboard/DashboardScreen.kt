package com.lmalakar.onecr.ui.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import com.lmalakar.onecr.AppContainer
import com.lmalakar.onecr.R
import com.lmalakar.onecr.core.designsystem.component.SectionHeader
import com.lmalakar.onecr.core.designsystem.motion.LocalMotionEnabled
import com.lmalakar.onecr.core.designsystem.motion.motionPulseAlpha
import com.lmalakar.onecr.data.files.EventLogEntry
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.service.recorder.RecorderContract
import com.lmalakar.onecr.service.recorder.RecordingEngine
import com.lmalakar.onecr.service.detection.LiveCallState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MilestoneRow(val milestone: String, val title: String, val done: Boolean)

private val MILESTONES = listOf(
    MilestoneRow("M1", "Repo skeleton, build.bat, Compose shell", done = true),
    MilestoneRow("M2", "Privilege bridge (Shizuku) + call detection", done = true),
    MilestoneRow("M2.5", "Embedded ADB bridge — no extra app needed", done = true),
    MilestoneRow("M3", "Audio capture engine (cellular + VoIP)", done = false),
    MilestoneRow("M4", "Library, storage, search, export", done = false),
    MilestoneRow("M5", "On-device AI (whisper.cpp + llama.cpp)", done = false),
)

/**
 * The v0.2.5 dashboard: a gradient command card (brand + live detection state +
 * the start/stop controls in one place), a 2×2 stat grid, a compact recent-
 * activity feed and a roadmap card with a progress bar. Replaces the v0.2.2
 * stack of same-looking status rows — the page now has a visual hierarchy:
 * what is happening (hero), the numbers (grid), what just happened (activity),
 * what is next (roadmap).
 *
 * All colors come from the Material 3 scheme, so light and dark both work.
 */
@Composable
fun DashboardScreen(
    container: AppContainer,
    onOpenNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appInfo = container.appInfo
    val context = LocalContext.current
    val health by container.privilegeMonitor.health.collectAsState()
    val callState by container.detection.callState.collectAsState()
    val capabilities by container.detection.capabilities.collectAsState()
    val recentEvents by container.detection.recentEvents.collectAsState()
    val running by container.detection.running.collectAsState()
    val recorderState by container.recorder.state.collectAsState()
    val recordScope = rememberCoroutineScope()
    val permissionSnapshot by container.permissions.collectAsState()

    fun startDetection() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallDetectionService::class.java)
                    .setAction(DetectionContract.ACTION_START),
            )
        }
    }

    fun stopDetection() {
        runCatching {
            context.startService(
                Intent(context, CallDetectionService::class.java)
                    .setAction(DetectionContract.ACTION_STOP),
            )
        }
    }

    // Same policy as the notification center: never nag about a bridge that was
    // never set up (it is optional until M3) — only about a configured or
    // degraded one.
    val bridgeNeedsAttention = health.state != PrivilegeState.OK && (
        health.state != PrivilegeState.NOT_CONFIGURED ||
            container.settings.privilegeMode != null
        )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- Command card (brand + live state + controls) --------
        CommandCard(
            versionName = appInfo.versionName,
            running = running,
            callState = callState,
            capabilitiesText = if (running) detectionCapabilityText(capabilities) else null,
            onStart = { startDetection() },
            onStop = { stopDetection() },
            onRecheck = { container.privilegeMonitor.refreshAsync() },
            recorderState = recorderState,
            onRecordNow = {
                recordScope.launch {
                    container.recorder.start(RecorderContract.TRIGGER_MANUAL)
                }
            },
            onStopRecording = { container.recorder.stop() },
        )

        // ---------------- At a glance ------------------------------------------
        SectionHeader("At a glance")
        val grantedCount = 5 - permissionSnapshot.missingCount
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Filled.PhoneInTalk,
                value = recentEvents.count {
                    it.kind == EventLogEntry.KIND_CALL || it.kind == EventLogEntry.KIND_VOIP
                }.toString(),
                label = "Calls seen",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Filled.VerifiedUser,
                value = "$grantedCount/5",
                label = "Permissions",
                supporting = if (permissionSnapshot.allGranted) "all granted" else "tap bell to fix",
                accent = permissionSnapshot.allGranted,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                icon = Icons.Filled.List,
                value = recentEvents.size.toString(),
                label = "Events logged",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = Icons.Filled.Security,
                value = when (health.state) {
                    PrivilegeState.OK -> "OK"
                    PrivilegeState.NOT_CONFIGURED -> "Optional"
                    PrivilegeState.NEEDS_PERMISSION -> "Pending"
                    PrivilegeState.PAIRING_LOST -> "Offline"
                    else -> "Check"
                },
                label = "Bridge",
                supporting = if (health.state == PrivilegeState.OK) {
                    "${health.mode.name.lowercase().replaceFirstChar { it.uppercase() }} connected"
                } else {
                    "needed for recording"
                },
                accent = health.state == PrivilegeState.OK,
                modifier = Modifier.weight(1f),
            )
        }

        if (bridgeNeedsAttention) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = health.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onOpenNotifications) { Text("Fix") }
                }
            }
        }

        // v0.2.6: the companion of the "watcher survives app close" fix — if
        // battery optimization is still active, many ROMs pause the 24×7
        // watcher as soon as the app is closed. One tap fixes it.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        if (!batteryExempt) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = "Allow background running to keep the watcher alive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) { Text("Allow") }
                }
            }
        }

        // ---------------- Recent activity ---------------------------------------
        SectionHeader("Recent activity")
        ActivityCard(
            events = recentEvents,
            onOpenNotifications = onOpenNotifications,
        )

        // ---------------- Roadmap -----------------------------------------------
        SectionHeader("Roadmap")
        RoadmapCard()

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Nothing leaves this device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------
// Command card
// ---------------------------------------------------------------------

/**
 * The dashboard's hero: a gradient brand header, the live detection state as
 * the biggest element on the page, and the Start/Stop controls right under it.
 */
@Composable
private fun CommandCard(
    versionName: String,
    running: Boolean,
    callState: LiveCallState,
    capabilitiesText: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRecheck: () -> Unit,
    recorderState: RecordingEngine.UiState,
    onRecordNow: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ),
                )
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- Brand row ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_brand_logo),
                        contentDescription = "oneCR logo",
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "oneCR",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "one call recorder — local-first, private",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    InfoChip("v$versionName")
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // ---- Live state row ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status dot: teal when live (with a gentle breathing pulse,
                    // v0.2.6), muted when off.
                    val motionDot = LocalMotionEnabled.current
                    val dotPulse = motionPulseAlpha(motionDot)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer { alpha = if (running) dotPulse else 1f }
                            .clip(CircleShape)
                            .background(
                                if (running) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            ),
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (running) "Detection live" else "Detection off",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                running -> describeCallState(callState)
                                else -> "Not watching — start below"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusPill(live = running)
                }
                if (capabilitiesText != null) {
                    Text(
                        text = capabilitiesText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---- Controls ----
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (running) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Start")
                        }
                    }
                    OutlinedButton(onClick = onRecheck, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Re-check")
                    }
                }

                // ---- Recording controls (M3, v0.3.6) ----
                val recordingNow = recorderState is RecordingEngine.UiState.Recording
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recordingNow) {
                        OutlinedButton(onClick = onStopRecording, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Stop recording")
                        }
                    } else {
                        Button(onClick = onRecordNow, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Record now")
                        }
                    }
                }
                Text(
                    text = when (val rs = recorderState) {
                        is RecordingEngine.UiState.Recording ->
                            "Recording via ${rs.sourceLabel.lowercase().replace('_', ' ')}"
                        is RecordingEngine.UiState.Finalizing -> "Saving the recording…"
                        is RecordingEngine.UiState.Failed -> "Recorder: ${rs.message}"
                        RecordingEngine.UiState.Idle -> "Records as shell (privileged telephony mix)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Small rounded chip used for the version tag (same style as About). */
@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** LIVE / OFF pill with a colored dot, mirroring the status dot. */
@Composable
private fun StatusPill(live: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (live) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The pill dot breathes in sync with the hero dot when live (v0.2.6).
            val motionPill = LocalMotionEnabled.current
            val pillPulse = motionPulseAlpha(motionPill)
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = if (live) pillPulse else 1f }
                    .clip(CircleShape)
                    .background(
                        if (live) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
            )
            Text(
                text = if (live) "LIVE" else "OFF",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (live) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ---------------------------------------------------------------------
// Stat tiles
// ---------------------------------------------------------------------

/**
 * Compact number tile for the "At a glance" grid (v0.3.5 minimal restyle):
 * a flat tonal surface — no elevation, no shadow — with one icon, one number
 * and one label.
 */
@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    supporting: String? = null,
    accent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (accent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // v0.2.6: when a counter changes, the old number fades up and out
            // while the new one fades in — no hard text swap (instant when
            // Animations is off).
            val motionTile = LocalMotionEnabled.current
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    if (motionTile) {
                        (
                            fadeIn(tween(200)) +
                                slideInVertically(tween(200)) { it / 2 }
                            ) togetherWith fadeOut(tween(150))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "statValue",
            ) { target ->
                Text(
                    text = target,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------

@Composable
private fun ActivityCard(
    events: List<EventLogEntry>,
    onOpenNotifications: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Latest events",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenNotifications) { Text("View all") }
            }
            if (events.isEmpty()) {
                Text(
                    "No calls yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Newest first — the store is oldest-first (v0.2.3 fix).
                events.takeLast(4).asReversed().forEach { entry ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        KindChip(entry.kind)
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = relativeTime(entry.timestampMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/** Colored kind badge for activity rows (same mapping as the Event log screen). */
@Composable
private fun KindChip(kind: String) {
    val label = when (kind) {
        EventLogEntry.KIND_CALL -> "call"
        EventLogEntry.KIND_VOIP -> "voip"
        EventLogEntry.KIND_PRIVILEGE -> "bridge"
        EventLogEntry.KIND_SERVICE -> "service"
        EventLogEntry.KIND_ERROR -> "error"
        else -> kind
    }
    val isError = kind == EventLogEntry.KIND_ERROR
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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

private fun relativeTime(timestampMillis: Long): String {
    if (timestampMillis <= 0) return ""
    val delta = System.currentTimeMillis() - timestampMillis
    return when {
        delta < 60_000 -> "now"
        delta < 3_600_000 -> "${delta / 60_000}m"
        delta < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMillis))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMillis))
    }
}

// ---------------------------------------------------------------------
// Roadmap
// ---------------------------------------------------------------------

@Composable
private fun RoadmapCard() {
    val done = MILESTONES.count { it.done }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Milestones",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "$done of ${MILESTONES.size}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            // v0.2.6: the roadmap bar glides to its value instead of snapping
            // (instant when Animations is off).
            val motionRoadmap = LocalMotionEnabled.current
            val animatedDone by animateFloatAsState(
                targetValue = done / MILESTONES.size.toFloat(),
                animationSpec = tween(durationMillis = if (motionRoadmap) 700 else 0),
                label = "roadmapProgress",
            )
            LinearProgressIndicator(
                progress = { animatedDone },
                modifier = Modifier.fillMaxWidth(),
            )
            MILESTONES.forEach { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (row.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (row.done) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = "${row.milestone} — ${row.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.done) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                text = "Next — M3: call audio capture",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

private fun describeCallState(state: LiveCallState): String = when (state) {
    is LiveCallState.Idle -> "Watching — line idle"
    is LiveCallState.CellularRinging -> "Incoming call…"
    is LiveCallState.CellularActive -> when (state.direction) {
        com.lmalakar.onecr.core.model.CallDirection.INCOMING -> "On a call (incoming)"
        com.lmalakar.onecr.core.model.CallDirection.OUTGOING -> "On a call (outgoing)"
        else -> "On a call"
    }
    is LiveCallState.VoipActive -> "VoIP call — ${state.appLabel ?: state.packageName}"
}

private fun detectionCapabilityText(
    caps: com.lmalakar.onecr.service.detection.DetectionEngine.DetectionCapabilities,
): String = buildString {
    append("Cellular ")
    append(if (caps.cellular) "on" else "off")
    append(" · VoIP ")
    append(if (caps.anyVoip) "on" else "off")
    append(" · recording via shell")
}
