# oneCR Roadmap

Status legend: [ ] planned, [~] in progress, [x] done.

## M1 — Repo and app skeleton (v0.1.0–0.1.1)
- [x] Gradle multi-module skeleton (all modules created, boundaries fixed)
- [x] Version catalog (`gradle/libs.versions.toml`) + `gradle.properties` version plumbing
- [x] `build.bat` (env checks, first-run keystore, APK publish to `oneCR/Android/oneCR-v<version>.apk`)
- [x] Gradle wrapper (8.10.2) + CI (`.github/workflows/android.yml`) + dependabot
- [x] Repo hygiene: LICENSE (MIT), SECURITY.md, PRIVACY.md, CONTRIBUTING.md, README.md, templates
- [x] docs/: ARCHITECTURE, ROADMAP, COMPATIBILITY, BRANDING
- [x] Compose app shell: Dashboard / Library / Settings navigation, theme, adaptive icon
- [x] Official logo vectorized and shipped as adaptive icon + in-app branding (v0.1.1)
- [x] R8 code/resource shrinking enabled for release builds — APK ~1.5 MB (v0.1.1)
- [x] Toolchain-verified compile: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21, JDK 17 — `assembleDebug`, `assembleRelease`, `test` all green; release-signing flow verified end-to-end (apksigner + zipalign checks green)
- [ ] Verified on a real device from a clean clone via `build.bat` (owner)

## M2 — Privilege bridge and call detection (v0.2.0)
- [x] `:priv-shizuku`: bind, permission request, user service (`IShellService` AIDL), binder-death handling (v0.2.0)
- [x] `PrivilegeHealthMonitor` + status UI + repair actions (install/start/grant/verify, reboot banner) (v0.2.0)
- [x] Onboarding wizard with per-OEM guidance (OPPO/OnePlus/Realme, MIUI, One UI, vivo) (v0.2.0)
- [x] Cellular call detection state machine + caller ID (READ_PHONE_STATE / CallLog enrichment) (v0.2.0)
- [x] VoIP detection: notification listener + playback-configuration heuristics (debounced) (v0.2.0)
- [x] Permission center screen + debug Event Log screen (v0.2.0)
- [x] Detection foreground service (specialUse) + live status notification (v0.2.0)
- [x] Unit tests for the pure state machines + event store (22 green) (v0.2.0)
- [x] `:priv-adb`: wireless-debugging pairing protocol + local adb client — **shipped in M2.5 (v0.3.0)**
- [ ] Exit criteria: reliable labeled call events on the test device; graceful degradation when privileges drop (needs owner device testing)

## M2.x — App shell polish (v0.2.1–v0.2.3)
- [x] Light / Dark / System theme with persisted choice, SYSTEM default (v0.2.1)
- [x] Low-end device accommodations: health-ping interval doubled on low-RAM devices, Android 8.0 install floor kept (v0.2.1)
- [x] Branded top app bar + notification bell, in-app notification center (needs / errors / activity, unread badge) (v0.2.2)
- [x] One-by-one permission wizard in onboarding + guided setup in Permission center (v0.2.2)
- [x] Back navigation: visited-tab history, back intercepts in notification center / settings sub-pages / onboarding, double-press exit confirmation (v0.2.3)
- [x] Full Material 3 color mapping in dark & light (no baseline fallbacks), themed window background, themed warning tone (v0.2.3)
- [x] Redesigned About section: gradient brand header, facts rows, setup shortcut (v0.2.3)
- [x] Feed fixes: errors no longer duplicated in activity; dashboard events newest-first; new unit test (v0.2.3)
- [x] CI green: gradlew exec-bit fix (exit 126), run concurrency, release-notes fallback (v0.2.3)
- [ ] Verified on a real device (owner)

## M2.5 — Embedded ADB bridge (v0.3.0)
- [x] Wireless-debugging pairing protocol (TLS 1.3 + SPAKE2 + 6-digit code) on-device (v0.3.0)
- [x] Local adb client: TLS connect to own adbd + CNXN + shell service (`shell,v2:` with exit codes) (v0.3.0)
- [x] adb key persistence, NSD discovery of pairing/connect services, Developer-options guard, Wi-Fi-after-reboot UX (v0.3.0)
- [x] Privilege mode picker in Settings + onboarding (Shizuku ↔ embedded ADB) (v0.3.0)
- [x] Protocol test coverage: SPAKE2 vs independent reference, HKDF RFC vectors, TLS pairing + shell e2e mocks (v0.3.0)
- [x] v0.3.1: the v0.2.6 UI/UX layer restored on top of the bridge (the v0.3.0 build had shipped an older UI base); versionCode regression fixed
- [x] v0.3.2: `app_process` capture-dex spawn hooks shipped (the M3 shell capture dex + streaming channel)

## M3 — Audio capture engine (step 1 in v0.3.2)
- [x] Shell capture dex (`probe`/`start`/`stop`) — `CaptureMain` spawned via `app_process`, raw PCM streamed over a dedicated second TLS session (`AdbStreamClient`), stop via stdin control word (v0.3.2)
- [x] Capability probe + source fallback chain — probe report parsed into a VOICE_CALL → downlink+uplink (dual) → MIC chain, silent-source detection included (v0.3.2)
- [x] WAV writer with patched RIFF sizes + recording journal + orphan sweep (crash-recovery, `recovered` status) (v0.3.2)
- [x] Recording foreground service with notification controls (specialUse type, live chronometer, Stop action) (v0.3.2)
- [x] Auto-record on detected cellular calls (Settings switch, default ON) + manual Record-now button on the Dashboard (v0.3.2)
- [x] Capture diagnostics card in Settings (probe results, fallback chain, rate, notes) (v0.3.2)
- [ ] Dual downlink+uplink streams (per-speaker files) — protocol ready, recorder support next
- [ ] VoIP dual-path capture (MediaProjection playback capture + mic) + mixer (and the microphone/mediaProjection FGS types)
- [ ] AAC encoding via MediaCodec streaming (WAV retained as option)
- [ ] Capture diagnostics screen polish: live levels, per-source result table
- Exit criteria: clean two-sided recordings for cellular and at least one VoIP app; 30-min soak test with flat memory and no ANRs.

## M4 — Library, storage, search, export
- [ ] Room schema (recordings, transcripts, summaries, tags, FTS) + repositories
- [ ] AAC encoding via MediaCodec streaming (WAV retained as option)
- [ ] Recordings list, Media3 player, tags, notes
- [ ] Local full-text search over transcripts
- [ ] Export: share sheet (audio + TXT/SRT/VTT/MD/JSON) and bulk SAF zip
- [ ] Retention policy (age/size caps) + storage stats screen
- Exit criteria: full offline CRUD + search + export; reboot-mid-pipeline recovery verified.

## M5 — On-device AI
- [ ] `third_party` submodules pinned (whisper.cpp, llama.cpp) + CMake arm64-v8a builds
- [ ] whisper JNI: chunked transcription, progress callbacks, timestamps, per-stream speaker labels (cellular)
- [ ] llama JNI: summarization prompt styles (Brief / Action items / Detailed)
- [ ] Model Manager: download with resume + SHA-256, SAF sideload, delete, storage checks
- [ ] Pre-flight gates: RAM, storage, thermal, battery
- [ ] Transcript UI with tap-to-seek + summary cards
- Exit criteria: end-to-end on-device pipeline (call → recording → transcript → summary), both models loaded/freed sequentially with stable native heap.

## Beyond v0.1
- [ ] Per-recording encryption at rest
- [ ] "Airplane" build variant without the INTERNET permission (sideload-only models)
- [ ] Live partial transcription during long calls
- [ ] Spam/caller heuristics fully local
- [ ] Localization (English first; Hinglish UI strings as needed)
