# Branding

App name: **oneCR** · package: `com.lmalakar.onecr`

The version lives in exactly one place — `gradle.properties` (`VERSION_NAME`,
consumed by `:app/build.gradle.kts` and `build.bat`) — so this file no longer
pins one.

## The logo

The official logo (introduced in v0.1.1) is a red gradient circle carrying a
green telephone handset on the left, a large white capital **R** on the right
and the white wordmark **ONE** at the bottom. The red is a linear gradient that
runs from a darker tone at the top-right to a brighter tone at the bottom-left;
the letters cast a soft drop shadow.

Master assets (all derived from the same traced vector paths):

| File | Purpose |
|---|---|
| `docs/branding/logo.svg` | Master vector logo with drop shadow (README, web, GitHub) |
| `docs/branding/logo-flat.svg` | Flat variant without the shadow (print, engraving) |
| `docs/branding/logo-512.png`, `logo-1024.png` | Raster exports (store listings, social) |

## Android icon layers

Android does not consume SVG directly. The launcher icon is an adaptive icon
built from the logo:

```
app/src/main/res/drawable/ic_launcher_background.xml    full-bleed brand-red gradient (vector)
app/src/main/res/drawable/ic_launcher_foreground.xml    handset + R + ONE, scaled into the safe zone
app/src/main/res/drawable/ic_launcher_monochrome.xml    single-tint silhouette (Android 13+ themed icons)
app/src/main/res/drawable/ic_brand_logo.xml             full logo used in-app (Dashboard header)
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml      adaptive icon wiring
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

Geometry: the logo circle maps to the 72 dp adaptive-icon mask area, which
places all content inside the 66 dp safe zone (content spans roughly
x 29–79, y 37–80 in the 108 dp viewport). No re-trimming is needed.

### Replacing or evolving the logo

1. Edit (or replace) `docs/branding/logo.svg`.
2. Convert the element paths into the four drawables above, keeping the
   72/999 scale about the circle center, or use Android Studio:
   right-click `app` → New → Image Asset → Launcher Icons (Adaptive and Legacy).
3. Re-run `build.bat` and check the launcher, Settings → Apps, and the
   Dashboard header.

## Color language

Brand palette (from the logo):

| Token | Value | Use |
|---|---|---|
| Brand red (dark end) | `#A0181A` | gradient start (top-right) |
| Brand red (bright end) | `#F50619` | gradient end (bottom-left) |
| Brand green | `#20C11D` | handset, success/active states |
| White | `#FFFFFF` | wordmark, glyph layer |

The in-app Material 3 theme stays dark-first (a recording app is used in-call,
at night). Since v0.2.3 the scheme maps **every** Material 3 role, so dialogs,
menus, switches and text fields match the navy palette instead of Material's
baseline purple-grey. Key tokens:

| Role (dark) | Value | Role (light) | Value |
|---|---|---|---|
| background / surface | `#0B1220` / `#101A2C` | background / surface | `#F6F8FB` / `#FFFFFF` |
| primary | `#2DD4BF` (bright teal) | primary | `#0F766E` (deep teal, AA on white) |
| primaryContainer / on | `#0E3A36` / `#9AF0E5` | primaryContainer / on | `#9AF0E5` / `#0F766E` |
| secondary | `#B6C2D9` (light slate) | secondary | `#334155` (slate) |
| secondaryContainer / on | `#233047` / `#D7E3F4` | secondaryContainer / on | `#D8E2F1` / `#233047` |
| tertiaryContainer / on (amber) | `#3D2D06` / `#FBBF24` | tertiaryContainer / on | `#FFE3AE` / `#6E4A00` |
| outline / outlineVariant | `#5B6B87` / `#28374F` | outline / outlineVariant | `#6B7A94` / `#D5DCE8` |
| surfaceContainer (low → high) | `#131D30` … `#1C2841` | surfaceContainer (low → high) | `#F0F3F8` … `#E9EDF4` |
| error | `#F87171` on `#4A1A1E` | error | `#B3261E` on `#F9DEDC` |

Support tokens: `#F59E0B` (AmberAccent, the top-bar badge), `#14181F`
(BadgeInk — text on the amber badge), window background `#0B1220` (night) /
`#F6F8FB` (light) via `onecr_window_background`. Brand red/green are reserved
for branding surfaces (logo, icons, headers) and recording-state accents so
the functional palette stays calm; the light scheme is provided but secondary.
The full token list lives in
`core/designsystem/.../theme/Color.kt` — add new colors there, never inline
hex values in screens.
