package com.lmalakar.onecr.feature.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lmalakar.onecr.core.designsystem.component.EmptyState

/**
 * M1 placeholder for the recordings library (implemented in M4 on top of the Room
 * schema, Media3 player and FTS search; capture itself lands in M3).
 */
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.PlayArrow,
        title = "No recordings yet",
        description = "Call capture arrives in M3 and the library in M4. Recordings, " +
            "transcripts and summaries will all show up here — stored only on this device.",
        modifier = modifier,
    )
}
