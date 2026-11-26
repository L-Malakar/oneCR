package com.lmalakar.onecr.feature.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lmalakar.onecr.core.model.AppInfo

private const val PROJECT_URL = "https://github.com/L-Malakar/oneCR"
private const val DEVELOPER_URL = "https://github.com/L-Malakar"
private const val BUGS_URL = "https://github.com/L-Malakar/oneCR/issues"
private const val PRIVACY_URL = "https://github.com/L-Malakar/oneCR/blob/main/PRIVACY.md"
private const val LICENSE_URL = "https://github.com/L-Malakar/oneCR/blob/main/LICENSE"
private const val RELEASES_URL = "https://github.com/L-Malakar/oneCR/releases"

/**
 * Plain state for the About page's manual update check (v0.3.5). Kept in the
 * feature module so :app can map its richer coordinator state onto it without
 * a reverse dependency.
 */
data class UpdateCheckUi(
    val checking: Boolean = false,
    val upToDate: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The About page (v0.3.4) — cut out of the Settings list and promoted to a
 * standalone page behind the ⓘ icon next to the notification bell, with the
 * same slide-in feel as the notification center. Same facts as before
 * (brand header, version chips, links), but restyled minimal: every row is
 * one icon, one title and one short line — no paragraphs.
 *
 * v0.3.5: a "Check for updates" row runs a real GitHub check on demand —
 * a newer release opens the full update page, "latest" and offline states
 * answer right in the row. Row shapes unified with the rest of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    appInfo: AppInfo,
    logoRes: Int,
    onRerunOnboarding: () -> Unit,
    onBack: () -> Unit,
    updateAvailableVersion: String? = null,
    updateCheck: UpdateCheckUi = UpdateCheckUi(),
    onCheckForUpdates: () -> Unit = {},
    onOpenUpdatePage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("About")
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- Brand header ----------------------------------------------
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
                            contentDescription = "oneCR logo",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(18.dp)),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                text = "oneCR",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Local-first call recorder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                InfoChip("v${appInfo.versionName}")
                                InfoChip("build ${appInfo.versionCode}")
                            }
                        }
                    }
                }
            }

            // ---- Update check (v0.3.5) --------------------------------------
            when {
                updateAvailableVersion != null -> AboutRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Update available",
                    detail = "oneCR $updateAvailableVersion is ready to install",
                    emphasized = true,
                    onClick = onOpenUpdatePage,
                )

                updateCheck.checking -> AboutRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Check for updates",
                    detail = "Talking to GitHub…",
                    busy = true,
                    onClick = {},
                )

                updateCheck.upToDate -> AboutRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Check for updates",
                    detail = "You're on the latest version",
                    onClick = onCheckForUpdates,
                )

                updateCheck.failed -> AboutRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Check for updates",
                    detail = "Couldn't reach GitHub — tap to retry",
                    onClick = onCheckForUpdates,
                )

                else -> AboutRow(
                    icon = Icons.Filled.SystemUpdate,
                    title = "Check for updates",
                    detail = "Check GitHub for a newer oneCR",
                    onClick = onCheckForUpdates,
                )
            }

            // ---- Links (one icon, one title, one short line) -----------------
            AboutRow(
                icon = Icons.Filled.Code,
                title = "Project home",
                detail = "Source, releases, issues",
                onClick = { openUrl(context, PROJECT_URL) },
            )
            AboutRow(
                icon = Icons.Filled.Person,
                title = "Developer",
                detail = "Lalmohan Malakar",
                onClick = { openUrl(context, DEVELOPER_URL) },
            )
            AboutRow(
                icon = Icons.Filled.BugReport,
                title = "Report a bug",
                detail = "GitHub issue tracker",
                onClick = { openUrl(context, BUGS_URL) },
            )
            AboutRow(
                icon = Icons.Filled.PrivacyTip,
                title = "Privacy",
                detail = "No cloud, no account, no telemetry",
                onClick = { openUrl(context, PRIVACY_URL) },
            )
            AboutRow(
                icon = Icons.Filled.Gavel,
                title = "License",
                detail = "MIT — know your local call-recording laws",
                onClick = { openUrl(context, LICENSE_URL) },
            )
            AboutRow(
                icon = Icons.Filled.Info,
                title = "Build",
                detail = "v${appInfo.versionName} · ${appInfo.buildType}",
                onClick = { openUrl(context, RELEASES_URL) },
            )

            OutlinedButton(
                onClick = onRerunOnboarding,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("Re-run setup")
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Opens an external page in the browser; a no-op if nothing can handle it. */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

@Composable
private fun AboutRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    busy: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (emphasized) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
