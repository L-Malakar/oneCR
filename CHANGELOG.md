# Changelog

All notable changes to oneCR are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versioning
is `MAJOR.MINOR.PATCH` from `gradle.properties` (`VERSION_NAME`).

## [0.2.1] — 2026-11-22

Polish release: **theme setting + low-end device support.** The app now follows
the phone's dark-mode setting by default and can be pinned to Light or Dark from
Settings. Low-end devices get fewer idle wakeups and a smaller resource table,
and the docs/onboarding now state plainly that oneCR installs and detects on
Android 8+ (only the v0.3.0 embedded-ADB bridge will need 11+).

### Added
- **Theme setting** (`core/model` + `data/files` + `feature/settings`): new
  `ThemeMode` enum (SYSTEM / LIGHT / DARK, default SYSTEM) persisted through
  `AppSettingsStore`; an *Appearance* card in Settings with radio options;
  `AppContainer.themeMode` mirrors the preference as Compose state so a change
  recomposes the whole tree instantly and survives restarts.
- **`ThemeModeTest`** (`core/model`): 3 unit tests pinning the persistence
  contract (round-trip of storage keys, SYSTEM fallback for unknown values).

### Changed
- **Low-end optimizations:** on `ActivityManager.isLowRamDevice` devices the
  privilege health ping interval extends from 30 s to 2 min (binder-death
  callbacks remain instant); `resourceConfigurations` strips the ~40 unused
  AndroidX locales from the APK; docs now note the APK is universal
  (arm64-v8a / armeabi-v7a / x86 / x86_64) so 32-bit Android 8 phones install it.
- **Android 8+ messaging corrected:** README tagline, badges, onboarding
  Device-step text and `docs/COMPATIBILITY.md` now say detection + the Shizuku
  bridge run on Android 8+; the 11+ floor belongs to the v0.3.0 embedded-ADB
  mode only (comment on `PrivilegeHealthMonitor.platformSupported` updated).
  No behavioral gate changed — the runtime fallbacks
  (`PhoneStateListener` on API 26–30, `TelephonyCallback` on 31+,
  FGS type on 34+, `POST_NOTIFICATIONS` on 33+) were already in place.

## [0.2.0] — 2026-11-22

Milestone 2: **the privilege bridge and call detection are real.** oneCR now sees
cellular and VoIP call lifecycle events, shows them live, logs them locally, and
proves the shell (UID 2000) bridge that M3's capture engine will ride on. This is
still a detection-only build — no audio is recorded yet.

### Added
- **Shizuku privilege bridge** (`:privilege:shizuku`): binds a tiny AIDL shell
  service (`IShellService` — `exec`/`getUid`, nothing else) as a Shizuku user
  service running as shell (UID 2000); handles permission requests, binder
  death and reconnects; a "Verify bridge" action runs `id` and shows
  `uid=2000(shell)` when the bridge is healthy. The embedded-ADB bridge stays
  scoped to v0.3.0.
- **Privilege health monitoring** (`:privilege:api`): `PrivilegeHealthMonitor`
  publishes `StateFlow<PrivilegeHealth>` (state + human detail + next action)
  from a 30 s ping, provider-pushed binder events, and reboot detection via
  `Settings.Global.BOOT_COUNT`; the Dashboard and Settings show it live with
  one-tap repair actions.
- **Cellular call detection** (`:service:detection`): pure `CallStateMachine`
  (IDLE → RINGING → OFFHOOK → IDLE; incoming/outgoing/missed) fed by
  `TelephonyCallback` (API 31+) or `PhoneStateListener` (API 26–30), with
  post-call caller-ID enrichment from the newest CallLog row (number + cached
  contact name).
- **VoIP call detection heuristics**: debounced rule `ongoing CATEGORY_CALL
  notification from an allowlisted VoIP package AND voice-communication audio
  active` — `NotificationListenerService` (category flag only, never content)
  plus `AudioManager` playback configurations; package allowlist fixed in code
  and mirrored in the manifest `<queries>` (no `QUERY_ALL_PACKAGES`).
- **Detection foreground service**: specialUse FGS (honest sub-type, proper
  phoneCall/microphone types arrive with the M3 recorder) with a live
  notification that reflects the current call state.
- **Onboarding wizard** (`:feature:onboarding`): welcome → device check →
  Shizuku bridge setup (install / start / grant / verify) → per-OEM survival
  steps (auto-detected: OPPO/OnePlus/Realme, Xiaomi, Samsung, vivo) →
  permission grants → done. The bridge step is skippable — detection works
  without it.
- **Permission center** (Settings): every permission with live status, request
  actions, and the full "coming in M3+" list.
- **Event log** (Settings): every detection/privilege/service event, persisted
  as JSONL under `files/logs/events.jsonl`, viewable in-app with clear button.
- **Dashboard v2**: live call state card (ringing / on-call / VoIP app), live
  privilege bridge card, recent events feed, detection start/stop.
- Unit tests: `CallStateMachineTest` (8), `VoipHeuristicTest` (8),
  `EventLogStoreTest` (6) — 22/22 green.

### Changed
- First-launch experience now opens the onboarding wizard (main UI gated
  behind completion; re-runnable from Settings).
- Manifest: `READ_PHONE_STATE`, `READ_CALL_LOG`, `POST_NOTIFICATIONS`,
  foreground-service + specialUse permissions added; Shizuku provider merged
  in; `<queries>` limited to Shizuku + the VoIP allowlist.
- CI release job now triggers on **any** tag (previous `refs/tags/v*` filter
  never matched this repo's `0.1.x` tags) and publishes the APK as
  `oneCR-v<version>.apk`.
- R8 keep rules added for the Shizuku user service (loaded reflectively by the
  Shizuku server from this APK's dex).
- Version bumped to 0.2.0 (versionCode 4).

### Known limitations
- No audio capture yet (M3); the bridge's `probeCapture()` is a stub.
- Incoming numbers on some OEMs arrive only via CallLog post-call enrichment.
- VoIP detection depends on apps posting `CATEGORY_CALL` ongoing notifications;
  apps that don't (or that suppress notifications) won't be detected until the
  M3 manual-record fallback.

## [0.1.2] — 2026-11-22

### Fixed
- **"There was a problem parsing the package" on Android 10 and older phones.**
  v0.1.1 declared `minSdk 30` (Android 11+), so Android's package parser rejected
  the whole APK on older devices before it could even show the app icon — the
  error looked like a corrupt file but was an install-floor mismatch. The
  install floor is now **Android 8.0 (API 26)** across all modules, so the
  smoke-test APK installs on any modern phone. The production capture features
  still require Android 11+; that gate moves to runtime checks in M2 instead of
  blocking the install.
- APK now signed with **v1 + v2 + v3** signature schemes simultaneously
  (previously v2 only) for maximum compatibility across OEM package installers.

### Added
- The local signing keystore generated for prebuilt releases is now shipped in
  `.signing/` inside the source zip (still gitignored). `build.bat` reuses it,
  so APKs you build yourself install as in-place updates over the prebuilt
  release APK — no more signature-mismatch uninstall dance between the two.

### Changed
- Version bumped to 0.1.2 (versionCode 3).
- README install guide rewritten around the new Android 8+ install floor.

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
