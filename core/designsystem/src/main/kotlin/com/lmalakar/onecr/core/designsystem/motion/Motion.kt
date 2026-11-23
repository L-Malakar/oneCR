package com.lmalakar.onecr.core.designsystem.motion

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * v0.2.6 motion preference — the app-wide master switch for smooth animations.
 *
 * It is provided from MainActivity out of `AppContainer.animationsEnabled`,
 * which mirrors the "Animations" toggle in Settings → Appearance (default ON).
 * Every transition, crossfade and pulse in the app reads this; when it is
 * false the UI swaps instantly and idle animations stand still, exactly the
 * state the user chose instead of a hard-coded style.
 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

/** Standard enter/exit durations so every screen transition feels the same. */
const val MOTION_ENTER_MS = 240
const val MOTION_EXIT_MS = 200

/**
 * Breathing alpha for live status dots (1.0 → 0.55 → 1.0, ~2.6 s round trip).
 * Returns a constant 1f when motion is disabled, so callers can apply it
 * unconditionally.
 */
@Composable
fun motionPulseAlpha(enabled: Boolean): Float {
    if (!enabled) return 1f
    val transition = rememberInfiniteTransition(label = "livePulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "livePulseAlpha",
    )
    return alpha
}
