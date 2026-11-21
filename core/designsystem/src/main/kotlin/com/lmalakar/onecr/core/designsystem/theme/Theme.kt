package com.lmalakar.onecr.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = TealPrimary,
    onPrimary = NavyBackground,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealOnContainer,
    secondary = SlateSecondary,
    onSecondary = NavyOnBackground,
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    background = NavyBackground,
    onBackground = NavyOnBackground,
    surface = NavySurface,
    onSurface = NavyOnBackground,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = NavyOnSurfaceVariant,
    error = Color(0xFFF87171),
)

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = TealOnContainer,
    onPrimaryContainer = TealContainer,
    secondary = SlateSecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = SlateSecondaryContainer,
    onSecondaryContainer = SlateOnSecondaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = Color(0xFFE4EAF2),
    onSurfaceVariant = LightOnSurfaceVariant,
)

@Composable
fun OneCRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
