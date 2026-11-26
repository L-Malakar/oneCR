package com.lmalakar.onecr.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lmalakar.onecr.core.designsystem.component.EmptyState

/**
 * M1 placeholder for the recordings library (implemented in M4 on top of the Room
 * schema, Media3 player and FTS search; capture itself lands in M3).
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    // Paint the scheme background so the screen follows dark mode end-to-end.
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EmptyState(
            icon = Icons.Filled.PlayArrow,
            title = "No recordings yet",
            description = "Capture arrives in M3 — everything stays on this device.",
            modifier = Modifier.fillMaxSize(),
        )
    }
}
