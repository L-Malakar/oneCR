# Changelog

All notable changes to oneCR are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project versioning
is `MAJOR.MINOR.PATCH` from `gradle.properties` (`VERSION_NAME`).

## [0.3.2] — 2026-11-24

M3 step 1 — the capture engine. oneCR records cellular calls for the first
time: the privilege bridge spawns a minimal capture process (`CaptureMain`,
shipped inside the APK, spawned via `app_process` as UID 2000) that streams
the telephony audio into a WAV file while the app wraps it with a
crash-recovery journal. This release also fixes the CI workflow YAML that had
rejected every push since v0.3.0.

### Added
- **Shell capture process** (`:service:recorder` → `capture/CaptureMain`):
  framework-only entry point with two subcommands — `probe` (opens every
  privileged source × rate for ~400 ms and prints a machine-readable report:
  which sources return real audio / open-but-silent / fail) and `start`
  (raw PCM s16-LE mono on stdout, diagnostics on stderr, clean stop via a
  `stop` line on stdin or a `--max-ms` guard). Runs as the adb-shell user,
  which holds `CAPTURE_AUDIO_OUTPUT` — no root needed.
- **Streaming shell channel** (`AdbStreamClient`): a second dedicated TLS
  session to the phone's adbd carries only the PCM stream, so health pings
  and shell checks keep flowing on the main bridge while audio streams.
  The shared adb packet codec moved to `AdbWire` (behavior unchanged).
- **Capture contract in `:privilege:api`**: `CaptureProtocol` (command
  builder + probe parser + fallback-chain composer), `CaptureStream` /
  `CaptureStreamSpec`, and the new `PrivilegeProvider.openCaptureStream` —
  implemented for embedded ADB (second TLS session) and Shizuku (new AIDL
  `openCaptureStream` returning a PCM pipe over binder; user-service version
  bumped to 3 so Shizuku re-loads the dex).
- **Recorder service** (`:service:recorder`): `RecordingEngine` (single
  active session, probe-driven source fallback VOICE_CALL → MIC, WAV writer
  with patched RIFF sizes, journal statuses
  recording/recovered/finalized/failed), `RecordingJournal` (atomic k=v
  journal + `sweepOrphans` that recovers sessions killed mid-call), and
  `RecordingService` — a specialUse foreground notification with a live
  chronometer and a Stop action.
- **Auto-record on detected cellular calls** (Settings switch, default ON)
  wired to the detection engine's live call state; manual recordings survive
  the call ending, auto ones stop with it.
- **Dashboard: Record now / Stop recording** in the command card with a live
  recording row (source + number while recording; failure messages surface
  as toasts).
- **Settings → Capture (M3) card**: recorder state, auto-record switch and
  the audio-probe button with the resulting fallback chain, rate and notes.
- **CI recovery file**: the workflow fix ships in the source tree
  (see `Fixed`).

### Fixed
- **The GitHub Actions workflow was rejected at startup on every push since
  v0.3.0** — the release-notes guard step's name contained an unquoted
  colon (`- name: Guard: release notes …`), a YAML syntax error, so GitHub
  logged "Invalid workflow file … line 97" and every run failed with zero
  jobs (build #25–#27 included). The name is now quoted, with a comment so
  it never regresses.

### Changed
- Version bumped to 0.3.2 (versionCode 12); tests grew from the protocol
  suite to **74 green** (probe parser, WAV writer and journal included).

## [0.3.1] — 2026-11-24

The UI/UX restoration release. **v0.3.0 accidentally shipped an older UI
layer** — its build tree predated the v0.2.5/v0.2.6 polish, so the modern
dashboard and settings, the animation system, the notification-center header
and the 24×7 watcher persistence quietly vanished under the new ADB engine
(and its versionCode even went backwards, 5 after v0.2.6's 10). v0.3.1
rebuilds on the true v0.2.6 source and layers the v0.3.0 embedded-ADB
bridge on top: the app now has both the good UI and the new engine.

### Fixed
- **The v0.3.0 UI regression** — restored from v0.2.6: the modern dashboard
  (gradient command card, 2×2 stat grid, compact activity feed, roadmap
  card), the icon-tile settings panel with the segmented System/Light/Dark
  theme control, the motion module (`LocalMotionEnabled` + the Animations
  switch), the themed notification-center header, About rows with Developer
  and Report-a-bug entries, the top-bar home button, per-screen back
  handling with visited-tab history, settings scroll-position restoration,
  and the DayNight platform theme sync (window, bars, dialogs).
- **The 24×7 watcher persistence is back**: explicit `stopWithTask=false`,
  the `onTaskRemoved` notification re-assert and the `BootReceiver` reboot
  restart — present in v0.2.6, all missing from the v0.3.0 build.
- **versionCode regression**: v0.3.0 shipped code 5, lower than v0.2.6's 10,
  so Android refused in-place updates from v0.2.6. v0.3.1 ships code 11 —
  higher than every previous release — and updates in place over v0.3.0
  (same release keystore); from v0.2.x or earlier one uninstall is still
  needed (the keystore was rotated at v0.3.0).
- **Repository hardening restored**: the gradlew exec-bit CI fix and run
  concurrency (v0.2.3/v0.2.4) were missing from the v0.3.0 workflow; they
  are back alongside the v0.3.0 release-notes guard, and the Aliyun Maven
  mirror (Maven Central rate-limit workaround) returns in settings.gradle.kts.
- `appcompat`, `material-icons-extended` (the icon-driven UI) and the
  English-only `resourceConfigurations` strip are back in `:app` — all three
  were silently dropped by the v0.3.0 build.

### Added
- **Privilege mode picker in Settings** (v0.3.0 feature, restyled v0.3.1):
  embedded ADB vs Shizuku radio rows inside the modern bridge card; switching
  persists the choice, restarts the live health monitor and the notification
  center follows the new bridge.
- **Setup wizard: mode picker + pairing on the polished chassis** — the
  v0.3.0 bridge step (mode cards, 6-digit pairing-code UI, mDNS port search)
  now lives inside the v0.2.6 three-region wizard with animated steps, the
  fixed bottom bar and the one-by-one permission flow. Skip stays re-run-only.

### Changed
- Version bumped to 0.3.1 (versionCode 11).

## [0.3.0] — 2026-11-24

Milestone 2.5: **the embedded ADB bridge is real.** oneCR now pairs with the
phone's own Wireless Debugging (the Android 11+ `adb pair` protocol, implemented
on-device) — the privilege bridge no longer needs any third-party app. Shizuku
remains available as a switchable alternative. Still a detection-only build;
recording arrives in M3.

### Added
- **Embedded ADB privilege bridge** (`:privilege:embedded-adb`): a minimal
  on-device adb client that pairs with this phone's Wireless Debugging using the
  exact AOSP protocol — TLS 1.3 with client certificates, 64 bytes of RFC 5705
  keying material (label `adb-label\0`) mixed into the SPAKE2 password, SPAKE2
  over edwards25519 (BoringSSL variant), AES-128-GCM PeerInfo exchange, and the
  classic adb public-key line (android_pubkey encoding) exported to the device.
- **Local adb client**: TLS 1.3 + `CNXN` handshake and the `shell,v2:` service
  with stdout/stderr/exit-code frames, ACK semantics and stale-connection
  reconnect; `runShellCommand` runs as UID 2000 with no extra app.
- **Wireless-debugging discovery**: mDNS/NSD discovery of `_adb-tls-pairing` and
  `_adb-tls-connect` services so the pairing port is found automatically; the
  last known port is kept as a fallback.
- **Privilege mode picker**: choose embedded ADB or Shizuku during onboarding or
  in Settings; the choice persists and switches the live health monitor. The
  setup wizard's bridge step shows per-mode guidance (pairing code + port UI for
  embedded ADB, install/start/grant/verify for Shizuku).
- **Wizard Skip button on re-run only**: the setup wizard offers Skip (top
  right) exclusively when re-run from Settings — first launches walk through
  setup to avoid accidental skips.
- **Unit tests**: SPAKE2 cross-validated against an independent Python
  reference implementation (deterministic vectors, both roles), RFC 5869 HKDF
  vectors, AES-128-GCM cipher vectors, X.509 + android_pubkey structure tests,
  and end-to-end TLS pairing/shell sessions against mock servers — 24/24 green.
- Bundled **Conscrypt** (BoringSSL) so `SSL_export_keying_material` is available
  on-device; the APK grows to ~9.5 MB with per-ABI native TLS libraries.

### Changed
- Manifest gains `INTERNET` — used exclusively for local TLS sockets to the
  phone's own adbd (and, in M5, model downloads); oneCR still sends nothing
  anywhere.
- README overhauled (download button, embedded-ADB setup guide, FAQ, developer
  section); release-notes folder now keeps only the latest version's file.
- Version bumped to 0.3.0 (versionCode 5).

### Known limitations
- Real-device pairing needs field validation (the protocol layer is covered by
  cross-implementation tests, but OEM adbd builds may differ) — report results
  in docs/COMPATIBILITY.md.
- No audio capture yet (M3); `probeCapture()` is still a stub.
- After a reboot the embedded bridge reconnects only once Wireless debugging is
  reachable over Wi-Fi again.
- Built from a stale source snapshot: the v0.2.5/v0.2.6 UI layer described in
  the v0.2.x entries was missing from this build — restored in v0.3.1.

## [0.2.6] — 2026-11-23

Motion-and-survival release: **the 24×7 watcher now truly survives closing
the app (and reboots), every screen animates with a settings switch, the
Settings panel got a modern icon-tile look, and About rows all open their own
pages.**

### Added
- **Animations everywhere + Animations switch** (default ON): tabs, settings
  sub-pages, the notification center, wizard steps, the setup/roadmap
  progress bars, stat-tile counters and the live status dots all transition
  with consistent slide + fade motion from a new `:core:designsystem` motion
  module (`LocalMotionEnabled`). The switch lives in Settings → Appearance;
  turning it off makes every swap instant. Persisted via
  `AppSettingsStore.animationsEnabled`.
- **The 24×7 watcher survives app close and reboot** (`:app`, `:service:detection`):
  `stopWithTask=false` is explicit on the detection service, `onTaskRemoved`
  re-asserts the foreground notification and logs
  "App closed — detection keeps running in the background", and a new
  `BootReceiver` + `RECEIVE_BOOT_COMPLETED` restarts the watcher seconds
  after a reboot when autostart was opted in during onboarding.
- **Battery-optimization protection**: a "Keep alive" card in Settings and a
  Dashboard banner detect a non-exempted ROM and offer a one-tap
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` request — the last piece the
  "notification disappears on close" puzzle needed on aggressive OEMs.
- **About rows open their own pages** (`:feature:settings`): Privacy by
  design → PRIVACY.md, License → LICENSE, Build → Releases. New **Developer**
  row (github.com/L-Malakar) and **Report a bug** row
  (github.com/L-Malakar/oneCR/issues), both one tap away.
- **Top bar home button** (`:app`): the logo + name block in the top app bar
  is now tappable and always returns to the Dashboard.
- **Skip button on the setup wizard** (`:feature:onboarding`): a top-right
  Skip on every step leaves the wizard without changing any setting — built
  for accidental "Re-run the setup wizard" taps.

### Changed
- **Settings panel restyle**: rounded icon tiles lead every card, the theme
  choice became a three-way segmented control (System / Light / Dark with
  icons), the Privilege bridge tile colors by health state, and cards use a
  softer 16 dp corner radius.
- **`docs/release-notes/` now keeps only the current release's notes**; the
  old per-version files were removed (the full history lives in
  CHANGELOG.md).

## [0.2.5] — 2026-11-23

Readability-and-dashboard release: **dark-mode text is finally correct
everywhere, the Dashboard grew a real visual hierarchy, and Settings keep
your scroll position.** Fixes the "black text never turns white" bug reported
on the v0.2.4 setup wizard and every other place the same latent bug hid.

### Added
- **App-wide content color** (`:core:designsystem`): `OneCRTheme` now
  provides `LocalContentColor = onBackground` for the whole tree. Compose's
  default for texts outside a `Surface` is plain black — invisible as a bug
  in light mode, unreadable on navy in dark mode. One change fixes the setup
  wizard's headline and bullets, the Dashboard hero title, the Permission
  center and Event log headlines, and the empty-state titles, in both themes.
- **Modern Dashboard** (`:app`): the v0.2.2 stack of same-looking status rows
  became a hierarchy — a gradient **command card** (brand row, live detection
  state with status dot + LIVE/OFF pill, capability line, Start/Stop and
  Re-check controls), an **at-a-glance 2×2 stat grid** (calls seen,
  permissions x/5, events logged, bridge state) with a conditional
  fix-me banner for degraded bridges, a compact **recent-activity feed**
  with kind chips and relative timestamps, and a **roadmap card** with a
  progress bar and next-up highlight.
- **Developer badge in the README**, between Platform and Version; the README
  logo now links to the latest release APK (same URL as the Download button).

### Fixed
- **Settings scroll position reset** (`:feature:settings`): the settings
  root's scroll state was created inside the root composable, so switching to
  the Permission center or Event log destroyed it and back navigation snapped
  to the top. The state is now hoisted above the page switch and saved with
  `ScrollState.Saver` — the list stays exactly where the user left it, across
  sub-pages, tab switches and process death.
- **Wizard steps carried the old scroll offset** (`:feature:onboarding`):
  advancing to a shorter step could land mid-content; every step now scrolls
  to its top on entry, and the "Set up oneCR" headline sets an explicit
  `onBackground` color.

### Changed
- Version bumped to 0.2.5 (versionCode 9). README download button, version
  badge and install/update instructions now point at v0.2.5; the feature
  table gained the v0.2.5 rows.

## [0.2.4] — 2026-11-22

Theme-and-controls release: **dark mode now follows the app everywhere, the
notification center has a real top bar, and the setup wizard's buttons are
pinned to the bottom.** Fixes the three dark-mode bugs reported on v0.2.3
(black panel title on navy, missing panel top bar, light-only setup wizard)
and shifts the UI further towards icons over prose.

### Added
- **Icon-driven UI** (`:app`, `:feature:*`): `material-icons-extended` joins
  the classpath (R8 strips unused icons; the release APK stays small). The
  setup wizard shows a per-step header icon and distinct permission-task
  icons (call log = history, notification access = active bell, battery =
  charging); Dashboard's Start/Stop detection buttons carry play/stop
  glyphs; theme choices get system-contrast / moon / sun icons; the guided
  permission flow, "Clear log" and the About rows grew leading icons, with a
  chevron on the Project home row.
- **Aliyun Maven mirror** (`settings.gradle.kts`): consulted before Maven
  Central for third-party artifacts because Central's HTTP 429 rate limiting
  aborted first-time builds on some IPs. Central remains the canonical
  fallback; CI behavior is unchanged.

### Fixed
- **Notification center title stayed black in dark mode** (`:app`): the
  hand-rolled header drew its title with the ambient content color, which
  defaults to black outside a Surface — black on navy. The header is now a
  real Material 3 `TopAppBar` (surface container, `onSurface` title, bell
  glyph, live status line, "Mark read" action) plus a divider under it —
  which also delivers the long-requested **top bar for the notification
  panel** (no bottom bar, as before).
- **"Set up oneCR" ignored the dark theme** (`:app`, `:core:model`): the
  Compose palette followed the theme setting, but the platform layer — the
  window background the transparent wizard draws on top of, system bars and
  platform dialogs — kept following the OS. The host activity is now an
  `AppCompatActivity` on a `Theme.AppCompat.DayNight` theme, and the
  persisted theme mode is synced through `AppCompatDelegate.setDefaultNightMode()`
  on app start and on every change, so **Dark pins dark everywhere, Light
  pins light, System follows the OS**. Every screen root additionally paints
  `colorScheme.background`, so no translucent surface can leak the light
  window underneath (onboarding, dashboard, settings root, permission
  center, event log, library).
- **Setup wizard buttons drifted with content** (`:feature:onboarding`): the
  Next/Back/Finish row was part of the scrollable column, so its position
  moved with each step's text length. The wizard is now a three-region
  column — fixed header (step icon + title + progress), scrollable step
  content, and a **fixed bottom bar** that always sits at the screen edge.
  Back and Finish gained arrow/check icons; the header/status/nav bar
  insets are handled by the screen itself since it renders without the
  shell's Scaffold.

### Changed
- Version bumped to 0.2.4 (versionCode 8). The GitHub Releases page hosts
  **v0.2.0 and newer** — the 0.1.x pre-releases were removed from the page;
  README and release notes now point new installs at v0.2.4 and describe the
  one-time uninstall path only for upgrades from those removed pre-releases.

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
