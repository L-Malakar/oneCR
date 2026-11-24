package com.lmalakar.onecr.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * v0.2.3: every Material 3 color role is mapped explicitly in both modes.
 * Unmapped roles fall back to the Material baseline (purple-tinted greys),
 * which was the source of the mismatched dialogs/menus/switches in dark mode.
 */
private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = TealOnBright,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealOnContainer,
    inversePrimary = TealPrimary,
    secondary = SlateSecondaryDark,
    onSecondary = SlateInkOnSecondaryDark,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    tertiary = AmberBright,
    onTertiary = AmberOnContainerDark,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberOnContainerDark,
    background = NavyBackground,
    onBackground = NavyOnBackground,
    surface = NavySurface,
    onSurface = NavyOnBackground,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = NavyOnSurfaceVariant,
    surfaceDim = NavyBackground,
    surfaceBright = NavyContainerHighest,
    surfaceContainerLowest = NavyContainerLowest,
    surfaceContainerLow = NavyContainerLow,
    surfaceContainer = NavyContainer,
    surfaceContainerHigh = NavyContainerHigh,
    surfaceContainerHighest = NavyContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = ScrimBlack,
    inverseSurface = LightSurface,
    inverseOnSurface = LightOnBackground,
    error = ErrorBrightDark,
    onError = ErrorOnDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorOnContainerDark,
    surfaceTint = TealBright,
)

private val LightColors = lightColorScheme(
    primary = TealDeep,
    onPrimary = PureWhite,
    primaryContainer = TealOnContainer,
    onPrimaryContainer = TealDeep,
    inversePrimary = TealBright,
    secondary = SlateSecondary,
    onSecondary = PureWhite,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = AmberOnContainerLight,
    onTertiary = PureWhite,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberOnContainerLight,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceDim = LightContainer,
    surfaceBright = PureWhite,
    surfaceContainerLowest = PureWhite,
    surfaceContainerLow = LightContainerLow,
    surfaceContainer = LightContainer,
    surfaceContainerHigh = LightContainerHigh,
    surfaceContainerHighest = LightContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = ScrimBlack,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = LightContainerLow,
    error = ErrorDeepLight,
    onError = PureWhite,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorOnContainerLight,
    surfaceTint = TealDeep,
)

/**
 * v0.2.5: the theme now also *provides* a content color for the whole tree.
 *
 * Compose's `LocalContentColor` defaults to plain `Color.Black`, and every
 * `Text`/`Icon` that sits outside a `Surface` (and does not set an explicit
 * color) falls back to that default. In light mode black-on-light looked
 * correct by accident; in dark mode the setup wizard's headline and bullet
 * texts, the Dashboard hero title and the sub-page headlines rendered black
 * on navy — the "black text never turns white" bug reported on v0.2.4.
 * Providing `onBackground` here fixes every such text app-wide in both modes;
 * components that draw their own Surface (cards, buttons, bars) still override
 * it locally with their proper on-colors, exactly as before.
 */
@Composable
fun OneCRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}
