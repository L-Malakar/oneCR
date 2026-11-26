package com.lmalakar.onecr.ui.update

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.R
import com.lmalakar.onecr.update.DownloadState
import com.lmalakar.onecr.update.UpdateInfo
import com.lmalakar.onecr.update.UpdateManager
import com.lmalakar.onecr.update.UpdateNotes
import com.lmalakar.onecr.update.UpdateState

/**
 * The v0.3.3 update page — a full-screen dialog rendered above everything
 * (it is a separate window, so it draws over the dashboard, the wizard and
 * the notification center alike).
 *
 * Layout: brand gradient header ("Update available" + the new version big +
 * current → new), the release notes in a scroll, then the flow state —
 * Update / Cancel buttons, the "Install unknown apps" step, live download
 * progress, verification and a Retry on failure.
 */
@Composable
fun UpdatePage(manager: UpdateManager) {
    val updateState by manager.updateState.collectAsState()
    val downloadState by manager.downloadState.collectAsState()
    val context = LocalContext.current

    val info = (updateState as? UpdateState.Available)?.info ?: return

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { manager.onDismissClicked() },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ---- Gradient header -------------------------------------
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_brand_logo),
                                contentDescription = "oneCR logo",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Update available",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "oneCR v${info.versionName} · ${formatSize(info.apkSizeBytes)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Installed: your current build → v${info.versionName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ---- Release notes ---------------------------------------
                Text(
                    "What's new in v${info.versionName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                    ) {
                        Text(
                            text = UpdateNotes.forPage(info.releaseNotes),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Default,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (info.releaseNotes.isBlank()) {
                            Text(
                                "No release notes were published with this version.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Full notes on GitHub →",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.releaseUrl)),
                                    )
                                }
                            },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // ---- Flow state: buttons / permission / progress ---------
                when (val dl = downloadState) {
                    DownloadState.NotStarted -> {
                        Text(
                            "The APK downloads from this GitHub release only — " +
                                "verified against the published SHA-256 before install. " +
                                "Nothing else leaves your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ActionRow(
                            primary = "Update",
                            onPrimary = { manager.requestUpdateFlow() },
                            secondary = "Cancel",
                            onSecondary = { manager.onDismissClicked() },
                        )
                    }

                    DownloadState.NeedsPermission -> {
                        PermissionStep(onOpenSettings = { manager.openPermissionSettings() })
                        ActionRow(
                            primary = "Allow and continue",
                            onPrimary = { manager.openPermissionSettings() },
                            secondary = "Cancel",
                            onSecondary = { manager.cancelDownload(); manager.onDismissClicked() },
                        )
                    }

                    is DownloadState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Downloading oneCR v${info.versionName}… ${dl.percent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            LinearProgressIndicator(
                                progress = { dl.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${formatSize(dl.receivedBytes)} of ${formatSize(dl.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ActionRow(
                            primary = null,
                            onPrimary = {},
                            secondary = "Cancel download",
                            onSecondary = { manager.cancelDownload() },
                        )
                    }

                    DownloadState.Verifying -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                "Verifying SHA-256 checksum…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    is DownloadState.Ready -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "Download verified — opening the installer…",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        ActionRow(
                            primary = "Install now",
                            onPrimary = {
                                manager.installNow()?.let { intent ->
                                    runCatching { context.startActivity(intent) }
                                }
                            },
                            secondary = "Close",
                            onSecondary = { manager.closePage() },
                        )
                    }

                    is DownloadState.Failed -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "Update failed: ${dl.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        ActionRow(
                            primary = "Retry",
                            onPrimary = { manager.startDownload() },
                            secondary = "Cancel",
                            onSecondary = { manager.cancelDownload(); manager.onDismissClicked() },
                        )
                    }
                }

                TextButton(
                    onClick = { manager.onDismissClicked() },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        "Remind me later (this version snoozes for 24 h)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    primary: String?,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onSecondary, modifier = Modifier.weight(1f)) {
            Text(secondary)
        }
        if (primary != null) {
            Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
                Icon(
                    Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(primary)
            }
        }
    }
}

@Composable
private fun PermissionStep(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Allow oneCR to install apps",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "Android needs the per-app \"Install unknown apps\" permission before " +
                    "the downloaded update can run. You will land on the system screen — " +
                    "toggle \"Allow from this source\", then come back; the download " +
                    "continues on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onOpenSettings) {
                Text("Open the permission screen")
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    bytes > 0 -> "$bytes B"
    else -> "unknown size"
}
