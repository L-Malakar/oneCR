package com.lmalakar.onecr.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.model.AppInfo

private data class SettingsRow(
    val title: String,
    val detail: String,
    val milestone: String?,
)

private val FUTURE_ROWS = listOf(
    SettingsRow("Privilege bridge", "Configure embedded ADB or Shizuku, health checks", "M2"),
    SettingsRow("Models", "whisper.cpp + llama.cpp download, sideload, delete", "M5"),
    SettingsRow("Storage and export", "Retention policy, export location, storage stats", "M4"),
    SettingsRow("Privacy", "What leaves this device: nothing. See PRIVACY.md.", null),
)

/**
 * M1 settings shell. Interactive rows activate as their milestones land.
 */
@Composable
fun SettingsScreen(
    appInfo: AppInfo,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
        )

        FUTURE_ROWS.forEach { row ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = row.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = row.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (row.milestone != null) {
                        Text(
                            text = "Arrives in ${row.milestone}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "About", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "oneCR v${appInfo.versionName} (build ${appInfo.versionCode}, ${appInfo.buildType})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Project home: github.com/L-Malakar/oneCR",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/L-Malakar/oneCR")),
                            )
                        }
                    },
                )
                Text(
                    text = "License: MIT. Recording laws vary by region — use lawfully and " +
                        "inform call participants.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
