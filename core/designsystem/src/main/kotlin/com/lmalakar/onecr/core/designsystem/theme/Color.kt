package com.lmalakar.onecr.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Brand palette — see docs/BRANDING.md. Dark-first: a recorder is used
// in-call, at night.
//
// v0.2.3 rework: the scheme is now complete. v0.2.1–0.2.2 only mapped a
// handful of Material 3 roles, so every unmapped role (tertiary, outline,
// errorContainer, surfaceContainer*, …) silently fell back to the Material
// baseline purple-grey — which is why dialogs, switches, chips and menus
// clashed with the navy background in dark mode. The dark palette also had
// `secondary` mapped to a near-black slate (0xFF334155), i.e. dark-on-dark.
// ---------------------------------------------------------------------------

// ---- Dark (navy) ----------------------------------------------------------

/** App background in dark mode — deep desaturated navy. */
val NavyBackground = Color(0xFF0B1220)

/** Base card/sheet surface in dark mode. */
val NavySurface = Color(0xFF101A2C)

/** Filled inputs, chips, secondary cards in dark mode. */
val NavySurfaceVariant = Color(0xFF1A2740)

/** Primary text in dark mode — soft off-white. */
val NavyOnBackground = Color(0xFFE6EDF7)

/** Secondary text in dark mode — raised for AA contrast on navy. */
val NavyOnSurfaceVariant = Color(0xFFA6B3CB)

/** Elevated container tones (dialogs, menus, the top bar's dropdown). */
val NavyContainerLowest = Color(0xFF0A0F1A)
val NavyContainerLow = Color(0xFF0E1727)
val NavyContainer = Color(0xFF131D30)
val NavyContainerHigh = Color(0xFF1C2841)
val NavyContainerHighest = Color(0xFF26344F)

// ---- Dark accents ----------------------------------------------------------

/** Brand teal tuned for dark backgrounds (teal-300 range). */
val TealBright = Color(0xFF2DD4BF)

/** Ink for text/icons sitting on TealBright. */
val TealOnBright = Color(0xFF04211C)

/** Amber warning family — themed replacement for the hardcoded 0xFFF59E0B. */
val AmberBright = Color(0xFFFBBF24)
val AmberContainerDark = Color(0xFF3D2D06)
val AmberOnContainerDark = Color(0xFFFBBF24)
val AmberContainerLight = Color(0xFFFFE3AE)
val AmberOnContainerLight = Color(0xFF6E4A00)

/** Muted red family that does not fight the navy cast. */
val ErrorBrightDark = Color(0xFFF87171)
val ErrorOnDark = Color(0xFF2C0B0E)
val ErrorContainerDark = Color(0xFF4A1A1E)
val ErrorOnContainerDark = Color(0xFFFFB3AB)

// ---- Legacy aliases (kept for existing references) -------------------------

val TealPrimary = Color(0xFF14B8A6)
val TealContainer = Color(0xFF0E3A36)
val TealOnContainer = Color(0xFF9AF0E5)

val SlateSecondary = Color(0xFF334155)
val SlateOnSecondaryContainer = Color(0xFFD7E3F4)
val SlateSecondaryContainer = Color(0xFF233047)

/** Light slate used as the dark-theme `secondary` — the v0.2.2 value was
 *  near-black and invisible on navy wherever onSecondary sat on it. */
val SlateSecondaryDark = Color(0xFFB6C2D9)

val AmberAccent = Color(0xFFF59E0B)

// ---- Light -----------------------------------------------------------------

val LightBackground = Color(0xFFF6F8FB)
val LightSurface = Color(0xFFFFFFFF)
val LightOnBackground = Color(0xFF16202E)
val LightOnSurfaceVariant = Color(0xFF56637A)

/** Darker teal for light mode — white on 0xFF14B8A6 had ~2.2:1 contrast. */
val TealDeep = Color(0xFF0F766E)

val LightContainerLow = Color(0xFFF7F9FC)
val LightContainer = Color(0xFFF0F3F8)
val LightContainerHigh = Color(0xFFE9EDF4)
val LightContainerHighest = Color(0xFFE2E7EF)

val LightSecondaryContainer = Color(0xFFD8E2F1)
val LightOnSecondaryContainer = Color(0xFF233047)

val LightOutline = Color(0xFF6B7A94)
val LightOutlineVariant = Color(0xFFD5DCE8)
val DarkOutline = Color(0xFF5B6B87)
val DarkOutlineVariant = Color(0xFF28374F)

// ---- Shared ----------------------------------------------------------------

/** Badge text ink — the amber badge uses dark text in both themes. */
val BadgeInk = Color(0xFF14181F)

/** Ink for text/icons sitting on the light-slate dark-theme secondary. */
val SlateInkOnSecondaryDark = Color(0xFF1B2740)

val LightSurfaceVariant = Color(0xFFE4EAF2)
val InverseSurfaceLight = Color(0xFF2A3548)

val ScrimBlack = Color(0xFF000000)
val PureWhite = Color(0xFFFFFFFF)

// Standard M3 light error family (consistent with the platform).
val ErrorDeepLight = Color(0xFFB3261E)
val ErrorContainerLight = Color(0xFFF9DEDC)
val ErrorOnContainerLight = Color(0xFF410E0B)
