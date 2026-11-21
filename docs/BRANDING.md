# Branding

App name: **oneCR** · version: **0.1.1** · package: `com.lmalakar.onecr`

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
at night): background `#0B1220`, primary `#14B8A6` (teal), secondary slate
`#334155`, amber `#F59E0B` for warnings. Brand red/green are reserved for
branding surfaces (logo, icons, headers) and recording-state accents so the
functional palette stays calm; the light scheme is provided but secondary.
