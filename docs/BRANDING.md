# Branding

App name: **oneCR** · version: **0.1.0** · package: `com.lmalakar.onecr`

## Logo / launcher icon

Android does not consume SVG directly. The launcher uses an adaptive icon with three layers:

```
app/src/main/res/drawable/ic_launcher_background.xml    solid background vector
app/src/main/res/drawable/ic_launcher_foreground.xml    main glyph vector (108dp viewport)
app/src/main/res/drawable/ic_launcher_monochrome.xml    white silhouette for themed icons (Android 13+)
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml      adaptive icon wiring
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

M1 ships a **placeholder glyph** (microphone silhouette on a dark navy field, teal accent).

### Swapping in the real logo.svg

1. Drop `logo.svg` into this folder (`docs/`) or attach it in chat.
2. Either:
   - **Automated (preferred):** hand the file to the assistant — it converts the SVG paths into `ic_launcher_foreground.xml` / `ic_launcher_monochrome.xml` and adjusts the background color.
   - **Manual:** in Android Studio: right-click `app` → New → Image Asset → Launcher Icon (Adaptive and Legacy) → load `logo.svg` for the foreground layer → Next → Finish. Then delete the placeholder `ic_launcher_foreground.xml` conflict if Studio writes to `mipmap-*/`.
3. Re-run `build.bat` and check the icon on the launcher and in Settings → About.

Keep the important content inside the **66 × 66 dp safe zone** (centered in the 108 × 108 viewport); the outer ring may be cropped into various mask shapes.

## Color language

The placeholder palette (define/replace in `core/designsystem` → `Color.kt`):

| Token | Value | Use |
|---|---|---|
| Background (dark) | `#0B1220` | dark scheme background |
| Primary | `#14B8A6` (teal) | primary actions, active tab |
| Secondary | `#334155` (slate) | surfaces, chips |
| Accent / alert | `#F59E0B` (amber) | warnings, degraded states |

Brand colors are intentionally dark-first (a recording app is used in-call, at night); the light scheme is provided but secondary.
