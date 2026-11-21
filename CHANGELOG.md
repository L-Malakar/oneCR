# Changelog

All notable changes to oneCR are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versioning
is `MAJOR.MINOR.PATCH` from `gradle.properties` (`VERSION_NAME`).

## [0.1.1] — 2026-11-21

### Added
- Official brand logo, fully vectorized from the original design: green handset,
  white "R" and "ONE" wordmark on the brand-red gradient circle
  (`docs/branding/logo.svg`, flat variant and PNG exports included).
- Real adaptive launcher icon (`ic_launcher_background/foreground/monochrome`)
  generated from the logo, with a themed-icon (Android 13+) monochrome layer.
- In-app branding: the full logo now heads the Dashboard screen.
- "Installing a prebuilt APK" guide in the README (Android 11+ requirement,
  unknown-sources, signature-mismatch and corrupt-download troubleshooting).
- This changelog.

### Changed
- Release builds now run R8 code shrinking + resource shrinking:
  the APK shrank from ~18 MB to ~1.5 MB (also far more robust to sideload
  transfer corruption).
- Version bumped to 0.1.1 (versionCode 2).

## [0.1.0] — 2026-11-21

### Added
- Milestone 1 skeleton: 17 Gradle modules (app, core×3, feature×3, privilege×3,
  service×2, data×2, ai×3) with M2–M5 interface stubs.
- Compose UI shell: Dashboard / Library / Settings navigation, Material 3
  dark-first theme.
- `build.bat` one-command Windows build: environment checks, first-run local
  keystore generation, release APK published as `oneCR\Android\oneCR-v<version>.apk`.
- Repository hygiene: MIT LICENSE, SECURITY.md, PRIVACY.md, CONTRIBUTING.md,
  GitHub CI (build + release workflow), dependabot, issue templates, CODEOWNERS.
- docs: ARCHITECTURE.md, ROADMAP.md, COMPATIBILITY.md, BRANDING.md.
