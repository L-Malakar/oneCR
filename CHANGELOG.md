# Changelog

All notable changes to oneCR are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versioning
is `MAJOR.MINOR.PATCH` from `gradle.properties` (`VERSION_NAME`).

## [0.2.3] — 2026-11-22

Polish release: **back navigation that behaves, a fixed dark theme, a
redesigned About screen and green CI.** System back now walks the user's
screen history (notification center → opener, settings sub-pages → root,
previous tab) and only exits after a second press at the root confirms it.
The Material 3 scheme maps every color role in both modes, so dialogs, menus,
switches and text fields finally match the navy palette in dark mode.

### Added
- **Back navigation with exit confirmation** (`:app`, `:feature:*`): a shell
  `BackHandler` coordinates a visited-tab history (`Dashboard ⇄ Library ⇄
  Settings`), the notification center and the onboarding wizard intercept back
  first via their own handlers, and the exit needs a second press within
  ~2.2 s ("Press back again to exit"). Sub-pages return to the settings root;
  the guided permission flow returns to its overview first. Tab history is
  `rememberSaveable`, so it survives process death; a persisted-but-renamed
  tab name now falls back to Dashboard instead of crashing.
- **Complete Material 3 color scheme** (`:core:designsystem`): both
  `darkColorScheme` and `lightColorScheme` now map every role — tertiary,
  errorContainer/onErrorContainer, outline/outlineVariant, inverse*,
  surfaceTint, scrim and all `surfaceContainer*` elevations. The dark
  `secondary` is a light slate (the old value was near-black on navy), light
  `primary` is a deeper teal for AA-contrast buttons, the warning tone uses a
  themed amber container pair, and the badge ink is a named token.
- **Themed window background** (`:app` res): `values`/`values-night`
  `onecr_window_background` colors remove the grey launch flash before the
  first Compose frame.
- **Redesigned About section** (`:feature:settings`): gradient brand header
  (logo, tagline, version/build chips), icon rows for Project home (deep-links
  to GitHub), Privacy by design, License and exact build, plus a setup-wizard
  button — all scheme-driven, no hardcoded colors.
- **CI fixes** (`.github/workflows/android.yml`): `chmod +x gradlew` after
  checkout in both jobs (Windows checkouts lose the exec bit → every Linux
  build failed with exit code 126 `Permission denied`), a concurrency group
  that cancels superseded runs, and a release-notes fallback so tagging without
  a notes file can no longer fail the publish step. `gradlew` ships with mode
  755 in the source archive; CONTRIBUTING.md documents the permanent
  `git update-index --chmod=+x gradlew` fix.

### Fixed
- **Notification center showed every error twice** — once under *Errors* and
  again under *Recent activity*. The activity feed now excludes error events;
  pinned by a new unit test (7 notification tests total).
- **Dashboard "Recent events" order** — the three latest events listed
  oldest-first; now newest-first like every other feed.
- Hardcoded `Color(0xFF14181F)` badge text and amber warning icon moved into
  the theme (`BadgeInk`, `tertiaryContainer`); dead imports and an unused
  local removed in `SettingsScreen`/`OneCRApp`.

## [0.2.2] — 2026-11-22

UX release: **a professional shell for the app — top bar + notification center,
one-by-one permission wizard.** Every screen now has a branded top app bar with a
notification bell (top-right); the bell opens a notification center that collects
everything the app needs, every error, and the activity log in one feed with an
unread badge. Permissions are no longer bundled: each tap asks for exactly one
thing.

### Added
- **Top app bar with notification center** (`:app`): a Material 3 `TopAppBar`
  (logo + current tab + version) on all main screens; the bell in the top-right
  shows an amber unread badge counting needs + errors + unseen activity, and
  opens the new **Notifications** page. The center has three sections:
  *Needs attention* (missing permissions with one-tap grant buttons, detection
  off with a start button, bridge repair), *Errors* (the new error event stream),
  and *Recent activity* (the event log as a feed). Opening it marks everything
  read; "Mark read" resets the badge.
- **Notification center model** (`:app/notify`): pure, unit-tested
  `NotificationItems.build()` composes the feed from the permission snapshot,
  bridge health, detection-running flag and event log; unread math
  (needs + errors + activity after last-seen, errors never double-counted)
  is pinned by 6 new unit tests. `NotificationCenter` combines the live flows;
  `notification_last_seen` persisted via `AppSettingsStore`.
- **One-by-one permission wizard** (`:feature:onboarding` + `:feature:settings`):
  the onboarding Permissions step was rewritten — each permission is a card,
  the active one is highlighted and its **Allow …** button fires exactly one
  system dialog per tap, then the next card activates (phone → call log →
  notifications (13+) → notification access → battery). The Permission center
  gains the same experience as **Guided setup**: "Step X of Y" with the
  rationale in front of every ask, per-item skip and exit.
- **Permission snapshot** (`core/model`): new pure `PermissionSnapshot`
  (phone / call log / notifications / listener / battery) published as a
  `StateFlow` by the app module and refreshed on activity resume and after
  every grant — replaces the old per-screen `refresh` counter hack, so every
  screen (wizard, permission center, notification center) updates reactively.

### Changed
- **UI polish:** `SectionHeader` (uppercase label) groups the Dashboard
  (Live status / Activity / Roadmap) and Settings (Appearance / Privilege
  bridge / Permissions and diagnostics / Coming next / About); the Dashboard
  hero carries a version chip and the Start/Stop buttons now reflect the real
  service state via the new observable `DetectionEngine.running` StateFlow;
  the nav bar and top bar are pinned to the theme surface color (the baseline
  grey `surfaceContainer` clashed with the navy palette); `StatusCard` gained
  semantic icon tones (PRIMARY / WARNING / NEUTRAL); roadmap icons no longer
  imply pending items are problems.
- **Error surface:** `EventLogEntry.KIND_ERROR` added; the detection service
  logs errors when the engine is missing, `startForeground` fails (and stops
  itself instead of being killed), or the engine throws — previously all of
  these failed silently. The Event log renders error entries with an
  error-colored badge; the notification center lists the last 10.

### Fixed
- Onboarding "Ready" step said "oneCR v0.2.0" regardless of the actual
  version — now injected from `AppInfo`.
- Event log list could collapse on small screens (`LazyColumn` now gets the
  remaining height via `weight(1f)`).
- Notification center badge math counts each error exactly once.

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
