package com.lmalakar.onecr.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private data class SetupStep(
    val order: Int,
    val title: String,
    val detail: String,
    val milestone: String,
)

private val SETUP_STEPS = listOf(
    SetupStep(1, "Choose privilege mode", "Embedded ADB (recommended) or Shizuku", "M2"),
    SetupStep(2, "Pair Wireless Debugging", "One-time pairing, done entirely on this phone", "M2"),
    SetupStep(3, "Grant permissions", "Phone state, notifications, battery optimization", "M2"),
    SetupStep(4, "Verify capture", "Probe which audio sources this device supports", "M3"),
)

/**
 * M1 placeholder for the onboarding wizard (implemented in M2 with the privilege
 * bridge). Shows the exact setup path so the UX is reviewable before it exists.
 */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Set up oneCR",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "The interactive setup wizard arrives with M2. It will walk you through:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SETUP_STEPS.forEach { step ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${step.order}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = step.milestone,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Everything stays on this device. No account, no cloud, no telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
