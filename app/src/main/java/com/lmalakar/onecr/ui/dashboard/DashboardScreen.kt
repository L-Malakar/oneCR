package com.lmalakar.onecr.ui.dashboard

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.designsystem.component.StatusCard
import com.lmalakar.onecr.core.model.AppInfo

private data class MilestoneRow(val milestone: String, val title: String, val done: Boolean)

private val MILESTONES = listOf(
    MilestoneRow("M1", "Repo skeleton, build.bat, Compose shell", done = true),
    MilestoneRow("M2", "Privilege bridge (ADB/Shizuku) + call detection", done = false),
    MilestoneRow("M3", "Audio capture engine (cellular + VoIP)", done = false),
    MilestoneRow("M4", "Library, storage, search, export", done = false),
    MilestoneRow("M5", "On-device AI (whisper.cpp + llama.cpp)", done = false),
)

@Composable
fun DashboardScreen(
    appInfo: AppInfo,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "oneCR",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "v${appInfo.versionName} (${appInfo.buildType}) — local-first call recorder. " +
                "Nothing leaves this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))

        StatusCard(
            title = "Recording engine",
            value = "Not wired yet",
            supportingText = "Shell-privilege capture (cellular) and playback-capture mix (VoIP) arrive in M3.",
            icon = Icons.Filled.Call,
            badge = "M3",
        )
        StatusCard(
            title = "Privilege bridge",
            value = "Not configured",
            supportingText = "Embedded ADB / Shizuku setup and health checks arrive in M2.",
            icon = Icons.Filled.Warning,
            badge = "M2",
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
    }
}
