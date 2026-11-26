package com.lmalakar.onecr.ui.update

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.designsystem.component.SectionHeader
import com.lmalakar.onecr.update.DownloadState
import com.lmalakar.onecr.update.UpdateInfo

/**
 * The v0.3.3 update page — rendered above everything the moment an app open
 * finds a newer GitHub release, and reachable again from the update
 * notification. Shows what changed and walks the user through the install:
 * the Update button requests the "Install unknown apps" grant when needed,
 * then streams the APK with live progress and hands it to the installer
 * automatically.
 *
 * v0.3.4: minimal-word restyle — short labels, icon-led rows, one helper
 * line at a time.
 */
@Composable
fun UpdateScreen(
    info: UpdateInfo,
    currentVersion: String,
    downloadState: DownloadState,
    canInstallApks: Boolean,
    logoRes: Int,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenReleasePage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // System back cancels the page for this session, like the Cancel button.
    BackHandler(onBack = onCancel)

    val downloading = downloadState is DownloadState.Running

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Hero -----------------------------------------------------------
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(logoRes),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Update available",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "v$currentVersion  →  v${info.versionName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (info.publishedAt.isNotEmpty()) {
                            Text(
                                text = info.publishedAt.take(10),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---- What's new -------------------------------------------------------
        SectionHeader("What's new")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = info.releaseNotes.ifBlank { "Release notes are on the GitHub page." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(16.dp),
            )
        }
        TextButton(onClick = onOpenReleasePage) { Text("Open on GitHub") }

        // ---- Download state ---------------------------------------------------
        when (val state = downloadState) {
            is DownloadState.Running -> {
                val pct = state.totalBytes?.let { if (it > 0) state.bytes.toFloat() / it else null }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = if (pct != null) "Downloading… ${(pct * 100).toInt()}%" else "Downloading…",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${state.bytes / 1_000} kB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { pct ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            is DownloadState.Done -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Downloaded — opening the installer…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is DownloadState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Download failed — ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = onRetry) { Text("Retry") }
            }

            DownloadState.Idle -> Unit
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Actions ----------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !downloading,
                modifier = Modifier.weight(1f),
            ) { Text("Cancel") }
            Button(
                onClick = onUpdate,
                enabled = downloadState !is DownloadState.Running,
                modifier = Modifier.weight(1f),
            ) { Text("Update") }
        }

        // One helper line, only while the grant is missing.
        if (!canInstallApks) {
            Text(
                text = "You'll be asked to allow installs from oneCR once.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
