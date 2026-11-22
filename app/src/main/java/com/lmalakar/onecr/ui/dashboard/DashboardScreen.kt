package com.lmalakar.onecr.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lmalakar.onecr.AppContainer
import com.lmalakar.onecr.R
import com.lmalakar.onecr.core.designsystem.component.SectionHeader
import com.lmalakar.onecr.core.designsystem.component.StatusCard
import com.lmalakar.onecr.core.designsystem.component.StatusTone
import com.lmalakar.onecr.privilege.api.PrivilegeState
import com.lmalakar.onecr.service.detection.CallDetectionService
import com.lmalakar.onecr.service.detection.DetectionContract
import com.lmalakar.onecr.service.detection.LiveCallState

private data class MilestoneRow(val milestone: String, val title: String, val done: Boolean)

private val MILESTONES = listOf(
    MilestoneRow("M1", "Repo skeleton, build.bat, Compose shell", done = true),
    MilestoneRow("M2", "Privilege bridge (Shizuku) + call detection", done = true),
    MilestoneRow("M3", "Audio capture engine (cellular + VoIP)", done = false),
    MilestoneRow("M4", "Library, storage, search, export", done = false),
    MilestoneRow("M5", "On-device AI (whisper.cpp + llama.cpp)", done = false),
)

/**
 * The v0.2.2 dashboard: branded hero, a live-status section whose Start/Stop
 * buttons reflect the real service state, an activity card that opens the
 * notification center, and the roadmap.
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

    fun startDetection() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallDetectionService::class.java)
                    .setAction(DetectionContract.ACTION_START),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- Hero ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_brand_logo),
                contentDescription = "oneCR logo",
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "oneCR",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "one call recorder — local-first, private",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "v${appInfo.versionName}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        SectionHeader("Live status")

        StatusCard(
            title = "Call detection",
            value = if (running) describeCallState(callState) else "Off",
            supportingText = if (running) {
                detectionCapabilityText(capabilities)
            } else {
                "Not watching for calls — start detection below."
            },
            icon = Icons.Filled.Call,
            badge = if (running) "LIVE" else "OFF",
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (running) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startService(
                                Intent(context, CallDetectionService::class.java)
                                    .setAction(DetectionContract.ACTION_STOP),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Stop detection") }
            } else {
                Button(
                    onClick = { startDetection() },
                    modifier = Modifier.weight(1f),
                ) { Text("Start detection") }
            }
            OutlinedButton(
                onClick = { container.privilegeMonitor.refreshAsync() },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Re-check bridge")
            }
        }

        StatusCard(
            title = "Privilege bridge",
            value = when (health.state) {
                PrivilegeState.OK -> "Connected (Shizuku)"
                PrivilegeState.NEEDS_PERMISSION -> "Needs Shizuku permission"
                PrivilegeState.PAIRING_LOST -> "Shizuku not running"
                PrivilegeState.NOT_CONFIGURED -> "Shizuku not installed"
                else -> health.state.name.lowercase().replace('_', ' ')
            },
            supportingText = health.detail,
            icon = if (health.state == PrivilegeState.OK) Icons.Filled.Lock else Icons.Filled.Warning,
            iconTone = if (health.state == PrivilegeState.OK) StatusTone.PRIMARY else StatusTone.WARNING,
        )

        SectionHeader("Activity")

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent events",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenNotifications) { Text("View all") }
                }
                if (recentEvents.isEmpty()) {
                    Text(
                        "Nothing yet — start detection, then make a call. Full log: Settings → Event log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recentEvents.takeLast(3).forEach { entry ->
                        Text(
                            text = "• ${entry.kind}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionHeader("Roadmap")

        StatusCard(
            title = "Recording engine",
            value = "Next milestone",
            supportingText = "Shell-privilege capture (cellular) and playback-capture mix (VoIP) arrive in M3 — this bridge is what they will use.",
            icon = Icons.Filled.Call,
            badge = "M3",
            iconTone = StatusTone.NEUTRAL,
        )
        StatusCard(
            title = "Library",
            value = "Empty",
            supportingText = "Storage, playback, full-text search and export arrive in M4.",
            icon = Icons.Filled.PlayArrow,
            badge = "M4",
            iconTone = StatusTone.NEUTRAL,
        )
        StatusCard(
            title = "AI models",
            value = "Not downloaded",
            supportingText = "whisper.cpp transcription and llama.cpp summaries arrive in M5, fully on-device.",
            icon = Icons.Filled.Star,
            badge = "M5",
            iconTone = StatusTone.NEUTRAL,
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Milestones",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                MILESTONES.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (row.done) Icons.Filled.CheckCircle else Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (row.done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(modifier = Modifier.size(10.dp))
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
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "v${appInfo.versionName} (${appInfo.buildType}) — detection build. Nothing leaves this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun describeCallState(state: LiveCallState): String = when (state) {
    is LiveCallState.Idle -> "Line idle"
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
    append("Cellular detection ")
    append(if (caps.cellular) "active" else "off (grant Phone permission)")
    append("; VoIP detection ")
    append(if (caps.anyVoip) "active" else "off (enable notification access)")
    append(". Recording arrives in M3.")
}
