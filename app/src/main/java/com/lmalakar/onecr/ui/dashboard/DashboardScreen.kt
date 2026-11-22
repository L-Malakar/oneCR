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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.lmalakar.onecr.core.designsystem.component.StatusCard
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

@Composable
fun DashboardScreen(
    container: AppContainer,
    modifier: Modifier = Modifier,
) {
    val appInfo = container.appInfo
    val context = LocalContext.current
    val health by container.privilegeMonitor.health.collectAsState()
    val callState by container.detection.callState.collectAsState()
    val capabilities by container.detection.capabilities.collectAsState()
    val recentEvents by container.detection.recentEvents.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_brand_logo),
                contentDescription = "oneCR logo",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(28.dp)),
            )
            Spacer(modifier = Modifier.size(14.dp))
            Column {
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
        }
        Text(
            text = "v${appInfo.versionName} (${appInfo.buildType}) — detection build. " +
                "Nothing leaves this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        // ---------------- Live call detection ----------------
        StatusCard(
            title = "Call detection (live)",
            value = describeCallState(callState),
            supportingText = detectionCapabilityText(capabilities),
            icon = Icons.Filled.Call,
            badge = "M2",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, CallDetectionService::class.java)
                            .setAction(DetectionContract.ACTION_START),
                    )
                }
            }) { Text("Start detection") }
            OutlinedButton(onClick = {
                runCatching {
                    context.startService(
                        Intent(context, CallDetectionService::class.java)
                            .setAction(DetectionContract.ACTION_STOP),
                    )
                }
            }) { Text("Stop") }
        }

        // ---------------- Privilege bridge ----------------
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
            badge = "M2",
        )

        // ---------------- Recent events ----------------
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Recent events",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (recentEvents.isEmpty()) {
                    Text(
                        "Nothing yet — start detection, then make a call. Full log: Settings → Event log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    recentEvents.takeLast(5).forEach { entry ->
                        Text(
                            text = "• ${entry.kind}: ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---------------- Future milestones ----------------
        StatusCard(
            title = "Recording engine",
            value = "Next milestone",
            supportingText = "Shell-privilege capture (cellular) and playback-capture mix (VoIP) arrive in M3 — this bridge is what they will use.",
            icon = Icons.Filled.Call,
            badge = "M3",
        )
        StatusCard(
            title = "Library",
            value = "Empty",
            supportingText = "Storage, playback, full-text search and export arrive in M4.",
            icon = Icons.Filled.PlayArrow,
            badge = "M4",
        )
        StatusCard(
            title = "AI models",
            value = "Not downloaded",
            supportingText = "whisper.cpp transcription and llama.cpp summaries arrive in M5, fully on-device.",
            icon = Icons.Filled.Star,
            badge = "M5",
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
                    text = "Roadmap",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                MILESTONES.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (row.done) Icons.Filled.CheckCircle else Icons.Filled.Warning,
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

        OutlinedButton(onClick = { container.privilegeMonitor.refreshAsync() }) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text("Re-check bridge")
        }
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
